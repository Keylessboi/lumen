package dev.lumen.core.sync

import dev.lumen.core.model.DeviceId
import dev.lumen.core.store.JvmLumenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceTrustTest {

    private val self = DeviceId("self")
    private fun key(b: Int) = DeviceTrust.encodeKey(ByteArray(32) { b.toByte() })
    private fun bundle(id: String, k: String, label: String = id) = DeviceBundle(DeviceId(id), k, label, 0)

    @Test
    fun `first key is pinned and survives a reload`() {
        val store = JvmLumenStore.inMemory()
        DeviceTrust(store, self).reconcile(listOf(bundle("phone", key(1))), nowMs = 10)

        val reloaded = DeviceTrust(store, self)
        assertEquals(listOf("phone"), reloaded.pinned().map { it.deviceId.value })
        assertEquals(32, reloaded.keyFor(DeviceId("phone"))?.size)
    }

    @Test
    fun `a changed key is refused and reported until the device is forgotten`() {
        val store = JvmLumenStore.inMemory()
        val trust = DeviceTrust(store, self)
        trust.reconcile(listOf(bundle("phone", key(1))), 10)

        val changed = trust.reconcile(listOf(bundle("phone", key(2))), 20)
        assertTrue(changed.warnings.single().startsWith("key change"))
        assertEquals(1.toByte(), trust.keyFor(DeviceId("phone"))!![0])

        trust.forget(DeviceId("phone"), 30)
        assertNull(trust.keyFor(DeviceId("phone")))
        trust.reconcile(listOf(bundle("phone", key(2))), 40)
        assertEquals(2.toByte(), trust.keyFor(DeviceId("phone"))!![0])
    }

    @Test
    fun `own bundle and malformed keys are not pinned`() {
        val store = JvmLumenStore.inMemory()
        val result = DeviceTrust(store, self).reconcile(
            listOf(bundle("self", key(1)), bundle("bad", "not-base64!!")),
            10,
        )
        assertTrue(result.trusted.isEmpty())
        assertEquals(1, result.warnings.size)
    }
}
