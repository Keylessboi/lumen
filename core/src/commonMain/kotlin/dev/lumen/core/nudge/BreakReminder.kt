package dev.lumen.core.nudge

/**
 * The one nudge in v1 — M7, `docs/plan.md`: "break-reminder (timer +
 * notification)".
 *
 * ## What it is allowed to be
 *
 * `docs/design-spec.md`: "Gentle, one-tap, never shaming, not too often."
 * And the design language above it: a mirror, not a judge. So this fires on
 * *continuous time at the screen*, states the fact, and stops. It does not
 * mention which app, does not compare today to yesterday, and never says the
 * user should have stopped earlier.
 *
 * ## Why continuous, and why idle resets it
 *
 * A nudge keyed on the day's total would fire at the same clock time every
 * day regardless of how the day went, which is a calendar reminder wearing a
 * nudge's clothes. What is actually worth noticing is a long unbroken stretch
 * — so the timer resets whenever the user is away for long enough that they
 * genuinely took a break.
 *
 * That threshold matters: too short and stepping away to make coffee resets
 * a two-hour session, so the nudge never fires; too long and a lunch break
 * does not count and the nudge fires immediately afterwards. Five minutes is
 * long enough to be a real pause and short enough not to swallow one.
 *
 * ## Why the state machine is pure
 *
 * No timers, no coroutines, no platform clock inside. The caller supplies
 * `nowMs`, which makes every rule here testable at the exact boundary rather
 * than by sleeping — and it is the same reason the rest of core takes time as
 * a parameter.
 */
class BreakReminder(
    private val settings: NudgeSettings = NudgeSettings(),
) {

    private var continuousStartMs: Long? = null
    private var lastActivityMs: Long = 0L
    private var lastFiredMs: Long? = null

    /**
     * Feed activity. Returns a reminder when one is due, else null.
     *
     * [isIdle] is the collector's own idle signal where a platform has one.
     * A platform that cannot detect idle (Linux today) simply stops calling
     * this, and the gap does the same job via [IDLE_RESET_MS].
     */
    fun onActivity(nowMs: Long, isIdle: Boolean = false): BreakNudge? {
        if (!settings.enabled) return null

        val gap = nowMs - lastActivityMs
        val awayLongEnough = lastActivityMs > 0L && gap >= IDLE_RESET_MS

        if (isIdle || awayLongEnough) {
            // A real break happened. Reset, and do not fire on the way back —
            // a reminder to take a break, delivered the moment someone
            // returns from one, is the app not paying attention.
            continuousStartMs = null
            lastFiredMs = null
            lastActivityMs = nowMs
            return null
        }

        val start = continuousStartMs ?: nowMs.also { continuousStartMs = it }
        lastActivityMs = nowMs

        val continuousMs = nowMs - start
        if (continuousMs < settings.afterMs) return null

        // Repeat at most once per interval, so a long session gets an
        // occasional reminder rather than a stream of them. "Not too often"
        // is a design requirement, not a preference.
        val since = lastFiredMs?.let { nowMs - it }
        if (since != null && since < settings.repeatEveryMs) return null

        lastFiredMs = nowMs
        return BreakNudge(atMs = nowMs, continuousMs = continuousMs)
    }

    /** The user took the nudge. Treated as a break, because it is one. */
    fun acknowledgeBreakTaken(nowMs: Long) {
        continuousStartMs = null
        lastFiredMs = null
        lastActivityMs = nowMs
    }

    /** Dismissed without taking a break: stay quiet for a full interval. */
    fun dismiss(nowMs: Long) {
        lastFiredMs = nowMs
    }

    /** Continuous time at the screen right now, for the UI. */
    fun continuousMsAt(nowMs: Long): Long =
        continuousStartMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L

    companion object {
        /**
         * Away this long and the streak resets.
         *
         * Long enough that making coffee does not reset a two-hour session;
         * short enough that a lunch break is not swallowed and answered with
         * an immediate nudge on return.
         */
        const val IDLE_RESET_MS: Long = 5 * 60_000L
    }
}

/**
 * When and how often. Defaults are the shipped behaviour.
 *
 * 50 minutes rather than a round hour: it is roughly the outer edge of
 * comfortable focus, and a nudge on the hour reads as a clock chiming rather
 * than as a response to what the user is doing.
 */
data class NudgeSettings(
    val enabled: Boolean = true,
    val afterMs: Long = 50 * 60_000L,
    val repeatEveryMs: Long = 30 * 60_000L,
) {
    init {
        require(afterMs > 0) { "afterMs must be positive" }
        require(repeatEveryMs > 0) { "repeatEveryMs must be positive" }
    }

    companion object {
        /** Settings key, so the choice syncs like any other setting. */
        const val ENABLED_KEY: String = "nudge.break.enabled"
        const val AFTER_MINUTES_KEY: String = "nudge.break.afterMinutes"
    }
}

/**
 * A due reminder.
 *
 * Carries the fact and nothing else — no app name, no comparison, no verdict.
 * The copy is built from [continuousMs] alone, which is what keeps it a
 * statement rather than an opinion.
 */
data class BreakNudge(
    val atMs: Long,
    val continuousMs: Long,
) {
    /**
     * The notification text.
     *
     * Declarative, and it does not tell the user what to do — "time for a
     * break" is an instruction, and the spec's whole posture is that the app
     * reports and the user decides. Rounded down to whole minutes so it never
     * claims more time than elapsed.
     */
    fun title(): String = "You've been at the screen for ${minutes()} minutes."

    fun body(): String = "Stretching your legs is an option."

    private fun minutes(): Long = continuousMs / 60_000L
}
