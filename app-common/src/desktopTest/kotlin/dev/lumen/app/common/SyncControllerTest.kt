package dev.lumen.app.common

import dev.lumen.core.crypto.InMemorySecretStore
import dev.lumen.core.crypto.KeyPairRef
import dev.lumen.core.crypto.Keychain
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.Setting
import dev.lumen.core.model.SyncRecord
import dev.lumen.core.store.JvmLumenStore
import dev.lumen.core.sync.AccountTransport
import dev.lumen.core.sync.DeviceBundle
import dev.lumen.core.sync.DeviceDirectory
import dev.lumen.core.sync.PublishResult
import dev.lumen.ui.AccountUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two installs, two keychains, one account — the whole app-level pass with
 * the server swapped for memory. This is the scenario that never worked:
 * time tracked on the laptop showing up on the phone.
 */
class SyncControllerTest {

    private class Account {
        val items = linkedMapOf<Pair<String, Long>, SyncRecord>()
        val devices = linkedMapOf<String, DeviceBundle>()
        var password = "correct horse"
    }

    private class FakeTransport(private val account: Account, private val password: String) : AccountTransport {
        override val isConfigured = true
        override suspend fun verifyLogin() {
            if (password != account.password) throw dev.lumen.transport.XmppUserError(
                dev.lumen.transport.XmppUserError.Kind.BAD_CREDENTIALS, "Wrong username or password for this server.",
            )
        }
        override suspend fun publish(records: List<SyncRecord>): PublishResult {
            records.forEach { account.items[it.deviceId.value to it.seq] = it }
            return PublishResult(records.groupBy { it.deviceId.value }.mapValues { (_, r) -> r.maxOf { it.seq } })
        }
        override suspend fun pull(after: Map<String, Long>) =
            account.items.values.filter { it.seq > (after[it.deviceId.value] ?: -1) }.reversed()
        override suspend fun close() {}
        override fun deviceDirectory() = object : DeviceDirectory {
            override suspend fun publish(bundle: DeviceBundle) { account.devices[bundle.deviceId.value] = bundle }
            override suspend fun bundles() = account.devices.values.toList()
            override suspend fun remove(deviceId: DeviceId) { account.devices.remove(deviceId.value) }
        }
    }

    private class MemKeychain : Keychain {
        private val sk = X25519PrivateKeyParameters(SecureRandom())
        override fun deviceKeyPair() = KeyPairRef(sk.generatePublicKey().encoded, sk.encoded)
    }

    private inner class Install(name: String, val account: Account) {
        val id = DeviceId(name)
        val store = JvmLumenStore.inMemory()
        val secrets = InMemorySecretStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = SyncController(
            store, id, MemKeychain(), secrets, "$name device", scope,
            transportFor = { _, password -> FakeTransport(account, password) },
        )

        fun track(seq: Long, app: String, minutes: Long) = store.insertEvent(
            FocusEvent(seq, id, AppKey(app), startedAtMs = 1_700_000_000_000 + seq * 3_600_000, durationMs = minutes * 60_000),
        )
    }

    @Test
    fun `time tracked on one device shows up on the other`() = runBlocking {
        val account = Account()
        val laptop = Install("laptop", account)
        val phone = Install("phone", account)
        laptop.controller.signIn("example.org", "me", account.password)
        phone.controller.signIn("example.org", "me", account.password)
        assertIs<AccountUiState.Connected>(laptop.controller.state.value)

        laptop.track(0, "org.gnome.Terminal", 30)
        laptop.track(1, "firefox", 15)
        laptop.controller.syncOnce()

        val report = assertNotNull(phone.controller.syncOnce())
        assertEquals(2, report.remoteEvents.size, "${report.integrityWarnings}")
        assertTrue(report.integrityWarnings.isEmpty(), "${report.integrityWarnings}")
        val buckets = phone.store.bucketsForRange(laptop.id, 0, Long.MAX_VALUE)
        assertEquals(45L * 60_000, buckets.sumOf { it.activeMs })
        assertEquals(listOf(laptop.id), phone.controller.peerDevices())

        val connected = assertIs<AccountUiState.Connected>(phone.controller.state.value)
        assertEquals(setOf("phone device", "laptop device"), connected.devices.map { it.label }.toSet())
        assertTrue(connected.lastSync != null)
    }

    @Test
    fun `a device that joins later still receives the history`() = runBlocking {
        val account = Account()
        val laptop = Install("laptop", account)
        laptop.controller.signIn("example.org", "me", account.password)
        (0L..4L).forEach { laptop.track(it, "app$it", 10) }
        laptop.controller.syncOnce() // published while it was the only device

        val phone = Install("phone", account)
        phone.controller.signIn("example.org", "me", account.password) // announces itself
        laptop.controller.syncOnce() // sees the phone, re-publishes for it
        phone.controller.syncOnce()

        assertEquals(5, phone.store.eventsAfter(laptop.id, Long.MIN_VALUE).size)
        assertEquals(50L * 60_000, phone.store.bucketsForRange(laptop.id, 0, Long.MAX_VALUE).sumOf { it.activeMs })
    }

    @Test
    fun `a fresh install is not configured and never syncs`() = runBlocking<Unit> {
        val install = Install("fresh", Account())
        assertEquals(false, install.controller.isConfigured())
        assertEquals(null, install.controller.account())
        assertEquals(null, install.controller.syncOnce())
        assertIs<AccountUiState.Unconfigured>(install.controller.state.value)
    }

    @Test
    fun `disconnect forgets the account and its password`() = runBlocking<Unit> {
        val account = Account()
        val install = Install("leaving", account)
        install.controller.signIn("example.org", "me", account.password)
        assertTrue(install.controller.isConfigured())
        assertEquals("example.org", install.controller.account()?.host)
        assertEquals("me@example.org", install.controller.account()?.jid)

        install.controller.disconnect()

        assertEquals(false, install.controller.isConfigured())
        assertEquals(null, install.secrets.get("sync.password"))
        assertIs<AccountUiState.Unconfigured>(install.controller.state.value)
    }

    @Test
    fun `a wrong password is reported and nothing is saved`() = runBlocking {
        val install = Install("solo", Account())
        install.controller.signIn("example.org", "me", "wrong")
        val failed = assertIs<AccountUiState.Failed>(install.controller.state.value)
        assertTrue(failed.message.contains("Wrong username or password"))
        assertEquals(null, install.controller.account())
    }

    @Test
    fun `a plaintext password from an older build moves to the keyring`() {
        val install = Install("old", Account())
        val store = install.store
        fun put(k: String, v: String) = store.upsertSetting(Setting(k, v.toByteArray(), 0, "1970-01-01", install.id))
        put("sync.host", "example.org"); put("sync.port", "5222"); put("sync.jid", "me@example.org"); put("sync.password", "legacy")

        val controller = SyncController(store, install.id, MemKeychain(), install.secrets, "x", install.scope)

        assertTrue(controller.isConfigured())
        assertEquals("legacy", install.secrets.get("sync.password"))
        assertEquals("", store.setting("sync.password")!!.value.decodeToString())
    }
}
