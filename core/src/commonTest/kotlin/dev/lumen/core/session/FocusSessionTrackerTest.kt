package dev.lumen.core.session

import dev.lumen.core.collector.FocusChange
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FocusSessionTrackerTest {

    private val device = DeviceId("test-device")
    private fun tracker() = FocusSessionTracker(device)
    private fun change(app: String, atMs: Long) = FocusChange(AppKey(app), atMs)

    @Test
    fun `first change closes nothing`() {
        assertNull(tracker().onChange(change("com.apple.Safari", 1_000)))
    }

    @Test
    fun `second change closes the first session with its elapsed duration`() {
        val t = tracker()
        t.onChange(change("com.apple.Safari", 1_000))
        val closed = t.onChange(change("com.apple.Terminal", 6_000))!!

        assertEquals("com.apple.Safari", closed.appKey.value)
        assertEquals(1_000, closed.startedAtMs)
        assertEquals(5_000, closed.durationMs)
    }

    @Test
    fun `seq increments per closed event and never repeats`() {
        val t = tracker()
        t.onChange(change("a", 0))
        val first = t.onChange(change("b", 1_000))!!
        val second = t.onChange(change("c", 2_000))!!

        assertEquals(0L, first.seq)
        assertEquals(1L, second.seq)
    }

    /**
     * The open session is not an event yet — it has no elapsed duration to
     * record. Exposing it separately keeps the store from inventing one.
     */
    @Test
    fun `open session is exposed but not emitted`() {
        val t = tracker()
        t.onChange(change("com.apple.Safari", 4_000))

        val open = t.open!!
        assertEquals("com.apple.Safari", open.appKey.value)
        assertEquals(4_000, open.sinceMs)
    }

    @Test
    fun `closeAt flushes the final session so shutdown does not lose it`() {
        val t = tracker()
        t.onChange(change("com.apple.Safari", 1_000))

        val closed = t.closeAt(3_500)!!
        assertEquals("com.apple.Safari", closed.appKey.value)
        assertEquals(2_500, closed.durationMs)
    }

    /**
     * A backwards clock step must not produce a negative duration.
     * Reconciliation is locked to a monotonic seq precisely because wall-clock
     * can move; the local view has to stay sane too.
     */
    @Test
    fun `a backwards clock step yields no event rather than negative time`() {
        val t = tracker()
        t.onChange(change("com.apple.Safari", 10_000))
        assertNull(t.onChange(change("com.apple.Terminal", 9_000)))
    }

    @Test
    fun `a zero-length session is not recorded`() {
        val t = tracker()
        t.onChange(change("com.apple.Safari", 5_000))
        assertNull(t.onChange(change("com.apple.Terminal", 5_000)))
    }
}
