package dev.lumen.core.rollup

import dev.lumen.core.model.AppDayRollup
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.AppLocalDayRollup
import dev.lumen.core.model.ControlState
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.MinuteBucket
import dev.lumen.core.model.Setting
import dev.lumen.core.store.LumenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecapEngineTest {

    private val device = DeviceId("aaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val safari = AppKey("com.apple.Safari")
    private val terminal = AppKey("com.mitchellh.ghostty")
    private val slack = AppKey("com.tinyspeck.slackmacgap")

    private val hourMs = 3_600_000L
    private val dayMs = 86_400_000L

    // 2026-06-15T00:00:00Z (Monday)
    private val june15Ms = 1781481600000L
    // 2026-06-22T00:00:00Z (next Monday)
    private val june22Ms = 1782086400000L
    // 2026-06-01T00:00:00Z (start of June)
    private val june1Ms = 1780272000000L
    // 2026-07-01T00:00:00Z (start of July)
    private val july1Ms = 1782864000000L
    // 2026-01-01T00:00:00Z (start of year)
    private val jan1Ms = 1767225600000L
    // 2027-01-01T00:00:00Z (start of next year)
    private val jan1_2027Ms = 1798761600000L

    // ---- Fake store ----

    private class FakeStore : LumenStore {
        var buckets: List<MinuteBucket> = emptyList()
        var settings: Map<String, Setting> = emptyMap()

        override fun insertEvent(event: FocusEvent) = TODO("unused")
        override fun eventsAfter(deviceId: DeviceId, afterSeq: Long): List<FocusEvent> = TODO("unused")
        override fun markEventSynced(deviceId: DeviceId, seq: Long, state: Int) = TODO("unused")
        override fun insertBucket(bucket: MinuteBucket) = TODO("unused")
        override fun bucketsForRange(deviceId: DeviceId, dayStartMs: Long, dayEndMs: Long): List<MinuteBucket> =
            buckets.filter { it.bucketTs >= dayStartMs && it.bucketTs < dayEndMs }

        override fun clearDerived(deviceId: DeviceId) = TODO("unused")
        override fun upsertRollup(rollup: AppDayRollup) = TODO("unused")
        override fun rollupsForDay(deviceId: DeviceId, dayUtc: String): List<AppDayRollup> = TODO("unused")
        override fun upsertLocalRollup(rollup: AppLocalDayRollup) = TODO("unused")
        override fun localRollupsForDay(deviceId: DeviceId, dayLocal: String): List<AppLocalDayRollup> = TODO("unused")
        override fun clearLocalRollups(deviceId: DeviceId, dayLocal: String) = TODO("unused")
        override fun upsertSetting(setting: Setting) = TODO("unused")
        override fun setting(key: String): Setting? = settings[key]
        override fun registryCategory(appKey: AppKey): String? = TODO("unused")
        override fun manualCategory(appKey: AppKey): String? = TODO("unused")
        override fun setManualOverride(appKey: AppKey, category: String) = TODO("unused")
        override fun lastAckedSeq(deviceId: DeviceId): Long = TODO("unused")
        override fun setAckedSeq(deviceId: DeviceId, seq: Long) = TODO("unused")
        override fun controlState(controlKey: String): ControlState? = TODO("unused")
        override fun takeControl(controlKey: String, deviceId: DeviceId, deviceSeq: Long, startedAtMs: Long) = TODO("unused")
        override fun releaseControl(controlKey: String, deviceId: DeviceId, deviceSeq: Long) = TODO("unused")
        override fun pruneEvents(beforeMs: Long) = TODO("unused")
        override fun pruneBuckets(beforeMs: Long) = TODO("unused")
    }

    private fun bucket(appKey: AppKey, bucketTs: Long, activeMs: Long) =
        MinuteBucket(device, bucketTs, appKey, activeMs)

    private fun setting(key: String, value: Long) = Setting(
        key = key,
        value = value.toString().encodeToByteArray(),
        updatedAtMs = 0L,
        updatedDayUtc = "",
        deviceId = device,
    )

    // ---- weeklyRecap tests ----

    @Test
    fun `weeklyRecap sums all buckets in the 7-day window`() {
        val store = FakeStore().apply {
            buckets = listOf(
                bucket(safari, june15Ms, 2 * hourMs),
                bucket(terminal, june15Ms + dayMs, hourMs),
                bucket(safari, june15Ms + 3 * dayMs, hourMs),
            )
        }
        val recap = RecapEngine.weeklyRecap(store, device, june15Ms)

        assertEquals(4 * hourMs, recap.totalMs)
        assertEquals(june15Ms, recap.startMs)
        assertEquals(june22Ms, recap.endMs)
        assertEquals(2, recap.appBreakdown.size)
    }

    @Test
    fun `weeklyRecap returns empty breakdown when no data`() {
        val store = FakeStore().apply { buckets = emptyList() }
        val recap = RecapEngine.weeklyRecap(store, device, june15Ms)

        assertEquals(0L, recap.totalMs)
        assertTrue(recap.appBreakdown.isEmpty())
    }

    @Test
    fun `weeklyRecap excludes buckets outside the 7-day window`() {
        val store = FakeStore().apply {
            buckets = listOf(
                bucket(safari, june15Ms, hourMs),
                bucket(safari, june22Ms, hourMs), // outside week
                bucket(safari, june15Ms - dayMs, hourMs), // outside week
            )
        }
        val recap = RecapEngine.weeklyRecap(store, device, june15Ms)

        assertEquals(hourMs, recap.totalMs)
        assertEquals(1, recap.appBreakdown.size)
    }

    // ---- monthlyRecap tests ----

    @Test
    fun `monthlyRecap covers the full calendar month`() {
        val store = FakeStore().apply {
            buckets = listOf(
                bucket(safari, june1Ms, 2 * hourMs),
                bucket(terminal, june1Ms + 15 * dayMs, hourMs),
            )
        }
        val recap = RecapEngine.monthlyRecap(store, device, june1Ms)

        assertEquals(june1Ms, recap.startMs)
        assertEquals(july1Ms, recap.endMs)
        assertEquals(3 * hourMs, recap.totalMs)
        assertEquals(2, recap.appBreakdown.size)
    }

    @Test
    fun `monthlyRecap rounds down non-boundary start to month start`() {
        val midJune = june1Ms + 10 * dayMs
        val store = FakeStore().apply {
            buckets = listOf(bucket(safari, midJune, hourMs))
        }
        val recap = RecapEngine.monthlyRecap(store, device, midJune)

        assertEquals(june1Ms, recap.startMs)
        assertEquals(july1Ms, recap.endMs)
    }

    // ---- yearlyRecap tests ----

    @Test
    fun `yearlyRecap covers the full calendar year`() {
        val store = FakeStore().apply {
            buckets = listOf(
                bucket(safari, jan1Ms, 3 * hourMs),
                bucket(terminal, jan1Ms + 180 * dayMs, 2 * hourMs),
            )
        }
        val recap = RecapEngine.yearlyRecap(store, device, jan1Ms)

        assertEquals(jan1Ms, recap.startMs)
        assertEquals(jan1_2027Ms, recap.endMs)
        assertEquals(5 * hourMs, recap.totalMs)
        assertEquals(2, recap.appBreakdown.size)
    }

    // ---- target comparison ----

    @Test
    fun `targetMs is null when no setting exists`() {
        val store = FakeStore().apply {
            buckets = listOf(bucket(safari, june15Ms, hourMs))
        }
        val recap = RecapEngine.weeklyRecap(store, device, june15Ms)

        assertNull(recap.targetMs)
    }

    @Test
    fun `targetMs is read from store settings`() {
        val store = FakeStore().apply {
            buckets = listOf(bucket(safari, june15Ms, hourMs))
            settings = mapOf("target.screentime.ms" to setting("target.screentime.ms", 4 * hourMs))
        }
        val recap = RecapEngine.weeklyRecap(store, device, june15Ms)

        assertEquals(4 * hourMs, recap.targetMs)
    }

    // ---- multi-app breakdown percentages ----

    @Test
    fun `breakdown percentages sum to 100`() {
        val store = FakeStore().apply {
            buckets = listOf(
                bucket(safari, june15Ms, 3 * hourMs),
                bucket(terminal, june15Ms, hourMs),
                bucket(slack, june15Ms, hourMs),
            )
        }
        val recap = RecapEngine.weeklyRecap(store, device, june15Ms)

        assertEquals(5 * hourMs, recap.totalMs)
        val totalPct = recap.appBreakdown.sumOf { it.percentage }
        assertEquals(100.0, totalPct, 0.01)
    }

    @Test
    fun `breakdown is sorted by totalMs descending`() {
        val store = FakeStore().apply {
            buckets = listOf(
                bucket(safari, june15Ms, hourMs),
                bucket(terminal, june15Ms, 3 * hourMs),
                bucket(slack, june15Ms, 2 * hourMs),
            )
        }
        val recap = RecapEngine.weeklyRecap(store, device, june15Ms)

        assertEquals(terminal, recap.appBreakdown[0].appKey)
        assertEquals(slack, recap.appBreakdown[1].appKey)
        assertEquals(safari, recap.appBreakdown[2].appKey)
    }

    @Test
    fun `single app gets 100 percent`() {
        val store = FakeStore().apply {
            buckets = listOf(bucket(safari, june15Ms, 2 * hourMs))
        }
        val recap = RecapEngine.weeklyRecap(store, device, june15Ms)

        assertEquals(1, recap.appBreakdown.size)
        assertEquals(100.0, recap.appBreakdown[0].percentage, 0.01)
        assertEquals(safari, recap.appBreakdown[0].appKey)
    }

    // ---- fullRecap tests ----

    @Test
    fun `fullRecap returns all three periods`() {
        val store = FakeStore().apply {
            buckets = listOf(bucket(safari, june15Ms, hourMs))
        }
        // june15Ms = 2026-06-15T00:00:00Z (Monday)
        val summary = RecapEngine.fullRecap(store, device, june15Ms)

        assertNotNull(summary.week)
        assertNotNull(summary.month)
        assertNotNull(summary.year)
    }

    @Test
    fun `fullRecap week starts on Monday`() {
        // june15Ms is a Monday, so week start should be the same day
        val store = FakeStore().apply {
            buckets = listOf(bucket(safari, june15Ms, hourMs))
        }
        val summary = RecapEngine.fullRecap(store, device, june15Ms)

        assertNotNull(summary.week)
        assertEquals(june15Ms, summary.week!!.startMs)
        assertEquals(june22Ms, summary.week!!.endMs)
    }

    @Test
    fun `fullRecap month covers June`() {
        val store = FakeStore().apply {
            buckets = listOf(bucket(safari, june15Ms, hourMs))
        }
        val summary = RecapEngine.fullRecap(store, device, june15Ms)

        assertNotNull(summary.month)
        assertEquals(june1Ms, summary.month!!.startMs)
        assertEquals(july1Ms, summary.month!!.endMs)
    }

    // ---- edge cases ----

    @Test
    fun `multiple buckets for same app are aggregated`() {
        val store = FakeStore().apply {
            buckets = listOf(
                bucket(safari, june15Ms, hourMs),
                bucket(safari, june15Ms + hourMs, hourMs),
                bucket(safari, june15Ms + 2 * hourMs, hourMs),
            )
        }
        val recap = RecapEngine.weeklyRecap(store, device, june15Ms)

        assertEquals(1, recap.appBreakdown.size)
        assertEquals(3 * hourMs, recap.appBreakdown[0].totalMs)
    }

    @Test
    fun `buckets across multiple days within range are all counted`() {
        val store = FakeStore().apply {
            buckets = listOf(
                bucket(safari, june15Ms, hourMs),
                bucket(safari, june15Ms + dayMs, hourMs),
                bucket(safari, june15Ms + 6 * dayMs, hourMs),
            )
        }
        val recap = RecapEngine.weeklyRecap(store, device, june15Ms)

        assertEquals(3 * hourMs, recap.totalMs)
        assertEquals(1, recap.appBreakdown.size)
    }
}
