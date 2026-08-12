package dev.lumen.core.rollup

import dev.lumen.core.model.AppDayRollup
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.MinuteBucket
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for the rollup engine (Agent B, M1 gate).
 *
 * The engine is pure, so these are the sharpest tests in the suite: every
 * rule in `docs/data-model.md`'s event -> 1-min bucket -> app-day rollup
 * chain is checkable without a database.
 *
 * Two tests are `@Ignore`d pending issue #15 (`rollup()` keeps only the
 * largest app and invents an empty-AppKey row for empty input). They are
 * written against the corrected contract and un-ignored when the fix lands;
 * see the issue for why this is a pre-M1-freeze blocker.
 */
class RollupEngineTest {

    private val device = DeviceId("11111111-2222-3333-4444-555555555555")
    private val safari = AppKey("com.apple.Safari")
    private val terminal = AppKey("com.mitchellh.ghostty")
    private val slack = AppKey("com.tinyspeck.slackmacgap")

    private val minute = 60_000L
    private val alignedStart = 1781518620000L      // 2026-06-15T10:17:00Z, exact minute
    private val unalignedStart = 1781518643456L    // 2026-06-15T10:17:23.456Z
    private val day = "2026-06-15"

    private fun event(
        seq: Long = 1L,
        appKey: AppKey = safari,
        startedAtMs: Long = alignedStart,
        durationMs: Long,
    ) = FocusEvent(
        seq = seq,
        deviceId = device,
        appKey = appKey,
        startedAtMs = startedAtMs,
        durationMs = durationMs,
    )

    // ---- bucket(): slicing raw events into 1-minute buckets ----

    @Test
    fun `a zero-duration event produces no buckets`() {
        assertEquals(emptyList(), RollupEngine.bucket(event(durationMs = 0)))
    }

    @Test
    fun `a negative-duration event produces no buckets`() {
        // A backwards clock step can yield end < start. The engine must drop
        // it rather than emit negative activeMs, which would silently
        // subtract time from a day total.
        assertEquals(emptyList(), RollupEngine.bucket(event(durationMs = -5_000)))
    }

    @Test
    fun `an event inside a single minute produces one bucket`() {
        val buckets = RollupEngine.bucket(event(startedAtMs = alignedStart, durationMs = 30_000))
        assertEquals(1, buckets.size)
        assertEquals(alignedStart, buckets[0].bucketTs)
        assertEquals(30_000L, buckets[0].activeMs)
    }

    @Test
    fun `bucket timestamps are always floored to the minute`() {
        val buckets = RollupEngine.bucket(event(startedAtMs = unalignedStart, durationMs = 10_000))
        assertEquals(1, buckets.size)
        assertEquals(alignedStart, buckets[0].bucketTs, "bucketTs must be the minute boundary")
        buckets.forEach { assertEquals(0L, it.bucketTs % minute) }
    }

    @Test
    fun `an event crossing a minute boundary splits at the boundary`() {
        // starts 23.456s into the minute, runs 60s -> 36.544s in this minute,
        // 23.456s in the next.
        val buckets = RollupEngine.bucket(event(startedAtMs = unalignedStart, durationMs = minute))
        assertEquals(2, buckets.size)
        assertEquals(alignedStart, buckets[0].bucketTs)
        assertEquals(alignedStart + minute, buckets[1].bucketTs)
        assertEquals(36_544L, buckets[0].activeMs)
        assertEquals(23_456L, buckets[1].activeMs)
    }

    @Test
    fun `an aligned multi-minute event produces whole minutes`() {
        val buckets = RollupEngine.bucket(event(startedAtMs = alignedStart, durationMs = 3 * minute))
        assertEquals(3, buckets.size)
        buckets.forEach { assertEquals(minute, it.activeMs) }
        assertEquals(
            listOf(alignedStart, alignedStart + minute, alignedStart + 2 * minute),
            buckets.map { it.bucketTs },
        )
    }

    @Test
    fun `bucketing conserves duration exactly`() {
        // The invariant that makes the three-layer model trustworthy: slicing
        // must never create or destroy time.
        val cases = listOf(
            1L, 999L, 30_000L, minute, minute + 1, 3 * minute, 7 * minute + 12_345L,
        )
        for (duration in cases) {
            for (start in listOf(alignedStart, unalignedStart, alignedStart + 59_999L)) {
                val buckets = RollupEngine.bucket(event(startedAtMs = start, durationMs = duration))
                assertEquals(
                    duration,
                    buckets.sumOf { it.activeMs },
                    "sum of slices must equal duration (start=$start, duration=$duration)",
                )
                assertTrue(
                    buckets.all { it.activeMs > 0 },
                    "no empty slices (start=$start, duration=$duration)",
                )
            }
        }
    }

