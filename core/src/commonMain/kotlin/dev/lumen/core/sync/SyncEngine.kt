package dev.lumen.core.sync

import dev.lumen.core.model.AppKey
import dev.lumen.core.clock.UtcDay
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.RecordKind
import dev.lumen.core.model.Setting
import dev.lumen.core.model.SyncRecord
import dev.lumen.core.model.SyncState
import dev.lumen.core.store.LumenStore

/**
 * Sync engine — M4. Orchestrates one sync pass over the frozen
 * [SyncTransport] seam, implementing the reconciliation contract in
 * `docs/data-model.md`:
 *
 *  - events / buckets / rollups: **append-merge** — dedupe on receipt by
 *    `(deviceId, seq)`, immutable, sum across devices. No CRDT, no
 *    wall-clock LWW for events.
 *  - settings: **LWW + UTC-day**, tiebreak `(device_id, seq)`.
 *  - control state: latest declarer takes over.
 *
 * The transport's server order is the ordering authority. A gap or jump
 * in a device's seq (server dropped/reordered/replayed) is surfaced as
 * an integrity warning, never silently converged — the [integrity] hook
 * is where a hash chain plugs in.
 *
 * ## Layering
 *
 * This class works on the wire unit ([SyncRecord]) and the store. The
 * E2EE layer encrypts/decrypts record payloads; it is deliberately NOT
 * here, so the engine is testable without crypto and E2EE can be swapped
 * (v1: X25519 crypto_box; post-MVP: OMEMO 2) without touching this file.
 */
