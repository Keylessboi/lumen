package dev.lumen.core.collector

import dev.lumen.core.model.AppKey

/**
 * Turns an [AppKey] into the name a human recognises.
 *
 * Every platform identifies apps by a machine id — a bundle id on macOS, a
 * package name on Android, a WM class or desktop-file id on Linux — and every
 * platform has a *different* place that maps it to a label. Live collectors
 * often learn the label incidentally while observing focus, but two paths do
 * not:
 *
 *  - **backfilled history**, which carries ids only (macOS Screen Time,
 *    Android UsageStats), and
 *  - **apps seen before the label was cached**, e.g. after a fresh install
 *    reading an existing store.
 *
 * Without this, a month of recovered history renders as
 * `com.spotify.client`. That is the same class of defect as the axis reading
 * "06 07 08": technically the data, not the information.
 *
 * Implementations must be:
 *
 *  - **conservative** — return null rather than guess. A wrong name is worse
 *    than an id, because an id is visibly an id.
 *  - **cheap on repeat** — callers cache, so this is a per-unknown-id cost,
 *    but it may still be called for a screenful at once.
 *  - **non-fatal** — a lookup that fails, times out or is unavailable
 *    returns null. Nothing here is worth failing a render over.
 */
fun interface AppNameResolver {

    /** Human-facing name for [appKey], or null when it cannot be determined. */
    fun resolve(appKey: AppKey): String?

    companion object {
        /** Resolves nothing. For platforms with no lookup, and for tests. */
        val None: AppNameResolver = AppNameResolver { null }
    }
}
