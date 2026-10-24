package dev.lumen.transport

import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.SyncRecord
import dev.lumen.core.sync.AccountTransport
import dev.lumen.core.sync.DeviceBundle
import dev.lumen.core.sync.DeviceDirectory
import dev.lumen.core.sync.PublishResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smackx.iqregister.AccountManager
import org.jivesoftware.smackx.pubsub.AccessModel
import org.jivesoftware.smackx.pubsub.LeafNode
import org.jivesoftware.smackx.pubsub.PayloadItem
import org.jivesoftware.smackx.pubsub.PubSubManager
import org.jivesoftware.smackx.pubsub.SimplePayload

/**
 * XMPP transport — M4. Implements the frozen [SyncTransport] seam over a
 * Smack TCP connection and PEP (XEP-0163) pubsub on the user's own account.
 *
 * ## Why pubsub, not MAM
 *
 * MAM (XEP-0313) is absent from smack-extensions 4.4.8, and pubsub fits the
 * seam better: a device publishes [SyncRecord]s to a node on its own
 * account, and [pull] reads what each device's watermark has not yet seen.
 *
 * ## Items
 *
 * One item carries a batch of up to [BATCH_SIZE] records from ONE device,
 * with id `<deviceId>:<firstSeq>-<lastSeq>` ([SyncWire]). The previous
 * layout used the bare seq as the item id, so two devices' records with
 * the same seq overwrote each other on the shared node — a second device's
 * first record silently replaced the first device's. The id also lets
 * [pull] decide from a cheap item listing which items it needs, instead of
 * downloading the whole node every five minutes.
 *
 * ## Threading
 *
 * Smack is blocking. Every call runs on Dispatchers.IO and the connection
 * is guarded by a mutex; [close] ends the stream (the sync loop now calls
 * it after every pass, instead of leaking one connection per pass).
 */
