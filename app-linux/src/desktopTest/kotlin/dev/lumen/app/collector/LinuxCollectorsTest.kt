package dev.lumen.app.collector

import dev.lumen.core.collector.PermissionState
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinuxCollectorsTest {

    @Test
    fun `no Hyprland does not crash and falls through to X11`() {
        assertIs<X11Collector>(LinuxCollectors.detect(mapOf("DISPLAY" to ":0")))
    }

    @Test
    fun `a Hyprland signature with no socket falls through instead of throwing`() {
        val picked = LinuxCollectors.detect(
            mapOf("HYPRLAND_INSTANCE_SIGNATURE" to "nope", "XDG_RUNTIME_DIR" to "/nonexistent", "DISPLAY" to ":0"),
        )
        assertIs<X11Collector>(picked)
    }

    @Test
    fun `an unsupported session says why`() {
        val picked = LinuxCollectors.detect(mapOf("XDG_CURRENT_DESKTOP" to "GNOME"))
        val state = picked.permissionState()
        assertIs<PermissionState.Unsupported>(state)
        assertTrue(state.reason.contains("GNOME"))
    }
}
