package dev.lumen.app.collector

import dev.lumen.core.collector.AppUsageCollector
import dev.lumen.core.collector.CollectorCapabilities
import dev.lumen.core.collector.FocusChange
import dev.lumen.core.collector.PermissionState
import dev.lumen.core.model.AppKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Sway collector — Agent A, M2. Reads sway's i3-ipc socket for focus
 * transitions.
 *
 * Sway speaks the i3-ipc protocol: a 14-byte frame header
 * (`i3-ipc` magic + u32 LE length + u32 LE type) followed by a JSON
 * payload. The `window` event carries `change: "focus"` and a `container`
 * with either `app_id` (native Wayland clients) or `window_properties.class`
 * (XWayland clients) — the `name` field is the window TITLE and is never
 * read, matching `docs/e2ee.md` §3: window titles never leave the device in
 * any form.
 *
 * [AppKey] resolution, in order:
 *   1. `container.app_id` — the Wayland app id, matches desktop entries
 *      for well-behaved apps (org.gnome.Nautilus -> org.gnome.Nautilus.desktop).
 *   2. `container.window_properties.class` — XWayland fallback (chromium).
 * Display name is the same value as the AppKey: never a window title.
 */
class SwayCollector(
    private val socketPath: Path = defaultSocketPath(),
    private val queryActiveTree: Boolean = true,
) : AppUsageCollector {

    override val capabilities = CollectorCapabilities(
        isRealtime = true,
        canBackfill = false,
        backfillHorizonMs = null,
        pollIntervalMs = null,
        detectsIdle = false,
    )

    override fun permissionState(): PermissionState =
        if (socketPath.toFile().exists()) {
            PermissionState.Granted
        } else {
            PermissionState.Unsupported(
                "Sway IPC socket not found at $socketPath — is Sway running?"
            )
        }

    override fun focusChanges(): Flow<FocusChange> = flow {
        val address = UnixDomainSocketAddress.of(socketPath)
        val channel = SocketChannel.open(address)
        channel.configureBlocking(true)
        try {
            // Baseline first: the focused node in the tree, so the engine has
            // a current state even when no event follows this connect.
            if (queryActiveTree) {
                currentFocusedNode()?.let { emit(it) }
            }

            // Subscribe to window events before the read loop, so no event is
            // missed between connect and subscribe. Consume the reply fully:
            // a half-read reply would leave bytes in the socket and corrupt
            // the frame boundary for everything that follows.
            sendFrame(channel, TYPE_SUBSCRIBE, """["window"]""".toByteArray())
            readFrameFully(channel)

            channel.configureBlocking(false)
            val buf = ByteBuffer.allocate(MAX_PENDING_CHARS)
            val frame = FrameAccumulator()
            // The seam requires emitting only on CHANGE. Sway re-emits focus
            // transitions when the same window regains focus after a
            // workspace switch, so dedupe by app key + idle state like the
            // Hyprland collector does.
            var lastKey: AppKey? = null
            var lastIdle = false

            while (true) {
                buf.clear()
                val n = channel.read(buf)
                when {
                    n > 0 -> {
                        buf.flip()
                        frame.append(buf)
                        while (true) {
                            val payload = frame.nextFrame() ?: break
                            val change = parseEvent(payload)
                            if (change != null &&
                                (change.appKey != lastKey || change.isIdle != lastIdle)
                            ) {
                                lastKey = change.appKey
                                lastIdle = change.isIdle
                                emit(change)
                            }
                        }
                    }
                    n == 0 -> delay(50) // idle, keep polling
                    else -> break // EOF
                }
            }
        } finally {
            channel.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * A single window-event payload (`{"change":"focus","container":{...}}`).
     *
     * The frame type (0x80000005) already guarantees this is a `window`
     * event — the JSON body of a native Wayland focus event carries no
     * `"window"` key at all (that field only exists on XWayland containers),
     * so a payload-content check for it would silently drop every native
     * event. Only the `change` field is consulted here.
     */
    internal fun parseEvent(payload: String): FocusChange? {
        // Only "focus" is a transition we track. "close", "title", "move"
        // and "bar" carry no focus move and would double-count.
        if (!payload.contains("\"change\":\"focus\"")) return null
        val appId = jsonStringField(payload, "app_id")
        val x11Class = nestedClassField(payload)
        val appValue = appId ?: x11Class
        if (appValue.isNullOrBlank()) {
            // Focus left all windows (no app focused).
            return FocusChange(
                appKey = AppKey(""),
                atMs = System.currentTimeMillis(),
                isIdle = true,
            )
        }
        return FocusChange(
            appKey = resolveAppKey(appValue),
            atMs = System.currentTimeMillis(),
            displayName = displayNameFor(appValue),
            titleHint = titleHintFor(jsonStringField(payload, "name")),
        )
    }

    private fun currentFocusedNode(): FocusChange? = runCatching {
        // GET_TREE returns the full node tree; walk it for the node with
        // `focused: true` (leaves carry app_id/class).
        val reply = runIpcCommand(TYPE_GET_TREE, ByteArray(0)) ?: return null
        val nodeAppKey = parseTree(reply) ?: return null
        FocusChange(
            appKey = resolveAppKey(nodeAppKey),
            atMs = System.currentTimeMillis(),
            displayName = displayNameFor(nodeAppKey),
        )
    }.getOrNull()

    /**
     * Find the focused leaf node's app id in a GET_TREE reply.
     *
     * The tree is deeply nested JSON. Locate each `"focused":true` marker,
     * then walk back to the brace that opens its enclosing node object and
     * forward to its closing brace with a string-aware scanner (window
     * titles may contain braces). Returns the first node that carries an
     * app_id or X11 class.
     */
    internal fun parseTree(json: String): String? {
        var searchFrom = 0
        while (true) {
            val focusIdx = json.indexOf("\"focused\":true", searchFrom)
            if (focusIdx < 0) return null
            val nodeStart = findNodeStart(json, focusIdx)
            val nodeEnd = matchCloseBrace(json, nodeStart)
            if (nodeStart < 0 || nodeEnd <= nodeStart) return null
            val node = json.substring(nodeStart, nodeEnd + 1)
            val appId = jsonStringField(node, "app_id")
            if (!appId.isNullOrBlank()) return appId
            val x11Class = nestedClassField(node)
            if (!x11Class.isNullOrBlank()) return x11Class
            searchFrom = focusIdx + 14
        }
    }

    /**
     * Forward scan to the [targetIdx] position, tracking a stack of open-brace
     * positions. Returns the `{` that opens the object enclosing [targetIdx].
     *
     * A backward scanner cannot distinguish opening from closing quotes, so a
     * forward scan with a depth stack is the correct approach.
     */
    private fun findNodeStart(json: String, targetIdx: Int): Int {
        var i = 0
        var inString = false
        val stack = ArrayDeque<Int>()
        while (i < targetIdx) {
            val c = json[i]
            if (inString) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inString = false
                i++
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> stack.addLast(i)
                '}' -> if (stack.isNotEmpty()) stack.removeLast()
            }
            i++
        }
        return stack.lastOrNull() ?: -1
    }

    /** Walk forward from an opening `{` to its matching `}`. */
    private fun matchCloseBrace(json: String, openIdx: Int): Int {
        if (openIdx < 0 || openIdx >= json.length) return -1
        var depth = 0
        var i = openIdx
        while (i < json.length) {
            when (json[i]) {
                '"' -> {
                    i = skipStringFwd(json, i)
                    continue
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun skipStringFwd(json: String, quoteIdx: Int): Int {
        var i = quoteIdx + 1
        while (i < json.length) {
            when (json[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return i
    }

    /**
     * Run a single request/response IPC round trip (for GET_TREE). Both the
     * header and body are read to completion — a partial read would leave
     * bytes in the socket.
     */
    private fun runIpcCommand(type: Int, payload: ByteArray): String? {
        val address = UnixDomainSocketAddress.of(socketPath)
        val channel = SocketChannel.open(address)
        channel.configureBlocking(true)
        try {
            sendFrame(channel, type, payload)
            val header = ByteBuffer.allocate(HEADER_SIZE)
            if (!readExact(channel, header)) return null
            header.flip()
            if (!isValidMagic(header)) return null
            val length = header.int
            val replyType = header.int
            if (replyType != type) return null
            if (length < 0 || length > MAX_PENDING_CHARS) return null
            val body = ByteBuffer.allocate(length)
            if (!readExact(channel, body)) return null
            return StandardCharsets.UTF_8.decode(body.flip()).toString()
        } finally {
            channel.close()
        }
    }

    /**
     * Read exactly one frame (header + body) and discard it, blocking. A
     * blocking socket cannot leave half a frame behind for the event loop.
     */
    private fun readFrameFully(channel: SocketChannel) {
        runCatching {
            channel.configureBlocking(true)
            val deadline = System.currentTimeMillis() + 2_000
            val header = ByteBuffer.allocate(HEADER_SIZE)
            if (!readExact(channel, header, deadline)) return
            header.flip()
            if (!isValidMagic(header)) return
            val length = header.int
            if (length < 0 || length > MAX_PENDING_CHARS) return
            val body = ByteBuffer.allocate(length)
            readExact(channel, body, deadline)
        }
    }

    /** Block until [target] is filled or EOF. @return true when fully read. */
    private fun readExact(channel: SocketChannel, target: ByteBuffer): Boolean =
        readExact(channel, target, Long.MAX_VALUE)

    private fun readExact(
        channel: SocketChannel,
        target: ByteBuffer,
        deadlineMs: Long,
    ): Boolean {
        while (target.hasRemaining()) {
            if (System.currentTimeMillis() > deadlineMs) return false
            val n = channel.read(target)
            if (n < 0) return false
        }
        return true
    }

    private fun sendFrame(channel: SocketChannel, type: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC)
        header.putInt(payload.size)
        header.putInt(type)
        header.flip()
        while (header.hasRemaining()) channel.write(header)
        if (payload.isNotEmpty()) {
            val body = ByteBuffer.wrap(payload)
            while (body.hasRemaining()) channel.write(body)
        }
    }

    private fun isValidMagic(header: ByteBuffer): Boolean {
        val magic = ByteArray(6)
        header.duplicate().get(magic)
        return magic.contentEquals(MAGIC)
    }

    private fun resolveAppKey(raw: String): AppKey {
        val normalized = raw.trim().lowercase()
        if (normalized.isNotEmpty()) return AppKey(normalized)
        return AppKey("unknown")
    }

    private fun displayNameFor(raw: String): String? =
        raw.trim().takeIf { it.isNotEmpty() }

    private fun titleHintFor(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.take(TITLE_HINT_MAX_CHARS)

    companion object {
        private const val TITLE_HINT_MAX_CHARS = 64
        private val MAGIC = "i3-ipc".toByteArray(StandardCharsets.US_ASCII)
        private const val HEADER_SIZE = 14
        private const val TYPE_SUBSCRIBE = 2
        private const val TYPE_GET_TREE = 4
        private const val MAX_PENDING_CHARS = 1 shl 20

        fun defaultSocketPath(): Path {
            val sock = System.getenv("SWAYSOCK")
                ?: throw IllegalStateException("SWAYSOCK not set")
            return Path.of(sock)
        }

        /** Extract a top-level string field from a JSON node fragment. */
        internal fun jsonStringField(json: String, field: String): String? {
            val key = "\"$field\"\\s*:"
            val m = Regex(key).find(json) ?: return null
            val rest = json.substring(m.range.last + 1).trimStart()
            if (!rest.startsWith("\"")) return null
            var i = 1
            while (i < rest.length) {
                if (rest[i] == '\\') { i += 2; continue }
                if (rest[i] == '"') break
                i++
            }
            if (i >= rest.length) return null
            return rest.substring(1, i)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }

        /**
         * `window_properties.class` for XWayland windows under sway.
         *
         * The `window_properties` object nests `{"class":.., "instance":..,
         * "title":..}` and the X11 title may contain braces, so the closing
         * brace is matched with string awareness — never a naive
         * `indexOf("}")`.
         */
        internal fun nestedClassField(json: String): String? {
            val start = json.indexOf("\"window_properties\"")
            if (start < 0) return null
            val brace = json.indexOf("{", start)
            if (brace < 0) return null
            val end = matchObjectEnd(json, brace)
            if (end < 0) return null
            return jsonStringField(json.substring(brace, end + 1), "class")
        }

        /** String-aware brace match from a known object start. */
        internal fun matchObjectEnd(json: String, openIdx: Int): Int {
            var depth = 0
            var i = openIdx
            while (i < json.length) {
                when (json[i]) {
                    '"' -> {
                        i++
                        while (i < json.length) {
                            if (json[i] == '\\') {
                                i += 2
                            } else if (json[i] == '"') {
                                i++
                                break
                            } else {
                                i++
                            }
                        }
                        continue
                    }
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
            return -1
        }
    }
}

/**
 * Incremental i3-ipc frame parser. The socket is read in arbitrary chunks;
 * the 14-byte header is BINARY (a u32 length and a u32 type that routinely
 * contain bytes >= 0x80), so it must be parsed from raw bytes — an early
 * UTF-8 decode of the chunk would turn every non-ASCII byte into a
 * replacement char and derail the length/type words. The JSON event body
 * is decoded only once a complete frame is buffered.
 *
 * Frames of any other type (subscribe ack, pong, workspace events) are
 * skipped atomically by their header length, and the stream resyncs by
 * magic if it ever goes out of sync.
 */
internal class FrameAccumulator {
    private var pending = ByteArray(0)

    /** @return the next complete window-event JSON payload, or null when a full frame is not yet buffered. */
    fun nextFrame(): String? {
        if (pending.size < HEADER_SIZE) return null
        val magic = String(pending, 0, 6, StandardCharsets.US_ASCII)
        if (magic != MAGIC_STR) {
            // Out of sync; drop bytes up to the next magic, or all of them.
            val next = indexOfMagic(pending)
            pending = if (next < 0) ByteArray(0) else pending.copyOfRange(next, pending.size)
            return nextFrame()
        }
        val length = leUInt(pending, 6)
        if (length < 0 || length > MAX_FRAME_BYTES) {
            pending = ByteArray(0)
            return null
        }
        val type = leUInt(pending, 10)
        // Strictly a window event (0x80000005). Anything else (pong,
        // subscribe ack, workspace events) is skipped atomically by its
        // declared length.
        if (type != WINDOW_EVENT_TYPE) {
            if (pending.size < HEADER_SIZE + length) return null
            pending = pending.copyOfRange(HEADER_SIZE + length.toInt(), pending.size)
            return nextFrame()
        }
        if (pending.size < HEADER_SIZE + length) return null
        val body = String(pending, HEADER_SIZE, length.toInt(), StandardCharsets.UTF_8)
        pending = pending.copyOfRange(HEADER_SIZE + length.toInt(), pending.size)
        return body
    }

    fun append(buf: ByteBuffer) {
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        pending = pending.copyOf(pending.size + bytes.size).also { next ->
            System.arraycopy(bytes, 0, next, pending.size, bytes.size)
        }
        if (pending.size > MAX_FRAME_BYTES) pending = ByteArray(0)
    }

    private fun indexOfMagic(data: ByteArray): Int {
        if (data.size < 6) return -1
        outer@ for (i in 0..data.size - 6) {
            for (j in 0..5) {
                if (data[i + j] != MAGIC_BYTES[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun leInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)

    /** Signed interpretation is wrong for u32 types (the window type is 0x80000005). */
    private fun leUInt(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xff) or
            ((data[offset + 1].toLong() and 0xff) shl 8) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 24)

    companion object {
        private const val HEADER_SIZE = 14
        private val WINDOW_EVENT_TYPE = 0x80000005L
        private val MAGIC_BYTES = "i3-ipc".toByteArray(StandardCharsets.US_ASCII)
        private const val MAGIC_STR = "i3-ipc"

        /** Cap on buffered socket bytes; bounds a stream that never resyncs. */
        private const val MAX_FRAME_BYTES = 1 shl 20
    }
}