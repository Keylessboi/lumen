package dev.lumen.core.store

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import java.io.File

/**
 * JVM desktop driver for [LumenStore] — Agent A, M1.
 *
 * Binds the frozen seam to the SQLDelight-generated [LumenDatabase]
 * (schema normative in docs/data-model.md, source in
 * core/src/commonMain/sqldelight). The schema is FROZEN at M1; this
 * class only reads/writes it, never changes it.
 */
class JvmLumenStore private constructor(
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

    override fun clearDerived(deviceId: DeviceId) {
        queries.clearBucketsForDevice(deviceId.value)
        queries.clearRollupsForDevice(deviceId.value)
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
        queries.selectWatermark(deviceId.value).executeAsOneOrNull() ?: 0L

    /**
     * The highest event seq stored for [deviceId], or -1 when none exist.
     *
     * `FocusSessionTracker` numbers from 0 on every launch; without this,
     * a restart writes seqs 0,1,2... that collide with the existing
     * `(device_id, seq)` PK and are silently dropped by `INSERT OR IGNORE` —
     * the day stops growing with no error. Seeding the tracker's nextSeq
     * from here is the single point where the monotonic counter is owned.
     */
    fun lastEventSeq(deviceId: DeviceId): Long =
        queries.selectEventsAfter(deviceId.value, Long.MIN_VALUE)
            .executeAsList()
            .lastOrNull()
            ?.seq
            ?: -1L

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
         * Open (or create) the database at [dbFile].
         *
         * Creates the schema only on a fresh database and migrates an older
         * one. Calling `Schema.create` unconditionally — as this did —
         * succeeds exactly once and then throws `table devices already
         * exists` on every subsequent launch, because SQLDelight emits plain
         * `CREATE TABLE`. Nothing caught it: the contract tests and the
         * driver's own round-trips all use [inMemory], which is fresh every
         * time, and no app persisted through this seam yet.
         *
         * `user_version` is SQLite's own schema-version pragma and is what
         * SQLDelight's migration machinery expects to drive.
         */
        fun open(dbFile: File): JvmLumenStore {
            dbFile.parentFile?.mkdirs()
            val driver = JdbcSqliteDriver(
                url = "jdbc:sqlite:${dbFile.absolutePath}",
            )
            val current = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA user_version",
                mapper = { cursor ->
                    app.cash.sqldelight.db.QueryResult.Value(
                        if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L,
                    )
                },
                parameters = 0,
            ).value

            val target = LumenDatabase.Schema.version
            when {
                current == 0L -> {
                    LumenDatabase.Schema.create(driver).value
                    driver.execute(null, "PRAGMA user_version = $target", 0)
                }
                current < target -> {
                    LumenDatabase.Schema.migrate(driver, current, target).value
                    driver.execute(null, "PRAGMA user_version = $target", 0)
                }
                // current > target: a newer Lumen wrote this file. Opening it
                // read-write would corrupt data the older schema cannot
                // represent, so refuse rather than guess.
                current > target -> error(
                    "database schema v$current is newer than this build understands (v$target)",
                )
            }

            val db = LumenDatabase(driver)
            return JvmLumenStore(db, db.lumenDatabaseQueries)
        }

        /** In-memory database for tests. */
        fun inMemory(): JvmLumenStore {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LumenDatabase.Schema.create(driver)
            val db = LumenDatabase(driver)
            return JvmLumenStore(db, db.lumenDatabaseQueries)
        }
    }
}
