package dev.lumen.core.collector

import dev.lumen.core.model.AppKey
import kotlinx.coroutines.flow.Flow

/**
 * Collector seam — FROZEN at M1. Proposed by Agent B (PR #11), landed in
 * core by Agent A per the two-agent contract. Every platform collector
 * implements this: Hyprland/Sway/X11 (Agent A), Android UsageStats (Agent A),
 * macOS NSWorkspace/lsappinfo (Agent B).
 *
 * ## Why transitions rather than durations
 *
 * Collectors report *when focus moved to an app*, never "app X was used for
 * N ms". Duration is derived centrally by the rollup engine, for three
 * reasons:
 *
 *  - The three platforms disagree on what they can observe. Hyprland pushes
 *    a compositor event on focus change; Android hands back a batch of
 *    historical MOVE_TO_FOREGROUND/MOVE_TO_BACKGROUND records; macOS posts an
 *    NSWorkspace notification. All three can produce a transition. Only some
 *    can produce a trustworthy duration.
 *  - Duration arithmetic near sleep/wake, timezone changes and clock steps is
 *    subtle, and `docs/data-model.md` already locks it (UTC-day, monotonic
 *    seq). Doing it once in core beats doing it three times in three
 *    collectors, wrong in three different ways.
 *  - A transition is idempotent and replayable. A duration is a claim that
 *    cannot be re-derived once recorded.
 */
interface AppUsageCollector {

    /** What this collector can and cannot do. Drives UI and engine behaviour. */
    val capabilities: CollectorCapabilities

    /**
     * The AppKey that identifies lumen ITSELF on this platform
     * (WM_CLASS/app_id on Linux, package name on Android, bundle id on macOS).
     *
     * MUST be excluded from reported focus. A screen-time app that counts its
     * own window corrupts every number it shows — `docs/design-spec.md` locks
     * this ("the app must exclude ITSELF from its own numbers from day one").
     * The engine filters [selfAppKey] out of [focusChanges] centrally, so a
     * collector only declares it and never has to special-case its own
     * identity in event logic.
     */
    val selfAppKey: AppKey

    /**
     * Hot stream of focus transitions.
     *
     * Push-based platforms (Hyprland, macOS NSWorkspace) emit as events
     * arrive. Poll-based implementations emit on their own cadence. Either
     * way the consumer sees the same shape.
     *
     * MUST emit only on *change* — a collector that polls is responsible for
     * de-duplicating consecutive identical observations, so that the engine
     * never has to distinguish "still in Safari" from "switched to Safari".
     */
    fun focusChanges(): Flow<FocusChange>

    /**
     * Retrieve transitions that happened while the collector was not running.
     *
     * Android's UsageStatsManager is the motivating case: it is authoritative
     * and retains events for days, so an app that was killed can recover what
     * it missed. Platforms that cannot do this return an empty list and
     * declare [CollectorCapabilities.canBackfill] false — the engine then
     * knows the gap is permanent rather than pending, which is the same
     * distinction `docs/providers.md` §5 draws for unfillable MAM gaps.
     */
    suspend fun backfill(sinceMs: Long): List<FocusChange> = emptyList()

    /**
     * Whether the collector can currently observe anything, and if not, why.
     * Checked before [focusChanges] and surfaced in the UI — never silently
     * swallowed into an empty stream, which is indistinguishable from an idle
     * user.
     */
    fun permissionState(): PermissionState
}

/**
 * A single focus transition: at [atMs], the foreground became [appKey].
 *
 * [atMs] is wall-clock epoch millis and is used ONLY for bucketing, never for
 * reconciliation ordering — `docs/plan.md` locks reconciliation to a
 * per-device monotonic seq. A clock step can distort which minute bucket an
 * event lands in; it must never reorder the merge.
 */
data class FocusChange(
    val appKey: AppKey,
    val atMs: Long,
    /**
     * Human-facing app name at observation time ("Safari"), for display when
     * the category registry has no entry. NEVER a window title, and never
     * synced — see `docs/e2ee.md` §3.
     */
    val displayName: String? = null,
    /** True when the transition is to "no app focused" (idle, locked, screen off). */
    val isIdle: Boolean = false,
)

data class CollectorCapabilities(
    /** Emits as transitions happen rather than on a timer. */
    val isRealtime: Boolean,
    /** [AppUsageCollector.backfill] can recover missed history. */
    val canBackfill: Boolean,
    /** How far back backfill can reach, or null when unsupported. */
    val backfillHorizonMs: Long? = null,
    /** Poll interval for non-realtime collectors, or null when push-based. */
    val pollIntervalMs: Long? = null,
    /** Can distinguish "screen locked / idle" from "app still focused". */
    val detectsIdle: Boolean,
)

sealed interface PermissionState {
    /** Collector can run now. */
    data object Granted : PermissionState

    /**
     * A permission is required and not yet held. [rationale] is shown to the
     * user in plain language; [settingsHint] tells them where to grant it.
     */
    data class Required(val rationale: String, val settingsHint: String) : PermissionState

    /** Structurally unavailable on this system — e.g. GNOME/KWin on Linux. */
    data class Unsupported(val reason: String) : PermissionState
}
