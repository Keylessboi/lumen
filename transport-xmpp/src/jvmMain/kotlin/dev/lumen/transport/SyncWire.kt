package dev.lumen.transport

import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.RecordKind
import dev.lumen.core.model.SyncRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The bytes inside a pubsub item. Pure functions, so they are unit-tested
 * without a server.
 *
 * Item payload: `<lumen-batch xmlns='urn:lumen:sync:batch:1'>BASE64</lumen-batch>`
 * where BASE64 is gzip(JSON array of [WireRecord]). Base64 needs no XML
 * escaping; the old layout put raw JSON in element text, which broke on the
 * first `&` or `<` and inflated every byte of payload into a JSON number.
 *
 * Items in the old single-record layout (`<lumen-record>`) still decode.
 */
internal object SyncWire {
    private const val BATCH_ELEMENT = "lumen-batch"
    private const val BATCH_NS = "urn:lumen:sync:batch:1"
    private const val LEGACY_ELEMENT = "lumen-record"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class WireRecord(val d: String, val s: Long, val k: RecordKind, val p: String)

    fun itemId(batch: List<SyncRecord>): String =
        "${batch.first().deviceId.value}:${batch.first().seq}-${batch.last().seq}"

    /** Whether an item id may hold records past [after]. Unknown ids: yes. */
    fun needed(itemId: String, after: Map<String, Long>): Boolean {
        val device = itemId.substringBeforeLast(':', "")
        val last = itemId.substringAfterLast(':').substringAfter('-', "").toLongOrNull()
        if (device.isEmpty() || last == null) return true
        return last > (after[device] ?: -1L)
    }

    fun encodeBatch(batch: List<SyncRecord>): String {
        val records = batch.map {
            WireRecord(it.deviceId.value, it.seq, it.kind, Base64.getEncoder().encodeToString(it.payload))
        }
        val text = json.encodeToString(ListSerializer(WireRecord.serializer()), records)
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.encodeToByteArray()) } }
        return "<$BATCH_ELEMENT xmlns='$BATCH_NS'>${Base64.getEncoder().encodeToString(gz.toByteArray())}</$BATCH_ELEMENT>"
    }

    fun decodeItem(xml: String, legacyJson: Json): List<SyncRecord> {
        unwrapText(xml, BATCH_ELEMENT)?.let { body ->
            return runCatching {
                val raw = GZIPInputStream(Base64.getDecoder().decode(body.trim()).inputStream()).use { it.readBytes() }
                json.decodeFromString(ListSerializer(WireRecord.serializer()), raw.decodeToString()).map {
                    SyncRecord(DeviceId(it.d), it.s, it.k, Base64.getDecoder().decode(it.p))
                }
            }.getOrDefault(emptyList())
        }
        unwrapText(xml, LEGACY_ELEMENT)?.let { body ->
            return listOfNotNull(runCatching { legacyJson.decodeFromString(SyncRecord.serializer(), body) }.getOrNull())
        }
        return emptyList()
    }

    /** `<element xmlns='ns'>escaped text</element>`. */
    fun wrapText(element: String, ns: String, text: String): String = "<$element xmlns='$ns'>${escape(text)}</$element>"

    /**
     * Inner text of [element], entity-decoded. Servers re-serialize stored
     * payloads with their own escaping (`&quot;` for quotes), so the
     * recovered text has to be decoded before it is valid JSON again.
     */
    fun unwrapText(xml: String, element: String): String? {
        val open = Regex("<$element(\\s[^>]*)?>").find(xml) ?: return null
        val end = xml.lastIndexOf("</$element>")
        if (end < open.range.last) return null
        return unescape(xml.substring(open.range.last + 1, end))
    }

    private fun escape(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun unescape(text: String) = text
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&")
}
