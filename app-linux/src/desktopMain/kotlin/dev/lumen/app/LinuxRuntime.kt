package dev.lumen.app

import dev.lumen.app.common.ScrobbleController
import dev.lumen.app.common.SyncController
import dev.lumen.app.keychain.LinuxKeychain
import dev.lumen.app.keychain.LinuxSecretStore
import dev.lumen.app.media.MprisSource
import dev.lumen.core.clock.UtcDay
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.Setting
import dev.lumen.core.rollup.RollupEngine
import dev.lumen.core.store.JvmLumenStore
import dev.lumen.scrobble.ScrobbleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * The pieces the window ([main]) and the background service
 * ([mainHeadless]) share. Both entry points used to carry their own copies
 * of the store/device-id/persist code.
 */
object LinuxRuntime {

    val dataDir: File = File(System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".local/share"), "lumen")

    fun openStore(): JvmLumenStore = JvmLumenStore.open(File(dataDir, "lumen.db"))

    /** The stable device id from the settings table, created on first run. */
    fun resolveDeviceId(store: JvmLumenStore): DeviceId {
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
        // A fresh device has nothing acked — the watermark must be -1, not the
        // store's 0 default, or the first event (seq 0) never syncs.
        store.setAckedSeq(id, -1)
        return id
    }

    /** Persist one closed session: event, buckets, and the day's rollups. */
    fun persistEvent(store: JvmLumenStore, event: FocusEvent) {
        store.insertEvent(event)
        RollupEngine.bucket(event).forEach(store::insertBucket)
        val day = UtcDay.dayOf(event.startedAtMs)
        val dayStart = UtcDay.boundary(day)
        RollupEngine.rollup(event.deviceId, day, store.bucketsForRange(event.deviceId, dayStart, dayStart + 86_400_000))
            .forEach(store::upsertRollup)
    }

    /** "Linux laptop (hostname)" — how this device appears in other devices' lists. */
    fun deviceLabel(): String {
        val host = runCatching { File("/etc/hostname").readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
        return if (host != null) "Linux ($host)" else "Linux"
    }

    /**
     * Only ONE process may record focus and scrobble: the window and the
     * service share a device id and a database, and two trackers numbering
     * events from the same seq collide on the primary key — one silently
     * loses its events — while two scrobblers send every song twice.
     * Whoever holds this lock tracks; the other only reads.
     */
    fun tryBecomeTracker(): FileLock? = heldLock ?: runCatching {
        dataDir.mkdirs()
        val channel = RandomAccessFile(File(dataDir, "tracker.lock"), "rw").channel
        channel.tryLock() ?: run {
            channel.close()
            null
        }
    }.getOrNull().also { heldLock = it }

    /** Held for the life of the process; the OS releases it on exit or crash. */
    @Volatile
    private var heldLock: FileLock? = null

    /** Open a link in the user's browser. */
    fun openUrl(url: String) {
        val opened = runCatching { ProcessBuilder("xdg-open", url).start(); true }.getOrDefault(false)
        if (!opened) {
            runCatching {
                if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            }
        }
    }

    class Services(
        val store: JvmLumenStore,
        val deviceId: DeviceId,
        val scope: CoroutineScope,
        val sync: SyncController,
        val scrobble: ScrobbleController,
        val media: MprisSource,
    )

    fun services(store: JvmLumenStore, deviceId: DeviceId): Services {
        // IO: everything here blocks — SQLite, secret-tool, sockets.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secrets = LinuxSecretStore(dataDir)
        val sync = SyncController(store, deviceId, LinuxKeychain(secrets), secrets, deviceLabel(), scope)
        val media = MprisSource()
        val scrobble = ScrobbleController(
            ScrobbleManager(store, secrets, deviceId, background = scope),
            scope,
            openUrl = ::openUrl,
            source = media.state,
        )
        return Services(store, deviceId, scope, sync, scrobble, media)
    }
}
