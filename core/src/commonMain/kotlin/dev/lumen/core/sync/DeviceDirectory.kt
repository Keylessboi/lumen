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
)

/**
 * Trust-on-first-use pinning for the account's devices (`docs/e2ee.md` §8:
 * fingerprint verification is advisory in v1).
 *
 * The first key a device id presents is pinned. A LATER, DIFFERENT key for
 * the same id is refused and reported, never silently re-pinned: that is
 * exactly what a provider injecting a rogue key would look like, and the
 * user is the only one who can tell it apart from a reinstall. They resolve
 * it by forgetting the device, after which the next key is pinned again.
 */
@OptIn(ExperimentalEncodingApi::class)
class DeviceTrust(
    private val store: LumenStore,
    private val localDeviceId: DeviceId,
) {
    data class Reconciled(
        val trusted: List<PinnedDevice>,
        val warnings: List<String>,
        /** Devices pinned by THIS reconcile — they have never been encrypted to. */
        val newlyPinned: List<DeviceId> = emptyList(),
    )

    fun pinned(): List<PinnedDevice> {
        val raw = store.setting(KEY)?.value?.decodeToString()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(PinnedDevice.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    /** Raw public key for a pinned device, or null. */
    fun keyFor(deviceId: DeviceId): ByteArray? =
        pinned().firstOrNull { it.deviceId == deviceId }?.let { Base64.decode(it.identityKey) }

    /**
     * Fold the directory's current bundles into the pin set. Our own bundle
     * is skipped (this device trusts its own keychain, not the server's copy
     * of it).
     */
    fun reconcile(bundles: List<DeviceBundle>, nowMs: Long): Reconciled {
        val pins = pinned().associateBy { it.deviceId }.toMutableMap()
        val warnings = mutableListOf<String>()
        val added = mutableListOf<DeviceId>()
        bundles.filter { it.deviceId != localDeviceId }.forEach { bundle ->
            val validKey = runCatching { Base64.decode(bundle.identityKey) }.getOrNull()?.takeIf { it.size == 32 }
            if (validKey == null) {
                warnings += "device ${bundle.deviceId.value} published a malformed key; ignored"
                return@forEach
            }
            val existing = pins[bundle.deviceId]
            when {
                existing == null -> {
                    pins[bundle.deviceId] = PinnedDevice(
                        deviceId = bundle.deviceId,
                        identityKey = bundle.identityKey,
                        label = bundle.label,
                        firstSeenMs = nowMs,
                    )
                    added += bundle.deviceId
                }
                existing.identityKey != bundle.identityKey -> warnings +=
                    "key change: \"${existing.label}\" now presents a different key. " +
                    "Its records are ignored until you forget the device and let it re-pair."
                existing.label != bundle.label -> pins[bundle.deviceId] = existing.copy(label = bundle.label)
            }
        }
        save(pins.values.toList(), nowMs)
        return Reconciled(pins.values.sortedBy { it.firstSeenMs }, warnings, added)
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
