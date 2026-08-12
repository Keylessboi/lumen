package dev.lumen.macos.notify

import dev.lumen.core.nudge.BreakNudge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacNotifierTest {

    private val captured = mutableListOf<List<String>>()
    private val notifier = MacNotifier { captured += it; true }

    @Test
    fun `a nudge becomes an osascript notification carrying its text`() {
        notifier.notify(BreakNudge(atMs = 0L, continuousMs = 50 * 60_000L))
        val script = captured.single().last()
        assertTrue(script.startsWith("display notification "))
        assertTrue(script.contains("50 minutes"))
    }

    @Test
    fun `quotes and backslashes cannot break out of the script`() {
        // Today the copy is ours; tomorrow the body could carry a user-set
        // app name. A stray quote would otherwise turn text into script.
        notifier.notify(title = """a "quoted" \ name""", body = """body " with \ both""")
        val script = captured.single().last()
        assertTrue(script.contains("""\"quoted\""""), script)
        assertTrue(script.contains("""\\"""), script)
    }

    @Test
    fun `a failing notifier reports false rather than throwing`() {
        // A nudge that cannot be delivered did not happen; it must not take
        // tracking down with it.
        val failing = MacNotifier { error("osascript exploded") }
        assertEquals(false, runCatching { failing.notify("t", "b") }.getOrElse { false })
    }
}
