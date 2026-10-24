package dev.lumen.transport

import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.RecordKind
import dev.lumen.core.model.SyncRecord
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncWireTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun rec(device: String, seq: Long, body: String = "p$seq") =
        SyncRecord(DeviceId(device), seq, RecordKind.EVENT, body.encodeToByteArray())

    @Test
    fun `two devices with the same seq get different item ids`() {
        // The bug: item id was the bare seq, so device B's seq 0 replaced A's.
        assertNotEquals(SyncWire.itemId(listOf(rec("a", 0))), SyncWire.itemId(listOf(rec("b", 0))))
    }

    @Test
    fun `a batch round-trips, payload bytes intact`() {
        val batch = listOf(rec("dev-1", 4, "<&>\"' {json}"), rec("dev-1", 5, "x"))
        val decoded = SyncWire.decodeItem(SyncWire.encodeBatch(batch), json)
        assertEquals(batch.map { it.seq }, decoded.map { it.seq })
        assertContentEquals(batch[0].payload, decoded[0].payload)
        assertEquals(DeviceId("dev-1"), decoded[0].deviceId)
    }

    @Test
    fun `the item id tells pull whether to fetch`() {
        val id = SyncWire.itemId(listOf(rec("dev-1", 10), rec("dev-1", 34)))
        assertEquals("dev-1:10-34", id)
        assertTrue(SyncWire.needed(id, emptyMap()))
        assertTrue(SyncWire.needed(id, mapOf("dev-1" to 33)))
        assertFalse(SyncWire.needed(id, mapOf("dev-1" to 34)))
        assertTrue(SyncWire.needed("17", mapOf("dev-1" to 99)), "legacy ids are always fetched")
    }

    @Test
    fun `legacy single-record items still decode after server re-escaping`() {
        val body = json.encodeToString(SyncRecord.serializer(), rec("old", 3))
            .replace("\"", "&quot;")
        val decoded = SyncWire.decodeItem("<lumen-record xmlns='urn:lumen:sync:record'>$body</lumen-record>", json)
        assertEquals(3L, decoded.single().seq)
    }

    @Test
    fun `wrapped text survives markup characters`() {
        val text = """{"label":"Tom's <laptop> & co"}"""
        val xml = SyncWire.wrapText("device", "urn:x", text)
        assertEquals(text, SyncWire.unwrapText(xml, "device"))
    }

    @Test
    fun `garbage items decode to nothing instead of throwing`() {
        assertTrue(SyncWire.decodeItem("<lumen-batch xmlns='urn:lumen:sync:batch:1'>not base64</lumen-batch>", json).isEmpty())
        assertTrue(SyncWire.decodeItem("<other/>", json).isEmpty())
    }
}
