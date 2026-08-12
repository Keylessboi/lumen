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
import dev.lumen.core.collector.AppNameResolver
import dev.lumen.core.collector.PermissionState
import dev.lumen.core.model.DeviceId
import dev.lumen.core.session.DayAccumulator
import dev.lumen.core.session.FocusSessionTracker
import dev.lumen.ui.AppTotal
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val collector = UsageStatsCollector(applicationContext)
        val nameResolver: AppNameResolver = PackageManagerNameResolver(applicationContext)

        setContent {
            val tracker = remember { FocusSessionTracker(DeviceId()) }
            val day = remember { DayAccumulator() }

            var totals by remember { mutableStateOf(emptyList<AppTotal>()) }
            var liveApp by remember { mutableStateOf<String?>(null) }
            var liveSinceMs by remember { mutableStateOf(0L) }
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            val permission = remember { collector.permissionState() }

            LaunchedEffect(permission) {
                // Without Usage Access there is nothing to collect. Say so in
                // the screen's own language rather than starting a stream that
                // silently yields nothing, which is indistinguishable from an
                // idle user.
                if (permission !is PermissionState.Granted) return@LaunchedEffect

                collector.focusChanges().collect { change ->
                    day.remember(change)
                    tracker.onChange(change)?.let { closed ->
                        day.add(closed)
                        totals = day.snapshot().map { (appKey, ms) ->
                            AppTotal(
                                appKey = appKey,
                                displayName = nameResolver.resolve(appKey) ?: day.nameFor(appKey),
                                totalMs = ms,
                            )
                        }
                    }
                    liveApp = nameResolver.resolve(change.appKey) ?: change.appKey.value
                    liveSinceMs = change.atMs
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1000)
                }
            }

            val liveMs = if (liveSinceMs > 0) (now - liveSinceMs).coerceAtLeast(0) else 0

            TodayScreen(
                totals = totals,
                totalMs = day.totalMs() + liveMs,
                liveApp = liveApp,
                reducedMotion = false,
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
