package dev.lumen.core.export

/**
 * The cryptographic half of the export — a seam, because Argon2id and AEAD
 * come from different libraries on JVM and Android.
 *
 * Kept deliberately small: everything that can be decided without crypto
 * (versioning, KDF-parameter floors, JSON shape) lives in [ExportCodec] and
 * is tested there, so a platform implementation has as little rope as
 * possible.
 */
interface ExportCrypto {

    /**
     * Derive a key from a passphrase using the file's own parameters.
     *
     * [params] comes from the file being read, which is why [ExportCodec]
     * validates it first: an unvalidated header could ask for a derivation
     * cheap enough to brute-force, or expensive enough to hang the app.
     */
    fun deriveKey(passphrase: CharArray, params: KdfParams): ByteArray

    /** Encrypt with an AEAD. The nonce must be fresh for every export. */
    fun seal(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray

    /**
     * Decrypt and authenticate.
     *
     * Returns null when authentication fails — which is both a wrong
     * passphrase and a tampered file, and the two are indistinguishable here
     * by design: saying which would tell an attacker whether they had the
     * right passphrase.
     */
    fun open(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray?

    /** Cryptographically secure random bytes, for salts and nonces. */
    fun randomBytes(count: Int): ByteArray

    /** Nonce width this AEAD requires. */
    val nonceBytes: Int
}

/**
 * Writes and reads exports, given an [ExportCrypto].
 *
 * Platform-neutral: the whole flow — generate salt and nonce, derive, seal,
 * frame — is the same everywhere, so it is written once here rather than per
 * platform. That is the same lesson the collectors kept teaching.
 */
class ExportService(
    private val crypto: ExportCrypto,
    private val defaults: KdfParams = KdfParams(
        memoryKib = KdfParams.DEFAULT_MEMORY_KIB,
        iterations = KdfParams.DEFAULT_ITERATIONS,
        parallelism = KdfParams.DEFAULT_PARALLELISM,
        salt = ByteArray(0), // replaced per export; never reused
    ),
) {

    /**
     * Produce an encrypted export.
     *
     * A fresh salt AND a fresh nonce per export. Reusing either across two
     * exports made with the same passphrase is the classic way to turn two
     * safe files into one broken pair.
     */
    fun export(
        payload: ExportPayload,
        passphrase: CharArray,
        exportedByDevice: dev.lumen.core.model.DeviceId,
        nowMs: Long,
    ): ExportFile {
        val salt = crypto.randomBytes(KdfParams.SALT_BYTES)
        val nonce = crypto.randomBytes(crypto.nonceBytes)
        val params = defaults.copy(salt = salt)

        val key = crypto.deriveKey(passphrase, params)
        val ciphertext = crypto.seal(ExportCodec.encodePayload(payload), key, nonce)

        return ExportFile(
            header = ExportHeader(
                exportedAtMs = nowMs,
                exportedByDevice = exportedByDevice,
                kdf = params,
                nonce = nonce,
            ),
            ciphertext = ciphertext,
        )
    }

    /**
     * Read an export back.
     *
     * Validates the header before deriving anything, so a hostile file cannot
     * choose the cost of the work — and a user with the wrong file does not
     * wait on a deliberately slow derivation to be told so.
     */
    fun import(file: ExportFile, passphrase: CharArray): ExportResult<ExportPayload> {
        ExportCodec.validate(file.header)?.let { return ExportResult.Failure(it) }

        val key = crypto.deriveKey(passphrase, file.header.kdf)
        val plaintext = crypto.open(file.ciphertext, key, file.header.nonce)
            ?: return ExportResult.Failure(ExportError.WrongPassphraseOrTampered)

        return ExportCodec.decodePayload(plaintext)
    }
}
