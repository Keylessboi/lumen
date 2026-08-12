package dev.lumen.core.sync

import dev.lumen.core.crypto.CryptoBoxE2EE
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.RecordKind
import dev.lumen.core.model.SyncRecord
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Round-trip test for [EncryptedTransport]: the decorator must encrypt
 * every payload before it reaches the wire and decrypt it on the way
 * back, so the SyncEngine above it only ever sees plaintext.
 */
class EncryptedTransportTest {

    private val sender = DeviceId("sender")
    private val recipient = DeviceId("recipient")

    private class Party(val id: DeviceId) {
        val sk = X25519PrivateKeyParameters(SecureRandom())
        val pk: X25519PublicKeyParameters = sk.generatePublicKey()
    }

    private val senderParty = Party(sender)
    private val recipientParty = Party(recipient)

    private fun e2ee(): CryptoBoxE2EE =
        CryptoBoxE2EE(
            senderDeviceId = senderParty.id,
            senderSk = senderParty.sk,
            senderPk = senderParty.pk,
            publicKeyResolver = { id ->
                when (id) {
                    senderParty.id -> senderParty.pk
                    recipientParty.id -> recipientParty.pk
                    else -> error("unknown device $id")
                }
            },
        )

    private fun plaintextRecord(seq: Long) =
        SyncRecord(
            deviceId = senderParty.id,
            seq = seq,
            kind = RecordKind.EVENT,
            payload = "plaintext-$seq".toByteArray(),
        )

    private class RecordingTransport : SyncTransport {
        val wirePayloads = mutableListOf<ByteArray>()
        var pullResult: List<SyncRecord> = emptyList()
        override val isConfigured: Boolean get() = true

        override suspend fun publish(records: List<SyncRecord>): PublishResult {
            wirePayloads += records.map { it.payload }
            val acked = records.groupBy { it.deviceId.value }.mapValues { (_, rs) -> rs.maxOf { it.seq } }
            return PublishResult(acked = acked)
        }

        override suspend fun pull(after: Map<String, Long>): List<SyncRecord> = pullResult
        override suspend fun close() {}
    }

    @Test
    fun `publish encrypts payloads before the wire`() {
        val wire = RecordingTransport()
        val transport = EncryptedTransport(wire, e2ee())

        runBlocking { transport.publish(listOf(plaintextRecord(1))) }

        assertEquals(1, wire.wirePayloads.size)
        // The wire must NOT carry the plaintext.
        assertNotEquals("plaintext-1".toByteArray().toList(), wire.wirePayloads[0].toList())
        assertTrue(String(wire.wirePayloads[0]).contains("\"version\":1"), "wire carries an envelope")
    }

    @Test
    fun `pull decrypts payloads back to plaintext`() {
        val wire = RecordingTransport()
        val transport = EncryptedTransport(wire, e2ee())

        // Simulate a peer that encrypted for us.
        runBlocking { transport.publish(listOf(plaintextRecord(1))) }
        wire.pullResult = wire.wirePayloads.map { payload ->
            plaintextRecord(1).copy(payload = payload)
        }

        val pulled = runBlocking { transport.pull(emptyMap()) }

        assertEquals(1, pulled.size)
        assertEquals("plaintext-1", String(pulled[0].payload))
    }

    @Test
    fun `full round trip survives publish then pull`() {
        val wire = RecordingTransport()
        val transport = EncryptedTransport(wire, e2ee())

        runBlocking { transport.publish(listOf(plaintextRecord(1), plaintextRecord(2))) }
        wire.pullResult = wire.wirePayloads.mapIndexed { i, payload ->
            plaintextRecord(i + 1L).copy(payload = payload)
        }

        val pulled = runBlocking { transport.pull(emptyMap()) }

        assertEquals(2, pulled.size)
        assertEquals("plaintext-1", String(pulled[0].payload))
        assertEquals("plaintext-2", String(pulled[1].payload))
        assertEquals(senderParty.id, pulled[0].deviceId)
    }
}