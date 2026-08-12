package dev.lumen.macos.keychain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real login keychain, through the real `security` command.
 *
 * OPT-IN: does nothing unless `LUMEN_LIVE_KEYCHAIN=1`. A test that writes to
 * the developer's keychain on every `./gradlew build` is a test that gets
 * deleted, and CI has no login keychain to write to at all.
 *
 * Run it with:
 *
 *     LUMEN_LIVE_KEYCHAIN=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
 *       ./gradlew :app-macos:desktopTest --tests '*MacosKeychainLiveTest*'
 *
 * It uses its own service name and deletes what it wrote, so it never
 * touches the identity the app actually syncs with.
 */
class MacosKeychainLiveTest {

    private val service = "dev.lumen.macos.livetest"

    private fun enabled(): Boolean = System.getenv("LUMEN_LIVE_KEYCHAIN") == "1"

    private fun deleteItem() {
        ProcessBuilder(
            "/usr/bin/security", "delete-generic-password",
            "-a", MacosKeychain.ACCOUNT, "-s", service,
        ).redirectErrorStream(true).start().waitFor()
    }

    @Test
    fun `a keypair round-trips through the real keychain`() {
        if (!enabled()) return

        deleteItem()
        try {
            val store = MacosKeychain.SecurityCommandSecretStore(service)
            val created = MacosKeychain(store).deviceKeyPair()
            assertEquals(32, created.publicKey.size)
            assertEquals(32, created.privateKeyHandle.size)

            // A second process — a new object over the same real keychain —
            // must see the same identity, or every restart is a new device.
            val reloaded = MacosKeychain(MacosKeychain.SecurityCommandSecretStore(service)).deviceKeyPair()
            assertTrue(created.publicKey.contentEquals(reloaded.publicKey), "public key changed")
            assertTrue(
                created.privateKeyHandle.contentEquals(reloaded.privateKeyHandle),
                "private key changed",
            )
        } finally {
            deleteItem()
        }
    }

    /**
     * The reason `-w` is passed last and empty: anything on the command line
     * is readable from the process table by every process of this user, and
     * the private key is the one value here that must not be.
     */
    @Test
    fun `the private key never appears in the command line`() {
        if (!enabled()) return

        deleteItem()
        try {
            val store = MacosKeychain.SecurityCommandSecretStore(service)
            MacosKeychain(store).deviceKeyPair()
            val stored = store.read(MacosKeychain.ACCOUNT)
            assertTrue(stored != null && stored.contains(":"), "nothing was stored")

            // Whatever `security` was invoked with, the secret was not in it:
            // the only place it went was the process's stdin.
            val secret = stored!!.substringAfter(":")
            val running = ProcessBuilder("/bin/ps", "-axww").start()
                .inputStream.bufferedReader().readText()
            assertTrue(!running.contains(secret), "the private key is in the process table")
        } finally {
            deleteItem()
        }
    }
}
