package dev.lumen.app.collector

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure parsing logic of [SwayCollector] and
 * [FrameAccumulator]. No Sway host is required — these lock the wire-format
 * contract with realistic payloads.
 */
class SwayCollectorTest {

    /** A collector whose socket path never exists — only parse logic is exercised. */
    private fun collector() = SwayCollector(socketPath = Path.of("/nonexistent-sway-sock"))

    // --- parseEvent -------------------------------------------------------

    private fun waylandFocus(appId: String) =
        """{"change":"focus","container":{"id":42,"type":"con","app_id":"$appId","name":"some window title","window":null,"window_properties":null}}"""

    private fun xwaylandFocus(x11Class: String) =
        """{"change":"focus","container":{"id":42,"type":"con","app_id":null,"name":"title with {braces} inside","window":1234,"window_properties":{"class":"$x11Class","instance":"$x11Class","title":"title with {braces} inside"}}}"""

    @Test
    fun `wayland focus resolves the app id`() {
        val change = collector().parseEvent(waylandFocus("org.gnome.Nautilus"))!!
        assertEquals("org.gnome.nautilus", change.appKey.value)
        // Display name mirrors the AppKey, never the window title.
        assertEquals("org.gnome.Nautilus", change.displayName)
        assertFalse(change.isIdle)
    }

    @Test
    fun `xwayland focus falls back to window_properties class`() {
        val change = collector().parseEvent(xwaylandFocus("chromium"))!!
        assertEquals("chromium", change.appKey.value)
        assertEquals("chromium", change.displayName)
    }

    @Test
    fun `app_id wins over the x11 class when both exist`() {
        val both = """{"change":"focus","container":{"app_id":"com.example.Native","window_properties":{"class":"XWaylandThing"}}}"""
        assertEquals("com.example.native", collector().parseEvent(both)!!.appKey.value)
    }

    @Test
    fun `title change events are ignored`() {
        val title = """{"change":"title","container":{"app_id":"firefox","name":"new title"}}"""
        assertNull(collector().parseEvent(title))
    }

    @Test
    fun `close and move events are ignored`() {
        for (change in listOf("close", "move", "bar")) {
            val payload = """{"change":"$change","container":{"app_id":"firefox"}}"""
            assertNull(collector().parseEvent(payload), "change=$change should be ignored")
        }
    }

    @Test
    fun `focus with no app is idle`() {
        val payload = """{"change":"focus","container":{"id":0,"type":"workspace","app_id":null,"window_properties":null}}"""
        val change = collector().parseEvent(payload)!!
        assertTrue(change.isIdle)
        assertEquals("", change.appKey.value)
    }

    @Test
    fun `a container name with braces does not confuse parsing`() {
        // The title is never read, but it must not corrupt field extraction.
        val payload = """{"change":"focus","container":{"app_id":"org.foo.Bar","name":"a {nested} [bracket] title"},"other":{"x":1}}"""
        val change = collector().parseEvent(payload)!!
        assertEquals("org.foo.bar", change.appKey.value)
    }

    // --- parseTree --------------------------------------------------------

    @Test
    fun `get_tree with a focused wayland leaf returns its app id`() {
        // Single-line for robustness.
        val tree = """{"name":"root","focused":false,"nodes":[{"name":"ws","focused":false,"nodes":[{"name":"firefox","focused":true,"app_id":"firefox","window_properties":null}]}]}"""
        assertEquals("firefox", collector().parseTree(tree))
    }

    @Test
    fun `get_tree with a focused xwayland leaf returns the class`() {
        val tree = """{"name":"root","focused":false,"nodes":[{"name":"ws","focused":false,"nodes":[{"name":"xterm","focused":true,"app_id":null,"window_properties":{"class":"xterm"}}]}]}"""
        assertEquals("xterm", collector().parseTree(tree))
    }

    @Test
    fun `get_tree skips container braces that precede the focused marker`() {
        // rect/deco_rect objects sit before "focused":true in sway's output;
        // a naive lastIndexOf("{") would grab the wrong opening brace.
        val tree = """{"name":"root","focused":false,"nodes":[
            {"name":"con","focused":true,"rect":{"x":0,"y":0,"w":10,"h":10},"deco_rect":{"x":0,"y":0},"app_id":"kitty","window_properties":null}
        ]}"""
        assertEquals("kitty", collector().parseTree(tree))
    }

