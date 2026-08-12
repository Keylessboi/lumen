package dev.lumen.core.store

import dev.lumen.core.clock.UtcDay
import dev.lumen.core.model.AppDayRollup
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.MinuteBucket
import dev.lumen.core.model.Setting
import dev.lumen.core.model.SyncState
import dev.lumen.core.rollup.RollupEngine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JvmLumenStoreTest {

    private lateinit var store: JvmLumenStore

    @BeforeTest
    fun setup() {
        store = JvmLumenStore.inMemory()
    }

    @AfterTest
    fun teardown() {
        // in-memory driver, nothing to close
    }

    @Test
    fun `event round-trips through the frozen schema`() {
        val device = DeviceId("dev-1")
        val event = FocusEvent(
            seq = 1,
            deviceId = device,
            appKey = AppKey("kitty"),
            titleHash = null,
            startedAtMs = 1_700_000_000_000,
            durationMs = 60_000,
            category = "Terminal",
            syncState = SyncState.LOCAL,
        )

        store.insertEvent(event)
        val readBack = store.eventsAfter(device, afterSeq = 0)

        assertEquals(1, readBack.size)
        assertEquals(event.seq, readBack[0].seq)
        assertEquals("kitty", readBack[0].appKey.value)
        assertEquals("Terminal", readBack[0].category)
        assertEquals(SyncState.LOCAL, readBack[0].syncState)

        // Ack path
        store.markEventSynced(device, seq = 1, state = 1)
        assertEquals(SyncState.ACKED, store.eventsAfter(device, 0)[0].syncState)
    }

    @Test
    fun `event dedupes by seq`() {
        val device = DeviceId("dev-2")
        val event = FocusEvent(1, device, AppKey("chromium"), null, 1000, 5000, null, SyncState.LOCAL)
        store.insertEvent(event)
        store.insertEvent(event) // same seq, INSERT OR IGNORE
        assertEquals(1, store.eventsAfter(device, 0).size)
    }

    @Test
    fun `bucket and rollup round-trip`() {
        val device = DeviceId("dev-3")
        val bucket = MinuteBucket(
            deviceId = device,
            bucketTs = 1_700_000_010_000, // UTC minute boundary
            appKey = AppKey("chromium"),
            activeMs = 42_000,
        )
        store.insertBucket(bucket)

        val day = UtcDay.dayOf(bucket.bucketTs)
        val start = UtcDay.boundary(day)
        val readBuckets = store.bucketsForRange(device, start, start + 86_400_000)
        assertEquals(1, readBuckets.size)
        assertEquals(42_000, readBuckets[0].activeMs)

        // Rollup derived through the engine, then stored.
        val rollup = RollupEngine.rollup(device, day, listOf(bucket))
        store.upsertRollup(rollup)
        val readRollup = store.rollupsForDay(device, day)
        assertEquals(1, readRollup.size)
        assertEquals(42_000, readRollup[0].totalMs)
        assertEquals(day, readRollup[0].dayUtc)
    }

    @Test
    fun `settings round-trip with last-writer`() {
        val setting = Setting(
            key = "focus.limit.minutes",
            value = byteArrayOf(90),
            updatedAtMs = 1_700_000_000_000,
            updatedDayUtc = "2026-08-11",
            deviceId = DeviceId("dev-4"),
        )
        store.upsertSetting(setting)
        val readBack = store.setting("focus.limit.minutes")
        assertNotNull(readBack)
        assertEquals(90, readBack.value[0].toInt())
        assertEquals("dev-4", readBack.deviceId.value)
    }

    @Test
    fun `category registry and sticky override`() {
        val app = AppKey("org.gajim.Gajim")
        store.setManualOverride(app, "Communication")

        assertEquals("Communication", store.manualCategory(app))
        assertNull(store.registryCategory(app)) // registry empty in this store
    }

    @Test
    fun `watermark advances`() {
        val device = DeviceId("dev-5")
        assertEquals(0L, store.lastAckedSeq(device))
        store.setAckedSeq(device, 42)
        assertEquals(42L, store.lastAckedSeq(device))
    }

    @Test
    fun `pruning removes old events and buckets`() {
        val device = DeviceId("dev-6")
        val now = System.currentTimeMillis()
        store.insertEvent(FocusEvent(1, device, AppKey("a"), null, now - 40L * 86_400_000, 1000, null, SyncState.LOCAL))
        store.insertEvent(FocusEvent(2, device, AppKey("b"), null, now - 1_000, 1000, null, SyncState.LOCAL))
        store.insertBucket(MinuteBucket(device, now - 200L * 86_400_000, AppKey("a"), 1000))

        store.pruneEvents(beforeMs = now - 30L * 86_400_000)
        store.pruneBuckets(beforeMs = now - 180L * 86_400_000)

        assertEquals(1, store.eventsAfter(device, 0).size) // only the recent one
        assertEquals(0, store.bucketsForRange(device, now - 300L * 86_400_000, now).size)
    }
}
