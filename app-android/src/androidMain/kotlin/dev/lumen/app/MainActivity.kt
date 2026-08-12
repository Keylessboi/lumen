package dev.lumen.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.lumen.app.collector.UsageStatsCollector

/**
 * Main activity — M3 scaffold. Wires the UsageStats collector to a
 * minimal dev screen (mirrors app-linux's dev harness). Real UI lands
 * later in M3.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val collector = UsageStatsCollector(applicationContext)
        setContent {
            LumenAndroidScreen(
                permissionGranted = collector.permissionState() is dev.lumen.core.collector.PermissionState.Granted,
            )
        }
    }
}

@Composable
fun LumenAndroidScreen(permissionGranted: Boolean) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0E1116)) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text("Lumen", color = Color(0xFF7C9CF5))
                Text(
                    if (permissionGranted) "Usage Access granted — tracking active"
                    else "Usage Access needed: Settings → Apps → Lumen → Usage Access",
                    color = Color(0xFFE8EAED),
                )
            }
        }
    }
}
