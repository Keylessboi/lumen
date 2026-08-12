package dev.lumen.core.store

import dev.lumen.core.model.AppDayRollup
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.MinuteBucket
import dev.lumen.core.model.Setting
import dev.lumen.core.model.SyncState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract kit for every [LumenStore] driver (Agent B, M1 gate).
 *
 * `JvmLumenStore` currently has its own round-trip tests, written by the
 * author of the seam. This kit is the same seam described by its *consumer*,
 * and it is platform-neutral: the Android driver at M4 and any future macOS
 * driver must pass exactly these, so "works on desktop" stops being the
 * definition of correct.
 *
 * Wiring it up is a one-liner in the owning zone — for Agent A's driver, in
 * `core/src/desktopTest`:
 *
 * ```kotlin
 * class JvmLumenStoreContractTest : LumenStoreContract() {
 *     override fun store(): LumenStore = JvmLumenStore.inMemory()
 * }
 * ```
 *
 * Deliberately scoped to behaviour the seam actually promises in its own
 * KDoc. Two things it does *not* assert, because the interface does not say
 * who owns them — flagged for the freeze review rather than guessed at:
 *
 *  - **Settings LWW.** `docs/data-model.md` locks LWW + UTC-day for settings,
 *    but `upsertSetting` reads as an unconditional write. If reconciliation
 *    is the caller's job this kit is right to stay quiet; if it is the
 *    store's, there should be a test here and there isn't.
 *  - **Event immutability.** Events are described as immutable, but
 *    `insertEvent` on an existing (deviceId, seq) is `INSERT OR IGNORE` at
 *    the SQL layer. The kit pins the observable outcome (first write wins)
 *    without claiming the interface guarantees it.
 */
abstract class LumenStoreContract {

    /** A fresh, empty store. Called once per test. */
    protected abstract fun store(): LumenStore

    protected val deviceA = DeviceId("11111111-1111-1111-1111-111111111111")
    protected val deviceB = DeviceId("22222222-2222-2222-2222-222222222222")

    private val safari = AppKey("com.apple.Safari")
    private val terminal = AppKey("com.mitchellh.ghostty")
    private val minute = 60_000L
    private val t0 = 1781518620000L // 2026-06-15T10:17:00Z
    private val day = "2026-06-15"

    private fun event(seq: Long, appKey: AppKey = safari, startedAtMs: Long = t0, durationMs: Long = minute) =
        FocusEvent(seq = seq, deviceId = deviceA, appKey = appKey, startedAtMs = startedAtMs, durationMs = durationMs)

    // ---- events ----

    @Test
    fun `an event written is an event read back`() {
        val store = store()
        store.insertEvent(event(seq = 1))

        val read = store.eventsAfter(deviceA, afterSeq = 0)
        assertEquals(1, read.size)
        assertEquals(1L, read[0].seq)
        assertEquals(safari, read[0].appKey)
        assertEquals(deviceA, read[0].deviceId)
        assertEquals(minute, read[0].durationMs)
        assertEquals(SyncState.LOCAL, read[0].syncState)
    }

    @Test
    fun `eventsAfter is exclusive of the given seq`() {
        val store = store()
        (1L..3L).forEach { store.insertEvent(event(seq = it)) }

        assertEquals(listOf(1L, 2L, 3L), store.eventsAfter(deviceA, 0).map { it.seq })
        assertEquals(listOf(2L, 3L), store.eventsAfter(deviceA, 1).map { it.seq })
        assertEquals(emptyList(), store.eventsAfter(deviceA, 3).map { it.seq })
    }

    @Test
    fun `eventsAfter returns seq order, which is the sync outbox order`() {
        val store = store()
        listOf(3L, 1L, 2L).forEach { store.insertEvent(event(seq = it)) }
        assertEquals(listOf(1L, 2L, 3L), store.eventsAfter(deviceA, 0).map { it.seq })
    }

