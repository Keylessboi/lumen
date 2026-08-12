package dev.lumen.core.export

import dev.lumen.core.model.AppDayRollup
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.Setting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The M5 export contract.
 *
 * `docs/plan.md` decision E makes this file the recovery path for provider
 * death and for OMEMO 2's new-device problem — so a silent failure here is a
 * user deleting their source data believing they have a backup. Everything
 * below is checkable without Argon2, which is the point of splitting the
 * framing from the crypto.
 */
class ExportTest {

    private val device = DeviceId("11111111-2222-3333-4444-555555555555")

    /**
     * Deterministic stand-in for real crypto: XOR with a key stretched from
     * the passphrase, plus a trailing tag so authentication can fail.
     *
     * NOT secure and not meant to be. It exercises the exact seam a real
     * implementation fills, so the framing, versioning and error paths are
     * tested without pulling Argon2 into commonTest.
     */
    private class FakeCrypto(private var seed: Int = 1) : ExportCrypto {
        override val nonceBytes = 24

        override fun deriveKey(passphrase: CharArray, params: KdfParams): ByteArray =
            ByteArray(params.keyLengthBytes) { i ->
                (passphrase.sumOf { it.code } + params.salt.sumOf { it.toInt() } + i).toByte()
            }

        override fun seal(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
            val body = ByteArray(plaintext.size) { i ->
                (plaintext[i].toInt() xor key[i % key.size].toInt()).toByte()
            }
            return body + tag(key, body)
        }

        override fun open(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? {
            if (ciphertext.size < TAG) return null
            val body = ciphertext.copyOfRange(0, ciphertext.size - TAG)
            val given = ciphertext.copyOfRange(ciphertext.size - TAG, ciphertext.size)
            if (!given.contentEquals(tag(key, body))) return null
            return ByteArray(body.size) { i -> (body[i].toInt() xor key[i % key.size].toInt()).toByte() }
        }

        override fun randomBytes(count: Int): ByteArray = ByteArray(count) { (seed++ * 31 + it).toByte() }

        /**
         * Each tag byte depends DIRECTLY on a distinct key byte.
         *
         * Two earlier attempts collided. A plain `key.sum()` collides
         * trivially, and a `h = h * 31 + b` polynomial does too: keys that
         * differ by a constant delta produce tags differing by
         * `delta * (31^n + ... + 31^0)`, which is 0 mod 256 for these sizes —
         * so two different passphrases authenticated each other's files. The
         * fake needs to be strong enough not to fake a pass.
         */
        private fun tag(key: ByteArray, body: ByteArray) = ByteArray(TAG) { i ->
            var h = key[i % key.size].toInt() * 31 + i * 17
            // Cover the CIPHERTEXT too. An AEAD authenticates what it
            // encrypted, so any modification fails the tag; a fake that
            // covers only the key happily authenticates a tampered file and
            // hands back garbage, which the caller then reports as a
            // malformed backup instead of a tampered one. The test double has
            // to model the contract it stands in for, or it fakes a pass.
            body.forEachIndexed { idx, b -> h = h * 33 + (b.toInt() xor idx) }
            h.toByte()
        }

        private companion object { const val TAG = 16 }
    }

    private fun service() = ExportService(FakeCrypto())

    private fun payload() = ExportPayload(
        rollups = listOf(
            AppDayRollup(device, "2026-06-15", AppKey("com.apple.Safari"), 7_200_000, "Browsing"),
            AppDayRollup(device, "2026-06-15", AppKey("com.apple.Terminal"), 3_600_000),
        ),
        settings = listOf(Setting("nudge.break", byteArrayOf(1), 1_000L, "2026-06-15", device)),
        deviceKeys = listOf(
            ExportedDeviceKey(device, "Laptop", byteArrayOf(1, 2, 3), byteArrayOf(9, 9, 9)),
        ),
    )

    // ---- the round trip ----

