package dev.lumen.core.nudge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one nudge in v1 — M7, gate G6.
 *
 * The state machine takes `nowMs` as a parameter, so every boundary here is
 * asserted exactly rather than approximated by sleeping.
 */
class BreakReminderTest {

    private val minute = 60_000L
    private val settings = NudgeSettings(afterMs = 50 * minute, repeatEveryMs = 30 * minute)

    private fun reminder() = BreakReminder(settings)

    /** Drive continuous activity in one-minute ticks, collecting nudges. */
    private fun BreakReminder.run(fromMs: Long, minutes: Int): List<BreakNudge> =
        (0 until minutes).mapNotNull { onActivity(fromMs + it * minute) }

    @Test
    fun `nothing fires before the threshold`() {
        assertTrue(reminder().run(0L, 50).isEmpty())
    }

    @Test
    fun `it fires once the threshold is crossed, not before`() {
        val r = reminder()
        assertNull(r.onActivity(0L))
        assertNull(r.onActivity(50 * minute - 1))
        val nudge = assertNotNull(r.onActivity(50 * minute))
        assertEquals(50 * minute, nudge.continuousMs)
    }

    @Test
    fun `a long session gets occasional reminders, not a stream`() {
        // "Not too often" is a design requirement. Three hours should produce
        // a handful, not 130.
        val nudges = reminder().run(0L, 180)
        assertEquals(listOf(50L, 80L, 110L, 140L, 170L), nudges.map { it.atMs / minute })
    }

    // ---- breaks reset it, which is the whole point ----

    @Test
    fun `a real break resets the streak`() {
        val r = reminder()
        r.run(0L, 45)
        // Away for six minutes: a genuine pause.
        assertNull(r.onActivity(45 * minute + BreakReminder.IDLE_RESET_MS + minute))
        // The clock restarts, so nothing fires at what would have been 50.
        assertTrue(r.run(52 * minute, 40).isEmpty())
    }

    @Test
    fun `a short gap does not reset the streak`() {
        // Making coffee must not swallow a two-hour session, or the nudge
        // never fires for anyone who ever stands up.
        val r = reminder()
        r.run(0L, 45) // ticks at 0..44; last activity is minute 44
        val lastActivity = 44 * minute
        val coffee = lastActivity + BreakReminder.IDLE_RESET_MS - minute
        assertNull(r.onActivity(coffee))
        assertNotNull(r.onActivity(50 * minute + minute), "a short gap wrongly reset the streak")
    }

    @Test
    fun `the boundary between a pause and a break is exact`() {
        val justUnder = BreakReminder.IDLE_RESET_MS - 1
        val exactly = BreakReminder.IDLE_RESET_MS

        // run(0, 45) ticks at 0..44, so the last activity is minute 44 —
        // gaps are measured from there, not from 45.
        val lastActivity = 44 * minute

        val a = reminder()
        a.run(0L, 45)
        val pausedA = lastActivity + justUnder
        a.onActivity(pausedA)
        // Resume within the idle window, or the check itself would be a break.
        assertNotNull(
            a.onActivity(pausedA + 2 * minute),
            "just under the threshold should NOT reset",
        )

        val b = reminder()
        b.run(0L, 45)
        val pausedB = lastActivity + exactly
        b.onActivity(pausedB)
        assertNull(b.onActivity(pausedB + 2 * minute), "exactly the threshold SHOULD reset")
    }

    @Test
    fun `it does not fire the moment someone returns from a break`() {
        // A reminder to take a break, delivered on return from one, is the
        // app not paying attention.
        val r = reminder()
        r.run(0L, 60) // fires at 50
        assertNull(r.onActivity(60 * minute + 2 * BreakReminder.IDLE_RESET_MS))
    }

    @Test
    fun `an explicit idle signal resets it, for platforms that have one`() {
        val r = reminder()
        r.run(0L, 45)
        assertNull(r.onActivity(45 * minute + 1000, isIdle = true))
        assertTrue(r.run(46 * minute, 40).isEmpty())
    }

    // ---- the user's response ----

    @Test
    fun `taking the break resets the timer`() {
        val r = reminder()
        r.run(0L, 55)
        r.acknowledgeBreakTaken(55 * minute)
        assertTrue(r.run(56 * minute, 40).isEmpty(), "fired again after the user took a break")
    }

    @Test
    fun `dismissing keeps it quiet for a full interval, then allows one`() {
        // Dismissing is not "never again" and not "ask me in a minute".
        val r = reminder()
        r.run(0L, 51)
        r.dismiss(51 * minute)
        assertTrue(r.run(52 * minute, 29).isEmpty())
        assertNotNull(r.onActivity(81 * minute + minute))
    }

    // ---- off means off ----

    @Test
    fun `a disabled reminder never fires`() {
        val off = BreakReminder(NudgeSettings(enabled = false))
        assertTrue((0..300).mapNotNull { off.onActivity(it * minute) }.isEmpty())
    }

    @Test
    fun `settings reject nonsense rather than firing constantly`() {
        listOf(0L, -1L).forEach { bad ->
            assertTrue(runCatching { NudgeSettings(afterMs = bad) }.isFailure)
            assertTrue(runCatching { NudgeSettings(repeatEveryMs = bad) }.isFailure)
        }
    }

    // ---- the copy ----

    @Test
    fun `the message states a fact and gives no instruction`() {
        // docs/design-spec.md: gentle, never shaming. The app reports; the
        // user decides. No "should", no "too long", no exclamation.
        val nudge = assertNotNull(reminder().run(0L, 51).firstOrNull())
        val text = nudge.title() + " " + nudge.body()

        assertTrue(text.contains("50 minutes"))
        listOf("should", "too long", "too much", "!", "streak", "goal", "failed").forEach {
            assertTrue(!text.lowercase().contains(it), "nudge copy contains '$it': $text")
        }
    }

    @Test
    fun `the message never claims more time than elapsed`() {
        // Rounded down: at 50m59s it says 50, not 51.
        val r = reminder()
        r.onActivity(0L)
        val nudge = assertNotNull(r.onActivity(50 * minute + 59_000))
        assertTrue(nudge.title().contains("50 minutes"), nudge.title())
    }

    @Test
    fun `the message names no app`() {
        // Which app it was is irrelevant to "you have been sitting a while",
        // and naming it turns a fact into a comment on a choice.
        val nudge = assertNotNull(reminder().run(0L, 51).firstOrNull())
        assertTrue(!nudge.title().contains(".") || nudge.title().endsWith("."))
    }

    // ---- live state for the UI ----

    @Test
    fun `continuous time is readable before any nudge is due`() {
        val r = reminder()
        r.onActivity(0L)
        r.onActivity(10 * minute)
        assertEquals(10 * minute, r.continuousMsAt(10 * minute))
    }

    @Test
    fun `continuous time is zero before any activity and after a break`() {
        val r = reminder()
        assertEquals(0L, r.continuousMsAt(minute))
        r.run(0L, 30)
        r.acknowledgeBreakTaken(30 * minute)
        assertEquals(0L, r.continuousMsAt(30 * minute))
    }

    @Test
    fun `a backwards clock step never yields negative continuous time`() {
        val r = reminder()
        r.onActivity(10 * minute)
        assertEquals(0L, r.continuousMsAt(5 * minute))
    }
}
