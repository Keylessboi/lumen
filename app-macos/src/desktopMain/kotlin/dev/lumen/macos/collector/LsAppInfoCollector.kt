package dev.lumen.macos.collector

import dev.lumen.core.collector.AppUsageCollector
import dev.lumen.core.collector.CollectorCapabilities
import dev.lumen.core.collector.FocusChange
import dev.lumen.core.collector.PermissionState
import dev.lumen.core.model.AppKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.TimeUnit

/**
 * macOS foreground-app collector, v0.
 *
 * Reads the frontmost application from `/usr/bin/lsappinfo`, a system tool
 * present on stock macOS. See `app-macos/README.md` for the mechanism
 * comparison and the permission analysis that selected it.
 *
 * **This is deliberately the boring implementation.** The production path is
 * `NSWorkspace.didActivateApplicationNotification`, which is push-based and
 * removes polling entirely; it needs a JNI/JNA bridge into AppKit and is
 * tracked separately. This collector exists so the seam is exercised by
 * something real, and so the macOS lane has a working baseline that costs no
 * permissions and no native build step.
 *
 * Both mechanisms yield the same [FocusChange] stream, so the swap is
 * internal to this class.
 */
class LsAppInfoCollector(
    private val pollInterval: kotlin.time.Duration = DEFAULT_POLL_INTERVAL,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val runner: FrontmostAppReader = LsAppInfoReader(),
) : AppUsageCollector {

    override val capabilities = CollectorCapabilities(
        isRealtime = false,
        canBackfill = false,
        backfillHorizonMs = null,
        pollIntervalMs = pollInterval.inWholeMilliseconds,
        // lsappinfo reports the frontmost app even while the screen is locked,
        // so this collector cannot tell "using Safari" from "locked, Safari was
        // last frontmost". Idle detection needs IOKit HIDIdleTime and lands
        // with the NSWorkspace bridge.
        detectsIdle = false,
    )

    override fun permissionState(): PermissionState =
        if (runner.isAvailable()) {
            PermissionState.Granted
        } else {
            PermissionState.Unsupported(
                "/usr/bin/lsappinfo is not available on this system"
            )
        }

    /**
     * Emits only on change. A poll that observes the same app as the previous
     * poll produces nothing, per the [AppUsageCollector.focusChanges] contract.
     */
    override fun focusChanges(): Flow<FocusChange> = flow {
        var last: AppKey? = null
        while (true) {
            val observed = runner.frontmost()
            // The lock screen and password prompts take the foreground
            // without the user choosing them — see MacSystemUi. Skipped here
            // rather than filtered downstream so `last` never becomes a
            // system id: otherwise returning to the app you were already in
            // would not register as a change and the session would be lost.
            if (observed != null && MacSystemUi.isSystemUi(observed.appKey)) {
                delay(pollInterval)
                continue
            }
            if (observed != null && observed.appKey != last) {
                last = observed.appKey
                emit(
                    FocusChange(
                        appKey = observed.appKey,
                        atMs = System.currentTimeMillis(),
                        displayName = observed.displayName,
                    )
                )
            }
            delay(pollInterval)
        }
    }.flowOn(dispatcher)

    companion object {
        /**
         * 1s. Storage granularity is a 1-minute bucket (`docs/data-model.md`),
         * so this is already far finer than anything downstream can represent;
         * it exists to catch brief switches, not to time them precisely.
         */
        val DEFAULT_POLL_INTERVAL: kotlin.time.Duration = kotlin.time.Duration.parse("1s")
    }
}

/** Observed frontmost app. */
data class FrontmostApp(val appKey: AppKey, val displayName: String?)

/** Seam for testing without spawning processes. */
interface FrontmostAppReader {
    fun isAvailable(): Boolean
    fun frontmost(): FrontmostApp?
}

/**
 * Shells out to `lsappinfo`.
 *
 * Two calls per poll: `lsappinfo front` returns an ASN (application serial
 * number) such as `ASN:0x0-0x7ff7ff:`, then `lsappinfo info -only ...` resolves
 * it. Measured at roughly 3.5 ms per poll pair on an M-series Mac, process
 * spawn included.
 *
 * Notably this needs **no** permission — no Accessibility, no Screen Recording,
 * no TCC prompt of any kind. That is the whole reason it was chosen over
 * AppleScript via System Events, which does require Accessibility. See README.
 */
