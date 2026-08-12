package dev.lumen.app.collector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class X11CollectorTest {

    private fun collector() = X11Collector(pollIntervalMs = 0)

    @Test
    fun `parseWmClass extracts the class from the second quoted field`() {
        assertEquals("Firefox", collector().parseWmClass("""WM_CLASS(STRING) = "firefox", "Firefox""""))
    }

    @Test
    fun `parseWmClass handles empty instance`() {
        assertEquals("code", collector().parseWmClass("""WM_CLASS(STRING) = "", "code""""))
    }

    @Test
    fun `parseWmClass returns null for blank class`() {
        assertNull(collector().parseWmClass("""WM_CLASS(STRING) = "foo", """"))
    }

    @Test
    fun `parseWmClass returns null when the format is unrecognized`() {
        assertNull(collector().parseWmClass("not a wm_class line"))
    }

    @Test
    fun `parseWmClass handles class with dots`() {
        assertEquals("org.gnome.Nautilus", collector().parseWmClass("""WM_CLASS(STRING) = "nautilus", "org.gnome.Nautilus""""))
    }
}