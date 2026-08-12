package dev.lumen.core.model

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Canonical app identity.
 *
 * Linux: desktop-file id / WM_CLASS resource name / exec name.
 * Android: package name (e.g. `com.instagram.android`).
 *
 * This is the key every other table joins on. Never store raw window titles
 * in the sync path — see [titleHash].
 */
@Serializable
@JvmInline
value class AppKey(val value: String)

/**
 * A device's identity. Generated once per install (UUID v4).
 * Sync events carry device provenance; reconciliation is
 * append-merge by (deviceId, monotonic seq).
 */
@OptIn(ExperimentalUuidApi::class)
@Serializable
@JvmInline
value class DeviceId(val value: String = Uuid.random().toString()) {
    override fun toString(): String = value
}

/**
 * Raw foreground event. Local-only, pruned ~30 days.
 *
 * [seq] is a per-device monotonic counter — the ONLY ordering authority
 * in the merge path. Never use wall-clock timestamps for reconciliation.
 */
@Serializable
data class FocusEvent(
    val seq: Long,
    val deviceId: DeviceId,
    val appKey: AppKey,
    /** Optional truncated/hashed window title. NEVER synced. */
    val titleHash: String? = null,
    val startedAtMs: Long,
    val durationMs: Long,
    /** Denormalized category snapshot at capture time. */
    val category: String? = null,
    val syncState: SyncState = SyncState.LOCAL,
)

enum class SyncState {
    LOCAL, ACKED, CONFLICT,
}

/**
 * 1-minute bucket — canonical stored granularity on device.
 * Pruned ~6 months.
 */
@Serializable
data class MinuteBucket(
    val deviceId: DeviceId,
    /** UTC minute boundary in epoch millis. */
    val bucketTs: Long,
    val appKey: AppKey,
    val activeMs: Long,
)

/**
 * Per-app-per-day rollup — the sync/API unit. Kept forever.
 * [dayUtc] is a UTC-day boundary string `YYYY-MM-DD` (locked rule:
 * never device-local midnight).
 */
@Serializable
data class AppDayRollup(
    val deviceId: DeviceId,
    val dayUtc: String,
    val appKey: AppKey,
    val totalMs: Long,
    val category: String? = null,
)

/**
 * User-editable settings (limits, category overrides, nudge prefs).
 * Reconciled LWW + UTC-day window. [deviceId] is the last writer.
 */
@Serializable
data class Setting(
    val key: String,
    val value: ByteArray,
    val updatedAtMs: Long,
    val updatedDayUtc: String,
    val deviceId: DeviceId,
)

/**
 * A day's total for one app, in the user's own day (discussion #29).
 *
 * The display counterpart to [AppDayRollup]: same numbers, different day
 * boundary. [dayLocal] is a `YYYY-MM-DD` day in the `display.timezone`
 * setting's zone, and [utcOffsetMin] records the offset that was in force
 * when it was written, so a day stays explainable after the user relocates.
 *
 * Derived, per-device, never synced — devices exchange raw events and each
 * derives its own views.
 */
@Serializable
data class AppLocalDayRollup(
    val deviceId: DeviceId,
    val dayLocal: String,
    val appKey: AppKey,
    val totalMs: Long,
    val utcOffsetMin: Int,
    val category: String? = null,
)

/**
 * Sync record envelope — what actually travels over the wire.
 * [deviceId]+[seq] make the record globally unique; dedupe on receipt.
 */
@Serializable
data class SyncRecord(
    val deviceId: DeviceId,
    val seq: Long,
    val kind: RecordKind,
    val payload: ByteArray,
)

enum class RecordKind {
    EVENT,
    ROLLUP,
    SETTING,
}

/** Acknowledged-seq watermark per device (for gap detection). */
data class SyncWatermark(
    val deviceId: DeviceId,
    val lastAckedSeq: Long,
)

/**
 * Control-state declaration — the "two devices at once" takeover rule
 * (docs/data-model.md, "Control-state takeover").
 *
 * Governs exactly one active control: which device owns the live focus
 * session / limit / nudge right now. Usage data is NEVER subject to this —
 * phone time + desktop time always sum.
 *
 * Takeover: the declaration with the newest per-device [deviceSeq] wins;
 * the prior controller yields. [startedAtMs] is a display hint ONLY, never
 * the tiebreak — two devices with skewed clocks must not flip-flop
 * ownership. Tiebreak is (deviceId, deviceSeq), deterministic on both sides.
 *
 * [released] is an explicit handoff ("this device ended the session"),
 * written instead of deleting the row — delete is a tombstone race,
 * release is a state.
 */
@Serializable
data class ControlState(
    val controlKey: String,
    val deviceId: DeviceId,
    val deviceSeq: Long,
    val startedAtMs: Long,
    val utcDay: String,
    val released: Boolean = false,
)
