package dev.lumen.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import dev.lumen.app.collector.HyprlandCollector
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Lumen",
        state = rememberWindowState(size = DpSize(1080.dp, 720.dp)),
    ) {
        // M2 dev harness: live focus stream until the real UI lands.
        val changes by produceState(emptyList<dev.lumen.core.collector.FocusChange>()) {
            value = HyprlandCollector()
                .focusChanges()
                .take(20)
                .toList()
        }
        LumenDevScreen(changes)
    }
}

@Composable
fun LumenDevScreen(changes: List<dev.lumen.core.collector.FocusChange>) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0E1116)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text("Lumen — live focus stream (dev harness)", color = Color(0xFF9AA4B2))
                Text("${changes.size}/20 events captured", color = Color(0xFF7C9CF5))
                changes.forEach { c ->
                    Text(
                        text = "${c.appKey.value}  —  ${c.displayName ?: "(no title)"}",
                        color = Color(0xFFE8EAED),
                    )
                }
            }
        }
    }
}
