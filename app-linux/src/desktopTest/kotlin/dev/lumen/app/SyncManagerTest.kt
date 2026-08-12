package dev.lumen.app

import dev.lumen.core.model.DeviceId
import dev.lumen.core.store.JvmLumenStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SyncManager]'s account lifecycle against the real
 * [JvmLumenStore] — the composition layer's data path. The live network
 * path (register/publish/pull) is covered by the SyncE2EHarness; these
 * pin the settings persistence without a provider.
 */
class SyncManagerTest {

    private fun store(): JvmLumenStore {
        val f = File.createTempFile("lumen-syncmgr", ".db").also { it.delete() }
        return JvmLumenStore.open(f)
    }

    @Test
    fun `unconfigured manager reports false`() {
        val mgr = SyncManager(store(), DeviceId("dev"))
        assertFalse(mgr.isConfigured())
        assertNull(mgr.account())
    }

    @Test
    fun `saveAccount then account round-trips`() {
        val mgr = SyncManager(store(), DeviceId("dev"))
        mgr.saveAccount(AccountConfig("jabber.fr", 5222, "user@jabber.fr", "secret"))

        assertTrue(mgr.isConfigured())
        val account = mgr.account()
        assertEquals("jabber.fr", account?.host)
        assertEquals(5222, account?.port)
        assertEquals("user@jabber.fr", account?.jid)
        assertEquals("secret", account?.password)
    }

    @Test
    fun `saveAccount twice overwrites`() {
        val mgr = SyncManager(store(), DeviceId("dev"))
        mgr.saveAccount(AccountConfig("a.example", 5222, "a@a", "one"))
        mgr.saveAccount(AccountConfig("b.example", 5223, "b@b", "two"))

        val account = mgr.account()
        assertEquals("b.example", account?.host)
        assertEquals(5223, account?.port)
        assertEquals("two", account?.password)
    }

    @Test
    fun `clearAccount removes the config`() {
        val mgr = SyncManager(store(), DeviceId("dev"))
        mgr.saveAccount(AccountConfig("jabber.fr", 5222, "u@j", "s"))
        mgr.clearAccount()

        assertFalse(mgr.isConfigured())
        assertNull(mgr.account())
    }

    @Test
    fun `account with blank jid is not configured`() {
        val mgr = SyncManager(store(), DeviceId("dev"))
        mgr.saveAccount(AccountConfig("jabber.fr", 5222, "", "s"))
        assertFalse(mgr.isConfigured())
        assertNull(mgr.account())
    }
}