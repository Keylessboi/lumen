package dev.lumen.macos.collector

import dev.lumen.core.collector.AppUsageCollector
import dev.lumen.core.collector.CollectorCapabilities
import dev.lumen.core.collector.FocusChange
import dev.lumen.core.collector.PermissionState
import dev.lumen.core.model.AppKey
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LsAppInfoCollectorTest {

    /** Replays a fixed sequence of observations, then repeats the last one forever. */
    private class FakeReader(
        private val sequence: List<FrontmostApp?>,
        private val available: Boolean = true,
    ) : FrontmostAppReader {
        private var i = 0
        override fun isAvailable() = available
        override fun frontmost(): FrontmostApp? =
            sequence[minOf(i++, sequence.lastIndex)]
    }

    private fun app(bundle: String, name: String? = null) =
        FrontmostApp(AppKey(bundle), name)

    private fun collector(vararg observations: FrontmostApp?) =
        LsAppInfoCollector(
            pollInterval = 1.milliseconds,
            runner = FakeReader(observations.toList()),
        )

    /**
     * Lumen counts its own screen time, like iOS Screen Time counts the
     * Screen Time app and Digital Wellbeing counts itself (LO's decision,
     * locked in `docs/design-spec.md`; the self-exclusion seam was reverted in
     * `248446f`). Hiding it would be the flattering lie a mirror must not
     * tell, and `app-macos/README.md` says so out loud.
     *
     * This test exists because the reverted position is the *non-obvious* one:
     * "a screen-time app shouldn't count itself" reads as an obvious bug fix,
     * and it was already implemented once. Without a test, the next person to
     * notice re-adds the filter and nothing objects.
     */
    @Test
    fun `lumen's own window is reported like any other app`() = runBlocking {
        val changes = collector(
            app("com.apple.Safari"),
            app("dev.lumen.macos", "Lumen"),
            app("com.googlecode.iterm2"),
        ).focusChanges().take(3).toList()

        assertEquals(
            listOf("com.apple.Safari", "dev.lumen.macos", "com.googlecode.iterm2"),
            changes.map { it.appKey.value },
            "Lumen must appear in its own numbers — no self-exclusion",
        )
    }

    @Test
    fun `emits one change per distinct app`() = runBlocking {
        val changes = collector(
            app("com.apple.Safari"),
            app("com.googlecode.iterm2"),
            app("com.apple.Safari"),
        ).focusChanges().take(3).toList()

        assertEquals(
            listOf("com.apple.Safari", "com.googlecode.iterm2", "com.apple.Safari"),
            changes.map { it.appKey.value },
        )
    }

    /**
     * The contract in [AppUsageCollector.focusChanges] requires a polling
     * collector to suppress repeats itself, so the engine never has to tell
     * "still in Safari" from "switched to Safari".
     */
    @Test
    fun `does not re-emit while focus is unchanged`() = runBlocking {
        val changes = collector(
            app("com.apple.Safari"),
            app("com.apple.Safari"),
            app("com.apple.Safari"),
            app("com.apple.Terminal"),
        ).focusChanges().take(2).toList()

        assertEquals(1, changes.count { it.appKey.value == "com.apple.Safari" })
        assertEquals("com.apple.Terminal", changes.last().appKey.value)
    }

    /** A failed observation must be skipped, never emitted as a focus change. */
    @Test
    fun `skips unreadable observations`() = runBlocking {
        val changes = collector(
            null,
            null,
            app("com.apple.Safari"),
        ).focusChanges().take(1).toList()

        assertEquals("com.apple.Safari", changes.single().appKey.value)
    }

    @Test
    fun `carries display name but never a window title`() = runBlocking {
        val change = collector(app("com.apple.Safari", "Safari"))
            .focusChanges().take(1).toList().single()

        assertEquals("Safari", change.displayName)
    }

    @Test
    fun `reports unsupported when lsappinfo is absent`() {
        val c = LsAppInfoCollector(runner = FakeReader(listOf(null), available = false))
        assertTrue(c.permissionState() is PermissionState.Unsupported)
    }

    @Test
    fun `declares itself non-realtime and non-backfilling`() {
        val caps = collector(app("com.apple.Safari")).capabilities
        assertTrue(!caps.isRealtime, "lsappinfo polling is not push-based")
        assertTrue(!caps.canBackfill, "lsappinfo cannot recover missed history")
        assertNull(caps.backfillHorizonMs)
        assertTrue(!caps.detectsIdle, "cannot distinguish locked screen from active use")
    }

    @Test
    fun `backfill is empty because the platform cannot provide it`() = runBlocking {
        assertEquals(
            emptyList(),
            collector(app("com.apple.Safari")).backfill(sinceMs = 0L),
        )
    }
}

