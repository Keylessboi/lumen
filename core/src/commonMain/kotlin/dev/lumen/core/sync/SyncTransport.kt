package dev.lumen.core.sync

import dev.lumen.core.model.SyncRecord

/**
 * Transport seam — the ONLY interface Agent B consumes from Agent A's
 * sync layer. FROZEN at M1.
 *
 * One implementation (XMPP, owned by Agent A in :transport-xmpp) with
 * three configurations: public provider (in-app IBR), self-hosted
 * (advanced), local-only (unconfigured — sync simply never runs).
 */
interface SyncTransport {
    /** True when an account/endpoint is configured and the transport may run. */
    val isConfigured: Boolean

    /**
     * Publish a batch of records. Returns the server-assigned ack sequence
     * for each (for watermarking). Suspends until acked or throws on
     * permanent failure — caller owns retry/backoff policy.
     */
    suspend fun publish(records: List<SyncRecord>): PublishResult

    /**
     * Pull records newer than [afterDeviceSeq] per device.
     * Returns records in server order (total order per server).
     */
    suspend fun pull(after: Map<String, Long>): List<SyncRecord>

    /** Close the underlying connection (XMPP stream end). Idempotent. */
    suspend fun close()
}

data class PublishResult(
    /** deviceId -> last server-assigned seq, for watermark advancement. */
    val acked: Map<String, Long>,
)

/**
 * Rollback-detection contract. The server can reorder/drop/replay; this
 * hook lets the sync engine surface integrity warnings instead of silently
 * converging. Implementation (hash chain over records) is Agent A's.
 */
interface SyncIntegrity {
    /** Returns true if [record] chains correctly after [prevSeq]. */
    fun verify(record: SyncRecord, prevSeq: Long): Boolean
}
