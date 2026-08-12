package dev.lumen.core.model

import dev.lumen.core.crypto.EncryptedPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Value equality for the three types that hold a `ByteArray` (#19).
 *
 * Kotlin generates `equals`/`hashCode` from constructor properties, and
 * `ByteArray` inherits identity equality — so two byte-identical records
 * compared unequal. Nothing was visibly broken, because the sync path dedupes
 * by `(deviceId, seq)` rather than by the record, but the type invited the
 * shortcut, and M5's export round-trip is exactly a "the same data comes
 * back" assertion.
 */
class ByteArrayEqualityTest {

    private val device = DeviceId("11111111-2222-3333-4444-555555555555")
    private val other = DeviceId("99999999-8888-7777-6666-555555555555")

    private fun setting(value: ByteArray, key: String = "nudge.break") =
        Setting(key, value, 1_000L, "2026-06-15", device)

    private fun record(payload: ByteArray, seq: Long = 1L) =
        SyncRecord(device, seq, RecordKind.EVENT, payload)

    private fun payload(cipher: ByteArray, nonce: ByteArray = ByteArray(24) { 7 }) =
        EncryptedPayload(senderDeviceId = device, nonce = nonce, ciphertext = cipher)

    // ---- Setting ----

    @Test
    fun `settings with identical bytes are equal`() {
        assertEquals(setting(byteArrayOf(1, 2, 3)), setting(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `settings differing only in bytes are not equal`() {
        assertNotEquals(setting(byteArrayOf(1, 2, 3)), setting(byteArrayOf(1, 2, 4)))
    }

    @Test
    fun `settings differing in a non-byte field are not equal`() {
        assertNotEquals(setting(byteArrayOf(1), key = "a"), setting(byteArrayOf(1), key = "b"))
    }

    @Test
    fun `equal settings collapse in a set`() {
        assertEquals(1, setOf(setting(byteArrayOf(1)), setting(byteArrayOf(1))).size)
    }

    @Test
    fun `an empty value is not the same as a different empty-ish value`() {
        assertEquals(setting(ByteArray(0)), setting(ByteArray(0)))
        assertNotEquals(setting(ByteArray(0)), setting(byteArrayOf(0)))
    }

    // ---- SyncRecord ----

    @Test
    fun `records with identical payloads are equal and hash alike`() {
        val a = record(byteArrayOf(4, 8, 15))
        val b = record(byteArrayOf(4, 8, 15))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `a replayed record deduplicates in a set`() {
        // The failure closest to real data loss: a Set<SyncRecord> that
        // silently keeps duplicates.
        assertEquals(1, setOf(record(byteArrayOf(1)), record(byteArrayOf(1))).size)
        assertEquals(2, setOf(record(byteArrayOf(1)), record(byteArrayOf(1), seq = 2)).size)
    }

    // ---- EncryptedPayload ----

    @Test
    fun `envelopes with identical contents are equal`() {
        assertEquals(payload(byteArrayOf(1, 2)), payload(byteArrayOf(1, 2)))
    }

    @Test
    fun `a different nonce makes a different envelope`() {
        assertNotEquals(
            payload(byteArrayOf(1), nonce = ByteArray(24) { 1 }),
            payload(byteArrayOf(1), nonce = ByteArray(24) { 2 }),
        )
    }

    @Test
    fun `wrappedKeys are compared by content, not identity`() {
        val a = payload(byteArrayOf(1)).copy(wrappedKeys = mapOf(other to byteArrayOf(9, 9)))
        val b = payload(byteArrayOf(1)).copy(wrappedKeys = mapOf(other to byteArrayOf(9, 9)))
        val c = payload(byteArrayOf(1)).copy(wrappedKeys = mapOf(other to byteArrayOf(9, 8)))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `wrappedKeys equality does not depend on map order`() {
        val d2 = DeviceId("33333333-3333-3333-3333-333333333333")
        val a = payload(byteArrayOf(1)).copy(
            wrappedKeys = linkedMapOf(other to byteArrayOf(1), d2 to byteArrayOf(2)),
        )
        val b = payload(byteArrayOf(1)).copy(
            wrappedKeys = linkedMapOf(d2 to byteArrayOf(2), other to byteArrayOf(1)),
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ---- the property that made this worth fixing ----

    @Test
    fun `hashCode is stable for equal values, not an identity hash`() {
        // The generated version returned a different hash per instance, so
        // anything keyed on one was non-deterministic in a way that looks
        // like a flake rather than a bug.
        repeat(20) {
            assertEquals(
                setting(byteArrayOf(1, 2, 3)).hashCode(),
                setting(byteArrayOf(1, 2, 3)).hashCode(),
            )
        }
    }

    @Test
    fun `a copy equals its original`() {
        // data class copy() is the idiom every caller reaches for; before
        // this it produced something unequal to what it was copied from.
        val original = setting(byteArrayOf(5, 6))
        assertEquals(original, original.copy())
        assertTrue(setOf(original, original.copy()).size == 1)
    }
}
