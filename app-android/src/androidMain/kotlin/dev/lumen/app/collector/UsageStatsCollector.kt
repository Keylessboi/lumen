package dev.lumen.app.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import dev.lumen.core.collector.AppUsageCollector
import dev.lumen.core.collector.CollectorCapabilities
import dev.lumen.core.collector.FocusChange
import dev.lumen.core.collector.PermissionState
import dev.lumen.core.model.AppKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Android UsageStats collector — Agent A, M3.
 *
 * Reads the OS's own usage data via UsageStatsManager, which IS the API
 * behind Digital Wellbeing (docs/plan.md: "UsageStatsManager is
 * AUTHORITATIVE per-app foreground; accessibility service NOT required").
 * This is the "shell over the OS's built-in screen time" model from
 * discussion #14 — no custom sensing, just reading what Android already
 * measures.
 *
 * Transitions only, per the seam: MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND
 * events become [FocusChange]; the engine derives durations centrally.
 */
class UsageStatsCollector(
    private val context: Context,
    private val pollIntervalMs: Long = 5_000,
) : AppUsageCollector {

    private val usageStatsManager: UsageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /** The app's own package — excluded from reported focus (design-spec). */
    override val selfAppKey: AppKey = AppKey(context.packageName)

    override val capabilities = CollectorCapabilities(
        isRealtime = false,
        canBackfill = true,
        backfillHorizonMs = BACKFILL_HORIZON_MS,
        pollIntervalMs = pollIntervalMs,
        detectsIdle = true,
    )

    override fun permissionState(): PermissionState {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
            as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return when (mode) {
            android.app.AppOpsManager.MODE_ALLOWED -> PermissionState.Granted
            else -> PermissionState.Required(
                rationale = "Lumen needs Usage Access to see which apps you use.",
                settingsHint = "Settings → Apps → Lumen → Usage Access",
            )
        }
    }

    override fun focusChanges(): Flow<FocusChange> = flow {
        // Seed with the current foreground app so the engine has a baseline.
        currentForeground()?.let { emit(it) }

        while (true) {
            // Events since the last poll; UsageStats batches them.
            val since = System.currentTimeMillis() - pollIntervalMs
            queryChanges(since).forEach { emit(it) }
            delay(pollIntervalMs)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun backfill(sinceMs: Long): List<FocusChange> =
        queryChanges(sinceMs)

    /**
     * Read MOVE_TO_FOREGROUND events from UsageStats. Events are batched
     * and retained by the OS for days, which is what makes backfill
     * possible (docs/e2ee.md §5: Android retains events, unlike Linux).
     */
    private fun queryChanges(sinceMs: Long): List<FocusChange> {
        val events = usageStatsManager.queryEvents(sinceMs, System.currentTimeMillis())
        val changes = mutableListOf<FocusChange>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                changes += FocusChange(
                    appKey = AppKey(event.packageName ?: "unknown"),
                    atMs = event.timeStamp,
                    displayName = null, // package names only; no titles on Android
                    isIdle = false,
                )
            }
        }
        // Dedupe consecutive identical apps, per the seam contract.
        return changes.distinctBy { it.appKey.value to it.atMs }
    }

    /** Best-effort current foreground via a short query window. */
    private fun currentForeground(): FocusChange? {
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - 60_000, now)
        val event = UsageEvents.Event()
        var last: FocusChange? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last = FocusChange(
                    appKey = AppKey(event.packageName ?: "unknown"),
                    atMs = event.timeStamp,
                    isIdle = false,
                )
            }
        }
        return last
    }

    companion object {
        /** UsageStats retains events for ~days; backfill can reach that far. */
        private const val BACKFILL_HORIZON_MS = 3L * 24 * 60 * 60 * 1000
    }
}
