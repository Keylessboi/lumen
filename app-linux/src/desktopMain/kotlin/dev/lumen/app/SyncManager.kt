package dev.lumen.app

import dev.lumen.app.keychain.LinuxKeychain
import dev.lumen.core.crypto.CryptoBoxE2EE
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.Setting
import dev.lumen.core.store.JvmLumenStore
import dev.lumen.core.sync.EncryptedTransport
import dev.lumen.core.sync.SyncEngine
import dev.lumen.core.sync.SyncReport
import dev.lumen.transport.XmppTransport
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.File

/**
 * M4 — composes the frozen seams into a working sync for app-linux.
 *
 * The full stack, bottom-up:
 *
 *   XmppTransport (transport-xmpp)         raw XMPP + PEP pubsub
 *   EncryptedTransport (core sync)         E2EE every payload on the wire
 *   SyncEngine (core sync)                 watermark pull/push, dedupe,
 *                                          gap/replay detection
 *   CryptoBoxE2EE (core desktop)           X25519 + XSalsa20-Poly1305
 *   LinuxKeychain (app-linux)              device keypair at rest
 *
 * Account credentials (provider host/port/JID/password) live in the
 * settings table; the device identity lives in the Secret Service. This
 * is the "sync additive, never a dependency" posture from docs/plan.md:
 * unconfigured -> [isConfigured] is false and nothing runs.
 */
class SyncManager(
    private val store: JvmLumenStore,
    private val deviceId: DeviceId,
    private val keychain: LinuxKeychain = LinuxKeychain(),
) {

    /** True when an account is saved and sync may run. */
    fun isConfigured(): Boolean = account() != null

    fun saveAccount(config: AccountConfig) {
        store.upsertSetting(Setting(KEY_HOST, config.host.toByteArray(), now(), day(), deviceId))
        store.upsertSetting(Setting(KEY_PORT, config.port.toString().toByteArray(), now(), day(), deviceId))
        store.upsertSetting(Setting(KEY_JID, config.jid.toByteArray(), now(), day(), deviceId))
        store.upsertSetting(Setting(KEY_PASSWORD, config.password.toByteArray(), now(), day(), deviceId))
    }

    fun clearAccount() {
        store.upsertSetting(Setting(KEY_HOST, ByteArray(0), now(), day(), deviceId))
        store.upsertSetting(Setting(KEY_PORT, ByteArray(0), now(), day(), deviceId))
        store.upsertSetting(Setting(KEY_JID, ByteArray(0), now(), day(), deviceId))
        store.upsertSetting(Setting(KEY_PASSWORD, ByteArray(0), now(), day(), deviceId))
    }

    fun account(): AccountConfig? {
        val host = setting(KEY_HOST) ?: return null
        val port = setting(KEY_PORT)?.toIntOrNull() ?: return null
        val jid = setting(KEY_JID) ?: return null
        val password = setting(KEY_PASSWORD) ?: return null
        return AccountConfig(host, port, jid, password)
    }

    /**
     * One full sync pass. Returns the engine's report; throws on
     * transport failure (caller owns retry/backoff).
     */
    suspend fun syncOnce(): SyncReport {
        val config = account() ?: error("sync not configured")
        val keyPair = keychain.deviceKeyPair()
        val sk = X25519PrivateKeyParameters(keyPair.privateKeyHandle)
        val pk = X25519PublicKeyParameters(keyPair.publicKey)

        // v1 is device-to-server-to-self: records are encrypted to the
        // account's OWN keypair, so the resolver serves the local public
        // key. Multi-device fan-out (peer keys from the devices table,
        // wrappedKeys in the envelope) is the later milestone — the
        // resolver is the extension point where that lands.
        val e2ee = CryptoBoxE2EE(
            senderDeviceId = deviceId,
            senderSk = sk,
            senderPk = pk,
            publicKeyResolver = { _ -> pk },
        )

        val xmpp = XmppTransport(
            host = config.host,
            port = config.port,
            jid = config.jid,
            password = config.password,
        )
        val transport = EncryptedTransport(xmpp, e2ee)
        val engine = SyncEngine(store, transport, deviceId)
        return engine.sync()
    }

    private fun setting(key: String): String? =
        store.setting(key)?.value?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

    private fun now(): Long = System.currentTimeMillis()
    private fun day(): String = dev.lumen.core.clock.UtcDay.today()

    companion object {
        const val KEY_HOST = "sync.host"
        const val KEY_PORT = "sync.port"
        const val KEY_JID = "sync.jid"
        const val KEY_PASSWORD = "sync.password"
    }
}

data class AccountConfig(
    val host: String,
    val port: Int,
    val jid: String,
    val password: String,
)