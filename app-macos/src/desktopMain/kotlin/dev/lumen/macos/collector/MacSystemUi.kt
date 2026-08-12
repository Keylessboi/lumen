package dev.lumen.macos.collector

import dev.lumen.core.collector.SystemUiFilter
import dev.lumen.core.model.AppKey

/**
 * macOS system UI that takes the foreground without being an app.
 *
 * Every id here was found in real recorded history on a normal Mac, not
 * guessed at. `com.apple.loginwindow` alone accounted for 478 minutes — the
 * lock screen, counted as screen time.
 *
 * ## The bar for adding something
 *
 * Only processes that take focus **without the user choosing them**. A system
 * app the user opens on purpose — System Settings, Finder, Preview — stays
 * counted, because opening it is using the computer. The test is not "is this
 * Apple's" but "did the user decide to be here".
 *
 * Matching is exact. Prefix matching on `com.apple.` would swallow Safari,
 * Mail, Notes and every other real app; the whole point is to remove four
 * things, not a vendor.
 */
object MacSystemUi : SystemUiFilter {

    override fun isSystemUi(appKey: AppKey): Boolean = appKey.value in IDS

    /** Exposed so the exclusion is inspectable and testable, not implicit. */
    val IDS: Set<String> = setOf(
        // The lock screen and the login screen. The single largest source of
        // phantom time: the machine is locked and the user is elsewhere.
        "com.apple.loginwindow",

        // Password, Touch ID and keychain prompts. Modal, unrequested, and
        // frequently left sitting for minutes.
        "com.apple.SecurityAgent",

        // System alert dialogs.
        "com.apple.UserNotificationCenter",

        // The "an app wants to control your computer" accessibility warning.
        "com.apple.accessibility.universalAccessAuthWarn",

        // Screensaver. Same argument as the lock screen.
        "com.apple.ScreenSaver.Engine",
        "com.apple.screensaver",
    )
}
