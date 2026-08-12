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

    // ---- collision handling among visible apps ----

    @Test
    fun `apps shown together never share a colour, up to the palette size`() {
        val keys = listOf(
            "com.apple.Terminal", "dev.lumen.macos", "com.google.Chrome",
            "com.apple.Safari", "com.spotify.client",
        )
        val colors = LumenTheme.colorsFor(keys)
        assertEquals(keys.size, colors.values.distinct().size)
    }

    @Test
    fun `assignment does not change when rows reorder`() {
        // The actual bug: colour must not follow rank. Reversing the list is
        // what happens naturally as one app overtakes another during the day.
        val keys = listOf("a.app", "b.app", "c.app", "d.app")
        assertEquals(LumenTheme.colorsFor(keys), LumenTheme.colorsFor(keys.reversed()))
        assertEquals(LumenTheme.colorsFor(keys), LumenTheme.colorsFor(keys.shuffled(kotlin.random.Random(7))))
    }

    @Test
    fun `more apps than colours still assigns every one`() {
        val keys = (1..20).map { "app-$it" }
        val colors = LumenTheme.colorsFor(keys)
        assertEquals(20, colors.size)
        assertTrue(colors.values.all { it in LumenTheme.CategoryPalette })
    }

    @Test
    fun `an empty set is empty`() {
        assertEquals(emptyMap(), LumenTheme.colorsFor(emptyList()))
    }
}
