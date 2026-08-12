package dev.lumen.macos.names

import dev.lumen.core.collector.AppNameResolver
import dev.lumen.core.model.AppKey
import java.util.concurrent.TimeUnit

/**
 * Resolves macOS app names from bundle ids via Spotlight.
 *
 * The Screen Time store hands back bundle ids only, so imported history is a
 * list of `com.spotify.client` without this. Spotlight already indexes the
 * mapping, so nothing new is collected and no permission is needed.
 *
 * [selfName] handles Lumen's own dev build: unpackaged, it runs as a bare JVM
 * and `lsappinfo` reports the main class as the name, so Lumen appeared in
 * its own list as "MainKt". The packaged `.app` never hits this.
 */
class SpotlightNameResolver(
    private val runQuery: (String) -> String? = ::mdfind,
) : AppNameResolver {

    override fun resolve(appKey: AppKey): String? =
        selfName(appKey.value) ?: bundleName(appKey.value)

    private fun bundleName(bundleId: String): String? {
        if (bundleId.isBlank() || bundleId.contains('"')) return null
        val out = runQuery("kMDItemCFBundleIdentifier == \"$bundleId\"") ?: return null
        return out.lineSequence()
            .firstOrNull { it.endsWith(".app") }
            ?.substringAfterLast('/')
            ?.removeSuffix(".app")
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Lumen running unpackaged, keyed as `<jvm-bundle-id>/<main-class>` by
     * the collector's runtime disambiguation.
     */
    private fun selfName(bundleId: String): String? {
        if (!bundleId.startsWith("$JVM_BUNDLE_ID/")) return null
        val mainClass = System.getProperty("sun.java.command")
            ?.substringBefore(' ')
            ?.substringAfterLast('.')
        return if (bundleId.substringAfter('/') == mainClass) "Lumen" else null
    }

    companion object {
        private const val JVM_BUNDLE_ID = "net.java.openjdk.java"
        private const val TIMEOUT_SECONDS = 2L

        /** Shells out to Spotlight. Any failure is a null, never a throw. */
        fun mdfind(query: String): String? = runCatching {
            val proc = ProcessBuilder("/usr/bin/mdfind", query)
                .redirectErrorStream(false)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                null
            } else {
                out
            }
        }.getOrNull()
    }
}
