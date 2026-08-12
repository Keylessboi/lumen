package dev.lumen.core.sync

import dev.lumen.core.crypto.E2EE
import dev.lumen.core.crypto.EncryptedPayload
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.SyncRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * E2EE transport decorator — M4. Wraps a raw [SyncTransport] so every
 * record payload is encrypted before upload and decrypted after pull,
 * using the frozen [E2EE] seam.
 *
 * ## Why a decorator, not a method on the engine
 *
 * `SyncEngine` is deliberately crypto-free (its KDoc says so): the E2EE
 * layer is swappable (v1 X25519 crypto_box; OMEMO 2 post-MVP) and the
 * engine must not change when that swap happens. The decorator is the
 * boundary: the engine sees plaintext [SyncRecord]s, the wire carries
 * [EncryptedPayload]s serialized into the record's `payload` field.
 *
 * ## Wire form
 *
 * `SyncRecord.payload` on the wire is the JSON serialization of
 * [EncryptedPayload] (the normative envelope in `docs/e2ee.md` §6).
 * The engine's domain mappers already treat payload as opaque bytes, so
 * nothing below this decorator needs to know the difference.
 */
class EncryptedTransport(
    private val base: SyncTransport,
    private val e2ee: E2EE,
) : SyncTransport {

    // encodeDefaults: the envelope version (default 1) MUST appear on the
    // wire — docs/e2ee.md §6: "Recipients MUST reject unknown versions".
    // kotlinx.serialization omits fields equal to their default otherwise,
    // which would silently drop the one field the spec gates on.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val isConfigured: Boolean get() = base.isConfigured

    override suspend fun publish(records: List<SyncRecord>): PublishResult {
        val encrypted = records.map { record ->
            val envelope = e2ee.encrypt(record.payload, recipientOf(record))
            record.copy(payload = json.encodeToString(EncryptedPayload.serializer(), envelope).toByteArray())
        }
        return base.publish(encrypted)
    }

    override suspend fun pull(after: Map<String, Long>): List<SyncRecord> =
        base.pull(after).map { record ->
            val envelope = json.decodeFromString(
                EncryptedPayload.serializer(),
                String(record.payload),
            )
            record.copy(payload = e2ee.decrypt(envelope))
        }

    override suspend fun close() = base.close()

    /**
     * The recipient of a record is the device it is addressed to. In the
     * single-device-to-single-device v1 path this is the peer device; the
     * multi-device fan-out (wrappedKeys) is a later milestone, so for now
     * records carry the sender's own id and the resolver maps peers.
     */
    private fun recipientOf(record: SyncRecord): DeviceId = record.deviceId
}