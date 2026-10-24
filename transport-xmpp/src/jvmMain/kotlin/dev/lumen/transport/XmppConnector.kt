package dev.lumen.transport

import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.DNSUtil
import org.jivesoftware.smack.util.dns.minidns.MiniDnsResolver
import org.jxmpp.jid.parts.Resourcepart
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier

/**
 * Builds Smack connections the one way Lumen allows: TLS required, the
 * certificate checked against the XMPP domain, SRV resolution on.
 *
 * Shared by [XmppTransport] (sync) and [XmppRegistration] (sign-up), which
 * used to build their configurations separately.
 */
internal object XmppConnector {

    init {
        // MiniDnsResolver registers itself through Smack's internal
        // initializer chain, which is not reliably run when the app bundles
        // the jars. Register it deterministically: without a resolver, SRV
        // lookup fails and the client connects to the domain's A record,
        // whose certificate does not authenticate the domain.
        DNSUtil.setDNSResolver(MiniDnsResolver.getInstance())
        // Same class of problem for SASL: Base64 needs a concrete encoder, and
        // smack-core ships only the interface. Without one, login fails with
        // "base64encoder is null". java.util.Base64 exists on every JVM
        // target we ship, Android API 26+ included.
        org.jivesoftware.smack.util.stringencoder.Base64.setEncoder(
            object : org.jivesoftware.smack.util.stringencoder.Base64.Encoder {
                override fun encodeToString(bytes: ByteArray): String =
                    java.util.Base64.getEncoder().encodeToString(bytes)

                override fun encodeToStringWithoutPadding(bytes: ByteArray): String =
                    java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)

                override fun encode(bytes: ByteArray): ByteArray = java.util.Base64.getEncoder().encode(bytes)

                override fun decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
            },
        )
        // Registration CAPTCHAs (XEP-0158) put the image reference in a
        // XEP-0221 <media/> child of a form field. The manager's static
        // initializer registers that parser; touch it so it has run before
        // the first form arrives.
        org.jivesoftware.smackx.mediaelement.MediaElementManager()
    }

    /**
     * Connect (but do not log in). [username]/[password] are only used by a
     * later `login()`; registration passes nulls.
     */
    fun connect(domain: String, port: Int, username: String?, password: String?): XMPPTCPConnection {
        val builder = XMPPTCPConnectionConfiguration.builder()
            // Only the domain, never setHost: SRV resolution finds the real
            // host (yax.im -> xmpp.yax.im). Forcing the host bypasses SRV.
            .setXmppDomain(domain)
            .setPort(port)
            // Unique resource per connection: XMPP replaces an existing
            // session with the same resource ("conflict"), silently killing
            // the first one mid-operation.
            // (identity hash codes repeat across processes; a UUID does not).
            .setResource(Resourcepart.from("lumen-${java.util.UUID.randomUUID().toString().take(12)}"))
            // REQUIRED, not ifpossible: `ifpossible` connects in plaintext
            // when STARTTLS is missing — or stripped by anyone on the path —
            // and then sends the account password over it.
            .setSecurityMode(ConnectionConfiguration.SecurityMode.required)
            .setConnectTimeout(CONNECT_TIMEOUT_MS)
            .setHostnameVerifier(verifierFor(domain))
        if (username != null && password != null) builder.setUsernameAndPassword(username, password)
        val conn = XMPPTCPConnection(builder.build())
        conn.replyTimeout = REPLY_TIMEOUT_MS
        conn.connect()
        return conn
    }

    /**
     * RFC 6120 §13.7: the reference identifier is the XMPP service DOMAIN,
     * checked against the certificate's DNS subjectAltNames. Wildcards are
     * honoured for exactly one left-most label (RFC 6125 §6.4.3) — many
     * public servers present `*.example.org`, which the exact-match check
     * this replaced refused, so those servers could not be used at all.
     * Never accept-all.
     */
    fun verifierFor(domain: String) = HostnameVerifier { _, session ->
        val cert = runCatching { session.peerCertificates.firstOrNull() as X509Certificate }.getOrNull()
            ?: return@HostnameVerifier false
        val names = cert.subjectAlternativeNames
            ?.filter { it[0] == 2 } // DNSName
            ?.map { it[1].toString() }
            .orEmpty()
        names.any { matchesDomain(it, domain) }
    }

    fun matchesDomain(certName: String, domain: String): Boolean {
        val name = certName.lowercase().trimEnd('.')
        val host = domain.lowercase().trimEnd('.')
        if (name == host) return true
        if (!name.startsWith("*.")) return false
        val suffix = name.removePrefix("*")
        // "*.example.org" covers "a.example.org", not "example.org" and not "a.b.example.org".
        return host.endsWith(suffix) && host.length > suffix.length && '.' !in host.removeSuffix(suffix)
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val REPLY_TIMEOUT_MS = 20_000L
}
