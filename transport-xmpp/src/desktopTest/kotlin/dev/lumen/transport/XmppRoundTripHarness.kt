package dev.lumen.transport

import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.RecordKind
import dev.lumen.core.model.SyncRecord
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

/**
 * MANUAL LIVE HARNESS — not a @Test.
 *
 * Proves the XMPP transport end-to-end against a REAL public provider: sign
 * up in-app via XEP-0077, publish, pull back, delete the account. Requires
 * network access, and only works unattended on a CAPTCHA-free server
 * (`Providers.all.filter { !it.captcha }`).
 */
fun runXmppRoundTrip(domain: String, port: Int = 5222) = runBlocking {
    val username = "lumen-test-${Random.nextInt(100000, 999999)}"
    val password = "lumen-test-${Random.nextBytes(8).joinToString("") { "%02x".format(it) }}"
    println("=== XMPP round-trip on $domain ===")

    val registeredJid = XmppRegistration.begin(domain, port).use { registration ->
        val form = registration.form()
        check(form.captcha == null) { "$domain wants a CAPTCHA; pick a CAPTCHA-free server for the harness" }
        registration.submit(username, password, emptyMap())
    }
    println("registered: $registeredJid")

    val transport = XmppTransport(host = domain, port = port, jid = registeredJid, password = password)
    val device = DeviceId("xmpp-roundtrip-device")
    val records = (0L..29L).map { SyncRecord(device, it, RecordKind.EVENT, "hello-$it".toByteArray()) }
    println("published, acked: ${transport.publish(records).acked}")
    val pulled = transport.pull(emptyMap())
    val ok = pulled.map { it.seq }.sorted() == records.map { it.seq } &&
        transport.pull(mapOf(device.value to 29L)).isEmpty()
    println(if (ok) "=== ROUND-TRIP PASS ===" else "=== ROUND-TRIP FAIL (${pulled.size} pulled) ===")

    runCatching { transport.deleteAccount() }.onFailure { println("cleanup skipped: ${it.message}") }
    transport.close()
}

fun main() = runXmppRoundTrip("yax.im", 5222)
