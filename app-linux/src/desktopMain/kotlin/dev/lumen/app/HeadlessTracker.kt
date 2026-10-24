package dev.lumen.app

import dev.lumen.app.collector.LinuxCollectors
import dev.lumen.core.collector.PermissionState
import dev.lumen.core.session.FocusSessionTracker
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Plain-JVM entry for the systemd service (`lumen-tracker.service`).
 *
 * Deliberately NOT the Compose `MainKt` entry: the Compose packaged binary
 * wraps main in `libapplauncher.so`, which initialises the AWT/Skiko runtime
 * and aborts with `__cxa_pure_virtual` under a headless systemd unit (no
 * display). This class is invoked with plain `java -cp` by the launcher
 * script, so only the tracking pipeline loads.
 *
 * Runs everything that must not depend on a window being open: focus
 * tracking, sync, scrobbling.
 */
fun main(args: Array<String>): Unit = mainHeadless()

/** Headless tracker — the tracking pipeline with no window. */
fun mainHeadless(): Unit = runBlocking {
    // The window may hold the tracker lock (it was opened first). Wait for it
    // rather than exit: exiting 0 meant systemd never restarted the service,
    // and after the window closed nothing tracked until the next login.
    if (LinuxRuntime.tryBecomeTracker() == null) {
        System.err.println("lumen: the Lumen window is tracking; waiting for it to close")
        while (LinuxRuntime.tryBecomeTracker() == null) delay(LOCK_RETRY_MS)
    }
    val store = LinuxRuntime.openStore()
    val deviceId = LinuxRuntime.resolveDeviceId(store)
    val services = LinuxRuntime.services(store, deviceId)

    // Sync and scrobbling run whether or not focus tracking is possible here.
    services.sync.startLoop()
    services.scrobble.attach(services.media.playback())

    val collector = LinuxCollectors.detect()
    val permission = collector.permissionState()
    if (permission !is PermissionState.Granted) {
        System.err.println("lumen: focus tracking unavailable: $permission")
        awaitCancellation()
    }
    // Seed the seq counter from the store: without it a restart numbers from
    // 0 and every new event collides on the (device_id, seq) PK, silently
    // dropped by INSERT OR IGNORE — the day stops growing.
    val tracker = FocusSessionTracker(deviceId, store.lastEventSeq(deviceId) + 1)
    launch {
        collector.focusChanges().collect { change ->
            tracker.onChange(change)?.let { closed -> LinuxRuntime.persistEvent(store, closed) }
        }
    }
    awaitCancellation()
}

private const val LOCK_RETRY_MS = 15_000L
