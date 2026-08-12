package dev.lumen.transport

import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.RecordKind
import dev.lumen.core.model.SyncRecord
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

/**
 * MANUAL LIVE HARNESS — not a @Test.
 *
 * Proves the XMPP transport end-to-end against a REAL public provider from
 * the embedded list (the same flow monocles chat / cheogram offer: sign up
 * in-app via XEP-0077 IBR, then sync). Requires network access.
 *
 * Run from a scratch main:
 *   runXmppRoundTrip("jabber.fr", 5222)
 *
 * Creates a throwaway account via IBR, publishes two records, pulls them
 * back, verifies the round-trip, then deletes the account if the provider
 * supports it (Smack AccountManager.deleteAccount) — best-effort cleanup.
 */
fun runXmppRoundTrip(providerJid: String, port: Int = 5222) = runBlocking {
    val username = "lumen-test-${Random.nextInt(100000, 999999)}"
    val password = "lumen-test-${Random.nextBytes(8).joinToString("") { "%02x".format(it) }}"
    println("=== XMPP round-trip on $providerJid ===")
    println("registering $username@$providerJid via IBR (XEP-0077)...")

    // Registration needs a transport WITHOUT the account existing yet.
    val registrar = XmppTransport(host = providerJid, port = port, jid = "unused", password = "unused")
    val registeredJid = registrar.register(username, password)
    println("registered: $registeredJid")

    // Now a normal transport with the new account.
    val transport = XmppTransport(host = providerJid, port = port, jid = registeredJid, password = password)
    val device = DeviceId("xmpp-roundtrip-device")

    val records = listOf(
        SyncRecord(deviceId = device, seq = 0, kind = RecordKind.EVENT, payload = "hello".toByteArray()),
        SyncRecord(deviceId = device, seq = 1, kind = RecordKind.ROLLUP, payload = "world".toByteArray()),
    )
    println("publishing ${records.size} records...")
    val result = transport.publish(records)
    println("published, acked: ${result.acked}")

    println("pulling...")
    val pulled = transport.pull(emptyMap())
    println("pulled ${pulled.size} records")
    pulled.forEach { r ->
        println("  seq=${r.seq} kind=${r.kind} payload=${String(r.payload)}")
    }

    val ok = pulled.size >= 2 &&
        pulled.any { it.seq == 0L && String(it.payload) == "hello" } &&
        pulled.any { it.seq == 1L && String(it.payload) == "world" }
    println(if (ok) "=== ROUND-TRIP PASS ===" else "=== ROUND-TRIP FAIL ===")

    // Best-effort cleanup: delete the throwaway account.
    runCatching {
        val cleanup = XmppTransport(host = providerJid, port = port, jid = registeredJid, password = password)
        cleanup.deleteAccount()
        println("account deleted")
    }.onFailure { println("cleanup skipped: ${it.message}") }

    transport.close()
    registrar.close()
}
fun main() = runXmppRoundTrip("jabber.fr", 5222)