    @Test
    fun `get_tree with no focused node returns null`() {
        val tree = """{"name":"root","focused":false,"nodes":[]}"""
        assertNull(collector().parseTree(tree))
    }

    @Test
    fun `jsonStringField handles escaped quotes in the value`() {
        assertEquals("a\"b", SwayCollector.jsonStringField("""{"x":"a\"b"}""", "x"))
    }

    @Test
    fun `nestedClassField returns null when window_properties is absent`() {
        assertNull(SwayCollector.nestedClassField("""{"app_id":"firefox"}"""))
    }

    @Test
    fun `nestedClassField tolerates a class with braces in title`() {
        // The title is inside window_properties and may contain braces.
        val payload = """{"window_properties":{"class":"code","instance":"code","title":"a {weird} title"}}"""
        assertEquals("code", SwayCollector.nestedClassField(payload))
    }

    // --- FrameAccumulator -------------------------------------------------

    private fun frame(type: Int, body: String): ByteArray {
        val header = java.nio.ByteBuffer.allocate(14).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("i3-ipc".toByteArray(Charsets.US_ASCII))
        header.putInt(body.toByteArray(Charsets.UTF_8).size)
        header.putInt(type)
        header.flip()
        val out = ByteArray(14 + body.toByteArray(Charsets.UTF_8).size)
        header.get(out, 0, 14)
        System.arraycopy(body.toByteArray(Charsets.UTF_8), 0, out, 14, body.toByteArray(Charsets.UTF_8).size)
        return out
    }

    private fun focusFrame(appId: String): ByteArray {
        val body = """{"change":"focus","container":{"app_id":"$appId"}}"""
        return frame(0x80000005.toInt(), body)
    }

    @Test
    fun `a complete window frame yields its body`() {
        val acc = FrameAccumulator()
        acc.append(java.nio.ByteBuffer.wrap(focusFrame("firefox")))
        assertEquals("""{"change":"focus","container":{"app_id":"firefox"}}""", acc.nextFrame())
        assertNull(acc.nextFrame())
    }

    @Test
    fun `chunked reads assemble a frame`() {
        val acc = FrameAccumulator()
        val all = focusFrame("firefox")
        for (i in all.indices) {
            acc.append(java.nio.ByteBuffer.wrap(byteArrayOf(all[i])))
            if (i < all.size - 1) {
                assertNull(acc.nextFrame(), "frame must not complete before all bytes arrive")
            }
        }
        assertEquals("""{"change":"focus","container":{"app_id":"firefox"}}""", acc.nextFrame())
    }

    @Test
    fun `non-window frames are skipped without desync`() {
        val acc = FrameAccumulator()
        val ack = frame(2, "[true]")
        val pong = frame(1, "")
        acc.append(java.nio.ByteBuffer.wrap(ack))
        acc.append(java.nio.ByteBuffer.wrap(pong))
        acc.append(java.nio.ByteBuffer.wrap(focusFrame("firefox")))
        assertEquals("""{"change":"focus","container":{"app_id":"firefox"}}""", acc.nextFrame())
        assertNull(acc.nextFrame())
    }

    @Test
    fun `two frames back to back parse independently`() {
        val acc = FrameAccumulator()
        val a = focusFrame("firefox")
        val b = focusFrame("kitty")
        val combined = a + b
        acc.append(java.nio.ByteBuffer.wrap(combined))
        assertEquals("""{"change":"focus","container":{"app_id":"firefox"}}""", acc.nextFrame())
        assertEquals("""{"change":"focus","container":{"app_id":"kitty"}}""", acc.nextFrame())
        assertNull(acc.nextFrame())
    }

    @Test
    fun `garbage before a frame resyncs by magic`() {
        val acc = FrameAccumulator()
        val junk = "corrupt-bytes-without-magic".toByteArray(Charsets.UTF_8)
        acc.append(java.nio.ByteBuffer.wrap(junk))
        acc.append(java.nio.ByteBuffer.wrap(focusFrame("firefox")))
        assertEquals("""{"change":"focus","container":{"app_id":"firefox"}}""", acc.nextFrame())
    }
}