    @Test
    fun `re-inserting the same seq does not duplicate the event`() {
        // Append-merge dedupes by (deviceId, seq). A replayed sync batch must
        // not double-count a day.
        val store = store()
        store.insertEvent(event(seq = 1, durationMs = minute))
        store.insertEvent(event(seq = 1, durationMs = 999 * minute))

        val read = store.eventsAfter(deviceA, 0)
        assertEquals(1, read.size, "(deviceId, seq) is unique")
        assertEquals(minute, read[0].durationMs, "first write wins; a replay must not rewrite history")
    }

    @Test
    fun `events are scoped to their device`() {
        val store = store()
        store.insertEvent(event(seq = 1))
        store.insertEvent(event(seq = 1).copy(deviceId = deviceB, appKey = terminal))

        assertEquals(listOf(safari), store.eventsAfter(deviceA, 0).map { it.appKey })
        assertEquals(listOf(terminal), store.eventsAfter(deviceB, 0).map { it.appKey })
    }

    @Test
    fun `marking an event synced changes only that event`() {
        val store = store()
        store.insertEvent(event(seq = 1))
        store.insertEvent(event(seq = 2))

        store.markEventSynced(deviceA, seq = 1, state = SyncState.ACKED.ordinal)

        val byS = store.eventsAfter(deviceA, 0).associateBy { it.seq }
        assertEquals(SyncState.ACKED, byS.getValue(1L).syncState)
        assertEquals(SyncState.LOCAL, byS.getValue(2L).syncState)
    }

    // ---- buckets ----

    @Test
    fun `bucketsForRange includes the start and excludes the end`() {
        val store = store()
        listOf(t0 - minute, t0, t0 + minute, t0 + 2 * minute).forEach {
            store.insertBucket(MinuteBucket(deviceA, it, safari, minute))
        }

        val inRange = store.bucketsForRange(deviceA, dayStartMs = t0, dayEndMs = t0 + 2 * minute)
        assertEquals(
            listOf(t0, t0 + minute).sorted(),
            inRange.map { it.bucketTs }.sorted(),
            "[start, end) — the start minute is in, the end minute is out",
        )
    }

    @Test
    fun `a bucket is replaced, not duplicated, on the same key`() {
        // PK is (device_id, bucket_ts, app_key). Re-deriving a minute after a
        // backfill must correct it, not add to it.
        val store = store()
        store.insertBucket(MinuteBucket(deviceA, t0, safari, 10_000L))
        store.insertBucket(MinuteBucket(deviceA, t0, safari, 45_000L))

        val read = store.bucketsForRange(deviceA, t0, t0 + minute)
        assertEquals(1, read.size)
        assertEquals(45_000L, read[0].activeMs)
    }

    @Test
    fun `two apps in the same minute are separate buckets`() {
        val store = store()
        store.insertBucket(MinuteBucket(deviceA, t0, safari, 20_000L))
        store.insertBucket(MinuteBucket(deviceA, t0, terminal, 40_000L))

        val read = store.bucketsForRange(deviceA, t0, t0 + minute)
        assertEquals(2, read.size)
        assertEquals(60_000L, read.sumOf { it.activeMs })
    }

    // ---- rollups ----

    @Test
    fun `a rollup is upserted by (device, day, app)`() {
        val store = store()
        store.upsertRollup(AppDayRollup(deviceA, day, safari, 3_600_000L))
        store.upsertRollup(AppDayRollup(deviceA, day, safari, 7_200_000L))
        store.upsertRollup(AppDayRollup(deviceA, day, terminal, 1_800_000L))

        val read = store.rollupsForDay(deviceA, day).associateBy { it.appKey }
        assertEquals(2, read.size)
        assertEquals(7_200_000L, read.getValue(safari).totalMs, "same key upserts, never accumulates")
        assertEquals(1_800_000L, read.getValue(terminal).totalMs)
    }

    @Test
    fun `rollups are scoped to their day`() {
        val store = store()
        store.upsertRollup(AppDayRollup(deviceA, "2026-06-15", safari, 1L))
        store.upsertRollup(AppDayRollup(deviceA, "2026-06-16", safari, 2L))

        assertEquals(listOf(1L), store.rollupsForDay(deviceA, "2026-06-15").map { it.totalMs })
        assertEquals(listOf(2L), store.rollupsForDay(deviceA, "2026-06-16").map { it.totalMs })
        assertEquals(emptyList(), store.rollupsForDay(deviceA, "2026-06-17"))
    }

