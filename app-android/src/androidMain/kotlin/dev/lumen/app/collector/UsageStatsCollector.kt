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

    override val capabilities = CollectorCapabilities(
        isRealtime = false,
        canBackfill = true,
        backfillHorizonMs = BACKFILL_HORIZON_MS,
        pollIntervalMs = pollIntervalMs,
        // TRUE only because SCREEN_NON_INTERACTIVE / KEYGUARD_SHOWN are now
        // translated into idle transitions below. It was declared true while
        // only MOVE_TO_FOREGROUND was handled, which meant a phone in a
        // pocket kept accruing time to the last app forever — the engine
        // believed idle was observable and simply never received one.
        detectsIdle = true,
    )

    override fun permissionState(): PermissionState {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
            as android.app.AppOpsManager
        // unsafeCheckOpNoThrow is API 29+. minSdk is 26, so on Android 8, 8.1
        // and 9 this threw NoSuchMethodError the first time the permission was
        // checked — which is at startup, before the UI draws. The pre-29 name
        // is checkOpNoThrow: deprecated, still functional, and the only option
        // on those releases.
        @Suppress("DEPRECATION")
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return when (mode) {
            android.app.AppOpsManager.MODE_ALLOWED -> PermissionState.Granted
            else -> PermissionState.Required(
                rationale = "Lumen needs Usage Access to see which apps you use.",
                settingsHint = "Settings → Apps → Lumen → Usage Access",
            )
        }
    }

    override fun focusChanges(): Flow<FocusChange> = flow {
        var lastEmitted: FocusChange? = null

        // Seed with the current foreground app so the engine has a baseline.
        currentForeground()?.let {
            emit(it)
            lastEmitted = it
        }

        // A CURSOR, not a sliding window. The previous version recomputed
        // `now - pollInterval` each pass, so anything happening while the
        // query itself ran fell between two windows and was lost — a silent
        // gap on every single poll.
        var cursorMs = System.currentTimeMillis()

        while (true) {
            delay(pollIntervalMs)
            val until = System.currentTimeMillis()
            val batch = queryChanges(cursorMs, until)
            // Advance only over what was actually queried, so a slow query
            // widens the next window instead of dropping the difference.
            cursorMs = until

            for (change in batch) {
                // The seam requires emitting only on CHANGE. distinctBy on
                // (app, timestamp) does not do that — it removes exact
                // duplicates, and carries no memory across polls, so a
                // still-foreground app re-emitted on every batch.
                if (change.appKey == lastEmitted?.appKey && change.isIdle == lastEmitted?.isIdle) continue
                emit(change)
                lastEmitted = change
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun backfill(sinceMs: Long): List<FocusChange> =
        queryChanges(sinceMs, System.currentTimeMillis())

    /**
     * Read MOVE_TO_FOREGROUND events from UsageStats. Events are batched
     * and retained by the OS for days, which is what makes backfill
     * possible (docs/e2ee.md §5: Android retains events, unlike Linux).
     */
    private fun queryChanges(sinceMs: Long, untilMs: Long): List<FocusChange> {
        val events = usageStatsManager.queryEvents(sinceMs, untilMs)
        val changes = mutableListOf<FocusChange>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    changes += FocusChange(
                        appKey = AppKey(event.packageName ?: "unknown"),
                        atMs = event.timeStamp,
                        displayName = null, // package names only; no titles on Android
                        isIdle = false,
                    )

                // Screen off or locked: the user has stopped, and without
                // this the last app accrues time until the phone is next
                // unlocked. This is the difference between a screen-time
                // number and a wall clock.
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN ->
                    changes += FocusChange(
                        appKey = AppKey(""),
                        atMs = event.timeStamp,
                        displayName = null,
                        isIdle = true,
                    )
            }
        }
        // Chronological: UsageStats does not guarantee ordering across event
        // types, and the engine derives durations from adjacency.
        return changes.sortedBy { it.atMs }
    }

    /**
     * Best-effort current foreground.
     *
     * The window is generous because the last foreground transition can be
     * hours old — someone reading in one app all morning has no recent
     * event, and a short window would return null and leave the engine with
     * no baseline at all.
     */
    private fun currentForeground(): FocusChange? {
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - SEED_LOOKBACK_MS, now)
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

        /** How far back to look for a baseline foreground app on startup. */
        private const val SEED_LOOKBACK_MS = 12L * 60 * 60 * 1000
    }
}
