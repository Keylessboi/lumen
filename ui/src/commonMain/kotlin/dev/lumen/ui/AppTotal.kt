package dev.lumen.ui

import dev.lumen.core.model.AppKey

/**
 * One app's time for one day, ready to render.
 *
 * This is the entire interface between a platform's storage and the shared
 * Today screen: give it a list of these and it draws. macOS derives them from
 * its NDJSON cache, Linux and Android will derive them from `LumenStore`
 * rollups — the screen neither knows nor cares.
 *
 * [displayName] is the human-facing app name, never a window title
 * (`docs/e2ee.md` §3). It is resolved at capture time by the collector and
 * cached per [appKey], because an app the user has uninstalled still has to
 * render in last week's history.
 *
 * **This type belongs in `:core`**, next to `AppDayRollup` — it is the view
 * shape every platform needs, and nothing about it is UI-specific. It lives
 * here only because `core/src/commonMain` is Agent A's zone and this PR is
 * Agent B's. Moving it is a one-line change whenever A wants it; the import
 * site is this file and nowhere else.
 */
data class AppTotal(
    val appKey: AppKey,
    val displayName: String,
    val totalMs: Long,
)
