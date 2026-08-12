package dev.lumen.macos.startup

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `LoginItem` shells out to `launchctl` for the load/unload side, which is
 * not worth mocking — these tests cover the parts that are pure logic:
 * the plist it writes and `isSupported()`'s gradle-vs-packaged distinction.
 * `launchctl` failing is swallowed by `runCatching`, so it never affects the
 * return value under test either way.
 */
class LoginItemTest {

    private val home = Files.createTempDirectory("lumen-loginitem-test").toFile()
    private val originalHome = System.getProperty("user.home")

    init {
        System.setProperty("user.home", home.absolutePath)
    }

    @AfterTest
    fun cleanup() {
        System.setProperty("user.home", originalHome)
        home.deleteRecursively()
    }

    private val plist: File
        get() = File(home, "Library/LaunchAgents/${LoginItem.LABEL}.plist")

    @Test
    fun `not enabled when no plist exists`() {
        assertFalse(LoginItem.isEnabled())
    }

    @Test
    fun `enable with an explicit target writes a plist pointing at it`() {
        val target = File(home, "Lumen.app/Contents/MacOS/Lumen").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n")
        }

        LoginItem.enable(target)

        assertTrue(plist.exists())
        val xml = plist.readText()
        assertTrue(xml.contains(target.absolutePath), "plist should reference the target executable")
        assertTrue(xml.contains("<key>RunAtLoad</key>"))
        assertTrue(xml.contains("<true/>"))
        // KeepAlive is deliberately absent: Quit must mean quit, not "restart
        // me immediately". A regression here would be a real behaviour change,
        // not a cosmetic one.
        assertFalse(xml.contains("KeepAlive"), "must not auto-relaunch after Quit")
    }

    @Test
    fun `isEnabled reflects the plist after enable`() {
        val target = File(home, "fake-lumen").apply { writeText("x") }
        LoginItem.enable(target)
        assertTrue(LoginItem.isEnabled())
    }

    @Test
    fun `disable removes the plist`() {
        val target = File(home, "fake-lumen").apply { writeText("x") }
        LoginItem.enable(target)
        assertTrue(plist.exists())

        LoginItem.disable()
        assertFalse(plist.exists())
    }

    @Test
    fun `disable on an already-absent plist still reports success`() {
        assertTrue(LoginItem.disable())
    }

    /**
     * Without a packaged app there is no stable executable to point a login
     * item at. `enable()` with no argument must fail closed rather than
     * writing an agent that points at a transient Gradle JVM invocation.
     */
    @Test
    fun `enable with no resolvable target fails rather than writing a broken agent`() {
        val result = LoginItem.enable(target = null)
        assertFalse(result)
        assertFalse(plist.exists())
    }
}
