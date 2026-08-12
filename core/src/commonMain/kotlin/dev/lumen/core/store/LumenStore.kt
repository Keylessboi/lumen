package dev.lumen.core.store

import dev.lumen.core.model.AppDayRollup
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.ControlState
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.MinuteBucket
import dev.lumen.core.model.Setting

/**
 * Storage seam — FROZEN at M1. Owned by Agent A.
 *
 * The SQLDelight-generated database (schema in
 * `core/src/commonMain/sqldelight/.../LumenDatabase.sq`, normative in
 * `docs/data-model.md`) is the persistence layer. This interface is the
 * contract the rest of the app builds against; the platform drivers
 * (JVM desktop, Android) implement [LumenStore.factory].
 */
interface LumenStore {

    // ---- events (local, pruned ~30 days) ----

    fun insertEvent(event: FocusEvent)

    /** Events for a device after a seq, for sync outbox. */
    fun eventsAfter(deviceId: DeviceId, afterSeq: Long): List<FocusEvent>

    /** Sync-state update after ack/conflict. */
    fun markEventSynced(deviceId: DeviceId, seq: Long, state: Int)

    // ---- buckets (pruned ~6 months) ----

    fun insertBucket(bucket: MinuteBucket)

    /** Buckets for a UTC day window: [dayStartMs, dayEndMs). */
    fun bucketsForRange(deviceId: DeviceId, dayStartMs: Long, dayEndMs: Long): List<MinuteBucket>

    // ---- rollups (kept forever) ----

    fun upsertRollup(rollup: AppDayRollup)

    fun rollupsForDay(deviceId: DeviceId, dayUtc: String): List<AppDayRollup>

    // ---- settings (LWW + UTC-day) ----

    fun upsertSetting(setting: Setting)

    fun setting(key: String): Setting?

    // ---- categories ----

    /** Registry lookup; returns null when unknown (→ Uncategorized). */
    fun registryCategory(appKey: AppKey): String?

    /** User override; sticky, wins over registry. */
    fun manualCategory(appKey: AppKey): String?

    fun setManualOverride(appKey: AppKey, category: String)

    // ---- sync ----

    fun lastAckedSeq(deviceId: DeviceId): Long

    fun setAckedSeq(deviceId: DeviceId, seq: Long)

    // ---- control-state takeover (docs/data-model.md) ----

    /**
     * Read the current control declaration for [controlKey].
     * Returns null when no device has declared it.
     */
    fun controlState(controlKey: String): ControlState?

    /**
     * Declare this device the active controller. The caller supplies the
     * per-device monotonic [deviceSeq]; the declaration with the newest
     * seq wins and the prior controller yields. Never wall-clock.
     */
    fun takeControl(controlKey: String, deviceId: DeviceId, deviceSeq: Long, startedAtMs: Long)

    /** Explicit handoff: this device releases the control. */
    fun releaseControl(controlKey: String, deviceId: DeviceId, deviceSeq: Long)

    /** Prune events older than the retention horizon (30 days). */
    fun pruneEvents(beforeMs: Long)

    /** Prune buckets older than the retention horizon (6 months). */
    fun pruneBuckets(beforeMs: Long)

    companion object {
        /**
         * Platform driver registration. Desktop JVM and Android each
         * provide one; the app wires it at startup.
         */
        var factory: ((name: String) -> LumenStore)? = null
    }
}
