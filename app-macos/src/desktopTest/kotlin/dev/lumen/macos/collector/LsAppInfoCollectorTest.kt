package dev.lumen.macos.collector

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