class SyncEngine(
    private val store: LumenStore,
    private val transport: SyncTransport,
    private val localDeviceId: DeviceId,
    private val integrity: SyncIntegrity? = null,
) {

    /**
     * One full sync pass: pull remote records newer than the local
     * watermark, apply them, then publish the local outbox.
     *
     * @return a report of what happened — records pulled/applied, records
     *   published, and any integrity warnings (server reorder/drop/replay).
     */
    suspend fun sync(): SyncReport {
        warnings.clear()
        remoteEvents.clear()
        val pulled = pull()
        val published = push()
        (transport as? SyncDiagnostics)?.let { warnings += it.drainWarnings() }
        return SyncReport(
            pulled = pulled.size,
            applied = pulled.size, // every dedupe-passing record applies
            published = published,
            integrityWarnings = warnings.toList(),
            remoteEvents = remoteEvents.toList(),
        )
    }

    /** Warnings from the most recent [pull] (used by tests). */
    fun lastWarnings(): List<String> = warnings.toList()

    /**
     * Pull records newer than each device's local watermark, dedupe by
     * `(deviceId, seq)`, verify integrity, and apply to the store.
     */
    suspend fun pull(): List<SyncRecord> {
        val records = transport.pull(watermarks())
        val skipped = (transport as? SyncDiagnostics)?.drainSkipped().orEmpty()
        return applyRemote(records, skipped)
    }
    /**
     * Publish the local outbox — records the store has that the server
     * has not acked. Advances the watermark on ack.
     */
    suspend fun push(): Int {
        val outbox = store.eventsAfter(localDeviceId, store.lastAckedSeq(localDeviceId))
            .map { it.toSyncRecord() }
        if (outbox.isEmpty()) return 0
        val result = transport.publish(outbox)
        result.acked[localDeviceId.value]?.let { acked ->
            store.setAckedSeq(localDeviceId, acked)
        }
        return outbox.size
    }

    private val warnings = mutableListOf<String>()
    private val remoteEvents = mutableListOf<FocusEvent>()

    private fun applyRemote(
        pulledRecords: List<SyncRecord>,
        knownSkips: Set<Pair<String, Long>> = emptySet(),
    ): List<SyncRecord> {
        // Our own records come back from the node too (it is one shared node
        // per account). This device is their source of truth — applying them
        // again is at best redundant, and a stale copy must never win.
        //
        // Within one device, order by seq. Pubsub servers do not promise item
        // order (several return newest first), and a descending batch read
        // as "server order" made every record after the first look like a
        // replay — dropped, while the watermark had already jumped to the top.
        val records = pulledRecords
            .filter { it.deviceId != localDeviceId }
            .sortedWith(compareBy<SyncRecord>({ it.deviceId.value }, { it.seq }))
        // Dedupe by (deviceId, seq): the transport may legitimately replay
        // items it already delivered (pubsub at-least-once). INSERT OR
        // IGNORE on the events PK does the final dedupe; the in-memory set
        // avoids redundant store work for repeat pulls within a pass.
        val seen = mutableSetOf<Pair<String, Long>>()
        val applied = mutableListOf<SyncRecord>()

        // Per-device last-seen seq for gap/jump/replay detection. The
        // store's lastAckedSeq default of 0 means "no row" — but 0 is also
        // a legitimate acked seq, so a first-contact device (no events in
        // the store) must be treated as -1, or its first record (seq 0) is
        // misread as a replay and silently dropped.
        val lastSeenSeq = mutableMapOf<String, Long>()
        records.forEach { r ->
            if (r.deviceId.value !in lastSeenSeq) {
                val hasLocalEvents = store.eventsAfter(r.deviceId, Long.MIN_VALUE).isNotEmpty()
                lastSeenSeq[r.deviceId.value] = if (hasLocalEvents) store.lastAckedSeq(r.deviceId) else -1L
            }
        }

        for (record in records) {
            val key = record.deviceId.value to record.seq
            if (!seen.add(key)) continue

            // Server order is authoritative; a gap means the server lost
            // something. Surface it, don't converge silently. First contact
            // with a device (no watermark yet) is NOT a gap — seq starts at
            // 0 or 1 and prev is -1 by convention.
            val prev = lastSeenSeq[record.deviceId.value] ?: -1L
            if (record.seq <= prev) {
                warnings += "replay: ${record.deviceId.value} seq ${record.seq} <= watermark $prev"
                continue
            }
            if (prev >= 0 && record.seq > prev + 1) {
                val explained = (prev + 1 until record.seq).all { (record.deviceId.value to it) in knownSkips }
                if (!explained) warnings += "gap: ${record.deviceId.value} jumped ${prev} -> ${record.seq}"
            }
            lastSeenSeq[record.deviceId.value] = record.seq

            // The record's (device, seq) is outside the ciphertext; the event's
            // own copy is inside it. A server swapping two valid envelopes of
            // the same device would otherwise advance the watermark past an
            // event that was never applied.
            if (!payloadMatches(record)) {
                warnings += "integrity: ${record.deviceId.value} seq ${record.seq} carries another record's payload"
                continue
            }

            if (integrity != null && !integrity.verify(record, prev)) {
                warnings += "integrity: ${record.deviceId.value} seq ${record.seq} failed chain"
                continue
            }

            apply(record)
            // Advance the per-device watermark so the next pass knows what
            // this device has already delivered (replay detection).
            store.setAckedSeq(record.deviceId, record.seq)
            applied += record
        }
        rememberPeers(applied.map { it.deviceId.value }.toSet())
        return applied
    }

    private fun payloadMatches(record: SyncRecord): Boolean {
        if (record.kind != RecordKind.EVENT) return true
        val event = runCatching { record.toFocusEvent() }.getOrNull() ?: return false
        return event.seq == record.seq && event.deviceId == record.deviceId
    }

    /** Apply one verified record per its kind. */
    private fun apply(record: SyncRecord) {
        when (record.kind) {
            RecordKind.EVENT -> record.toFocusEvent().also {
                store.insertEvent(it)
                remoteEvents += it
            }
            RecordKind.ROLLUP -> store.upsertRollup(record.toAppDayRollup())
            RecordKind.SETTING -> store.upsertSetting(record.toSetting())
        }
    }

    /**
     * Every device's watermark, not just ours. Passing only the local one
     * meant the transport returned each peer's ENTIRE history on every pass,
     * and the engine then flagged all of it as "replay" — a warning storm
     * that buried any real one.
     */
    private fun watermarks(): Map<String, Long> =
        (peers() + localDeviceId.value).associateWith { store.lastAckedSeq(DeviceId(it)) }

    /** Device ids this store has applied records from. */
    fun peers(): Set<String> =
        store.setting(PEERS_KEY)?.value?.decodeToString()
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            .orEmpty()

    private fun rememberPeers(ids: Set<String>) {
        val known = peers()
        if (known.containsAll(ids)) return
        val now = currentTimeMs()
        store.upsertSetting(
            Setting(
                key = PEERS_KEY,
                value = (known + ids).sorted().joinToString(",").encodeToByteArray(),
                updatedAtMs = now,
                updatedDayUtc = UtcDay.dayOf(now),
                deviceId = localDeviceId,
            ),
        )
    }

    companion object {
        /** Local bookkeeping: which peer devices have delivered records. */
        const val PEERS_KEY = "sync.peers"
    }
}

