package dev.lumen.core.crypto

/**
 * Small named secrets: the sync account password, scrobbler session keys.
 *
 * These used to sit in the `settings` table in plaintext — next to the
 * usage data they protect, in a file every same-user process can read and
 * every backup tool copies. Platforms put them in the keyring instead
 * (Secret Service on Linux; app-private, Keystore-wrapped storage on
 * Android).
 */
interface SecretStore {
    fun get(name: String): String?
    fun put(name: String, value: String)
    fun remove(name: String)
}

/** Process-lifetime store for tests and for "the keyring said no". */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun get(name: String): String? = values[name]
    override fun put(name: String, value: String) { values[name] = value }
    override fun remove(name: String) { values.remove(name) }
}
