package dev.lumen.core.export

import kotlinx.serialization.json.Json

/**
 * Reading and writing the export file, minus the cryptography.
 *
 * Split from the crypto deliberately: the framing rules — version handling,
 * KDF-parameter validation, JSON shape — are platform-neutral and testable
 * without Argon2 or libsodium, and they are where the security-relevant
 * *decisions* live. The actual key derivation and AEAD are a platform seam
 * ([ExportCrypto]).
 */
object ExportCodec {

    /**
     * Strict by construction. A v2 file must fail on a v1 reader rather than
     * half-load; tolerating unknown keys would make that impossible to detect
     * for additive changes, which are exactly the ones a version bump covers.
     */
    val json: Json = Json {
        // Strict: a v2 file must fail on a v1 reader rather than half-load.
        ignoreUnknownKeys = false
        // Defaults MUST be written. kotlinx omits a field equal to its
        // default, so formatVersion — whose default IS the current version —
        // vanished from every file, and a v1 export contained no version at
        // all. A future reader would then have to guess whether an absent
        // field meant v1 or a corrupted header, which is exactly the guessing
        // the versioned envelope exists to prevent. Caught by a test
        // asserting the header is readable, not by reading the code.
        encodeDefaults = true
    }

    fun encodeFile(file: ExportFile): String = json.encodeToString(ExportFile.serializer(), file)

    /**
     * Parse and validate an export file's cleartext framing.
     *
     * Everything here happens **before** the passphrase is used, so a
     * malformed or hostile file is rejected without the user waiting on a
     * deliberately expensive key derivation — and without a tampered header
     * getting to choose that derivation's cost.
     */
    fun decodeFile(text: String): ExportResult<ExportFile> {
        val file = try {
            json.decodeFromString(ExportFile.serializer(), text)
        } catch (e: Exception) {
            return ExportResult.Failure(
                ExportError.Malformed(e.message ?: "not a Lumen export file"),
            )
        }
        return validate(file.header).let { error ->
            if (error != null) ExportResult.Failure(error) else ExportResult.Success(file)
        }
    }

    /**
     * Header checks, in the order a reader should care about them.
     *
     * Returns null when the header is acceptable.
     */
    fun validate(header: ExportHeader): ExportError? {
        // Version first: on an unknown version every other field's meaning is
        // unknown too, so validating them would be theatre.
        if (header.formatVersion != ExportHeader.CURRENT_FORMAT_VERSION) {
            return ExportError.UnsupportedVersion(
                found = header.formatVersion,
                supported = ExportHeader.CURRENT_FORMAT_VERSION,
            )
        }
        if (header.nonce.isEmpty()) return ExportError.Malformed("missing nonce")

        val kdf = header.kdf
        if (kdf.algorithm != KdfParams.ARGON2ID) {
            // Not merely unsupported: docs/e2ee.md §7 names Argon2id
            // specifically. A file asking for PBKDF2 is either from another
            // product or is an attempt to downgrade the KDF.
            return ExportError.UnsupportedKdf(kdf.algorithm)
        }

        // The parameters travel IN the file, so an attacker who can modify it
        // could ask for memory=8, iterations=1 — a key derivation cheap enough
        // to brute-force — and a reader that simply obeyed would comply.
        // Refusing weak parameters is what makes that attack pointless.
        if (kdf.memoryKib < KdfParams.MIN_MEMORY_KIB ||
            kdf.iterations < KdfParams.MIN_ITERATIONS ||
            kdf.salt.size < KdfParams.MIN_SALT_BYTES ||
            kdf.parallelism < 1 ||
            kdf.keyLengthBytes < 16
        ) {
            return ExportError.WeakKdfParams(
                "memory=${kdf.memoryKib}KiB iterations=${kdf.iterations} " +
                    "salt=${kdf.salt.size}B parallelism=${kdf.parallelism} " +
                    "keyLength=${kdf.keyLengthBytes}B",
            )
        }
        return null
    }

    fun encodePayload(payload: ExportPayload): ByteArray =
        json.encodeToString(ExportPayload.serializer(), payload).encodeToByteArray()

    fun decodePayload(bytes: ByteArray): ExportResult<ExportPayload> = try {
        ExportResult.Success(json.decodeFromString(ExportPayload.serializer(), bytes.decodeToString()))
    } catch (e: Exception) {
        // Reached only after successful decryption, so this is a genuinely
        // corrupt or future-shaped payload rather than a wrong passphrase —
        // which the AEAD would have caught first. Worth distinguishing,
        // because the advice to the user differs.
        ExportResult.Failure(ExportError.Malformed(e.message ?: "unreadable export contents"))
    }
}

/** Success or a named failure. Never a null that a caller can mistake for empty. */
sealed interface ExportResult<out T> {
    data class Success<T>(val value: T) : ExportResult<T>
    data class Failure(val error: ExportError) : ExportResult<Nothing>
}

/**
 * Why an export could not be read.
 *
 * Distinct cases rather than one message, because the honest advice differs:
 * a wrong passphrase is retryable, an unsupported version means "use a newer
 * Lumen", and weak parameters mean the file should be treated as hostile.
 */
sealed interface ExportError {
    /** The passphrase did not decrypt the file, or it was modified. */
    data object WrongPassphraseOrTampered : ExportError

    data class UnsupportedVersion(val found: Int, val supported: Int) : ExportError

    data class UnsupportedKdf(val algorithm: String) : ExportError

    /** Parameters below the floor Lumen will derive a key with. */
    data class WeakKdfParams(val detail: String) : ExportError

    data class Malformed(val detail: String) : ExportError

    /** A human-facing sentence. Plain, no jargon, per docs/design-spec.md. */
    fun message(): String = when (this) {
        WrongPassphraseOrTampered ->
            "That passphrase didn't open this file. If it's definitely right, " +
                "the file may have been changed or damaged since it was made."
        is UnsupportedVersion ->
            "This backup was made by a newer version of Lumen (format $found; " +
                "this version reads $supported). Update Lumen and try again."
        is UnsupportedKdf ->
            "This file uses an unexpected encryption setting ($algorithm), so " +
                "Lumen won't open it."
        is WeakKdfParams ->
            "This file asks Lumen to unlock it with settings weaker than it " +
                "will accept, so it won't be opened."
        is Malformed -> "This doesn't look like a Lumen backup file."
    }
}
