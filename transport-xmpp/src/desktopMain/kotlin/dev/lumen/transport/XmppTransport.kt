package dev.lumen.transport

import dev.lumen.core.model.SyncRecord
import dev.lumen.core.sync.PublishResult
import dev.lumen.core.sync.SyncTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.DNSUtil
import org.jivesoftware.smack.util.dns.minidns.MiniDnsResolver
import org.jivesoftware.smackx.iqregister.AccountManager
import org.jivesoftware.smackx.pubsub.LeafNode
import org.jivesoftware.smackx.pubsub.PayloadItem
import org.jivesoftware.smackx.pubsub.PubSubManager
import org.jivesoftware.smackx.pubsub.SimplePayload
import org.jxmpp.jid.parts.Localpart
import org.jxmpp.jid.parts.Resourcepart

/**
 * XMPP transport — M4. Implements the frozen [SyncTransport] seam over a
 * Smack TCP connection and XEP-0060 pubsub.
 *
 * ## Why pubsub, not MAM
 *
 * The transport README names "PEP pubsub" as the publish surface. MAM
 * (XEP-0313) would work but is absent from smack-extensions 4.4.8, and
 * pubsub actually fits the seam better: a device publishes [SyncRecord]s
 * to a shared node, and [pull] reads every item on the node and filters
 * by each device's watermark — which is exactly the append-merge contract
 * (`docs/plan.md`: no CRDT, no wall-clock LWW, per-device monotonic seq).
 * One node, one total order per server.
 *
 * ## Threading
 *
 * Smack is a blocking, event-driven stack. Publish/pull are wrapped in
 * [withContext] (Dispatchers.IO) so the coroutine caller never blocks a
 * UI thread; the connection itself is long-lived and reconnected by
 * Smack's own reconnect manager.
 */