internal class LsAppInfoReader : FrontmostAppReader {

    override fun isAvailable(): Boolean = LSAPPINFO.canExecute()

    override fun frontmost(): FrontmostApp? {
        val asn = exec(LSAPPINFO.path, "front")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bundleId = exec(LSAPPINFO.path, "info", "-only", "bundleid", asn)
            ?.let { parseQuoted(it, "CFBundleIdentifier") }
            ?: return null
        val name = exec(LSAPPINFO.path, "info", "-only", "name", asn)
            ?.let { parseQuoted(it, "LSDisplayName") }
        return FrontmostApp(appKeyFor(bundleId, name), name)
    }

    private fun exec(vararg cmd: String): String? = try {
        val p = ProcessBuilder(*cmd)
            .redirectErrorStream(false)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        // A hung lsappinfo must not wedge the collector coroutine forever.
        if (!p.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            null
        } else if (p.exitValue() == 0) {
            out
        } else {
            null
        }
    } catch (_: Exception) {
        // A collector that throws kills the stream, which reads downstream as
        // "user was idle". Failing to null is the honest outcome: no
        // observation, rather than a false one.
        null
    }

    internal companion object {
        val LSAPPINFO = java.io.File("/usr/bin/lsappinfo")

        /**
         * Bundle ids that identify a *runtime* rather than an application.
         *
         * An unpackaged JVM app reports the JDK's own bundle id, so every
         * Java tool on the machine looks like the same app: they collapse
         * into one row, labelled with whichever main class was seen first.
         * On a developer's Mac — the buyer `docs/plan.md` names — that is a
         * confidently wrong number, which `docs/design-spec.md` rules out
         * ("Charts that lie = uninstall").
         *
         * The executable path does not help: it is the same `bin/java` for
         * every one of them. `LSDisplayName` (the main class) is the only
         * signal lsappinfo exposes that differs, so it becomes part of the
         * key. Verified against a live process:
         *
         * ```
         * "MainKt" ASN:0x0-0x8d18d1:
         *     bundleID="net.java.openjdk.java"
         *     executable path=".../openjdk@17/.../bin/java"
         * ```
         */
        val RUNTIME_BUNDLE_IDS = setOf(
            "net.java.openjdk.java",
            "com.oracle.java.JavaAppletPlugin",
            "com.apple.JavaApplicationStub",
        )

        /**
         * Key an observation, disambiguating shared-runtime bundle ids.
         *
         * A packaged app keys on its bundle id, unchanged — that is the
         * common path and it must stay byte-identical, because the AppKey is
         * what every stored row joins on. Only a runtime id gets the
         * `<runtime>/<name>` suffix, and `/` is used because a bundle id
         * cannot contain one, so the composite can never collide with a real
         * app's id.
         *
         * With no name to disambiguate by, the runtime id is returned as-is:
         * one lumpy row is bad, but inventing a distinction we cannot observe
         * is worse.
         */
        fun appKeyFor(bundleId: String, displayName: String?): AppKey =
            if (bundleId in RUNTIME_BUNDLE_IDS && !displayName.isNullOrBlank()) {
                AppKey("$bundleId/$displayName")
            } else {
                AppKey(bundleId)
            }
        const val EXEC_TIMEOUT_SECONDS = 2L

        /**
         * `lsappinfo info -only <key>` prints `"CFBundleIdentifier"="com.foo.bar"`.
         * Returns the value for [key], or null when absent — some processes
         * (agents, helpers) genuinely have no bundle id.
         */
        fun parseQuoted(output: String, key: String): String? {
            val marker = "\"$key\"="
            val start = output.indexOf(marker).takeIf { it >= 0 } ?: return null
            val afterKey = output.substring(start + marker.length).trimStart()
            if (!afterKey.startsWith("\"")) return null
            val end = afterKey.indexOf('"', startIndex = 1).takeIf { it > 0 } ?: return null
            return afterKey.substring(1, end).takeIf { it.isNotBlank() }
        }
    }
}