    @Test
    fun `rollups from different devices coexist and are never merged away`() {
        // The locked rule: phone time + desktop time always sum. Two devices
        // reporting the same app on the same day are two rows, not a conflict.
        val store = store()
        store.upsertRollup(AppDayRollup(deviceA, day, safari, 3_600_000L))
        store.upsertRollup(AppDayRollup(deviceB, day, safari, 1_800_000L))

        assertEquals(1, store.rollupsForDay(deviceA, day).size)
        assertEquals(1, store.rollupsForDay(deviceB, day).size)
    }

    // ---- settings ----

    @Test
    fun `a setting written is a setting read back`() {
        val store = store()
        store.upsertSetting(Setting("nudge.break", byteArrayOf(1), 1_000L, day, deviceA))

        val read = assertNotNull(store.setting("nudge.break"))
        assertTrue(byteArrayOf(1).contentEquals(read.value))
        assertEquals(day, read.updatedDayUtc)
        assertEquals(deviceA, read.deviceId, "the last writer is recorded")
    }

    @Test
    fun `an unset setting is null, not a default`() {
        // Distinguishing "never set" from "set to the default" is what makes
        // first-run behaviour and sync reconciliation decidable.
        assertNull(store().setting("nudge.break"))
    }

    // ---- categories ----

    @Test
    fun `an unknown app has no category, which the UI shows as Uncategorized`() {
        // Locked: "never a confident wrong guess."
        val store = store()
        assertNull(store.registryCategory(AppKey("com.unknown.thing")))
        assertNull(store.manualCategory(AppKey("com.unknown.thing")))
    }

    @Test
    fun `a manual override is readable and sticky across writes`() {
        val store = store()
        store.setManualOverride(safari, "Work")
        assertEquals("Work", store.manualCategory(safari))

        store.setManualOverride(safari, "Browsing")
        assertEquals("Browsing", store.manualCategory(safari), "the user's latest choice wins")
    }

    @Test
    fun `a manual override does not write into the registry`() {
        // Two separate tables by design: the registry is the shipped dataset,
        // overrides are the user's. Mixing them makes a registry update able
        // to silently undo a user's choice.
        val store = store()
        store.setManualOverride(safari, "Work")
        assertNull(store.registryCategory(safari), "overrides must not leak into the registry")
    }

    // ---- sync watermark ----

    @Test
    fun `an unknown device's watermark starts at zero`() {
        assertEquals(0L, store().lastAckedSeq(deviceA))
    }

    @Test
    fun `the watermark advances and is per-device`() {
        val store = store()
        store.setAckedSeq(deviceA, 10L)
        store.setAckedSeq(deviceB, 3L)

        assertEquals(10L, store.lastAckedSeq(deviceA))
        assertEquals(3L, store.lastAckedSeq(deviceB))

        store.setAckedSeq(deviceA, 11L)
        assertEquals(11L, store.lastAckedSeq(deviceA))
    }

    // ---- control-state takeover ----

    @Test
    fun `no controller means no control state`() {
        assertNull(store().controlState("focus_session"))
    }

    @Test
    fun `the latest declarer takes over`() {
        val store = store()
        store.takeControl("focus_session", deviceA, deviceSeq = 1L, startedAtMs = t0)
        store.takeControl("focus_session", deviceB, deviceSeq = 2L, startedAtMs = t0 + 1_000)

        val held = assertNotNull(store.controlState("focus_session"))
        assertEquals(deviceB, held.deviceId)
        assertEquals(2L, held.deviceSeq)
    }

