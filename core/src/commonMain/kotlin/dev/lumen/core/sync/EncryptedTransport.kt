package dev.lumen.core.sync

import dev.lumen.core.crypto.E2EE
import dev.lumen.core.crypto.EncryptedPayload
import dev.lumen.core.crypto.MultiDeviceE2EE
import dev.lumen.core.crypto.NotAddressedToThisDevice
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.SyncRecord
import kotlinx.serialization.json.Json

/**
 * E2EE transport decorator — M4. Wraps a raw [SyncTransport] so every
 * record payload is encrypted before upload and decrypted after pull,
 * using the frozen [E2EE] seam.
 *
 * ## Why a decorator, not a method on the engine
 *
 * `SyncEngine` is deliberately crypto-free (its KDoc says so): the E2EE
 * layer is swappable (v1 X25519 crypto_box; v2 OMEMO 2 fan-out) and the
 * engine must not change when that swap happens. The decorator is the
 * boundary: the engine sees plaintext [SyncRecord]s, the wire carries
 * [EncryptedPayload]s serialized into the record's `payload` field.
 *
 * ## Recipients
 *
 * With a [MultiDeviceE2EE] every record is encrypted for [recipients] (the
 * account's trusted devices) plus the sender. With a plain [E2EE] the
 * record's own device id is the recipient, which is only ever readable by
 * the device that wrote it — kept for tests and v1 compatibility.
 *
 * ## One bad record must not stop the sync
 *
 * A record that will not decrypt — tampered, from a device whose key is not
 * trusted yet, or relabelled by the server as another device's — used to
 * throw out of [pull] and abort the whole pass, so one stray item on the
 * node stopped sync for good. Now it is reported as a warning and that
 * device's later records are held back for this pass, so the watermark
 * cannot advance past it and nothing is lost: it is retried next pass.
 *
 * Records that were never addressed to this device (published before it
 * joined the account) cannot be decrypted here. Senders re-publish their
 * retained history when they first see a new device, so usually a readable
 * copy arrives too; records with no readable copy are skipped, counted, and
 * reported to the engine as known gaps rather than as server data loss.
 */
class EncryptedTransport(
    private val base: SyncTransport,
    private val e2ee: E2EE,
    private val recipients: () -> Set<DeviceId> = { emptySet() },
) : SyncTransport, SyncDiagnostics {

    // encodeDefaults: the envelope version (default 1) MUST appear on the
    // wire — docs/e2ee.md §6: "Recipients MUST reject unknown versions".
    // kotlinx.serialization omits fields equal to their default otherwise,
    // which would silently drop the one field the spec gates on.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val warnings = mutableListOf<String>()
    private val skipped = mutableSetOf<Pair<String, Long>>()

    override val isConfigured: Boolean get() = base.isConfigured

    override suspend fun publish(records: List<SyncRecord>): PublishResult {
        val to = if (e2ee is MultiDeviceE2EE) recipients() else emptySet()
        val encrypted = records.map { record ->
            val envelope = if (e2ee is MultiDeviceE2EE) {
                e2ee.encryptForDevices(record.payload, to)
            } else {
                e2ee.encrypt(record.payload, record.deviceId)
            }
            record.copy(payload = json.encodeToString(EncryptedPayload.serializer(), envelope).encodeToByteArray())
        }
        return base.publish(encrypted)
    }

    override suspend fun pull(after: Map<String, Long>): List<SyncRecord> {
        val heldBack = mutableSetOf<String>()
        val skippedThisPass = mutableSetOf<Pair<String, Long>>()
        val out = mutableListOf<SyncRecord>()
        // Ascending seq per device BEFORE the hold-back logic: if a later batch
        // were applied ahead of a held-back earlier one, the watermark would
        // jump past the held-back records and they would never be retried.
        val pulled = base.pull(after).sortedWith(compareBy<SyncRecord>({ it.deviceId.value }, { it.seq }))
        for (record in pulled) {
            val device = record.deviceId.value
            if (device in heldBack) continue
            val result = runCatching {
                val envelope = json.decodeFromString(EncryptedPayload.serializer(), record.payload.decodeToString())
                // The sender id inside the envelope is bound to the sender's
                // key by crypto_box; the record's device id is not. A server
                // that relabels device A's record as device B's must fail here.
                require(envelope.senderDeviceId == record.deviceId) {
                    "envelope sender ${envelope.senderDeviceId.value} does not match record device $device"
                }
                record.copy(payload = e2ee.decrypt(envelope))
            }
            result.onSuccess { out += it }.onFailure { e ->
                if (e is NotAddressedToThisDevice) {
                    skippedThisPass += device to record.seq
                } else {
                    heldBack += device
                    warnings += "undecryptable: $device seq ${record.seq}: ${e.message ?: e::class.simpleName}"
                }
            }
        }
        // The sender re-publishes its history when a device joins, so the same
        // record can sit on the node twice: an old copy not addressed to us and
        // a new one that is. Only records we got NO readable copy of count.
        val delivered = out.mapTo(mutableSetOf()) { it.deviceId.value to it.seq }
        val unreadable = skippedThisPass.filterNot { it in delivered }
        skipped += unreadable
        unreadable.groupingBy { it.first }.eachCount().forEach { (device, count) ->
            warnings += "skipped $count older record(s) from $device that were encrypted before this device joined"
        }
        return out.distinctBy { it.deviceId.value to it.seq }
    }

    override fun drainWarnings(): List<String> = warnings.toList().also { warnings.clear() }

    override fun drainSkipped(): Set<Pair<String, Long>> = skipped.toSet().also { skipped.clear() }

    override suspend fun close() = base.close()
}

/**
 * Side channel from a transport decorator to [SyncEngine]: problems that
 * are not exceptions, and seqs that were deliberately not delivered.
 */
interface SyncDiagnostics {
    fun drainWarnings(): List<String>

    /** `(deviceId, seq)` pairs the transport knowingly withheld. */
    fun drainSkipped(): Set<Pair<String, Long>>
}
