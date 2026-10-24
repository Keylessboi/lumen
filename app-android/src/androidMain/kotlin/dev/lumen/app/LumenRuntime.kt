package dev.lumen.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.lumen.app.collector.UsageStatsCollector
import dev.lumen.app.common.ScrobbleController
import dev.lumen.app.common.SyncController
import dev.lumen.app.media.MediaListenerService
import dev.lumen.core.clock.UtcDay
import dev.lumen.core.crypto.AndroidKeychain
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.Setting
import dev.lumen.core.store.AndroidLumenStore
import dev.lumen.scrobble.MediaSourceState
import dev.lumen.scrobble.NowPlaying
import dev.lumen.scrobble.ScrobbleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * One per process: the store, the importer, sync and scrobbling, shared by
 * [MainActivity], [SyncWorker] and [MediaListenerService].
 *
 * The Activity used to own all of it, so nothing ran unless the app was on
 * screen, and every Activity recreation (a rotation) opened a second
 * database driver over the same file.
 */
class LumenRuntime private constructor(private val app: Context) {

    val store: AndroidLumenStore = AndroidLumenStore.open(app)
    val deviceId: DeviceId = resolveDeviceId(store)
    val collector = UsageStatsCollector(app)
    val importer = UsageImporter(store, deviceId, collector)
    // IO: everything here blocks — SQLite, the Keystore, sockets.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secrets = AndroidSecretStore(app)

    val sync = SyncController(store, deviceId, AndroidKeychain(app), secrets, deviceLabel(), scope)

    /** Fed by [MediaListenerService]. */
    val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val mediaSource = MutableStateFlow<MediaSourceState>(MediaSourceState.Ready)

    val scrobble = ScrobbleController(
        ScrobbleManager(store, secrets, deviceId, background = scope),
        scope,
        openUrl = ::openUrl,
        source = mediaSource,
        sourceActionLabel = "Allow notification access",
    )

    init {
        refreshMediaAccess()
        scrobble.attach(nowPlaying.filterNotNull())
    }

    /**
     * Android only shares media sessions with an enabled notification
     * listener — the same access every Android scrobbler needs. Lumen reads
     * media sessions through it, never notification content.
     */
    fun refreshMediaAccess() {
        val enabled = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners")
            ?.split(':')
            ?.mapNotNull { ComponentName.unflattenFromString(it) }
            ?.any { it.packageName == app.packageName } == true
        mediaSource.value = if (enabled) {
            MediaSourceState.Ready
        } else {
            MediaSourceState.NeedsPermission(
                "To see what is playing, Lumen needs notification access. It reads the music players' " +
                    "media controls only — never your notifications.",
            )
        }
    }

    fun openUrl(url: String) {
        runCatching {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openNotificationAccessSettings() = openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun openUsageAccessSettings() = openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    private fun openSettings(action: String) {
        runCatching { app.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun deviceLabel(): String {
        val model = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        return if (model.isBlank()) "Android" else "Android ($model)"
    }

    companion object {
        @Volatile private var instance: LumenRuntime? = null

        /** The runtime if it already exists — never creates it (safe on the main thread). */
        fun peek(): LumenRuntime? = instance

        fun get(context: Context): LumenRuntime =
            instance ?: synchronized(this) {
                instance ?: LumenRuntime(context.applicationContext).also { instance = it }
            }

        private fun resolveDeviceId(store: AndroidLumenStore): DeviceId {
            val existing = store.setting("device_id")
            if (existing != null) return DeviceId(String(existing.value, Charsets.UTF_8))
            val id = DeviceId()
            store.upsertSetting(
                Setting("device_id", id.value.toByteArray(Charsets.UTF_8), System.currentTimeMillis(), UtcDay.today(), id),
            )
            // Nothing acked yet: -1, not the store's 0 default, or seq 0 never syncs.
            store.setAckedSeq(id, -1)
            return id
        }
    }
}