class XmppTransport(
    private val host: String,
    private val port: Int,
    private val jid: String,
    private val password: String,
    private val nodeName: String = DEFAULT_NODE,
) : AccountTransport {

    private var connection: XMPPTCPConnection? = null
    private val nodes = mutableMapOf<String, LeafNode>()
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override val isConfigured: Boolean get() = jid.isNotBlank() && password.isNotBlank()

    /**
     * Log in once and disconnect. Used by "I already have an account" so a
     * typo'd password is reported on the spot, instead of the screen saying
     * "Connected" and every sync failing quietly afterwards.
     */
    override suspend fun verifyLogin() = io {
        connect()
        Unit
    }

    override suspend fun publish(records: List<SyncRecord>): PublishResult = io {
        val leaf = node(nodeName)
        val acked = LinkedHashMap<String, Long>()
        records.groupBy { it.deviceId.value }.forEach { (device, deviceRecords) ->
            deviceRecords.sortedBy { it.seq }.chunked(BATCH_SIZE).forEach { batch ->
                leaf.publish(PayloadItem(SyncWire.itemId(batch), SimplePayload(SyncWire.encodeBatch(batch))))
                acked[device] = batch.last().seq
            }
        }
        PublishResult(acked = acked)
    }

    override suspend fun pull(after: Map<String, Long>): List<SyncRecord> = io {
        val leaf = node(nodeName)
        // List item ids first; fetch only items holding records past the
        // caller's watermark. Servers that refuse disco#items on PEP nodes
        // get the old full fetch.
        val ids = runCatching { leaf.discoverItems().items.mapNotNull { it.name } }.getOrNull()
        val items: List<PayloadItem<SimplePayload>> = if (ids == null) {
            leaf.getItems()
        } else {
            val wanted = ids.filter { SyncWire.needed(it, after) }
            wanted.chunked(FETCH_CHUNK).flatMap { leaf.getItems<PayloadItem<SimplePayload>>(it) }
        }
        items.flatMap { item ->
            val xml = item.payload?.toXML(XmlEnvironment.EMPTY)?.toString() ?: return@flatMap emptyList()
            SyncWire.decodeItem(xml, json)
        }.filter { it.seq > (after[it.deviceId.value] ?: -1L) }
    }

    /** This account's device list, as a [DeviceDirectory] on the same connection. */
    override fun deviceDirectory(): DeviceDirectory = object : DeviceDirectory {
        override suspend fun publish(bundle: DeviceBundle) = io {
            val body = json.encodeToString(DeviceBundle.serializer(), bundle)
            node(DEVICES_NODE).publish(
                PayloadItem(bundle.deviceId.value, SimplePayload(SyncWire.wrapText(DEVICE_ELEMENT, DEVICES_NODE, body))),
            )
        }

        override suspend fun bundles(): List<DeviceBundle> = io {
            node(DEVICES_NODE).getItems<PayloadItem<SimplePayload>>().mapNotNull { item ->
                val xml = item.payload?.toXML(XmlEnvironment.EMPTY)?.toString() ?: return@mapNotNull null
                val text = SyncWire.unwrapText(xml, DEVICE_ELEMENT) ?: return@mapNotNull null
                runCatching { json.decodeFromString(DeviceBundle.serializer(), text) }.getOrNull()
            }
        }

        override suspend fun remove(deviceId: DeviceId) = io {
            node(DEVICES_NODE).deleteItem(deviceId.value)
        }
    }

    /**
     * Delete the account on the provider (XEP-0077 account removal). Used
     * by the round-trip harness to clean up throwaway test accounts.
     */
    suspend fun deleteAccount() = io {
        AccountManager.getInstance(connect()).deleteAccount()
    }

    /** Runs even from a cancelled coroutine, or the connection would leak. */
    override suspend fun close() = withContext(NonCancellable) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                runCatching { connection?.disconnect() }
                connection = null
                nodes.clear()
            }
        }
    }

    private fun node(name: String): LeafNode {
        nodes[name]?.let { return it }
        val conn = connect()
        // PEP: the pubsub service is the user's OWN bare JID, so node creation
        // is always permitted. The shared pubsub service on public providers
        // (pubsub.yax.im) rejects node creation for regular accounts.
        val manager = PubSubManager.getInstanceFor(conn, conn.user.asBareJid())
        val leaf = manager.getOrCreateLeafNode(name)
        configure(leaf)
        nodes[name] = leaf
        return leaf
    }

    /**
     * Best-effort node policy. Each setting is applied on its own: one server
     * refusing a value (jabber.fr: "max_items: out of bounds") used to throw
     * away the whole form, including the access model.
     *
     *  - access_model=whitelist: PEP's default is `presence`, which lets
     *    every contact who sees the account's presence read the node.
     *    Payloads are encrypted, but timing and volume are exactly the
     *    metadata `docs/e2ee.md` §4 says we must not hand out further.
     *  - persistent, with the largest max_items the server accepts.
     */
    private fun configure(leaf: LeafNode) {
        fun attempt(edit: (org.jivesoftware.smackx.pubsub.form.FillableConfigureForm) -> Unit) = runCatching {
            val form = leaf.nodeConfiguration.fillableForm
            edit(form)
            leaf.sendConfigurationForm(form)
        }.isSuccess
        attempt { it.setAccessModel(AccessModel.whitelist) }
        attempt { it.setPersistentItems(true) }
        MAX_ITEMS_LADDER.firstOrNull { max -> attempt { it.setMaxItems(max) } }
    }

    private fun connect(): XMPPTCPConnection {
        connection?.let { if (it.isConnected && it.isAuthenticated) return it }
        runCatching { connection?.disconnect() }
        nodes.clear()
        val conn = XmppConnector.connect(host, port, jid.substringBefore('@'), password)
        try {
            conn.login()
        } catch (e: Throwable) {
            runCatching { conn.disconnect() }
            throw e
        }
        connection = conn
        return conn
    }

    private suspend fun <T> io(block: () -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: Throwable) {
                throw e.toUserError()
            }
        }
    }

    companion object {
        private const val DEFAULT_NODE = "lumen"
        /**
         * Device list node. Deliberately NOT `urn:xmpp:omemo:2:devices`:
         * that node belongs to the user's chat clients (monocles,
         * Conversations), and a Lumen entry there would make them try — and
         * fail — to encrypt chat messages to a sync device.
         */
        const val DEVICES_NODE = "urn:lumen:omemo:2:devices"
        private const val DEVICE_ELEMENT = "device"
        /** Records per item: keeps a stanza far below common 256 KB limits. */
        const val BATCH_SIZE = 25
        private const val FETCH_CHUNK = 20
        private val MAX_ITEMS_LADDER = listOf(1000, 500, 256, 100)
    }
}
