package dev.lumen.core.sync

import dev.lumen.core.crypto.CryptoBoxE2EE
import dev.lumen.core.crypto.EncryptedPayload
import dev.lumen.core.crypto.Omemo2E2EE
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.SyncRecord
import dev.lumen.core.store.JvmLumenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The regression this file exists for: two devices, two DIFFERENT keypairs,
 * one account. Before envelope v2 each device encrypted to itself, so the
 * second device could pull every record and decrypt none of them.
 */
class MultiDeviceSyncTest {

    /** One account's pubsub node, shared by every device's transport. */
    private class SharedNode {
        val items = linkedMapOf<Pair<String, Long>, SyncRecord>()
        var newestFirst = false
    }

    private class NodeTransport(private val node: SharedNode) : SyncTransport {
        override val isConfigured = true
        override suspend fun publish(records: List<SyncRecord>): PublishResult {
            records.forEach { node.items[it.deviceId.value to it.seq] = it }
            return PublishResult(records.groupBy { it.deviceId.value }.mapValues { (_, r) -> r.maxOf { it.seq } })
        }
        override suspend fun pull(after: Map<String, Long>): List<SyncRecord> {
            val all = node.items.values.filter { it.seq > (after[it.deviceId.value] ?: -1L) }
            return if (node.newestFirst) all.reversed() else all
        }
        override suspend fun close() {}
    }

    private class Device(name: String) {
        val id = DeviceId(name)
        val sk = X25519PrivateKeyParameters(SecureRandom())
        val pk: X25519PublicKeyParameters = sk.generatePublicKey()
        val store = JvmLumenStore.inMemory().also { it.setAckedSeq(id, -1) }
        val peers = mutableMapOf<DeviceId, X25519PublicKeyParameters>()

        val e2ee get() = Omemo2E2EE(id, sk, pk, publicKeyResolver = { peers[it] })

        fun engine(node: SharedNode): Pair<SyncEngine, EncryptedTransport> {
            val transport = EncryptedTransport(NodeTransport(node), e2ee, recipients = { peers.keys })
            return SyncEngine(store, transport, id) to transport
        }

        fun track(seq: Long) = store.insertEvent(
            FocusEvent(seq = seq, deviceId = id, appKey = AppKey("app.$seq"), startedAtMs = seq * 60_000, durationMs = 60_000),
        )
    }

    private fun pair(a: Device, b: Device) {
        a.peers[b.id] = b.pk
        b.peers[a.id] = a.pk
    }

    @Test
    fun `a second device with its own key decrypts what the first published`() = runBlocking {
        val node = SharedNode()
        val laptop = Device("laptop")
        val phone = Device("phone")
        pair(laptop, phone)

        (0L..2L).forEach(laptop::track)
        laptop.engine(node).first.sync()
        val report = phone.engine(node).first.sync()

        assertEquals(3, report.pulled)
        assertEquals(3, report.remoteEvents.size)
        assertTrue(report.integrityWarnings.isEmpty(), "${report.integrityWarnings}")
        assertEquals(listOf(0L, 1L, 2L), phone.store.eventsAfter(laptop.id, Long.MIN_VALUE).map { it.seq })
    }

    @Test
    fun `newest-first server order still applies every record`() = runBlocking {
        val node = SharedNode().apply { newestFirst = true }
        val a = Device("a")
        val b = Device("b")
        pair(a, b)
        (0L..4L).forEach(a::track)
        a.engine(node).first.sync()

        val report = b.engine(node).first.sync()

        assertEquals(5, b.store.eventsAfter(a.id, Long.MIN_VALUE).size)
        assertTrue(report.integrityWarnings.none { it.startsWith("replay") }, "${report.integrityWarnings}")
    }

    @Test
    fun `a second pass pulls nothing and warns about nothing`() = runBlocking {
        val node = SharedNode()
        val a = Device("a")
        val b = Device("b")
        pair(a, b)
        (0L..3L).forEach(a::track)
        a.engine(node).first.sync()
        b.engine(node).first.sync()

        val again = b.engine(node).first.sync()

        assertEquals(0, again.pulled)
        assertTrue(again.integrityWarnings.isEmpty(), "${again.integrityWarnings}")
    }

