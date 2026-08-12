package dev.lumen.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Colour identity.
 *
 * These exist because the first version coloured rows by their POSITION in
 * the list, so an app changed colour whenever its ranking moved during the
 * day. Colour reads as identity; a row that changes colour is the chart
 * saying something untrue about which row is which.
 */
class ThemeTest {

    @Test
    fun `an app keeps its colour regardless of rank`() {
        val safari = LumenTheme.colorForKey("com.apple.Safari")
        assertEquals(safari, LumenTheme.colorForKey("com.apple.Safari"))
    }

    @Test
    fun `different apps generally get different colours`() {
        // Not a guarantee — the palette is 8 wide, so collisions exist — but
        // a handful of common apps should not all land on one hue.
        val keys = listOf(
            "com.apple.Safari", "com.spotify.client", "com.google.Chrome",
            "com.apple.Terminal", "com.anthropic.claudefordesktop",
        )
        assertTrue(keys.map { LumenTheme.colorForKey(it) }.distinct().size >= 3)
    }

    @Test
    fun `the colour is always inside the palette`() {
        listOf("", "a", "com.very.long.bundle.identifier.indeed", "😀").forEach {
            assertTrue(LumenTheme.colorForKey(it) in LumenTheme.CategoryPalette)
        }
    }

    @Test
    fun `a key with a negative hash does not fall outside the palette`() {
        // Int.MIN_VALUE has no positive absolute value; a naive abs() would
        // stay negative and index out of bounds.
        val negative = generateSequence(0) { it + 1 }
            .map { "app-$it" }
            .first { it.hashCode() < 0 }
        assertTrue(LumenTheme.colorForKey(negative) in LumenTheme.CategoryPalette)
    }
}
