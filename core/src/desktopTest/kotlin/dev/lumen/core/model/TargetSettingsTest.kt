package dev.lumen.core.model

import dev.lumen.core.store.JvmLumenStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetSettingsTest {

    private lateinit var store: JvmLumenStore
    private val device = DeviceId("test-device")

    @BeforeTest
    fun setup() {
        store = JvmLumenStore.inMemory()
    }

    @Test
    fun `targetScreentime returns default when no setting exists`() {
        val result = store.targetScreentime(device)
        assertEquals(DEFAULT_TARGET_MS, result)
    }

    @Test
    fun `targetScreentime returns value after setTargetScreentime`() {
        val twoHours = 2 * 60 * 60 * 1000L
        store.setTargetScreentime(device, twoHours)

        assertEquals(twoHours, store.targetScreentime(device))
    }

    @Test
    fun `setTargetScreentime updates an existing setting`() {
        val oneHour = 60 * 60 * 1000L
        val threeHours = 3 * 60 * 60 * 1000L

        store.setTargetScreentime(device, oneHour)
        assertEquals(oneHour, store.targetScreentime(device))

        store.setTargetScreentime(device, threeHours)
        assertEquals(threeHours, store.targetScreentime(device))
    }

    @Test
    fun `setting is stored with correct key`() {
        store.setTargetScreentime(device, 1234L)
        val raw = store.setting(TARGET_SCREENTIME_KEY)
        assertEquals("1234", raw?.value?.decodeToString())
    }

    @Test
    fun `different devices share the same setting key`() {
        val device2 = DeviceId("other-device")
        store.setTargetScreentime(device, 5000L)

        // Setting is global by key, not per-device.
        assertEquals(5000L, store.targetScreentime(device2))
    }
}
