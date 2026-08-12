package dev.lumen.core.store

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.lumen.core.clock.UtcDay
import dev.lumen.core.db.LumenDatabase
import dev.lumen.core.model.AppDayRollup
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.AppLocalDayRollup
import dev.lumen.core.model.ControlState
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.MinuteBucket
import dev.lumen.core.model.Setting

/**
 * Android driver for [LumenStore] — Agent A, M3.
 *
 * Mirrors [JvmLumenStore]'s query mapping against the SQLDelight-generated
 * [LumenDatabase] (schema FROZEN at M1, source in
 * `core/src/commonMain/sqldelight`). The schema is identical on both
 * platforms — only the driver differs, so an export written on desktop
 * opens on Android and vice versa.
 *
 * `AndroidSqliteDriver` handles create/migrate from the schema version
 * itself, so [open] is far simpler than the JVM driver's manual
 * `PRAGMA user_version` dance (see JvmLumenStore.open for why that exists).
 */
class AndroidLumenStore private constructor(
    private val db: LumenDatabase,
    private val queries: dev.lumen.core.db.LumenDatabaseQueries,
) : LumenStore {

    override fun insertEvent(event: FocusEvent) {
        queries.insertEvent(
            seq = event.seq,
            device_id = event.deviceId.value,
            app_key = event.appKey.value,
            title_hash = event.titleHash,
            started_at_ms = event.startedAtMs,
            duration_ms = event.durationMs,
            category = event.category,
            sync_state = event.syncState.ordinal.toLong(),
        )
    }

    override fun eventsAfter(deviceId: DeviceId, afterSeq: Long): List<FocusEvent> =
        queries.selectEventsAfter(deviceId.value, afterSeq)
            .executeAsList()
            .map { row ->
                FocusEvent(
                    seq = row.seq,
                    deviceId = DeviceId(row.device_id),
                    appKey = AppKey(row.app_key),
                    titleHash = row.title_hash,
                    startedAtMs = row.started_at_ms,
                    durationMs = row.duration_ms,
                    category = row.category,
                    syncState = when (row.sync_state) {
                        0L -> dev.lumen.core.model.SyncState.LOCAL
                        1L -> dev.lumen.core.model.SyncState.ACKED
                        else -> dev.lumen.core.model.SyncState.CONFLICT
                    },
                )
            }

    override fun markEventSynced(deviceId: DeviceId, seq: Long, state: Int) {
        queries.markEventSynced(
            sync_state = state.toLong(),
            device_id = deviceId.value,
            seq = seq,
        )
    }

    override fun insertBucket(bucket: MinuteBucket) {
        queries.insertBucket(
            device_id = bucket.deviceId.value,
            bucket_ts = bucket.bucketTs,
            app_key = bucket.appKey.value,
            active_ms = bucket.activeMs,
        )
    }

    override fun bucketsForRange(
        deviceId: DeviceId,
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<MinuteBucket> =
        queries.selectBucketsForDay(deviceId.value, dayStartMs, dayEndMs)
            .executeAsList()
            .map { row ->
                MinuteBucket(
                    deviceId = DeviceId(row.device_id),
                    bucketTs = row.bucket_ts,
                    appKey = AppKey(row.app_key),
                    activeMs = row.active_ms,
                )
            }

    override fun upsertRollup(rollup: AppDayRollup) {
        queries.upsertRollup(
            device_id = rollup.deviceId.value,
            day_utc = rollup.dayUtc,
            app_key = rollup.appKey.value,
            total_ms = rollup.totalMs,
            category = rollup.category,
        )
    }

    override fun rollupsForDay(deviceId: DeviceId, dayUtc: String): List<AppDayRollup> =
        queries.selectAllRollups(deviceId.value, dayUtc)
            .executeAsList()
            .map { row ->
                AppDayRollup(
                    deviceId = DeviceId(row.device_id),
                    dayUtc = row.day_utc,
                    appKey = AppKey(row.app_key),
                    totalMs = row.total_ms,
                    category = row.category,
                )
            }

    override fun upsertLocalRollup(rollup: AppLocalDayRollup) {
        queries.upsertLocalRollup(
            device_id = rollup.deviceId.value,
            day_local = rollup.dayLocal,
            app_key = rollup.appKey.value,
            total_ms = rollup.totalMs,
            utc_offset_min = rollup.utcOffsetMin.toLong(),
            category = rollup.category,
        )
    }

    override fun localRollupsForDay(deviceId: DeviceId, dayLocal: String): List<AppLocalDayRollup> =
        queries.selectLocalRollups(deviceId.value, dayLocal)
            .executeAsList()
            .map { row ->
                AppLocalDayRollup(
                    deviceId = DeviceId(row.device_id),
                    dayLocal = row.day_local,
                    appKey = AppKey(row.app_key),
                    totalMs = row.total_ms,
                    utcOffsetMin = row.utc_offset_min.toInt(),
                    category = row.category,
                )
            }

    override fun clearLocalRollups(deviceId: DeviceId, dayLocal: String) {
        queries.deleteLocalRollupsForDay(deviceId.value, dayLocal)
    }

    override fun upsertSetting(setting: Setting) {
        queries.upsertSetting(
            key = setting.key,
            value_ = setting.value,
            updated_at_ms = setting.updatedAtMs,
            updated_day_utc = setting.updatedDayUtc,
            device_id = setting.deviceId.value,
        )
    }

    override fun setting(key: String): Setting? =
        queries.selectSetting(key).executeAsOneOrNull()?.let { row ->
            Setting(
                key = row.key,
                value = row.value_,
                updatedAtMs = row.updated_at_ms,
                updatedDayUtc = row.updated_day_utc,
                deviceId = DeviceId(row.device_id),
            )
        }

    override fun registryCategory(appKey: AppKey): String? =
        queries.selectAppKeyCategory(appKey.value).executeAsOneOrNull()

    override fun manualCategory(appKey: AppKey): String? =
        queries.selectManualOverride(appKey.value).executeAsOneOrNull()

    override fun setManualOverride(appKey: AppKey, category: String) {
        queries.insertManualOverride(
            app_key = appKey.value,
            category = category,
            created_at_ms = System.currentTimeMillis(),
        )
    }

    override fun lastAckedSeq(deviceId: DeviceId): Long =
        // -1, not 0: a missing row means "nothing acked". Returning 0 makes
        // the first record (seq 0) look like a replay on the receive side
        // (`seq 0 <= watermark 0`), silently dropping every first sync.
        queries.selectWatermark(deviceId.value).executeAsOneOrNull() ?: -1L

    override fun setAckedSeq(deviceId: DeviceId, seq: Long) {
        queries.upsertWatermark(deviceId.value, seq)
    }

    override fun controlState(controlKey: String): ControlState? =
        queries.selectControlState(controlKey).executeAsOneOrNull()?.let { row ->
            ControlState(
                controlKey = row.control_key,
                deviceId = DeviceId(row.device_id),
                deviceSeq = row.device_seq,
                startedAtMs = row.started_at_ms,
                utcDay = row.utc_day,
                released = row.released == 1L,
            )
        }

    override fun takeControl(
        controlKey: String,
        deviceId: DeviceId,
        deviceSeq: Long,
        startedAtMs: Long,
    ) {
        val existing = controlState(controlKey)
        // Newest per-device seq wins. Existing declarations with a higher
        // seq keep control; equal seq falls back to the LOWER device_id
        // (deterministic on both sides, never wall-clock).
        if (existing != null && existing.deviceSeq > deviceSeq) return
        if (existing != null && existing.deviceSeq == deviceSeq && existing.deviceId.value < deviceId.value) return
        queries.upsertControlState(
            control_key = controlKey,
            device_id = deviceId.value,
            device_seq = deviceSeq,
            started_at_ms = startedAtMs,
            utc_day = UtcDay.today(),
            released = 0L,
        )
    }

    override fun releaseControl(controlKey: String, deviceId: DeviceId, deviceSeq: Long) {
        val existing = controlState(controlKey) ?: return
        // Only the current controller may release.
        if (existing.deviceId != deviceId) return
        if (deviceSeq < existing.deviceSeq) return
        queries.upsertControlState(
            control_key = controlKey,
            device_id = deviceId.value,
            device_seq = deviceSeq,
            started_at_ms = existing.startedAtMs,
            utc_day = existing.utcDay,
            released = 1L,
        )
    }

    override fun pruneEvents(beforeMs: Long) {
        queries.pruneEventsBefore(beforeMs)
    }

    override fun pruneBuckets(beforeMs: Long) {
        queries.pruneBucketsBefore(beforeMs)
    }

    companion object {
        /**
         * Open (or create) the app database. [AndroidSqliteDriver] derives
         * the schema version from [LumenDatabase.Schema] and runs
         * create/migrate itself — no manual PRAGMA handling needed.
         */
        fun open(context: Context): AndroidLumenStore {
            val driver = AndroidSqliteDriver(
                schema = LumenDatabase.Schema,
                context = context,
                name = DB_NAME,
            )
            val db = LumenDatabase(driver)
            return AndroidLumenStore(db, db.lumenDatabaseQueries)
        }

        private const val DB_NAME = "lumen.db"
    }
}