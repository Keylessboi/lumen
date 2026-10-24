package dev.lumen.scrobble

/**
 * Decides when a play counts — the rule Last.fm and ListenBrainz both
 * publish: a track longer than 30 seconds scrobbles once it has PLAYED for
 * half its length or 4 minutes, whichever comes first. Paused time does not
 * count. A track with no known length scrobbles after 4 minutes.
 *
 * Fed with snapshots from a media source plus periodic [tick]s; emits each
 * qualifying play exactly once. Pure — the clock is an argument.
 */
class PlayTracker(
    /** Players never scrobbled (e.g. web browsers, unless opted in). */
    private val ignore: (player: String) -> Boolean = { false },
) {
    sealed interface Event {
        /** A new track started playing — services show it as "now playing". */
        data class Started(val track: Track, val player: String) : Event
        data class Qualified(val scrobble: Scrobble) : Event
    }

    private var player: String? = null
    private var track: Track? = null
    private var startedAtMs = 0L
    private var playedMs = 0L
    private var resumedAtMs: Long? = null
    private var done = false
    private var lastPositionMs: Long? = null

    val current: Track? get() = track

    fun update(now: NowPlaying, atMs: Long): List<Event> {
        val incoming = now.track?.takeIf { it.artist.isNotBlank() && it.title.isNotBlank() && !ignore(now.player) }
        val events = mutableListOf<Event>()

        val restarted = incoming != null && done && incoming.sameSongAs(track) && now.player == player &&
            (lastPositionMs ?: 0) - (now.positionMs ?: Long.MAX_VALUE) > REPEAT_JUMP_MS
        if (incoming == null || !incoming.sameSongAs(track) || now.player != player || restarted) {
            events += settle(atMs)
            reset()
            if (incoming != null) {
                player = now.player
                track = incoming
                startedAtMs = atMs - (now.positionMs ?: 0).coerceIn(0, MAX_BACKDATE_MS)
                if (now.playing) {
                    resumedAtMs = atMs
                    events += Event.Started(incoming, now.player)
                }
            }
        } else {
            val wasPlaying = resumedAtMs != null
            if (wasPlaying && !now.playing) {
                playedMs += atMs - resumedAtMs!!
                resumedAtMs = null
            } else if (!wasPlaying && now.playing) {
                resumedAtMs = atMs
                if (playedMs == 0L) events += Event.Started(incoming, now.player)
            }
            // Players refine metadata mid-play (length arrives late); keep the richer copy.
            if (incoming.durationMs != null && track?.durationMs == null) track = track?.copy(durationMs = incoming.durationMs)
        }
        lastPositionMs = now.positionMs
        events += settle(atMs)
        return events
    }

    /** Call every few seconds so a play qualifies without waiting for a state change. */
    fun tick(atMs: Long): List<Event> = settle(atMs)

    private fun settle(atMs: Long): List<Event> {
        val t = track ?: return emptyList()
        if (done) return emptyList()
        val threshold = thresholdMs(t) ?: return emptyList()
        val played = playedMs + (resumedAtMs?.let { atMs - it } ?: 0)
        if (played < threshold) return emptyList()
        done = true
        return listOf(Event.Qualified(Scrobble(t, startedAtMs / 1000, player ?: "")))
    }

    private fun reset() {
        player = null
        track = null
        playedMs = 0
        resumedAtMs = null
        done = false
        lastPositionMs = null
    }

    companion object {
        const val MIN_TRACK_MS = 30_000L
        const val MAX_WAIT_MS = 240_000L
        private const val REPEAT_JUMP_MS = 10_000L
        private const val MAX_BACKDATE_MS = 6 * 60 * 60 * 1000L

        /** Null when the track can never scrobble (30 s or shorter). */
        fun thresholdMs(track: Track): Long? {
            val length = track.durationMs ?: return MAX_WAIT_MS
            if (length <= MIN_TRACK_MS) return null
            return minOf(length / 2, MAX_WAIT_MS)
        }

        private val BROWSERS = listOf(
            "firefox", "chromium", "chrome", "brave", "vivaldi", "opera", "edge", "epiphany", "librewolf",
            "org.mozilla", "com.android.chrome", "com.brave", "com.opera", "com.microsoft.emmx",
        )

        /** Browsers mostly play video, not music — scrobbling them is opt-in. */
        fun isBrowser(player: String): Boolean = BROWSERS.any { player.lowercase().contains(it) }
    }
}
