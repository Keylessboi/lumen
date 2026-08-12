package dev.lumen.ui

/**
 * Live time for the day in progress: how much of the currently-open session
 * belongs to *today*.
 *
 * The Today number is stored-time plus the session that has not closed yet.
 * That open session can have started before local midnight, and its
 * pre-midnight portion has already been counted into yesterday — so adding
 * the whole thing to today would both double-count it and make a brand new
 * day open at however long the user happened to have been sitting there.
 *
 * At 00:01, after an evening that began at 21:30, the honest answer is one
 * minute.
 *
 * Shared rather than per-platform because every platform has the same
 * open-session-across-midnight case, and it is the kind of arithmetic that is
 * wrong in a different way in each copy.
 *
 * @param nowMs current wall clock
 * @param sessionStartedAtMs when the open session took focus, or 0 if none
 * @param dayStartMs local midnight of the day being displayed
 */
fun liveMsWithinDay(nowMs: Long, sessionStartedAtMs: Long, dayStartMs: Long): Long {
    if (sessionStartedAtMs <= 0L) return 0L
    return (nowMs - maxOf(sessionStartedAtMs, dayStartMs)).coerceAtLeast(0L)
}
