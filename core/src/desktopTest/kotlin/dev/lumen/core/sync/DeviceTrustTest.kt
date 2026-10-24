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
    fun `a new device is pending, not trusted, until approved`() {
        val store = JvmLumenStore.inMemory()
        val first = DeviceTrust(store, self).reconcile(listOf(bundle("phone", key(1))), nowMs = 10)
        assertTrue(first.trusted.isEmpty(), "a device the provider lists must not be trusted on sight")
        assertEquals(listOf("phone"), first.pending.map { it.deviceId.value })
        assertNull(DeviceTrust(store, self).keyFor(DeviceId("phone")))

        assertTrue(DeviceTrust(store, self).approve(DeviceId("phone"), 20))

        val reloaded = DeviceTrust(store, self)
        assertEquals(32, reloaded.keyFor(DeviceId("phone"))?.size)
        assertEquals(listOf("phone"), reloaded.reconcile(listOf(bundle("phone", key(1))), 30).trusted.map { it.deviceId.value })
    }

    @Test
    fun `a changed key is refused and reported until the device is forgotten`() {
        val store = JvmLumenStore.inMemory()
        val trust = DeviceTrust(store, self)
        trust.reconcile(listOf(bundle("phone", key(1))), 10)
        trust.approve(DeviceId("phone"), 11)

        val changed = trust.reconcile(listOf(bundle("phone", key(2))), 20)
        assertTrue(changed.warnings.single().startsWith("key change"))
        assertEquals(1.toByte(), trust.keyFor(DeviceId("phone"))!![0])

        trust.forget(DeviceId("phone"), 30)
        assertNull(trust.keyFor(DeviceId("phone")))
        trust.reconcile(listOf(bundle("phone", key(2))), 40)
        assertNull(trust.keyFor(DeviceId("phone")), "the re-pinned key is pending again")
        trust.approve(DeviceId("phone"), 41)
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
        assertTrue(result.pending.isEmpty())
        assertEquals(1, result.warnings.size)
    }
}
