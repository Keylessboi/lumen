package dev.lumen.app

import dev.lumen.app.collector.HyprlandCollector
import dev.lumen.core.clock.UtcDay
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.Setting
import dev.lumen.core.rollup.RollupEngine
import dev.lumen.core.session.FocusSessionTracker
import dev.lumen.core.store.JvmLumenStore
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Plain-JVM entry for the systemd service (`lumen-tracker.service`).
 *
 * Deliberately NOT the Compose `MainKt` entry: the Compose packaged binary
 * wraps main in `libapplauncher.so`, which initialises the AWT/Skiko runtime
 * and aborts with `__cxa_pure_virtual` under a headless systemd unit (no
 * display). This class is invoked with plain `java -cp` by the launcher
 * script, so only the tracking pipeline loads.
 */
fun main(args: Array<String>) = mainHeadless()

/** Headless tracker — the tracking pipeline with no window. */
fun mainHeadless() = runBlocking {
    val collector = HyprlandCollector()
    val store = openStore()
    val deviceId = resolveDeviceId(store)
    // Same seq seeding as the windowed app: without it a tracker restart
    // resets to seq 0 and every new event collides on the (device_id, seq)
    // PK, silently dropped by INSERT OR IGNORE — the day stops growing.
    val tracker = FocusSessionTracker(deviceId, store.lastEventSeq(deviceId) + 1)

    if (collector.permissionState() !is dev.lumen.core.collector.PermissionState.Granted) {
        System.err.println("lumen: ${collector.permissionState()}")
        return@runBlocking
    }

    collector.focusChanges().collect { change ->
        tracker.onChange(change)?.let { closed ->
            persistEvent(store, closed)
        }
    }
}

/** Open (or create) the database at the standard data location. */
private fun openStore(): JvmLumenStore {
    val dataDir = File(System.getProperty("user.home"), ".local/share/lumen")
    return JvmLumenStore.open(File(dataDir, "lumen.db"))
}

/** Resolve the stable device id from the settings table, creating it on first run. */
private fun resolveDeviceId(store: JvmLumenStore): DeviceId {
    val existing = store.setting("device_id")
    if (existing != null) return DeviceId(String(existing.value, Charsets.UTF_8))
    val id = DeviceId()
    store.upsertSetting(
        Setting(
            key = "device_id",
            value = id.value.toByteArray(Charsets.UTF_8),
            updatedAtMs = System.currentTimeMillis(),
            updatedDayUtc = UtcDay.today(),
            deviceId = id,
        ),
    )
    return id
}

/** Persist one closed session: event, buckets, and the day's rollups. */
private fun persistEvent(store: JvmLumenStore, event: FocusEvent) {
    store.insertEvent(event)
    RollupEngine.bucket(event).forEach(store::insertBucket)
    val today = UtcDay.dayOf(event.startedAtMs)
    val dayStart = UtcDay.boundary(today)
    val buckets = store.bucketsForRange(event.deviceId, dayStart, dayStart + 86_400_000)
    RollupEngine.rollup(event.deviceId, today, buckets).forEach(store::upsertRollup)
}
