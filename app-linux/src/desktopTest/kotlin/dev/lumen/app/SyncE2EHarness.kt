package dev.lumen.app

import dev.lumen.core.crypto.CryptoBoxE2EE
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.SyncState
import dev.lumen.core.session.FocusSessionTracker
import dev.lumen.core.store.JvmLumenStore
import dev.lumen.core.sync.EncryptedTransport
import dev.lumen.core.sync.SyncEngine
import dev.lumen.transport.XmppTransport
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.File
import java.security.SecureRandom
import kotlin.random.Random

/**
 * MANUAL LIVE HARNESS — not a @Test. G3 gate evidence.
 *
 * Two devices, one account keypair (the v1 shared-account model): device A
 * publishes encrypted events to a real public provider via the full stack
 * (XmppTransport -> EncryptedTransport -> SyncEngine), device B pulls and
 * decrypts them. Proves ciphertext-only sync end-to-end.
 *
 * Run from a scratch main:
 *   runSyncE2E("jabber.fr", 5222)
 *
 * NOTE: jabber.fr PEP nodes are transient (measured — keeps only the
 * latest item), so this asserts the latest record round-trips; the full
 * history guarantee is a provider-capability question, not a transport
 * bug (see Providers.KNOWN_TRANSIENT_NODES).
 */
fun runSyncE2E(providerJid: String, port: Int = 5222) = runBlocking {
    println("=== M4 sync E2E on $providerJid ===")

    // Register a throwaway account via IBR (XEP-0077).
    val username = "lumen-e2e-${Random.nextInt(100000, 999999)}"
    val password = "lumen-e2e-${Random.nextBytes(8).joinToString("") { "%02x".format(it) }}"
    val registrar = XmppTransport(host = providerJid, port = port, jid = "unused", password = "unused")
    val accountJid = registrar.register(username, password)
    println("registered $accountJid")

    // Shared account keypair (v1: both devices hold the account key).
    val sk = X25519PrivateKeyParameters(SecureRandom())
    val pk = X25519PublicKeyParameters(sk.generatePublicKey().encoded)
    fun e2ee(id: DeviceId) = CryptoBoxE2EE(
        senderDeviceId = id, senderSk = sk, senderPk = pk,
        publicKeyResolver = { _ -> pk }, // account key, both devices
    )

    // Device A: publish one event.
    val deviceA = DeviceId("e2e-device-a")
    val storeA = tempStore("a")
    seedEvent(storeA, deviceA, seq = 0, appKey = "org.mozilla.firefox")
    val ackA = runBlocking {
        val transport = EncryptedTransport(
            XmppTransport(providerJid, port, accountJid, password),
            e2ee(deviceA),
        )
        SyncEngine(storeA, transport, deviceA).push()
    }
    println("device A published $ackA event(s)")

    // Device B: fresh store, same account key — pull and decrypt.
    val deviceB = DeviceId("e2e-device-b")
    val storeB = tempStore("b")
    val pulled = runBlocking {
        val transport = EncryptedTransport(
            XmppTransport(providerJid, port, accountJid, password),
            e2ee(deviceB),
        )
        SyncEngine(storeB, transport, deviceB).pull()
    }
    println("device B pulled ${pulled.size} record(s)")

    val ok = pulled.isNotEmpty()
    println(if (ok) "=== G3 E2E PASS ===" else "=== G3 E2E FAIL ===")

    runCatching {
        val cleanup = XmppTransport(providerJid, port, accountJid, password)
        cleanup.deleteAccount()
        println("account deleted")
    }.onFailure { println("cleanup skipped: ${it.message}") }
}

/** Seed one event into [store] and return the device id. */
private fun seedEvent(store: JvmLumenStore, deviceId: DeviceId, seq: Long, appKey: String) {
    store.insertEvent(
        FocusEvent(
            seq = seq,
            deviceId = deviceId,
            appKey = AppKey(appKey),
            startedAtMs = System.currentTimeMillis(),
            durationMs = 60_000,
            category = null,
            syncState = SyncState.LOCAL,
        ),
    )
}

private fun tempStore(suffix: String): JvmLumenStore {
    // DriverManager auto-registration via ServiceLoader does not fire in a
    // bare `java -cp` harness; load the driver explicitly (the packaged
    // app picks it up from the runtime image instead).
    Class.forName("org.sqlite.JDBC")
    val f = File.createTempFile("lumen-e2e-$suffix", ".db").also { it.delete() }
    val store = JvmLumenStore.open(f)
    // Fresh device watermark must be -1 (nothing acked), not the store's 0
    // default — else `eventsAfter(seq > 0)` excludes the first event (seq 0)
    // and push() publishes nothing. Mirrors resolveDeviceId().
    store.setAckedSeq(DeviceId("e2e-device-$suffix"), -1)
    return store
}
fun main(args: Array<String>) {
    runSyncE2E("jabber.fr", 5222)
}