    @Test
    fun `an export restores to exactly what went in`() {
        // The whole promise of the file. Relies on the value equality added
        // in #19 — before that, this assertion could not be written.
        val original = payload()
        val file = service().export(original, "correct horse".toCharArray(), device, 1_000L)
        val restored = service().import(file, "correct horse".toCharArray())

        assertTrue(restored is ExportResult.Success, "import failed: $restored")
        assertEquals(original, (restored as ExportResult.Success).value)
    }

    @Test
    fun `an empty export round-trips`() {
        // A user with no history still gets a valid backup, not a broken file.
        val file = service().export(ExportPayload(), "pw".toCharArray(), device, 1L)
        val restored = service().import(file, "pw".toCharArray())
        assertEquals(ExportPayload(), (restored as ExportResult.Success).value)
    }

    @Test
    fun `device private keys survive, so a restored device resumes its identity`() {
        val file = service().export(payload(), "pw".toCharArray(), device, 1L)
        val restored = (service().import(file, "pw".toCharArray()) as ExportResult.Success).value
        val key = restored.deviceKeys.single()
        assertEquals(device, key.deviceId)
        assertTrue(byteArrayOf(9, 9, 9).contentEquals(key.privateKey))
    }

    // ---- the passphrase ----

    @Test
    fun `a wrong passphrase fails as wrong-or-tampered, never as empty data`() {
        // The dangerous failure is returning an empty payload: a user would
        // see "restored" and nothing there.
        val file = service().export(payload(), "right".toCharArray(), device, 1L)
        val result = service().import(file, "wrong".toCharArray())
        assertEquals(ExportError.WrongPassphraseOrTampered, (result as ExportResult.Failure).error)
    }

    @Test
    fun `a modified ciphertext is rejected`() {
        val file = service().export(payload(), "pw".toCharArray(), device, 1L)
        val tampered = file.copy(
            ciphertext = file.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() },
        )
        val result = service().import(tampered, "pw".toCharArray())
        assertTrue(result is ExportResult.Failure)
    }

    @Test
    fun `wrong passphrase and tampering are indistinguishable to the caller`() {
        // Deliberate: saying which would tell an attacker whether they had
        // guessed the passphrase.
        val file = service().export(payload(), "pw".toCharArray(), device, 1L)
        val wrongPass = service().import(file, "nope".toCharArray())
        val tampered = service().import(
            file.copy(ciphertext = file.ciphertext.copyOf().also { it[0] = 0 }),
            "pw".toCharArray(),
        )
        assertEquals(
            (wrongPass as ExportResult.Failure).error,
            (tampered as ExportResult.Failure).error,
        )
    }

    // ---- salt and nonce hygiene ----

    @Test
    fun `two exports never share a salt or a nonce`() {
        // Reusing either across two files made with the same passphrase turns
        // two safe files into one broken pair.
        val svc = service()
        val a = svc.export(payload(), "pw".toCharArray(), device, 1L)
        val b = svc.export(payload(), "pw".toCharArray(), device, 2L)
        assertTrue(!a.header.kdf.salt.contentEquals(b.header.kdf.salt), "salt reused")
        assertTrue(!a.header.nonce.contentEquals(b.header.nonce), "nonce reused")
        assertNotEquals(a.ciphertext.toList(), b.ciphertext.toList())
    }

    @Test
    fun `the salt is recorded in the file, or the export could never be opened`() {
        val file = service().export(payload(), "pw".toCharArray(), device, 1L)
        assertEquals(KdfParams.SALT_BYTES, file.header.kdf.salt.size)
        assertEquals(24, file.header.nonce.size)
    }

    // ---- the header, which is read before the passphrase ----

    @Test
    fun `a newer format version is refused rather than half-read`() {
        val file = service().export(payload(), "pw".toCharArray(), device, 1L)
        val future = file.copy(header = file.header.copy(formatVersion = 2))
        val result = service().import(future, "pw".toCharArray())
        val error = (result as ExportResult.Failure).error
        assertTrue(error is ExportError.UnsupportedVersion)
        assertEquals(2, error.found)
    }

