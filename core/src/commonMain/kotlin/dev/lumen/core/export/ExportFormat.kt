package dev.lumen.core.export

import dev.lumen.core.model.AppDayRollup
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.Setting
import kotlinx.serialization.Serializable

/**
 * The Argon2id encrypted export — M5, decision E in `docs/plan.md`.
 *
 * This one file retires two of the top five risks: provider death (the user
 * can leave a dead XMPP server with their history intact) and OMEMO 2's
 * new-device problem post-MVP. `docs/e2ee.md` §7 is its normative spec.
 *
 * ## The file, in layers
 *
 * ```
 * ExportFile          cleartext header + ciphertext   <- what lands on disk
 *   └ ExportHeader    version, KDF params, nonce      <- must be readable
 *                                                        WITHOUT the passphrase
 *   └ ciphertext of ExportPayload (JSON)
 *        └ rollups, settings, events, device keys
 * ```
 *
 * The header is deliberately cleartext. A reader has to know which KDF
 * parameters to use *before* it can derive the key to decrypt anything, so
 * putting them inside the ciphertext is a chicken-and-egg problem. §7
 * requires them recorded "so future readers do not need to guess", and a
 * future reader includes a different Lumen build on a different device.
 *
 * ## What it contains, stated plainly
 *
 * Decrypted history **and device private keys**. `docs/e2ee.md` §7: "It is
 * the most sensitive artifact Lumen ever produces and the UI must say so at
 * the moment of creation." [ExportPayload.deviceKeys] exists so a restored
 * device can resume a sync identity rather than appear as a stranger — but it
 * is also why this file is worth more to an attacker than the device it came
 * from.
 */
@Serializable
data class ExportFile(
    val header: ExportHeader,
    /** Argon2id-derived-key encrypted [ExportPayload], serialized as JSON. */
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExportFile) return false
        return header == other.header && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int = 31 * header.hashCode() + ciphertext.contentHashCode()
}

/**
 * Cleartext header: everything a reader needs before it has the passphrase.
 *
 * [formatVersion] bumps are **additive-only**, and a reader MUST reject an
 * unknown version rather than best-effort parse — the same rule as the E2EE
 * envelope (`docs/e2ee.md` §6), pinned for the export by Agent A in
 * discussion #12. A half-loaded restore that looks like success is worse than
 * a refusal, because the user deletes the source believing they are safe.
 *
 * The KDF parameters are recorded rather than assumed because they are
 * *tuned per device*: §7 requires interactive-grade parameters, and a phone
 * and a laptop have very different budgets. An export written on a laptop
 * must still open on a phone, which means the phone has to be told what the
 * laptop used.
 */
@Serializable
data class ExportHeader(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val exportedAtMs: Long,
    /** Which device wrote it. Display only; never a trust decision. */
    val exportedByDevice: DeviceId,
    val kdf: KdfParams,
    /** Nonce for the payload cipher. Fresh per export, never reused. */
    val nonce: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExportHeader) return false
        return formatVersion == other.formatVersion &&
            exportedAtMs == other.exportedAtMs &&
            exportedByDevice == other.exportedByDevice &&
            kdf == other.kdf &&
            nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int {
        var result = formatVersion
        result = 31 * result + exportedAtMs.hashCode()
        result = 31 * result + exportedByDevice.hashCode()
        result = 31 * result + kdf.hashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }

    companion object {
        const val CURRENT_FORMAT_VERSION: Int = 1
    }
}

/**
 * Argon2id parameters, recorded in the file.
 *
 * Argon2**id**, not PBKDF2 or scrypt, and not Argon2i or Argon2d — §7 names
 * the variant. id is the hybrid: data-independent in the first pass (so it
 * resists side-channel attacks on the memory access pattern) and
 * data-dependent afterwards (so it resists time-memory tradeoffs). It is the
 * variant RFC 9106 recommends when you have no reason to prefer another.
 *
 * [memoryKib] is the parameter that actually costs an attacker money — GPUs
 * and ASICs are limited by memory bandwidth, not by iterations, which is why
 * Argon2 exists at all. Raising [iterations] on a low memory setting buys far
 * less than it looks like it does.
 */
@Serializable
data class KdfParams(
    val algorithm: String = ARGON2ID,
    /** Memory cost in KiB. */
    val memoryKib: Int,
    /** Time cost: passes over memory. */
    val iterations: Int,
    /** Lanes. Above the device's core count this buys nothing. */
    val parallelism: Int,
    /** Per-export random salt. Never reused, never derived from the passphrase. */
    val salt: ByteArray,
    /** Derived key length in bytes. */
    val keyLengthBytes: Int = 32,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KdfParams) return false
        return algorithm == other.algorithm &&
            memoryKib == other.memoryKib &&
            iterations == other.iterations &&
            parallelism == other.parallelism &&
            keyLengthBytes == other.keyLengthBytes &&
            salt.contentEquals(other.salt)
    }

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + memoryKib
        result = 31 * result + iterations
        result = 31 * result + parallelism
        result = 31 * result + keyLengthBytes
        result = 31 * result + salt.contentHashCode()
        return result
    }

    companion object {
        const val ARGON2ID: String = "argon2id"

        /** RFC 9106 §4 second recommended option: 64 MiB, t=3, p=4. */
        const val DEFAULT_MEMORY_KIB: Int = 64 * 1024
        const val DEFAULT_ITERATIONS: Int = 3
        const val DEFAULT_PARALLELISM: Int = 4
        const val SALT_BYTES: Int = 16

        /**
         * Floors below which a file is rejected rather than opened.
         *
         * The parameters travel *in the file*, so a tampered header could ask
         * a reader to derive a key with memory=8 and iterations=1 — trivially
         * brute-forcible — and an obliging reader would comply. Refusing weak
         * parameters is what stops the attacker choosing them.
         *
         * Set below the defaults so an export written on a constrained device
         * still opens, but far above anything brute-forcible.
         */
        const val MIN_MEMORY_KIB: Int = 8 * 1024
        const val MIN_ITERATIONS: Int = 2
        const val MIN_SALT_BYTES: Int = 8
    }
}

/**
 * The decrypted contents.
 *
 * Rollups are the history that matters — they are kept forever, while events
 * prune at ~30 days and buckets at ~6 months (`docs/data-model.md`). Events
 * are included when present because a restore within the retention window
 * should not silently coarsen a user's recent data, but an export is not
 * expected to carry them for old periods.
 */
@Serializable
data class ExportPayload(
    val rollups: List<AppDayRollup> = emptyList(),
    val settings: List<Setting> = emptyList(),
    val events: List<FocusEvent> = emptyList(),
    /**
     * Device sync identities, so a restored device resumes rather than
     * appears as a stranger. The single most sensitive field in the file.
     */
    val deviceKeys: List<ExportedDeviceKey> = emptyList(),
)

/** A device's sync identity, as carried by an export. */
@Serializable
data class ExportedDeviceKey(
    val deviceId: DeviceId,
    val displayName: String,
    val publicKey: ByteArray,
    val privateKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExportedDeviceKey) return false
        return deviceId == other.deviceId &&
            displayName == other.displayName &&
            publicKey.contentEquals(other.publicKey) &&
            privateKey.contentEquals(other.privateKey)
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        return result
    }
}
