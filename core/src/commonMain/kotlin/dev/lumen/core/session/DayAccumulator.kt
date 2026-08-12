package dev.lumen.core.session

import dev.lumen.core.collector.FocusChange
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.FocusEvent

/**
 * Per-app totals for the day so far, accumulated in memory.
 *
 * The minimum a platform needs to render the Today screen before it has a
 * store: feed it closed [FocusEvent]s from [FocusSessionTracker] and it keeps
 * a running total per app.
 *
 * This is deliberately not a replacement for `LumenStore` — nothing here
 * survives a restart, and it holds one day. It exists so a platform can show
 * the real, shared UI while its persistence is still being built, instead of
 * shipping a "dev harness" screen that looks nothing like the product and
 * quietly becomes the thing users see.
 */
class DayAccumulator {

    private val totals = mutableMapOf<AppKey, Long>()
    private val names = mutableMapOf<AppKey, String>()

    /** Record a closed session. */
    fun add(event: FocusEvent) {
        if (event.durationMs <= 0) return
        totals.merge(event.appKey, event.durationMs, Long::plus)
    }

    /** Remember an app's human-facing name, if the collector supplied one. */
    fun remember(change: FocusChange) {
        val name = change.displayName?.takeIf { it.isNotBlank() } ?: return
        names[change.appKey] = name
    }

    /** Total across every app. */
    fun totalMs(): Long = totals.values.sum()

    /** Name for [appKey], falling back to the id — never a fabricated label. */
    fun nameFor(appKey: AppKey): String = names[appKey] ?: appKey.value

    /** Per-app totals, largest first. */
    fun snapshot(): List<Pair<AppKey, Long>> =
        totals.entries.sortedByDescending { it.value }.map { it.key to it.value }

    /** Start a new day. */
    fun clear() {
        totals.clear()
    }
}