    @Test
    fun `a non-Argon2id KDF is refused`() {
        val file = service().export(payload(), "pw".toCharArray(), device, 1L)
        val downgraded = file.copy(
            header = file.header.copy(kdf = file.header.kdf.copy(algorithm = "pbkdf2")),
        )
        assertTrue(
            (service().import(downgraded, "pw".toCharArray()) as ExportResult.Failure)
                .error is ExportError.UnsupportedKdf,
        )
    }

    @Test
    fun `KDF parameters weak enough to brute-force are refused`() {
        // The parameters travel IN the file, so an attacker who can modify it
        // could ask for a derivation cheap enough to attack offline. Refusing
        // is what makes choosing them pointless.
        val file = service().export(payload(), "pw".toCharArray(), device, 1L)
        val weakened = listOf(
            file.header.kdf.copy(memoryKib = 8),
            file.header.kdf.copy(iterations = 1),
            file.header.kdf.copy(salt = ByteArray(2)),
            file.header.kdf.copy(parallelism = 0),
            file.header.kdf.copy(keyLengthBytes = 4),
        )
        weakened.forEach { kdf ->
            val result = service().import(file.copy(header = file.header.copy(kdf = kdf)), "pw".toCharArray())
            assertTrue(
                (result as ExportResult.Failure).error is ExportError.WeakKdfParams,
                "accepted weak params: $kdf",
            )
        }
    }

    @Test
    fun `the header is validated BEFORE the passphrase is used`() {
        // Otherwise a hostile file chooses how much work the reader does, and
        // a user with the wrong file waits on a deliberately slow derivation
        // to be told so.
        var derivations = 0
        val counting = object : ExportCrypto by FakeCrypto() {
            override fun deriveKey(passphrase: CharArray, params: KdfParams): ByteArray {
                derivations++
                return FakeCrypto().deriveKey(passphrase, params)
            }
        }
        val file = service().export(payload(), "pw".toCharArray(), device, 1L)
        ExportService(counting).import(
            file.copy(header = file.header.copy(formatVersion = 99)),
            "pw".toCharArray(),
        )
        assertEquals(0, derivations, "derived a key for a file it was going to reject")
    }

    // ---- the file on disk ----

    @Test
    fun `the file round-trips through its serialized form`() {
        val file = service().export(payload(), "pw".toCharArray(), device, 1L)
        val decoded = ExportCodec.decodeFile(ExportCodec.encodeFile(file))
        assertEquals(file, (decoded as ExportResult.Success).value)
    }

    @Test
    fun `the header is readable without the passphrase`() {
        // A reader must know the KDF parameters before it can derive the key,
        // so they cannot live inside the ciphertext.
        val file = service().export(payload(), "pw".toCharArray(), device, 12_345L)
        val text = ExportCodec.encodeFile(file)
        assertTrue(text.contains("\"formatVersion\":1"))
        assertTrue(text.contains("argon2id"))
        assertTrue(text.contains("12345"))
    }

    @Test
    fun `an unrelated file is reported as not a backup`() {
        listOf("", "{}", "not json at all", """{"header":{}}""").forEach {
            val result = ExportCodec.decodeFile(it)
            assertTrue((result as ExportResult.Failure).error is ExportError.Malformed, "accepted: $it")
        }
    }

    @Test
    fun `every error explains itself in plain language`() {
        // docs/design-spec.md: plain declarative sentences, no jargon. These
        // are read by someone whose sync provider just died.
        listOf(
            ExportError.WrongPassphraseOrTampered,
            ExportError.UnsupportedVersion(2, 1),
            ExportError.UnsupportedKdf("pbkdf2"),
            ExportError.WeakKdfParams("memory=8KiB"),
            ExportError.Malformed("bad"),
        ).forEach {
            val message = it.message()
            assertTrue(message.isNotBlank() && message.first().isUpperCase() && message.endsWith("."))
        }
    }
}
