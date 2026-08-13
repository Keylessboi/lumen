package dev.lumen.core.session

import dev.lumen.core.collector.FocusChange
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent

/**
 * Turns a stream of focus *transitions* into closed [FocusEvent]s with
 * durations.
 *
 * The collector seam deliberately reports transitions only — see
 * `AppUsageCollector`. Duration is derived here, once, rather than in each
 * platform collector.
 *
 * An event closes when focus moves elsewhere: the session that was open is
 * emitted with `durationMs = newChange.atMs - openedAt`. The currently focused
 * app is therefore *not* an event yet — [open] exposes it so the UI can show
 * live time without the store having to invent a duration that hasn't elapsed.
 *
 * Not thread-safe; drive it from a single collector coroutine.
 *
 * Lives in core because it is pure and every platform needs exactly it: the
 * collector seam reports transitions precisely so duration is derived once,
 * centrally, rather than three times in three collectors and wrong in three
 * different ways. It started in app-macos only because macOS was the first
 * platform to need it.
 */
class FocusSessionTracker(
    private val deviceId: DeviceId,
    private var nextSeq: Long = 0L,
) {
    private var openApp: AppKey? = null
    private var openName: String? = null
    private var openTitleHint: String? = null
    private var openedAt: Long = 0L

    /** The app currently in focus and when it took focus, or null before the first change. */
    val open: OpenSession?
        get() = openApp?.let { OpenSession(it, openName, openTitleHint, openedAt) }

    /**
     * Record a transition. Returns the event that just closed, or null when
     * this is the first observation and nothing was open yet.
     *
     * A change whose timestamp precedes the open session's start (a clock step
     * backwards) closes the session with zero duration rather than a negative
     * one. `docs/plan.md` locks reconciliation to a monotonic seq precisely
     * because wall-clock can move; this keeps the local view sane too.
     */
    fun onChange(change: FocusChange): FocusEvent? {
        val closed = closeAt(change.atMs)
        openApp = change.appKey
        openName = change.displayName
        openTitleHint = change.titleHint
        openedAt = change.atMs
        return closed
    }

    /**
     * Close the open session at [atMs] without opening a new one — used on
     * shutdown so the final session is not silently lost.
     */
    fun closeAt(atMs: Long): FocusEvent? {
        val app = openApp ?: return null
        val duration = (atMs - openedAt).coerceAtLeast(0L)
        if (duration <= 0L) return null
        return FocusEvent(
            seq = nextSeq++,
            deviceId = deviceId,
            appKey = app,
            titleHash = openTitleHint,
            startedAtMs = openedAt,
            durationMs = duration,
        )
    }

    data class OpenSession(
        val appKey: AppKey,
        val displayName: String?,
        val titleHint: String?,
        val sinceMs: Long,
    )
}