    @Test
    fun `an older declaration cannot take over a newer one`() {
        val store = store()
        store.takeControl("focus_session", deviceB, deviceSeq = 5L, startedAtMs = t0)
        store.takeControl("focus_session", deviceA, deviceSeq = 2L, startedAtMs = t0 + 60_000)

        val held = assertNotNull(store.controlState("focus_session"))
        assertEquals(deviceB, held.deviceId, "seq is the authority, not arrival order")
        assertEquals(5L, held.deviceSeq)
    }

    @Test
    fun `a later wall clock never beats a higher seq`() {
        // The whole reason the tiebreak is (deviceId, seq): two devices with
        // skewed clocks must not flip-flop ownership.
        val store = store()
        store.takeControl("focus_session", deviceA, deviceSeq = 9L, startedAtMs = t0)
        store.takeControl("focus_session", deviceB, deviceSeq = 4L, startedAtMs = t0 + 86_400_000L)

        assertEquals(deviceA, assertNotNull(store.controlState("focus_session")).deviceId)
    }

    @Test
    fun `controls are independent of each other`() {
        val store = store()
        store.takeControl("focus_session", deviceA, deviceSeq = 1L, startedAtMs = t0)
        store.takeControl("nudge", deviceB, deviceSeq = 1L, startedAtMs = t0)

        assertEquals(deviceA, assertNotNull(store.controlState("focus_session")).deviceId)
        assertEquals(deviceB, assertNotNull(store.controlState("nudge")).deviceId)
    }

    @Test
    fun `release is a state, not a deletion`() {
        // Delete is a tombstone race; release is a state that can be synced.
        val store = store()
        store.takeControl("focus_session", deviceA, deviceSeq = 1L, startedAtMs = t0)
        store.releaseControl("focus_session", deviceA, deviceSeq = 2L)

        val held = assertNotNull(store.controlState("focus_session"), "the row must survive a release")
        assertTrue(held.released, "released must be observable, not inferred from absence")
    }

    @Test
    fun `a released control can be taken again`() {
        val store = store()
        store.takeControl("focus_session", deviceA, deviceSeq = 1L, startedAtMs = t0)
        store.releaseControl("focus_session", deviceA, deviceSeq = 2L)
        store.takeControl("focus_session", deviceB, deviceSeq = 3L, startedAtMs = t0 + 5_000)

        val held = assertNotNull(store.controlState("focus_session"))
        assertEquals(deviceB, held.deviceId)
        assertTrue(!held.released, "a fresh take clears the released flag")
    }

    // ---- pruning ----

    @Test
    fun `pruning events removes only those before the horizon`() {
        val store = store()
        store.insertEvent(event(seq = 1, startedAtMs = t0 - 10 * minute))
        store.insertEvent(event(seq = 2, startedAtMs = t0))
        store.insertEvent(event(seq = 3, startedAtMs = t0 + 10 * minute))

        store.pruneEvents(beforeMs = t0)

        assertEquals(
            listOf(2L, 3L),
            store.eventsAfter(deviceA, 0).map { it.seq },
            "the horizon is exclusive — an event exactly at it survives",
        )
    }

    @Test
    fun `pruning buckets removes only those before the horizon`() {
        val store = store()
        listOf(t0 - minute, t0, t0 + minute).forEach {
            store.insertBucket(MinuteBucket(deviceA, it, safari, minute))
        }

        store.pruneBuckets(beforeMs = t0)

        assertEquals(
            listOf(t0, t0 + minute),
            store.bucketsForRange(deviceA, t0 - 10 * minute, t0 + 10 * minute).map { it.bucketTs }.sorted(),
        )
    }

    @Test
    fun `pruning events leaves rollups untouched`() {
        // Events prune at ~30d, buckets at ~6mo, rollups forever. A prune that
        // took history with it would be silent, permanent data loss.
        val store = store()
        store.insertEvent(event(seq = 1, startedAtMs = t0 - 10 * minute))
        store.upsertRollup(AppDayRollup(deviceA, day, safari, 3_600_000L))

        store.pruneEvents(beforeMs = t0)

        assertEquals(emptyList(), store.eventsAfter(deviceA, 0))
        assertEquals(1, store.rollupsForDay(deviceA, day).size, "rollups are kept forever")
    }
}
