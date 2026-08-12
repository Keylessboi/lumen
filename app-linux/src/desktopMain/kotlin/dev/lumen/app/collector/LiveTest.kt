package dev.lumen.app.collector

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Live test harness — runs the Hyprland collector against the real
 * compositor socket and prints the first focus changes. Not part of the
 * shipped app; exists to prove the collector on real hardware.
 */
fun main() = runBlocking {
    val collector = HyprlandCollector()
    println("permission: ${collector.permissionState()}")
    println("capabilities: $collector")

    println("collecting up to 6 focus changes (12s window)...")
    val changes = withTimeout(12_000) {
        collector.focusChanges().take(6).toList()
    }
    println("=== ${changes.size} focus changes ===")
    changes.forEach { c ->
        println("  app=${c.appKey} name=${c.displayName ?: "-"} idle=${c.isIdle} at=${c.atMs}")
    }
}
