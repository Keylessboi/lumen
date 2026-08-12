package dev.lumen.macos.collector

import dev.lumen.core.model.AppKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lock screen is not screen time.
 *
 * Real recorded history on one Mac held **478 minutes of
 * `com.apple.loginwindow`** — nearly eight hours of locked screen counted as
 * use. These tests exist so that never comes back.
 */
class MacSystemUiTest {

    private fun isSystem(id: String) = MacSystemUi.isSystemUi(AppKey(id))

    @Test
    fun `the lock screen is excluded`() {
        assertTrue(isSystem("com.apple.loginwindow"))
    }

    @Test
    fun `password and keychain prompts are excluded`() {
        // Modal, unrequested, and often left sitting for minutes.
        assertTrue(isSystem("com.apple.SecurityAgent"))
        assertTrue(isSystem("com.apple.UserNotificationCenter"))
        assertTrue(isSystem("com.apple.accessibility.universalAccessAuthWarn"))
    }

    @Test
    fun `the screensaver is excluded`() {
        assertTrue(isSystem("com.apple.ScreenSaver.Engine"))
    }

    @Test
    fun `apps the user chooses to open are NOT excluded, Apple's included`() {
        // The bar is "did the user decide to be here", not "is this Apple's".
        // Opening System Settings is using the computer.
        listOf(
            "com.apple.systempreferences",
            "com.apple.finder",
            "com.apple.Safari",
            "com.apple.Terminal",
            "com.apple.MobileSMS",
            "com.apple.Preview",
            "com.apple.Photos",
        ).forEach {
            assertTrue(!isSystem(it), "$it was wrongly treated as system UI")
        }
    }

    @Test
    fun `Lumen itself is not excluded`() {
        // LO's decision: the app counts its own time, because reading your
        // screen-time app IS screen time. This filter must not quietly undo
        // that — it is about time the user did not choose, not about which
        // windows are ours.
        assertTrue(!isSystem("dev.lumen.macos"))
        assertTrue(!isSystem("net.java.openjdk.java/MainKt"))
    }

    @Test
    fun `matching is exact, not by vendor prefix`() {
        // A com.apple.* prefix rule would swallow Safari, Mail and Notes. The
        // point is to remove a handful of things, not a vendor.
        assertTrue(!isSystem("com.apple.loginwindow.helper"))
        assertTrue(!isSystem("com.apple."))
        assertTrue(!isSystem(""))
    }

    @Test
    fun `the list is small and inspectable`() {
        // If this grows large, something has gone wrong: the exclusion should
        // be a handful of named system surfaces, not a denylist of Apple.
        assertTrue(MacSystemUi.IDS.size <= 10, "exclusion list grew to ${MacSystemUi.IDS.size}")
        assertEquals(MacSystemUi.IDS.size, MacSystemUi.IDS.distinct().size)
    }
}
