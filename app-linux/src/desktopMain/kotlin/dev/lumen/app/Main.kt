package dev.lumen.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.lumen.app.collector.HyprlandCollector
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.AppTotal
import dev.lumen.ui.TodayScreen
import kotlinx.coroutines.delay
import java.awt.image.BufferedImage

/**
 * Lumen Linux desktop.
 *
 * The collector stream lives in the APPLICATION scope, NOT inside the Window:
 * closing the window hides it to the tray and tracking keeps running. The old
 * dev harness (produceState + take(20)) stopped forever after 20 events — this
 * is continuous for as long as the process runs.
 */
fun main() = application {
    var isWindowVisible by remember { mutableStateOf(true) }

    // -- Tracking state (hoisted above the window so it survives hide/show) --
    var totals by remember { mutableStateOf<List<AppTotal>>(emptyList()) }
    var liveAppKey by remember { mutableStateOf<String?>(null) }
    var liveAppName by remember { mutableStateOf<String?>(null) }
    var liveSinceMs by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    val collector = remember { HyprlandCollector() }

    // Continuous focus tracking. Runs for the life of the process no matter
    // what the window does.
    LaunchedEffect(collector) {
        collector.focusChanges().collect { change ->
            val at = System.currentTimeMillis()
            val key = change.appKey.value
            when {
                change.isIdle || key.isBlank() -> {
                    // Focus left all windows: commit the live app, close its row.
                    if (liveAppKey != null && liveSinceMs > 0) {
                        totals = commitApp(totals, liveAppKey!!, liveAppName ?: liveAppKey!!, at - liveSinceMs)
                    }
                    liveAppKey = null
                    liveAppName = null
                    liveSinceMs = 0L
                }
                key != liveAppKey -> {
                    // Real switch: commit the previous app, open the new one.
                    if (liveAppKey != null && liveSinceMs > 0) {
                        totals = commitApp(totals, liveAppKey!!, liveAppName ?: liveAppKey!!, at - liveSinceMs)
                    }
                    liveAppKey = key
                    liveAppName = change.displayName?.takeIf { it.isNotBlank() } ?: key
                    liveSinceMs = at
                }
                // else: same app, nothing to do.
            }
        }
    }

    // 1s tick so the big total keeps moving even between app switches.
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val windowState = rememberWindowState(size = DpSize(1080.dp, 720.dp))

    Window(
        onCloseRequest = { isWindowVisible = false }, // hide to tray, keep tracking
        visible = isWindowVisible,
        title = "Lumen",
        state = windowState,
    ) {
        val liveElapsed = if (liveAppKey != null && liveSinceMs > 0) (nowMs - liveSinceMs) else 0L
        TodayScreen(
            totals = totals,
            totalMs = totals.sumOf { it.totalMs } + liveElapsed,
            liveApp = liveAppName ?: liveAppKey,
            reducedMotion = false,
        )
    }

    Tray(
        icon = remember { BitmapPainter(trayIcon()) },
        tooltip = "Lumen — screen time tracker",
        menu = {
            Item("Show Lumen", onClick = { isWindowVisible = true })
            Item("Quit", onClick = ::exitApplication)
        },
    )
}

/** Add [elapsedMs] to the row for [key], creating it when first seen. */
private fun commitApp(
    current: List<AppTotal>,
    key: String,
    name: String,
    elapsedMs: Long,
): List<AppTotal> {
    if (elapsedMs <= 0) return current
    return current.mapIndexed { i, t ->
        if (t.appKey.value == key) t.copy(totalMs = t.totalMs + elapsedMs) else t
    } + if (current.none { it.appKey.value == key }) {
        listOf(AppTotal(appKey = AppKey(key), displayName = name, totalMs = elapsedMs))
    } else {
        emptyList()
    }
}

/** 16x16 accent dot — no asset file to ship for the tray icon. */
private fun trayIcon(): ImageBitmap {
    val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = java.awt.Color(0x7C9CF5)
    g.fillOval(0, 0, 16, 16)
    g.dispose()
    return img.toComposeImageBitmap()
}