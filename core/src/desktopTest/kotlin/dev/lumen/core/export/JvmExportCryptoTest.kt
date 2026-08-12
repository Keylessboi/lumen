package dev.lumen.core.export

import dev.lumen.core.model.AppDayRollup
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.Setting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real crypto, against the real library.
 *
 * The framing is covered in commonTest with a fake; this is the other half —
 * that Argon2id and AES-GCM actually behave the way the format assumes. A
 * mistake here only shows up when a user tries to restore.
 *
 * Cost parameters are reduced where a test does not depend on them: at the
 * shipped 64 MiB each derivation takes a noticeable fraction of a second and
 * these run on every build.
 */
class JvmExportCryptoTest {

    private val crypto = JvmExportCrypto()
    private val device = DeviceId("11111111-2222-3333-4444-555555555555")

    private fun cheapParams(salt: ByteArray = ByteArray(16) { it.toByte() }) = KdfParams(
        memoryKib = KdfParams.MIN_MEMORY_KIB,
        iterations = KdfParams.MIN_ITERATIONS,
        parallelism = 1,
        salt = salt,
    )

    // ---- the KDF ----

    @Test
    fun `the same passphrase and salt derive the same key`() {
        // Non-negotiable: without this an export can never be reopened.
        val a = crypto.deriveKey("correct horse".toCharArray(), cheapParams())
        val b = crypto.deriveKey("correct horse".toCharArray(), cheapParams())
        assertTrue(a.contentEquals(b))
        assertEquals(32, a.size)
    }

    @Test
    fun `a different passphrase derives a different key`() {
        assertTrue(
            !crypto.deriveKey("one".toCharArray(), cheapParams())
                .contentEquals(crypto.deriveKey("two".toCharArray(), cheapParams())),
        )
    }

    @Test
    fun `a different salt derives a different key from the same passphrase`() {
        // Why the salt is per-export: two files made with one passphrase must
        // not share a key.
        val a = crypto.deriveKey("pw".toCharArray(), cheapParams(ByteArray(16) { 1 }))
        val b = crypto.deriveKey("pw".toCharArray(), cheapParams(ByteArray(16) { 2 }))
        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun `changing any cost parameter changes the key`() {
        // Which is exactly why the parameters are recorded in the file rather
        // than assumed by the reader.
        val base = cheapParams()
        val key = crypto.deriveKey("pw".toCharArray(), base)
        listOf(
            base.copy(iterations = base.iterations + 1),
            base.copy(memoryKib = base.memoryKib * 2),
            base.copy(parallelism = 2),
            base.copy(keyLengthBytes = 16),
        ).forEach {
            assertTrue(
                !key.contentEquals(crypto.deriveKey("pw".toCharArray(), it)),
                "key unchanged after altering $it",
            )
        }
    }

    @Test
    fun `a unicode passphrase works`() {
        // Advice pushes people toward long memorable strings, and people type
        // in their own language and in emoji.
        val pass = "правильная лошадь 🐴".toCharArray()
        assertTrue(
            crypto.deriveKey(pass, cheapParams()).contentEquals(crypto.deriveKey(pass, cheapParams())),
        )
    }

    @Test
    fun `a non-Argon2id parameter set is refused rather than silently substituted`() {
        val wrong = cheapParams().copy(algorithm = "pbkdf2")
        assertTrue(
            runCatching { crypto.deriveKey("pw".toCharArray(), wrong) }.isFailure,
            "derived a key for a KDF this implementation does not provide",
        )
    }

    // ---- the AEAD ----

    @Test
    fun `sealed data opens back to itself`() {
        val key = crypto.deriveKey("pw".toCharArray(), cheapParams())
        val nonce = crypto.randomBytes(crypto.nonceBytes)
        val plaintext = "where your attention went".encodeToByteArray()

        val opened = crypto.open(crypto.seal(plaintext, key, nonce), key, nonce)
        assertTrue(plaintext.contentEquals(assertNotNull(opened)))
    }

    @Test
    fun `an empty payload seals and opens`() {
        val key = crypto.deriveKey("pw".toCharArray(), cheapParams())
        val nonce = crypto.randomBytes(crypto.nonceBytes)
        assertEquals(0, assertNotNull(crypto.open(crypto.seal(ByteArray(0), key, nonce), key, nonce)).size)
    }

    @Test
    fun `the wrong key returns null rather than garbage`() {
        val nonce = crypto.randomBytes(crypto.nonceBytes)
        val sealed = crypto.seal(
            "secret".encodeToByteArray(),
            crypto.deriveKey("right".toCharArray(), cheapParams()),
            nonce,
        )
        assertNull(crypto.open(sealed, crypto.deriveKey("wrong".toCharArray(), cheapParams()), nonce))
    }

    @Test
    fun `any single-bit modification to the ciphertext is detected`() {
        // The property that makes tampering indistinguishable from a wrong
        // passphrase, and makes both safe.
        val key = crypto.deriveKey("pw".toCharArray(), cheapParams())
        val nonce = crypto.randomBytes(crypto.nonceBytes)
        val sealed = crypto.seal("history".encodeToByteArray(), key, nonce)

        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull(crypto.open(tampered, key, nonce), "byte $i was modified undetected")
        }
    }