    @Test
    fun `an untrusted device is reported, not fatal, and retried once trusted`() = runBlocking {
        val node = SharedNode()
        val a = Device("a")
        val b = Device("b")
        a.peers[b.id] = b.pk // a encrypts for b, but b has not pinned a yet
        (0L..1L).forEach(a::track)
        a.engine(node).first.sync()

        val first = b.engine(node).first.sync()
        assertEquals(0, first.pulled)
        assertTrue(first.integrityWarnings.any { it.startsWith("undecryptable") })

        b.peers[a.id] = a.pk
        val second = b.engine(node).first.sync()
        assertEquals(2, second.pulled)
    }

    @Test
    fun `records written before a device joined are skipped as known gaps`() = runBlocking {
        val node = SharedNode()
        val a = Device("a")
        val b = Device("b")
        a.track(0)
        a.engine(node).first.sync() // b not yet a recipient
        pair(a, b)
        a.track(1)
        a.engine(node).first.sync()

        val report = b.engine(node).first.sync()

        assertEquals(1, report.pulled)
        assertTrue(report.integrityWarnings.none { it.startsWith("gap") }, "${report.integrityWarnings}")
        assertTrue(report.integrityWarnings.any { it.contains("before this device joined") })
    }

    @Test
    fun `a record relabelled as another device is refused`() = runBlocking {
        val node = SharedNode()
        val a = Device("a")
        val b = Device("b")
        val c = Device("c")
        pair(a, b)
        b.peers[c.id] = c.pk
        a.track(0)
        a.engine(node).first.sync()
        val stolen = node.items.values.single()
        node.items.clear()
        node.items[c.id.value to 0L] = stolen.copy(deviceId = c.id)

        val report = b.engine(node).first.sync()

        assertEquals(0, report.pulled)
        assertTrue(report.integrityWarnings.any { it.startsWith("undecryptable") })
    }

    @Test
    fun `swapping two valid records of one device is caught`() = runBlocking {
        val node = SharedNode()
        val a = Device("a")
        val b = Device("b")
        pair(a, b)
        a.track(0)
        a.track(1)
        a.engine(node).first.sync()
        val r0 = node.items.getValue("a" to 0L)
        val r1 = node.items.getValue("a" to 1L)
        node.items["a" to 0L] = r0.copy(payload = r1.payload)
        node.items["a" to 1L] = r1.copy(payload = r0.payload)

        val report = b.engine(node).first.sync()

        assertTrue(report.integrityWarnings.any { it.contains("another record") }, "${report.integrityWarnings}")
        assertEquals(0, b.store.eventsAfter(a.id, Long.MIN_VALUE).size)
    }

    @Test
    fun `v2 ciphertext is tamper-evident and padded`() {
        val a = Device("a")
        val b = Device("b")
        pair(a, b)
        val short = a.e2ee.encryptForDevices(ByteArray(3), setOf(b.id))
        val longer = a.e2ee.encryptForDevices(ByteArray(40), setOf(b.id))
        assertEquals(short.ciphertext.size, longer.ciphertext.size, "sizes inside one pad block must match")
        assertEquals(setOf(a.id, b.id), short.wrappedKeys.keys)

        val flipped = short.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<IllegalArgumentException> { b.e2ee.decrypt(short.copy(ciphertext = flipped)) }
        assertEquals(3, b.e2ee.decrypt(short).size)
    }

    @Test
    fun `v1 envelopes already on the server still decrypt`() {
        val a = Device("a")
        val v1 = CryptoBoxE2EE(a.id, a.sk, a.pk, publicKeyResolver = { a.pk }).encrypt("old".toByteArray(), a.id)
        assertEquals("old", a.e2ee.decrypt(v1).decodeToString())
    }

    @Test
    fun `the envelope version is on the wire`() {
        val a = Device("a")
        val json = Json { encodeDefaults = true }
        val text = json.encodeToString(EncryptedPayload.serializer(), a.e2ee.encryptForDevices(ByteArray(1), emptySet()))
        assertTrue(text.contains("\"version\":2"), text)
    }
}
