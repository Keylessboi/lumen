package dev.lumen.core.sync

import dev.lumen.core.clock.UtcDay
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.Setting
import dev.lumen.core.store.LumenStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * One device's public identity as it is published to the account — the
 * OMEMO 2 "device list + bundle" idea, reduced to what Lumen needs: which
 * devices exist and which X25519 key each one decrypts with.
 */
@Serializable
data class DeviceBundle(
    val deviceId: DeviceId,
    /** Raw 32-byte X25519 public key, base64 (RFC 4648, padded). */
    val identityKey: String,
    /** Human label shown in the device list ("Linux laptop", "Pixel 8"). */
    val label: String,
    val publishedAtMs: Long,
)

/**
 * Where devices announce themselves. The XMPP implementation is a PEP node
 * on the account (`transport-xmpp`); tests use an in-memory one.
 */
interface DeviceDirectory {
    suspend fun publish(bundle: DeviceBundle)
    suspend fun bundles(): List<DeviceBundle>
    suspend fun remove(deviceId: DeviceId)
}

/** A device this install has decided to encrypt to and accept records from. */
@Serializable
data class PinnedDevice(
    val deviceId: DeviceId,
    val identityKey: String,
    val label: String,
    val firstSeenMs: Long,
    /**
     * The user confirmed this device on THIS device. Until then it is
     * pending: nothing is encrypted to it, nothing it sends is applied, and
     * no history is re-published for it.
     */
    val approved: Boolean = false,
)

/**
 * Device pinning for the account, with explicit approval.
 *
 * The first key a device id presents is pinned, but only as PENDING. The
 * device list lives on the provider, so a hostile or compromised provider
 * can add a device of its own at any time; if new devices were trusted on
 * sight it would receive every future record and — since devices re-publish
 * their history for newcomers — the last 30 days as well. The user approves a
 * new device on the Sync screen after comparing the code it shows for itself.
 *
 * A LATER, DIFFERENT key for the same id is refused and reported, never
 * silently re-pinned. The user resolves it by forgetting the device, after
 * which the next key is pinned (as pending) again.
 */
@OptIn(ExperimentalEncodingApi::class)
class DeviceTrust(
    private val store: LumenStore,
    private val localDeviceId: DeviceId,
) {
    data class Reconciled(
        /** Approved devices: the only ones encrypted to and accepted from. */
        val trusted: List<PinnedDevice>,
        val warnings: List<String>,
        /** Seen but not approved on this device yet. */
        val pending: List<PinnedDevice> = emptyList(),
    )

    fun pinned(): List<PinnedDevice> {
        val raw = store.setting(KEY)?.value?.decodeToString()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(PinnedDevice.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    /** Raw public key for an APPROVED device, or null (unknown or pending). */
    fun keyFor(deviceId: DeviceId): ByteArray? =
        pinned().firstOrNull { it.deviceId == deviceId && it.approved }?.let { Base64.decode(it.identityKey) }

    /** Raw key of a pinned device whether or not approved — for showing its code. */
    fun pinnedKey(deviceId: DeviceId): ByteArray? =
        pinned().firstOrNull { it.deviceId == deviceId }?.let { Base64.decode(it.identityKey) }

    /** The user confirmed [deviceId]. Returns false if it is not pinned. */
    fun approve(deviceId: DeviceId, nowMs: Long): Boolean {
        val pins = pinned()
        if (pins.none { it.deviceId == deviceId }) return false
        save(pins.map { if (it.deviceId == deviceId) it.copy(approved = true) else it }, nowMs)
        return true
    }

    /**
     * Fold the directory's current bundles into the pin set. Our own bundle
     * is skipped (this device trusts its own keychain, not the server's copy
     * of it).
     */
    fun reconcile(bundles: List<DeviceBundle>, nowMs: Long): Reconciled {
        val pins = pinned().associateBy { it.deviceId }.toMutableMap()
        val warnings = mutableListOf<String>()
        bundles.filter { it.deviceId != localDeviceId }.forEach { bundle ->
            val validKey = runCatching { Base64.decode(bundle.identityKey) }.getOrNull()?.takeIf { it.size == 32 }
            if (validKey == null) {
                warnings += "device ${bundle.deviceId.value} published a malformed key; ignored"
                return@forEach
            }
            val existing = pins[bundle.deviceId]
            when {
                existing == null -> pins[bundle.deviceId] = PinnedDevice(
                    deviceId = bundle.deviceId,
                    identityKey = bundle.identityKey,
                    label = bundle.label,
                    firstSeenMs = nowMs,
                    approved = false,
                )
                existing.identityKey != bundle.identityKey -> warnings +=
                    "key change: \"${existing.label}\" now presents a different key. " +
                    "Its records are ignored until you forget the device and let it re-pair."
                existing.label != bundle.label -> pins[bundle.deviceId] = existing.copy(label = bundle.label)
            }
        }
        save(pins.values.toList(), nowMs)
        val all = pins.values.sortedBy { it.firstSeenMs }
        return Reconciled(all.filter { it.approved }, warnings, all.filterNot { it.approved })
    }

    fun forget(deviceId: DeviceId, nowMs: Long) {
        save(pinned().filterNot { it.deviceId == deviceId }, nowMs)
    }

    private fun save(pins: List<PinnedDevice>, nowMs: Long) {
        store.upsertSetting(
            Setting(
                key = KEY,
                value = json.encodeToString(ListSerializer(PinnedDevice.serializer()), pins).encodeToByteArray(),
                updatedAtMs = nowMs,
                updatedDayUtc = UtcDay.dayOf(nowMs),
                deviceId = localDeviceId,
            ),
        )
    }

    companion object {
        const val KEY = "sync.devices.pinned"
        private val json = Json { ignoreUnknownKeys = true }

        fun encodeKey(raw: ByteArray): String = Base64.encode(raw)
    }
}

/**
 * A sync transport bound to one account: what the apps' sync pass needs
 * beyond [SyncTransport]. XMPP implements it; tests use an in-memory one.
 */
interface AccountTransport : SyncTransport {
    /** Log in once, throwing a user-presentable error on failure. */
    suspend fun verifyLogin()

    fun deviceDirectory(): DeviceDirectory
}