class LsAppInfoParsingTest {

    private fun parse(output: String, key: String) =
        LsAppInfoReader.parseQuoted(output, key)

    @Test
    fun `parses real lsappinfo output`() {
        assertEquals(
            "com.anthropic.claudefordesktop",
            parse("\"CFBundleIdentifier\"=\"com.anthropic.claudefordesktop\"\n", "CFBundleIdentifier"),
        )
        assertEquals(
            "Claude",
            parse("\"LSDisplayName\"=\"Claude\"\n", "LSDisplayName"),
        )
    }

    @Test
    fun `returns null when the key is absent`() {
        assertNull(parse("\"LSDisplayName\"=\"Safari\"\n", "CFBundleIdentifier"))
        assertNull(parse("", "CFBundleIdentifier"))
    }

    /** Helper processes legitimately have no bundle id; that is not an error. */
    @Test
    fun `returns null for an empty value`() {
        assertNull(parse("\"CFBundleIdentifier\"=\"\"\n", "CFBundleIdentifier"))
    }

    @Test
    fun `handles surrounding output and whitespace`() {
        assertEquals(
            "com.apple.Safari",
            parse(
                "ASN:0x0-0x123:\n  \"CFBundleIdentifier\"=\"com.apple.Safari\"\n  other=1\n",
                "CFBundleIdentifier",
            ),
        )
    }

    @Test
    fun `returns null on a malformed unterminated value`() {
        assertNull(parse("\"CFBundleIdentifier\"=\"com.apple.Safari\n", "CFBundleIdentifier"))
    }
}

class RuntimeBundleIdTest {

    private fun key(bundleId: String, name: String?) =
        LsAppInfoReader.appKeyFor(bundleId, name).value

    @Test
    fun `a packaged app keys on its bundle id, unchanged`() {
        // The common path. AppKey is what every stored row joins on, so this
        // must stay byte-identical or history stops matching live tracking.
        assertEquals("com.apple.Safari", key("com.apple.Safari", "Safari"))
        assertEquals("dev.lumen.macos", key("dev.lumen.macos", "Lumen"))
        assertEquals("com.apple.Safari", key("com.apple.Safari", null))
    }

    @Test
    fun `two JVM apps get distinct keys instead of merging into one row`() {
        // The bug: lsappinfo reports the JDK's bundle id for every unpackaged
        // Java app, so they collapsed into a single row named after whichever
        // was seen first.
        val gradle = key("net.java.openjdk.java", "GradleDaemon")
        val lumen = key("net.java.openjdk.java", "MainKt")
        assertTrue(gradle != lumen, "distinct Java apps must not share an AppKey")
        assertEquals("net.java.openjdk.java/GradleDaemon", gradle)
        assertEquals("net.java.openjdk.java/MainKt", lumen)
    }

    @Test
    fun `the same JVM app keys identically across observations`() {
        assertEquals(key("net.java.openjdk.java", "MainKt"), key("net.java.openjdk.java", "MainKt"))
    }

    @Test
    fun `an unnamed runtime process is left lumped rather than split arbitrarily`() {
        // One lumpy row is bad; inventing a distinction we cannot observe is
        // worse, because it would fragment a single app across restarts.
        assertEquals("net.java.openjdk.java", key("net.java.openjdk.java", null))
        assertEquals("net.java.openjdk.java", key("net.java.openjdk.java", "   "))
    }

    @Test
    fun `the composite key cannot collide with a real bundle id`() {
        // '/' is not legal in a bundle id, so no real app can ever produce
        // the composite form.
        assertTrue(key("net.java.openjdk.java", "MainKt").contains('/'))
        assertTrue(!key("com.apple.Safari", "Safari").contains('/'))
    }
}
