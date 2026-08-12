package dev.lumen.app.names

import dev.lumen.core.model.AppKey
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopEntryNameResolverTest {

    private val dir: File = Files.createTempDirectory("lumen-desktop").toFile()

    @AfterTest
    fun cleanup() = dir.deleteRecursively().let { }

    private fun entry(file: String, body: String) = File(dir, file).writeText(body.trimIndent())

    private fun resolver() = DesktopEntryNameResolver(listOf(dir))

    @Test
    fun `resolves by desktop-file id`() {
        entry("firefox.desktop", """
            [Desktop Entry]
            Name=Firefox
            Exec=firefox
        """)
        assertEquals("Firefox", resolver().resolve(AppKey("firefox")))
    }

    @Test
    fun `resolves by StartupWMClass when it differs from the file name`() {
        // The case the WM_CLASS index exists for: apps whose reported class
        // does not match their desktop file.
        entry("code-oss.desktop", """
            [Desktop Entry]
            Name=Visual Studio Code
            StartupWMClass=Code
        """)
        assertEquals("Visual Studio Code", resolver().resolve(AppKey("code")))
        assertEquals("Visual Studio Code", resolver().resolve(AppKey("code-oss")))
    }

    @Test
    fun `matching is case-insensitive, because WM classes are not consistent`() {
        entry("gimp.desktop", """
            [Desktop Entry]
            Name=GNU Image Manipulation Program
            StartupWMClass=Gimp-2.10
        """)
        assertEquals("GNU Image Manipulation Program", resolver().resolve(AppKey("gimp-2.10")))
    }

    @Test
    fun `an unknown app resolves to null rather than a guess`() {
        entry("firefox.desktop", "[Desktop Entry]\nName=Firefox\n")
        assertNull(resolver().resolve(AppKey("some-unpackaged-binary")))
    }

    @Test
    fun `NoDisplay entries are skipped`() {
        // MIME handlers and helpers the user never launched by name; letting
        // them in means a real app can be shadowed by a helper's label.
        entry("helper.desktop", """
            [Desktop Entry]
            Name=Some Helper
            NoDisplay=true
        """)
        assertNull(resolver().resolve(AppKey("helper")))
    }

    @Test
    fun `keys outside the Desktop Entry group are ignored`() {
        entry("thing.desktop", """
            [Desktop Entry]
            Name=Real Name
            [Desktop Action New]
            Name=New Window
        """)
        assertEquals("Real Name", resolver().resolve(AppKey("thing")))
    }

    @Test
    fun `a malformed file does not take the whole index down`() {
        File(dir, "broken.desktop").writeBytes(byteArrayOf(0x00, 0x01, 0x02))
        entry("firefox.desktop", "[Desktop Entry]\nName=Firefox\n")
        assertEquals("Firefox", resolver().resolve(AppKey("firefox")))
    }

    @Test
    fun `a missing directory is not an error`() {
        val absent = File(dir, "does-not-exist")
        assertNull(DesktopEntryNameResolver(listOf(absent)).resolve(AppKey("firefox")))
    }
}
