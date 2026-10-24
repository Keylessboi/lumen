package dev.lumen.app.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures are real `gdbus call` output shapes (GLib 2.7x). */
class MprisTest {

    @Test
    fun `player names come out of ListNames`() {
        val out = "(['org.freedesktop.DBus', ':1.4', 'org.mpris.MediaPlayer2.spotify', " +
            "'org.mpris.MediaPlayer2.firefox.instance_1_42', 'org.gnome.Shell'],)\n"
        assertEquals(
            listOf("org.mpris.MediaPlayer2.spotify", "org.mpris.MediaPlayer2.firefox.instance_1_42"),
            Mpris.playerNames(out),
        )
    }

    @Test
    fun `a playing spotify track parses with length and position`() {
        val out = "({'CanPlay': <true>, 'Metadata': <{'mpris:trackid': <'/com/spotify/track/7'>, " +
            "'mpris:length': <uint64 245000000>, 'xesam:album': <'OK Computer'>, " +
            "'xesam:artist': <['Radiohead']>, 'xesam:title': <'Let Down'>}>, " +
            "'PlaybackStatus': <'Playing'>, 'Position': <int64 12000000>, 'Volume': <1.0>},)"
        val np = Mpris.parsePlayer("spotify", out)
        assertTrue(np.playing)
        assertEquals("Radiohead", np.track?.artist)
        assertEquals("Let Down", np.track?.title)
        assertEquals("OK Computer", np.track?.album)
        assertEquals(245_000L, np.track?.durationMs)
        assertEquals(12_000L, np.positionMs)
    }

    @Test
    fun `apostrophes, several artists and a paused player`() {
        val out = "({'Metadata': <{'xesam:artist': <['Simon & Garfunkel', \"Guns N' Roses\"]>, " +
            "'xesam:title': <\"Don't Stop\">, 'mpris:length': <@x 1000000>}>, 'PlaybackStatus': <'Paused'>},)"
        val np = Mpris.parsePlayer("mpv", out)
        assertFalse(np.playing)
        assertEquals("Simon & Garfunkel, Guns N' Roses", np.track?.artist)
        assertEquals("Don't Stop", np.track?.title)
        assertNull(np.track?.album)
    }

    @Test
    fun `no metadata means no track`() {
        val np = Mpris.parsePlayer("vlc", "({'PlaybackStatus': <'Stopped'>, 'Metadata': <@a{sv} {}>},)")
        assertNull(np.track)
        assertFalse(np.playing)
    }
}
