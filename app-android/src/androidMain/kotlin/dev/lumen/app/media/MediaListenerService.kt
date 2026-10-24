package dev.lumen.app.media

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import dev.lumen.app.LumenRuntime
import dev.lumen.scrobble.NowPlaying
import dev.lumen.scrobble.Track

/**
 * Sees what is playing for scrobbling, through Android media sessions.
 *
 * A notification listener only because that is the permission Android gates
 * `MediaSessionManager.getActiveSessions` behind; no notification is ever
 * read. While enabled, the system keeps it bound, which is what lets
 * scrobbling work with Lumen closed.
 */
class MediaListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var sessions: MediaSessionManager? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        track(controllers.orEmpty())
    }

    override fun onListenerConnected() {
        val runtime = LumenRuntime.get(this)
        runtime.refreshMediaAccess()
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        sessions = manager
        val me = ComponentName(this, MediaListenerService::class.java)
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, me, handler)
            track(manager.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() {
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        untrackAll()
        LumenRuntime.get(this).refreshMediaAccess()
    }

    override fun onDestroy() {
        untrackAll()
        super.onDestroy()
    }

    private fun track(controllers: List<MediaController>) {
        untrackAll()
        controllers.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onSessionDestroyed() = publish()
            }
            controller.registerCallback(callback, handler)
            callbacks[controller] = callback
        }
        publish()
    }

    private fun untrackAll() {
        callbacks.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        callbacks.clear()
    }

    private fun publish() {
        val controllers = callbacks.keys.toList()
        val pick = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
        val now = pick?.let(::snapshot) ?: NowPlaying(player = "", track = null, playing = false)
        LumenRuntime.get(this).nowPlaying.value = now
    }

    private fun snapshot(controller: MediaController): NowPlaying {
        val meta = controller.metadata
        val state = controller.playbackState
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val track = if (!title.isNullOrBlank() && !artist.isNullOrBlank()) {
            Track(artist, title, meta.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }, duration)
        } else {
            null
        }
        val playing = state?.state == PlaybackState.STATE_PLAYING
        // PlaybackState.position is as of lastPositionUpdateTime; extrapolate.
        val position = state?.let {
            if (it.position < 0) null
            else if (playing) it.position + ((SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong()
            else it.position
        }
        return NowPlaying(player = controller.packageName, track = track, playing = playing, positionMs = position)
    }
}
