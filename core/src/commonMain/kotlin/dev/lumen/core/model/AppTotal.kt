package dev.lumen.core.model

import kotlinx.serialization.Serializable

/**
 * One app's time for one day, ready to render.
 *
 * This is the view shape every platform's Today screen needs: give the shared
 * UI a list of these and it draws. macOS derives them from its NDJSON cache,
 * Linux and Android derive them from `LumenStore` rollups — the screen
 * neither knows nor cares.
 *
 * [displayName] is the human-facing app name, never a window title
 * (`docs/e2ee.md` §3). It is resolved at capture time by the collector and
 * cached per [appKey], because an app the user has uninstalled still has to
 * render in last week's history.
 *
 * Lives here (not in `:ui`) because it is the interface between a platform's
 * storage and the shared screen — nothing about it is UI-specific
 * (discussion #21).
 */
@Serializable
data class AppTotal(
    val appKey: AppKey,
    val displayName: String,
    val totalMs: Long,
)