    @Test
    fun `a truncated ciphertext returns null instead of throwing`() {
        val key = crypto.deriveKey("pw".toCharArray(), cheapParams())
        val nonce = crypto.randomBytes(crypto.nonceBytes)
        val sealed = crypto.seal("history".encodeToByteArray(), key, nonce)
        assertNull(crypto.open(sealed.copyOf(sealed.size / 2), key, nonce))
        assertNull(crypto.open(ByteArray(0), key, nonce))
    }

    @Test
    fun `the wrong nonce fails to open`() {
        val key = crypto.deriveKey("pw".toCharArray(), cheapParams())
        val sealed = crypto.seal("x".encodeToByteArray(), key, crypto.randomBytes(crypto.nonceBytes))
        assertNull(crypto.open(sealed, key, crypto.randomBytes(crypto.nonceBytes)))
    }

    @Test
    fun `random bytes are the requested size and do not repeat`() {
        assertEquals(16, crypto.randomBytes(16).size)
        assertEquals(50, List(50) { crypto.randomBytes(16).toList() }.distinct().size)
    }

    // ---- the whole file, for real ----

    @Test
    fun `a real export round-trips at the shipped default parameters`() {
        // The only test that runs Argon2id at 64 MiB — the cost a user
        // actually pays. Slower than the rest, and worth it: this is the
        // end-to-end promise the file makes.
        val service = ExportService(crypto)
        val payload = ExportPayload(
            rollups = listOf(AppDayRollup(device, "2026-06-15", AppKey("com.apple.Safari"), 7_200_000)),
            settings = listOf(Setting("nudge.break", byteArrayOf(1), 1_000L, "2026-06-15", device)),
            deviceKeys = listOf(ExportedDeviceKey(device, "Laptop", byteArrayOf(1, 2), byteArrayOf(3, 4))),
        )

        val file = service.export(payload, "a real passphrase".toCharArray(), device, 1_000L)
        // Through its serialized form, as it would actually reach disk.
        val parsed = ExportCodec.decodeFile(ExportCodec.encodeFile(file))
        assertTrue(parsed is ExportResult.Success, "file did not parse: $parsed")

        val restored = service.import((parsed as ExportResult.Success).value, "a real passphrase".toCharArray())
        assertEquals(payload, (restored as ExportResult.Success).value)
    }

    @Test
    fun `a real export refuses the wrong passphrase`() {
        val service = ExportService(crypto)
        val file = service.export(ExportPayload(), "right".toCharArray(), device, 1L)
        assertEquals(
            ExportError.WrongPassphraseOrTampered,
            (service.import(file, "wrong".toCharArray()) as ExportResult.Failure).error,
        )
    }
}
