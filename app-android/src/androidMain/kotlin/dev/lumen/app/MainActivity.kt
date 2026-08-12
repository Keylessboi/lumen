package dev.lumen.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.lumen.app.collector.UsageStatsCollector
import dev.lumen.app.names.PackageManagerNameResolver
import dev.lumen.core.clock.LocalDay
import dev.lumen.core.collector.AppNameResolver
import dev.lumen.core.collector.PermissionState
import dev.lumen.core.model.DeviceId
import dev.lumen.core.session.DayAccumulator
import dev.lumen.core.session.FocusSessionTracker
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.AppTotal
import dev.lumen.ui.HistoryState
import dev.lumen.ui.TodayScreen
import kotlinx.coroutines.delay

/**
 * Lumen for Android.
 *
 * Renders the SHARED `:ui` TodayScreen — byte-for-byte the same composable
 * macOS and Linux draw, so the three platforms cannot drift apart visually.
 * It previously drew its own two-line scaffold with hex colours copied out of
 * `docs/design-spec.md`, which is the drift the shared module exists to stop.
 *
 * Storage is in-memory ([DayAccumulator]) pending `LumenStore` at M3. The
 * surface is the real one; only what is behind it is provisional.
 */
class MainActivity : ComponentActivity() {

    /**
     * Whether the system asks for reduced motion.
     *
     * `docs/design-spec.md` requires respecting it on both platforms. This
     * was hardcoded to false, so the setting worked on macOS and was silently
     * ignored here — worse than not claiming support, because the spec says
     * we do.
     *
     * `ANIMATOR_DURATION_SCALE` is the value Android's own accessibility
     * "Remove animations" toggle writes, and what the platform widgets read.
     */
    private fun reducedMotionEnabled(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val collector = UsageStatsCollector(applicationContext)
        val nameResolver: AppNameResolver = PackageManagerNameResolver(applicationContext)

        setContent {
            val tracker = remember { FocusSessionTracker(DeviceId()) }
            val day = remember { DayAccumulator() }

            var totals by remember { mutableStateOf(emptyList<AppTotal>()) }
            var liveAppKey by remember { mutableStateOf<AppKey?>(null) }
            var liveAppName by remember { mutableStateOf<String?>(null) }
            var liveSinceMs by remember { mutableStateOf(0L) }
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            val permission = remember { collector.permissionState() }

            // The app list must tick with the live session, not freeze until
            // the next focus change. Without this the top total climbs every
            // second while the rows beneath stay static, which reads as a
            // broken screen even though the data is fine.
            fun refreshTotals(liveMs: Long) {
                val base = day.snapshot().associate { it.first to it.second }.toMutableMap()
                val liveKey = liveAppKey
                if (liveKey != null && liveMs > 0) {
                    base.merge(liveKey, liveMs, Long::plus)
                }
                totals = base.entries
                    // AppKey("") is the screen-locked/idle transition, not an
                    // app. It is already inside totalMs; surfacing it as the
                    // largest row would present "lock screen" as an app.
                    .filter { it.key.value.isNotBlank() }
                    .sortedByDescending { it.value }
                    .map { (appKey, ms) ->
                        AppTotal(
                            appKey = appKey,
                            displayName = nameResolver.resolve(appKey) ?: day.nameFor(appKey),
                            totalMs = ms,
                        )
                    }
            }

            LaunchedEffect(permission) {
                // Without Usage Access there is nothing to collect. Say so in
                // the screen's own language rather than starting a stream that
                // silently yields nothing, which is indistinguishable from an
                // idle user.
                if (permission !is PermissionState.Granted) return@LaunchedEffect

                // Android retains usage events for ~3 days (the collector's
                // backfillHorizonMs). The live stream only sees events from
                // this point on, so without a backfill the Today screen would
                // start at zero every launch even though the user has been
                // using the phone for hours. UsageStatsManager is the same
                // backend Digital Wellbeing reads — this is how the app shows
                // the real day.
                val zone = LocalDay.zoneOf(null)
                val today = LocalDay.today(zone)
                val sinceMs = LocalDay.startOfDayMs(today, zone)
                collector.backfill(sinceMs).forEach { change ->
                    day.remember(change)
                    tracker.onChange(change)?.let { closed ->
                        day.add(closed)
                    }
                    liveAppKey = change.appKey
                    liveAppName = nameResolver.resolve(change.appKey) ?: change.appKey.value
                    liveSinceMs = change.atMs
                }
                refreshTotals(0)

                collector.focusChanges().collect { change ->
                    day.remember(change)
                    tracker.onChange(change)?.let { closed ->
                        day.add(closed)
                        refreshTotals(0)
                    }
                    liveAppKey = change.appKey
                    liveAppName = nameResolver.resolve(change.appKey) ?: change.appKey.value
                    liveSinceMs = change.atMs
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    now = System.currentTimeMillis()
                    val liveMs = if (liveSinceMs > 0 && liveAppKey != null && liveAppKey!!.value.isNotBlank())
                        (now - liveSinceMs).coerceAtLeast(0) else 0
                    refreshTotals(liveMs)
                    delay(1000)
                }
            }

            // The visible total must exclude the idle key (AppKey("")) — the
            // screen-locked sessions. They are needed internally to close the
            // previous app's session, but they are not screen time: a phone
            // locked from 2am to 11am would otherwise show an 9-hour "day".
            fun visibleTotalMs(): Long =
                day.snapshot().filter { it.first.value.isNotBlank() }.sumOf { it.second }

            val liveMs = if (liveSinceMs > 0 && liveAppKey != null && liveAppKey!!.value.isNotBlank())
                (now - liveSinceMs).coerceAtLeast(0) else 0

            TodayScreen(
                totals = totals,
                totalMs = visibleTotalMs() + liveMs,
                liveApp = null,
                showLiveApp = false,
                reducedMotion = remember { reducedMotionEnabled() },
                historyState = when (permission) {
                    is PermissionState.Granted -> HistoryState.Hidden
                    is PermissionState.Required -> HistoryState.Message(
                        "${permission.rationale} ${permission.settingsHint}",
                    )
                    is PermissionState.Unsupported -> HistoryState.Message(permission.reason)
                },
            )
        }
    }
}