private fun currentTimeMs(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

/** What one [SyncEngine.sync] pass did. */
data class SyncReport(
    val pulled: Int,
    val applied: Int,
    val published: Int,
    val integrityWarnings: List<String>,
    /**
     * Events applied from OTHER devices this pass. Buckets and rollups are
     * derived per device and never synced, so the caller derives them from
     * these — without that, pulled time sat in the events table and no
     * screen ever showed it.
     */
    val remoteEvents: List<FocusEvent> = emptyList(),
)

/**
 * Wire <-> domain mappers. The payload travels as JSON for now; the E2EE
 * layer wraps it in ciphertext at the transport boundary later (M4), so
 * this file does not change when crypto lands — only the envelope does.
 */
private val syncJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/**
 * The EVENT wire shape — deliberately NOT [dev.lumen.core.model.FocusEvent].
 *
 * `FocusEvent.titleHash` is a local-only process/window hint (docs/e2ee.md
 * §3: "window titles never leave the device in any form"). Serializing the
 * domain type directly would put it on the wire whenever a collector set it;
 * this DTO structurally cannot carry it, so the hard rule is enforced by the
 * type system rather than by collector discipline.
 */
@kotlinx.serialization.Serializable
private data class FocusEventWire(
    val seq: Long,
    val deviceId: DeviceId,
    val appKey: AppKey,
    val startedAtMs: Long,
    val durationMs: Long,
    val category: String? = null,
    val syncState: SyncState = SyncState.LOCAL,
)

private fun dev.lumen.core.model.FocusEvent.toSyncRecord() =
    SyncRecord(
        deviceId = deviceId,
        seq = seq,
        kind = RecordKind.EVENT,
        payload = syncJson.encodeToString(
            FocusEventWire.serializer(),
            FocusEventWire(
                seq = seq,
                deviceId = deviceId,
                appKey = appKey,
                startedAtMs = startedAtMs,
                durationMs = durationMs,
                category = category,
                syncState = syncState,
            ),
        ).toByteArray(),
    )

private fun SyncRecord.toFocusEvent() =
    syncJson.decodeFromString<FocusEventWire>(
        String(payload),
    ).let {
        dev.lumen.core.model.FocusEvent(
            seq = it.seq,
            deviceId = it.deviceId,
            appKey = it.appKey,
            titleHash = null, // never on the wire; receiver has no title for it
            startedAtMs = it.startedAtMs,
            durationMs = it.durationMs,
            category = it.category,
            syncState = it.syncState,
        )
    }

private fun SyncRecord.toAppDayRollup() =
    syncJson.decodeFromString(
        dev.lumen.core.model.AppDayRollup.serializer(),
        String(payload),
    )

private fun SyncRecord.toSetting() =
    syncJson.decodeFromString(
        dev.lumen.core.model.Setting.serializer(),
        String(payload),
    )