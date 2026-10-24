package dev.lumen.scrobble

import kotlinx.serialization.Serializable

/** One piece of music, as a media player reports it. */
@Serializable
data class Track(
    val artist: String,
    val title: String,
    val album: String? = null,
    val durationMs: Long? = null,
) {
    /** Same song, regardless of what else the player says about it. */
    fun sameSongAs(other: Track?): Boolean =
        other != null && artist.equals(other.artist, true) && title.equals(other.title, true) &&
            (album ?: "").equals(other.album ?: "", true)
}

/**
 * What a platform's media source sees right now. [player] is the MPRIS bus
 * name suffix on Linux ("spotify", "firefox") or the package name on
 * Android.
 */
data class NowPlaying(
    val player: String,
    val track: Track?,
    val playing: Boolean,
    /** Position within the track, when the player reports one. */
    val positionMs: Long? = null,
)

/** A play that crossed the threshold, ready to send. */
@Serializable
data class Scrobble(
    val track: Track,
    /** When the play STARTED, epoch seconds — what every service wants. */
    val startedAtSec: Long,
    val player: String,
)

enum class ScrobbleService(val displayName: String) {
    LASTFM("Last.fm"),
    LIBREFM("Libre.fm"),
    LISTENBRAINZ("ListenBrainz"),
}

/** Platforms that can say what is playing. */
sealed interface MediaSourceState {
    data object Ready : MediaSourceState
    /** Needs a user action — e.g. Android notification access. */
    data class NeedsPermission(val explanation: String) : MediaSourceState
    data class Unavailable(val reason: String) : MediaSourceState
}
