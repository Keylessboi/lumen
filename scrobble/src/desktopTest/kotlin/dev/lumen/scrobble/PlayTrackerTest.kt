package dev.lumen.scrobble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayTrackerTest {

    private val song = Track("Artist", "Song", "Album", durationMs = 200_000) // threshold 100 s
    private fun np(t: Track? = song, playing: Boolean = true, pos: Long? = null, player: String = "spotify") =
        NowPlaying(player, t, playing, pos)

    private fun List<PlayTracker.Event>.scrobbles() = filterIsInstance<PlayTracker.Event.Qualified>().map { it.scrobble }

    @Test
    fun `half the track scrobbles once, timestamped at the start`() {
        val t = PlayTracker()
        assertTrue(t.update(np(), 1_000_000).any { it is PlayTracker.Event.Started })
        assertTrue(t.tick(1_000_000 + 99_000).scrobbles().isEmpty())
        val s = t.tick(1_000_000 + 100_000).scrobbles().single()
        assertEquals(1_000L, s.startedAtSec)
        assertTrue(t.tick(1_000_000 + 190_000).scrobbles().isEmpty(), "never twice")
    }

    @Test
    fun `paused time does not count`() {
        val t = PlayTracker()
        t.update(np(), 0)
        t.update(np(playing = false), 60_000)
        assertTrue(t.tick(600_000).scrobbles().isEmpty())
        t.update(np(playing = true), 600_000)
        assertTrue(t.tick(639_000).scrobbles().isEmpty())
        assertEquals(1, t.tick(640_000).scrobbles().size)
    }

    @Test
    fun `long tracks cap at four minutes, short ones never count`() {
        assertEquals(240_000L, PlayTracker.thresholdMs(song.copy(durationMs = 3_600_000)))
        assertNull(PlayTracker.thresholdMs(song.copy(durationMs = 30_000)))
        assertEquals(240_000L, PlayTracker.thresholdMs(song.copy(durationMs = null)))
    }

    @Test
    fun `skipping early does not scrobble`() {
        val t = PlayTracker()
        t.update(np(), 0)
        val events = t.update(np(song.copy(title = "Next")), 20_000)
        assertTrue(events.scrobbles().isEmpty())
        assertTrue(events.any { it is PlayTracker.Event.Started && it.track.title == "Next" })
    }

    @Test
    fun `replaying from the start counts again`() {
        val t = PlayTracker()
        t.update(np(pos = 0), 0)
        assertEquals(1, t.update(np(pos = 150_000), 150_000).scrobbles().size)
        t.update(np(pos = 1_000), 160_000) // jumped back to the start: a new play
        assertEquals(1, t.tick(260_000).scrobbles().size)
    }

    @Test
    fun `browsers are ignored unless allowed, and blank metadata never counts`() {
        val t = PlayTracker(ignore = PlayTracker::isBrowser)
        t.update(np(player = "firefox"), 0)
        assertTrue(t.tick(500_000).scrobbles().isEmpty())

        val u = PlayTracker()
        u.update(np(song.copy(artist = "")), 0)
        assertTrue(u.tick(500_000).scrobbles().isEmpty())
    }

    @Test
    fun `stopping a qualifying track still scrobbles it`() {
        val t = PlayTracker()
        t.update(np(), 0)
        val events = t.update(np(t = null, playing = false), 120_000)
        assertEquals(1, events.scrobbles().size)
    }
}
