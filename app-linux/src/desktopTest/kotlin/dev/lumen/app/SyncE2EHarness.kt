package dev.lumen.app

import dev.lumen.app.common.SyncController
import dev.lumen.core.crypto.InMemorySecretStore
import dev.lumen.core.crypto.KeyPairRef
import dev.lumen.core.crypto.Keychain
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.store.JvmLumenStore
import dev.lumen.transport.XmppRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom
import kotlin.random.Random

/**
 * MANUAL LIVE HARNESS — not a @Test. Creates a real throwaway account.
 *
 * Two devices with TWO DIFFERENT keypairs on one real account, through the
 * same [SyncController] the apps use. The previous version of this harness
 * gave both devices the same keypair ("v1: both devices hold the account
 * key"), which is not what the apps did — each app generates its own — so
 * it passed while real two-device sync could never decrypt anything.
 *
 * Needs a CAPTCHA-free server, e.g. `runSyncE2E("yax.im")`.
 */
fun runSyncE2E(domain: String) = runBlocking {
    val username = "lumen-e2e-${Random.nextInt(100000, 999999)}"
    val password = "lumen-e2e-${Random.nextBytes(8).joinToString("") { "%02x".format(it) }}"
    XmppRegistration.begin(domain).use { it.form(); it.submit(username, password, emptyMap()) }
    println("registered $username@$domain")

    class Device(name: String) : Keychain {
        val id = DeviceId("e2e-$name")
        val store = JvmLumenStore.inMemory()
        private val sk = X25519PrivateKeyParameters(SecureRandom())
        override fun deviceKeyPair() = KeyPairRef(sk.generatePublicKey().encoded, sk.encoded)
        val sync = SyncController(store, id, this, InMemorySecretStore(), name, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    }

    val a = Device("laptop")
    val b = Device("phone")
    a.sync.signIn(domain, username, password)
    b.sync.signIn(domain, username, password)
    a.store.insertEvent(FocusEvent(0, a.id, AppKey("org.mozilla.firefox"), startedAtMs = System.currentTimeMillis(), durationMs = 60_000))
    a.sync.syncOnce()
    val report = b.sync.syncOnce()
    val ok = report?.remoteEvents?.singleOrNull()?.appKey == AppKey("org.mozilla.firefox")
    println(if (ok) "=== E2E PASS ===" else "=== E2E FAIL: $report / ${b.sync.state.value} ===")
}

fun main() = runSyncE2E("yax.im")
