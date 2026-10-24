package dev.lumen.app.media

import dev.lumen.scrobble.MediaSourceState
import dev.lumen.scrobble.NowPlaying
import dev.lumen.scrobble.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * What is playing, from MPRIS — the D-Bus interface every Linux media player
 * speaks (Spotify, mpv, VLC, Rhythmbox, browsers, mpd via mpDris2).
 *
 * Reads it with `gdbus` (part of GLib, present on effectively every desktop)
 * by polling, so there is no native D-Bus binding in the distribution — the
 * same subprocess approach the collectors and the keychain take. Polling
 * every [pollMs] is precise enough: scrobble thresholds are measured in
 * minutes.
 *
 * With several players, the one PLAYING wins; with none playing, the most
 * recently seen one is reported as paused.
 */
class MprisSource(private val pollMs: Long = 5_000) {

    private val _state = MutableStateFlow<MediaSourceState>(MediaSourceState.Ready)
    val state: StateFlow<MediaSourceState> = _state.asStateFlow()

    fun playback(): Flow<NowPlaying> = flow {
        if (!gdbusAvailable()) {
            _state.value = MediaSourceState.Unavailable(
                "Scrobbling needs gdbus (from GLib) to see your media players. Install glib2 / libglib2.0-bin.",
            )
            return@flow
        }
        _state.value = MediaSourceState.Ready
        var last: NowPlaying? = null
        while (coroutineContext.isActive) {
            val players = listPlayers().mapNotNull { name -> readPlayer(name) }
            val pick = players.firstOrNull { it.playing } ?: players.firstOrNull { it.player == last?.player } ?: players.firstOrNull()
            val now = pick ?: NowPlaying(player = last?.player ?: "", track = null, playing = false)
            emit(now)
            last = now
            delay(pollMs)
        }
    }.distinctUntilChanged { a, b -> a.copy(positionMs = null) == b.copy(positionMs = null) && !jumpedBack(a, b) }
        .flowOn(Dispatchers.IO)

    private fun jumpedBack(a: NowPlaying, b: NowPlaying): Boolean =
        (a.positionMs ?: 0) - (b.positionMs ?: Long.MAX_VALUE) > 10_000

    private fun listPlayers(): List<String> {
        val out = run(
            "gdbus", "call", "--session", "--dest", "org.freedesktop.DBus", "--object-path", "/org/freedesktop/DBus",
            "--method", "org.freedesktop.DBus.ListNames",
        ) ?: return emptyList()
        return Mpris.playerNames(out)
    }

    private fun readPlayer(busName: String): NowPlaying? {
        val out = run(
            "gdbus", "call", "--session", "--dest", busName, "--object-path", "/org/mpris/MediaPlayer2",
            "--method", "org.freedesktop.DBus.Properties.GetAll", "org.mpris.MediaPlayer2.Player",
        ) ?: return null
        return Mpris.parsePlayer(busName.removePrefix(Mpris.PREFIX), out)
    }

    private fun gdbusAvailable(): Boolean = run("gdbus", "help") != null

    private fun run(vararg command: String): String? = runCatching {
        val proc = ProcessBuilder(*command).redirectErrorStream(false).start()
        if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null
        proc.inputStream.bufferedReader().readText()
    }.getOrNull()
}

/** Parsing of `gdbus` GVariant text output. Pure, so it is unit-tested. */
internal object Mpris {
    const val PREFIX = "org.mpris.MediaPlayer2."

    fun playerNames(listNamesOutput: String): List<String> =
        Regex("'(${Regex.escape(PREFIX)}[^']+)'").findAll(listNamesOutput).map { it.groupValues[1] }.toList()

    fun parsePlayer(player: String, getAll: String): NowPlaying {
        val status = string(getAll, "PlaybackStatus")
        val title = string(getAll, "xesam:title")
        val artist = stringList(getAll, "xesam:artist").joinToString(", ").ifBlank { null }
            ?: stringList(getAll, "xesam:albumArtist").joinToString(", ").ifBlank { null }
        val album = string(getAll, "xesam:album")?.ifBlank { null }
        // mpris:length and Position are microseconds.
        val lengthMs = number(getAll, "mpris:length")?.let { it / 1000 }?.takeIf { it > 0 }
        val positionMs = number(getAll, "Position")?.let { it / 1000 }
        val track = if (title != null && artist != null) Track(artist, title, album, lengthMs) else null
        return NowPlaying(player = player, track = track, playing = status == "Playing", positionMs = positionMs)
    }

    /** `'key': <'value'>` — also handles gdbus's double-quoted form for values with apostrophes. */
    private fun string(text: String, key: String): String? {
        val m = Regex("'${Regex.escape(key)}': <(?:'((?:[^'\\\\]|\\\\.)*)'|\"((?:[^\"\\\\]|\\\\.)*)\")>").find(text) ?: return null
        return unescape(m.groupValues[1].ifEmpty { m.groupValues[2] })
    }

    /** `'key': <['a', 'b']>` */
    private fun stringList(text: String, key: String): List<String> {
        val m = Regex("'${Regex.escape(key)}': <\\[(.*?)]>").find(text) ?: return emptyList()
        return Regex("'((?:[^'\\\\]|\\\\.)*)'|\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(m.groupValues[1])
            .map { unescape(it.groupValues[1].ifEmpty { it.groupValues[2] }) }.toList()
    }

    /** `'key': <int64 123>`, `<uint64 123>`, `<@x 123>` or a bare `<123>`. */
    private fun number(text: String, key: String): Long? =
        Regex("'${Regex.escape(key)}': <(?:@?[a-z]+\\d*\\s+)?(-?\\d+)>").find(text)?.groupValues?.get(1)?.toLongOrNull()

    private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")
}
