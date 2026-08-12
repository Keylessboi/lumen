package dev.lumen.core.collector

import dev.lumen.core.model.AppKey

/**
 * Which app identities are system UI rather than an app the user chose.
 *
 * A lock screen, a password prompt or a screensaver takes the foreground the
 * way an app does, so every collector reports it — but none of it is time the
 * user spent *using* something. On this machine the recorded history held
 * **478 minutes of `com.apple.loginwindow`**: nearly eight hours of locked
 * screen, filed as screen time.
 *
 * That is worse than a rounding error. `docs/design-spec.md` says the app is a
 * mirror, and a mirror that reports the hours you were away from the desk as
 * hours at it is not reflecting anything.
 *
 * ## Why this is not the same as the self-inclusion rule
 *
 * LO's decision was that Lumen counts its own window, because reading your
 * screen-time app IS screen time and hiding it would be a flattering lie
 * (`docs/design-spec.md`). That reasoning turns on the user having chosen to
 * look at something. Nobody chooses to look at a lock screen; it appears
 * because they walked away, which is the definition of not using the computer.
 *
 * ## Why it is a seam
 *
 * Every platform has this and none of them spell it the same way: macOS has
 * `loginwindow` and `SecurityAgent`, Linux has swaylock / gtklock / i3lock and
 * a polkit agent, Android has the keyguard. The concept belongs in core; the
 * identities belong to whoever knows the platform.
 */
fun interface SystemUiFilter {

    /** True when [appKey] is system UI rather than a user-facing app. */
    fun isSystemUi(appKey: AppKey): Boolean

    companion object {
        /** Treats nothing as system UI. For tests and platforms with no list. */
        val None: SystemUiFilter = SystemUiFilter { false }
    }
}

/** Drop system UI from a stream of focus changes. */
fun List<FocusChange>.excludingSystemUi(filter: SystemUiFilter): List<FocusChange> =
    filterNot { filter.isSystemUi(it.appKey) }