class XmppTransport(
    private val host: String,
    private val port: Int,
    private val jid: String,
    private val password: String,
    private val nodeName: String = DEFAULT_NODE,
) : SyncTransport {

    private var connection: XMPPTCPConnection? = null
    private var node: LeafNode? = null
    private val json = Json { ignoreUnknownKeys = true }

    override val isConfigured: Boolean get() = jid.isNotBlank() && password.isNotBlank()

    /**
     * In-band registration (XEP-0077) against this provider — the "sign up
     * in the app" flow monocles chat / cheogram offer. Creates the account
     * on a fresh, unauthenticated connection, so [password] is the new
     * account's password, not an existing one.
     *
     * @return the registered bare JID (localpart@domain)
     */
    suspend fun register(username: String, newPassword: String): String = withContext(Dispatchers.IO) {
        val conn = connectWithoutLogin()
        val manager = AccountManager.getInstance(conn)
        if (!manager.supportsAccountCreation()) {
            error("provider does not support in-band registration (XEP-0077)")
        }
        val local = Localpart.from(username)
        manager.createAccount(local, newPassword)
        "${local}@${host}"
    }

    override suspend fun publish(records: List<SyncRecord>): PublishResult = withContext(Dispatchers.IO) {
        val leaf = ensureNode()
        val published = LinkedHashMap<String, Long>()
        records.forEach { record ->
            // SimplePayload's ctor parses the body as XML, so the JSON must
            // be wrapped in a well-formed element, not sent bare.
            val body = json.encodeToString(SyncRecord.serializer(), record)
            val xml = "<$ELEMENT xmlns='$NS'>$body</$ELEMENT>"
            val payload = SimplePayload(ELEMENT, NS, xml)
            // PayloadItem(id, payload): the item id is our own per-record
            // seq, so pull() can dedupe by id across devices.
            leaf.publish(PayloadItem(record.seq.toString(), payload))
            published[record.deviceId.value] = record.seq
        }
        PublishResult(acked = published)
    }

    override suspend fun pull(after: Map<String, Long>): List<SyncRecord> = withContext(Dispatchers.IO) {
        val leaf = ensureNode()
        val items: List<PayloadItem<SimplePayload>> = leaf.getItems()
        items.mapNotNull { item ->
            val body = item.payload?.toXML(XmlEnvironment.EMPTY) ?: return@mapNotNull null
            val jsonText = unwrapRecord(body) ?: return@mapNotNull null
            val record = runCatching {
                json.decodeFromString(SyncRecord.serializer(), jsonText)
            }.getOrNull() ?: return@mapNotNull null
            val watermark = after[record.deviceId.value] ?: -1L
            if (record.seq > watermark) record else null
        }
    }

    /**
     * Strip the `<lumen-record ...>...</lumen-record>` wrapper and unescape
     * XML entities. The server re-serializes the stored payload, escaping
     * the JSON's quotes (`&quot;`), so the recovered text must be decoded
     * before it is valid JSON.
     */
    private fun unwrapRecord(xml: String): String? {
        val start = xml.indexOf('>')
        val end = xml.lastIndexOf("</$ELEMENT>")
        if (start < 0 || end <= start) return null
        return xml.substring(start + 1, end)
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    /**
     * Delete the account on the provider (XEP-0077 account removal). Used
     * by the round-trip harness to clean up throwaway test accounts.
     */
    suspend fun deleteAccount() = withContext(Dispatchers.IO) {
        val conn = connect()
        AccountManager.getInstance(conn).deleteAccount()
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        connection?.disconnect()
        connection = null
        node = null
    }

    private suspend fun ensureNode(): LeafNode {
        node?.let { return it }
        val conn = connect()
        // PEP (XEP-0163): the pubsub service is the user's OWN bare JID, so
        // node creation is always permitted. The shared pubsub service on
        // public providers (pubsub.yax.im) rejects node creation with
        // "forbidden - auth" for regular accounts — a real finding from the
        // yax.im round-trip. Sync data is per-account anyway, so per-account
        // nodes are the correct shape.
        val manager = PubSubManager.getInstanceFor(conn, conn.user.asBareJid())
        val leaf = manager.getOrCreateLeafNode(nodeName)
        // Nodes default to transient (latest item only) on many servers —
        // the live jabber.fr round-trip kept just 1 of 2 published items.
        // Sync needs the full history between watermarks, so persist.
        // Some servers reject persistence/max_items changes on PEP nodes
        // ("max_items: out of bounds"); that is a server policy, not a
        // transport bug, so it must not break sync — best-effort only.
        runCatching {
            val form = leaf.getNodeConfiguration().fillableForm
            form.setPersistentItems(true)
            form.setMaxItems(MAX_NODE_ITEMS)
            leaf.sendConfigurationForm(form)
        }
        node = leaf
        return leaf
    }

    private fun connect(): XMPPTCPConnection {
        connection?.let { if (it.isConnected) return it }
        val conn = connectWithoutLogin()
        conn.login()
        connection = conn
        return conn
    }

    /** Build + connect, but do NOT log in — needed for IBR (no account yet). */
    private fun connectWithoutLogin(): XMPPTCPConnection {
        // Unique resource per instance: two transports on the same account
        // (e.g. round-trip harness's publish + cleanup) must not fight over
        // one resource — XMPP replaces an existing session with the same
        // resource ("conflict: replaced by new connection"), silently
        // killing the first one mid-operation.
        val resource = Resourcepart.from("lumen-${hashCode()}")
        // Smack 4.4 requires an explicit hostname verifier for TLS. The
        // JVM default (HttpsURLConnection) is unreliable here — it depends
        // on the session's peer-host bookkeeping, which Smack's SRV path
        // does not populate consistently. RFC 6120 §13.7 is unambiguous:
        // the reference identifier is the XMPP service DOMAIN (yax.im),
        // checked against the certificate's subjectAltName DNS entries.
        // Implement exactly that: extract SANs from the peer cert, compare
        // against the domain we are connecting to. Never accept-all.
        val verifier = javax.net.ssl.HostnameVerifier { _, session ->
            val cert = runCatching {
                session.peerCertificates.firstOrNull() as java.security.cert.X509Certificate
            }.getOrNull() ?: return@HostnameVerifier false
            cert.subjectAlternativeNames
                ?.filter { it[0] == 2 } // DNSName
                ?.map { it[1].toString().lowercase() }
                ?.any { it == host.lowercase() }
                ?: false
        }
        // Only setXmppDomain, NOT setHost: SRV resolution (via the minidns
        // resolver) finds the real connection host — e.g. yax.im's SRV
        // points to xmpp.yax.im. Forcing setHost(host) would connect to the
        // domain directly and bypass SRV entirely.
        val config = XMPPTCPConnectionConfiguration.builder()
            .setXmppDomain(host)
            .setPort(port)
            .setUsernameAndPassword(jid.substringBefore('@'), password)
            .setResource(resource)
            .setSecurityMode(ConnectionConfiguration.SecurityMode.ifpossible)
            .setConnectTimeout(10_000)
            .setHostnameVerifier(verifier)
            .build()
        val conn = XMPPTCPConnection(config)
        conn.connect()
        return conn
    }

    companion object {
        private const val DEFAULT_NODE = "lumen"
        private const val ELEMENT = "lumen-record"
        private const val NS = "urn:lumen:sync:record"
        /** Persistent-node item cap — well beyond a device's watermark window. */
        private const val MAX_NODE_ITEMS = 1000

        init {
            // MiniDnsResolver registers itself through Smack's internal
            // initializer chain, which is not reliably run when the app
            // bundles the jars. Register it deterministically: without a
            // resolver, SRV lookup fails and the client connects to the
            // domain's A record, whose certificate does not authenticate
            // the domain (e.g. yax.im -> xmpp.yax.im).
            DNSUtil.setDNSResolver(MiniDnsResolver.getInstance())
            // Same class of problem for SASL: Base64 needs a concrete
            // encoder, and smack-core ships only the interface. Without
            // one, login fails with "base64encoder is null". java.util.Base64
            // is the correct impl on every JVM target we ship.
            org.jivesoftware.smack.util.stringencoder.Base64.setEncoder(
                object : org.jivesoftware.smack.util.stringencoder.Base64.Encoder {
                    override fun encodeToString(bytes: ByteArray): String =
                        java.util.Base64.getEncoder().encodeToString(bytes)

                    override fun encodeToStringWithoutPadding(bytes: ByteArray): String =
                        java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)

                    override fun encode(bytes: ByteArray): ByteArray =
                        java.util.Base64.getEncoder().encode(bytes)

                    override fun decode(s: String): ByteArray =
                        java.util.Base64.getDecoder().decode(s)
                },
            )
        }
    }
}