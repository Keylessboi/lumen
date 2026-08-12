package dev.lumen.core.sync

import dev.lumen.core.model.AppDayRollup
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.MinuteBucket
import dev.lumen.core.model.RecordKind
import dev.lumen.core.model.Setting
import dev.lumen.core.model.SyncRecord
import dev.lumen.core.model.SyncState
import dev.lumen.core.store.LumenStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEngineTest {

    private val self = DeviceId("self")
    private val other = DeviceId("other")

    private fun event(device: DeviceId, seq: Long) =
        FocusEvent(
            seq = seq,
            deviceId = device,
            appKey = AppKey("app-$seq"),
            startedAtMs = seq * 1000,
            durationMs = 1000,
            category = null,
            syncState = SyncState.LOCAL,
        )

    private fun FocusEvent.toRecord() =
        SyncRecord(
            deviceId = deviceId,
            seq = seq,
            kind = RecordKind.EVENT,
            payload = syncJson.encodeToString(FocusEvent.serializer(), this).toByteArray(),
        )

    private companion object {
        val syncJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }

    // --- pull: dedupe -----------------------------------------------------

    @Test
    fun `pull dedupes by deviceId and seq`() {
        val store = FakeStore()
        val transport = FakeTransport(
            pullResult = listOf(
                event(other, 1).toRecord(),
                event(other, 1).toRecord(), // replay within the pass
                event(other, 2).toRecord(),
            ),
        )
        val engine = SyncEngine(store, transport, self)

        val applied = runBlocking { engine.pull() }

        assertEquals(2, applied.size)
        assertEquals(2, store.events.size)
    }

    @Test
    fun `pull detects gaps in a device seq`() {
        val store = FakeStore()
        val transport = FakeTransport(
            pullResult = listOf(
                event(other, 1).toRecord(),
                event(other, 3).toRecord(), // 2 missing
            ),
        )
        val engine = SyncEngine(store, transport, self)

        runBlocking { engine.pull() }

        assertTrue(engine.lastWarnings().any { it.contains("gap") })
    }

    @Test
    fun `pull warns on replays below the watermark`() {
        val store = FakeStore()
        // Seq 1 was already applied in a prior pass (watermark advanced).
        store.events += event(other, 1)
        store.setAckedSeq(other, 1L)
        val transport = FakeTransport(
            pullResult = listOf(event(other, 1).toRecord()), // delivered again
        )
        val engine = SyncEngine(store, transport, self)

        runBlocking { engine.pull() }

        assertTrue(engine.lastWarnings().any { it.contains("replay") })
        // The replayed record is not applied twice.
        assertEquals(1, store.events.size)
    }

    @Test
    fun `integrity failure stops that record`() {
        val store = FakeStore()
        val transport = FakeTransport(
            pullResult = listOf(event(other, 1).toRecord()),
        )
        val engine = SyncEngine(store, transport, self, integrity = object : SyncIntegrity {
            override fun verify(record: SyncRecord, prevSeq: Long): Boolean = false
        })

        runBlocking { engine.pull() }

        assertTrue(engine.lastWarnings().any { it.contains("integrity") })
        assertEquals(0, store.events.size)
    }

    // --- push -------------------------------------------------------------

    @Test
    fun `push publishes the outbox and advances the watermark`() {
        val store = FakeStore()
        store.events += event(self, 1)
        store.events += event(self, 2)
        val transport = FakeTransport()
        val engine = SyncEngine(store, transport, self)

        val published = runBlocking { engine.push() }

        assertEquals(2, published)
        assertEquals(2L, store.lastAckedSeq(self))
        assertEquals(2, transport.published.size)
    }

    @Test
    fun `push with empty outbox does nothing`() {
        val store = FakeStore()
        val transport = FakeTransport()
        val engine = SyncEngine(store, transport, self)

        assertEquals(0, runBlocking { engine.push() })
        assertEquals(0, transport.published.size)
    }

    // --- full pass --------------------------------------------------------

    @Test
    fun `sync pulls remote then publishes local`() {
        val store = FakeStore()
        store.events += event(self, 1)
        val transport = FakeTransport(
            pullResult = listOf(event(other, 1).toRecord()),
        )
        val engine = SyncEngine(store, transport, self)

        val report = runBlocking { engine.sync() }

        assertEquals(1, report.pulled)
        assertEquals(1, report.published)
        assertEquals(1, store.events.count { it.deviceId == other })
        assertTrue(report.integrityWarnings.isEmpty())
    }

    // --- fakes ------------------------------------------------------------

    private class FakeTransport(
        private val pullResult: List<SyncRecord> = emptyList(),
    ) : SyncTransport {
        val published = mutableListOf<SyncRecord>()
        override val isConfigured: Boolean get() = true

        override suspend fun publish(records: List<SyncRecord>): PublishResult {
            published += records
            val acked = records.groupBy { it.deviceId.value }
                .mapValues { (_, rs) -> rs.maxOf { it.seq } }
            return PublishResult(acked = acked)
        }

        override suspend fun pull(after: Map<String, Long>): List<SyncRecord> =
            pullResult.filter { r -> (after[r.deviceId.value] ?: -1L) < r.seq }

        override suspend fun close() {}
    }

    private class FakeStore : LumenStore {
        val events = mutableListOf<FocusEvent>()
        // Per-device watermarks, mirroring the sync_watermark table
        // (device_id PRIMARY KEY). A single shared counter would let one
        // device's pull advance another's watermark — a real bug the tests
        // exist to catch, not to fake away.
        private val watermarks = mutableMapOf<String, Long>()

        // INSERT OR IGNORE on (device_id, seq).
        override fun insertEvent(event: FocusEvent) {
            val dup = events.any { it.deviceId == event.deviceId && it.seq == event.seq }
            if (!dup) events += event
        }

        override fun eventsAfter(deviceId: DeviceId, afterSeq: Long): List<FocusEvent> =
            events.filter { it.deviceId == deviceId && it.seq > afterSeq }

        override fun markEventSynced(deviceId: DeviceId, seq: Long, state: Int) {}

        override fun insertBucket(bucket: MinuteBucket) {}
        override fun bucketsForRange(deviceId: DeviceId, dayStartMs: Long, dayEndMs: Long) = emptyList<MinuteBucket>()

        override fun upsertRollup(rollup: AppDayRollup) {}
        override fun rollupsForDay(deviceId: DeviceId, dayUtc: String) = emptyList<AppDayRollup>()

        override fun upsertLocalRollup(rollup: dev.lumen.core.model.AppLocalDayRollup) {}
        override fun localRollupsForDay(deviceId: DeviceId, dayLocal: String) = emptyList<dev.lumen.core.model.AppLocalDayRollup>()
        override fun clearLocalRollups(deviceId: DeviceId, dayLocal: String) {}

        override fun upsertSetting(setting: Setting) {}
        override fun setting(key: String): Setting? = null

        override fun registryCategory(appKey: AppKey): String? = null
        override fun manualCategory(appKey: AppKey): String? = null
        override fun setManualOverride(appKey: AppKey, category: String) {}

        override fun lastAckedSeq(deviceId: DeviceId): Long = watermarks[deviceId.value] ?: -1L
        override fun setAckedSeq(deviceId: DeviceId, seq: Long) { watermarks[deviceId.value] = seq }

        override fun controlState(controlKey: String): dev.lumen.core.model.ControlState? = null
        override fun takeControl(controlKey: String, deviceId: DeviceId, deviceSeq: Long, startedAtMs: Long) {}
        override fun releaseControl(controlKey: String, deviceId: DeviceId, deviceSeq: Long) {}

        override fun pruneEvents(beforeMs: Long) {}
        override fun pruneBuckets(beforeMs: Long) {}
    }
}