    @Test
    fun `bucketing is contiguous with no gaps or overlaps`() {
        val buckets = RollupEngine.bucket(event(startedAtMs = unalignedStart, durationMs = 5 * minute))
        val timestamps = buckets.map { it.bucketTs }
        assertEquals(timestamps.sorted(), timestamps, "buckets must be emitted in time order")
        assertEquals(timestamps.distinct(), timestamps, "a minute must appear at most once")
        timestamps.zipWithNext { a, b -> assertEquals(minute, b - a, "buckets must be adjacent") }
    }

    @Test
    fun `bucketing preserves device and app identity`() {
        val buckets = RollupEngine.bucket(
            event(appKey = terminal, startedAtMs = unalignedStart, durationMs = 2 * minute),
        )
        assertTrue(buckets.isNotEmpty())
        buckets.forEach {
            assertEquals(device, it.deviceId)
            assertEquals(terminal, it.appKey)
        }
    }

    @Test
    fun `bucket is pure — the same event always yields the same slices`() {
        val e = event(startedAtMs = unalignedStart, durationMs = 2 * minute + 7)
        assertEquals(RollupEngine.bucket(e), RollupEngine.bucket(e))
    }

    // ---- dayTotal(): summing a day ----

    @Test
    fun `an empty day totals zero`() {
        assertEquals(0L, RollupEngine.dayTotal(emptyList()))
    }

    @Test
    fun `dayTotal sums every rollup it is given`() {
        val rollups = listOf(
            AppDayRollup(device, day, safari, 2 * 3_600_000L),
            AppDayRollup(device, day, terminal, 3_600_000L),
            AppDayRollup(device, day, slack, 1_800_000L),
        )
        assertEquals(3_600_000L * 3 + 1_800_000L, RollupEngine.dayTotal(rollups))
    }

    // ---- rollup(): buckets -> per-app-day totals ----

    @Test
    fun `a single-app day rolls up to that app's total`() {
        val buckets = listOf(
            MinuteBucket(device, alignedStart, safari, minute),
            MinuteBucket(device, alignedStart + minute, safari, minute),
            MinuteBucket(device, alignedStart + 2 * minute, safari, 30_000L),
        )
        val produced = RollupEngine.rollup(device, day, buckets)
        assertEquals(safari, produced.appKey)
        assertEquals(2 * minute + 30_000L, produced.totalMs)
        assertEquals(device, produced.deviceId)
        assertEquals(day, produced.dayUtc)
    }

    @Test
    fun `rollup never bakes a category into the totals`() {
        // Category is a separate lookup (registry + sticky override). Baking a
        // snapshot into the rollup would make a re-categorisation silently
        // wrong for all history.
        val buckets = listOf(MinuteBucket(device, alignedStart, safari, minute))
        assertEquals(null, RollupEngine.rollup(device, day, buckets).category)
    }

    @Test
    @Ignore("Blocked on #15 — rollup() keeps only the largest app, so a day's total is the max, not the sum.")
    fun `a day with three apps totals all three`() {
        val buckets = listOf(
            MinuteBucket(device, alignedStart, safari, 2 * 3_600_000L),
            MinuteBucket(device, alignedStart + minute, terminal, 3_600_000L),
            MinuteBucket(device, alignedStart + 2 * minute, slack, 1_800_000L),
        )
        val produced = RollupEngine.rollup(device, day, buckets)
        assertEquals(
            3_600_000L * 3 + 1_800_000L,
            RollupEngine.dayTotal(listOf(produced)),
            "Safari 2h + Terminal 1h + Slack 30m must total 3h30m, not Safari's 2h",
        )
    }

    @Test
    @Ignore("Blocked on #15 — an empty day fabricates a rollup row keyed on AppKey(\"\").")
    fun `an empty day produces no rollup row`() {
        val produced = RollupEngine.rollup(device, day, emptyList())
        assertTrue(
            produced.appKey.value.isNotEmpty(),
            "an empty day must not fabricate a rollup keyed on a blank AppKey — that row " +
                "would be persisted and joined against like a real app",
        )
    }
}
