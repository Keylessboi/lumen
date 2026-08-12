package dev.lumen.app.collector

import dev.lumen.core.clock.UtcDay
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.SyncState
import dev.lumen.core.rollup.RollupEngine
import dev.lumen.core.store.JvmLumenStore
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Integration test: Hyprland collector -> RollupEngine -> SQLite store.
 * Proves the full data path on a real session. Not part of the shipped app.
 */
fun main() = runBlocking {
    val deviceId = DeviceId("integration-test-device")
    val dbFile = File("/tmp/lumen-integration.db")
    dbFile.delete()
    val store = JvmLumenStore.open(dbFile)

    val collector = HyprlandCollector()
    println("collector permission: ${collector.permissionState()}")

    println("capturing up to 8 focus changes (15s window)...")
    val changes = withTimeout(15_000) {
        collector.focusChanges().take(8).toList()
    }
    println("captured ${changes.size} changes")

    // Phase 1: transitions -> events. Duration is the gap to the next change;
    // the last one is left open (60s nominal). This is the seam's contract:
    // collectors report transitions, the engine derives durations.
    val events = changes.mapIndexed { i, change ->
        val end = changes.getOrNull(i + 1)?.atMs ?: (change.atMs + 60_000)
        FocusEvent(
            seq = i.toLong(),
            deviceId = deviceId,
            appKey = change.appKey,
            titleHash = null,
            startedAtMs = change.atMs,
            durationMs = (end - change.atMs).coerceAtLeast(1),
            category = null,
            syncState = SyncState.LOCAL,
        )
    }
    events.forEach { store.insertEvent(it) }

    // Phase 2: events -> buckets -> rollups via the engine, then store.
    val dayStart = UtcDay.boundary(UtcDay.today())
    val dayEnd = dayStart + 86_400_000
    events.forEach { event ->
        RollupEngine.bucket(event).forEach { store.insertBucket(it) }
    }
    val buckets = store.bucketsForRange(deviceId, dayStart, dayEnd)
    println("buckets in store: ${buckets.size}")
    buckets.groupBy { it.appKey.value }.forEach { (app, bs) ->
        val total = bs.sumOf { it.activeMs }
        println("  $app: $total ms across ${bs.size} buckets")
    }

    // Phase 3: rollup per app for today.
    val today = UtcDay.today()
    buckets.groupBy { it.appKey }.forEach { (app, bs) ->
        val rollups = RollupEngine.rollup(deviceId, today, bs)
        rollups.forEach { store.upsertRollup(it) }
    }
    val rollups = store.rollupsForDay(deviceId, today)
    println("rollups for $today: ${rollups.size}")
    rollups.forEach { println("  ${it.appKey.value}: ${it.totalMs} ms") }

    println("=== INTEGRATION RESULT ===")
    println("events=${events.size} buckets=${buckets.size} rollups=${rollups.size} db=${dbFile.absolutePath} (${dbFile.length()} bytes)")
}
