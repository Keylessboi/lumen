package dev.lumen.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jivesoftware.smack.filter.StanzaIdFilter
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smackx.bob.BoBManager
import org.jivesoftware.smackx.bob.ContentId
import org.jivesoftware.smackx.bob.element.BoBDataExtension
import org.jivesoftware.smackx.iqregister.packet.Registration
import org.jivesoftware.smackx.mediaelement.element.MediaElement
import org.jivesoftware.smackx.xdata.FormField
import org.jivesoftware.smackx.xdata.packet.DataForm
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Localpart
import java.net.HttpURLConnection
import java.net.URI

/** What a server asks for before it will create an account. */
data class RegistrationForm(
    val domain: String,
    val instructions: String?,
    /** Fields beyond username and password that need an answer. */
    val extraFields: List<RegistrationField>,
    val captcha: RegistrationCaptcha?,
    /** The server's own sign-up page, when it offers one (jabber:x:oob). */
    val webPage: String?,
)

data class RegistrationField(
    val name: String,
    val label: String,
    val kind: Kind,
    val required: Boolean,
    val defaultValue: String?,
    val options: List<Pair<String, String>> = emptyList(),
) {
    enum class Kind { TEXT, SECRET, BOOLEAN, CHOICE, INFO }
}

/** XEP-0158: an image the user must read, answered in [answerField]. */
class RegistrationCaptcha(
    val answerField: String,
    val label: String,
    val image: ByteArray?,
    val mimeType: String?,
    /** Where the image lives when it could not be fetched inline. */
    val imageUrl: String?,
)

/**
 * In-band registration (XEP-0077) the way monocles chat and Conversations
 * do it: ask the server for its form, show whatever it asks for — including
 * a CAPTCHA (XEP-0158, image via Bits of Binary XEP-0231 or a URL) — and
 * submit the answers on the SAME connection.
 *
 * The old path called `AccountManager.createAccount(user, pass)` blind,
 * which only works on servers that ask for nothing else. Of the 15 public
 * servers in the XMPP Providers Category A feed (2026-10-23), 9 require a
 * CAPTCHA, so in-app sign-up failed on most of them with an opaque error.
 *
 * One instance is one sign-up attempt. A CAPTCHA is bound to the stream it
 * was issued on, which is why [form] and [submit] share the connection and
 * why a failed answer needs a fresh [form] (servers issue a new challenge).
 */
class XmppRegistration private constructor(
    private val conn: XMPPTCPConnection,
    val domain: String,
) : AutoCloseable {

    private var lastForm: DataForm? = null
    private var lastAttributes: Map<String, String> = emptyMap()

    suspend fun form(): RegistrationForm = io {
        val request = Registration().apply {
            type = IQ.Type.get
            to = JidCreate.domainBareFrom(domain)
        }
        val response = request(request) as Registration
        parse(response)
    }

    /** Create the account. Returns the new bare JID. */
    suspend fun submit(username: String, password: String, answers: Map<String, String>): String = io {
        val local = Localpart.from(username.trim())
        val form = lastForm
        val request = if (form != null) {
            val submit = DataForm.builder(DataForm.Type.submit)
            form.fields.forEach { field ->
                val name = field.fieldName ?: return@forEach
                val value = when {
                    field.type == FormField.Type.fixed -> null
                    field.type == FormField.Type.hidden -> field.firstValue
                    name == FIELD_USERNAME -> local.toString()
                    name == FIELD_PASSWORD -> password
                    else -> answers[name] ?: field.firstValue
                }
                if (value != null) submit.addField(FormField.builder(name).setValue(value).build())
            }
            Registration(emptyMap()).apply { addExtension(submit.build()) }
        } else {
            val attributes = linkedMapOf(FIELD_USERNAME to local.toString(), FIELD_PASSWORD to password)
            lastAttributes.keys
                .filter { it !in attributes && it !in LEGACY_REGISTRATION_KEYS }
                .forEach { key -> answers[key]?.let { attributes[key] = it } }
            Registration(attributes)
        }
        request.type = IQ.Type.set
        request.to = JidCreate.domainBareFrom(domain)
        request(request)
        "$local@$domain"
    }

    /**
     * Send and wait, matched by stanza id. `sendIqRequestAndWaitForResponse`
     * matches replies against the connection's own JID, which does not exist
     * before login — it threw "Must have a local (user) JID set" for every
     * sign-up. This is what Smack's AccountManager does internally.
     */
    private fun request(iq: IQ): IQ =
        conn.createStanzaCollectorAndSend(StanzaIdFilter(iq), iq).nextResultOrThrow()

    override fun close() {
        runCatching { conn.disconnect() }
    }

    private fun parse(response: Registration): RegistrationForm {
        lastForm = DataForm.from(response)
        lastAttributes = response.attributes.orEmpty()
        return parseRegistration(domain, response) { cid ->
            runCatching {
                BoBManager.getInstanceFor(conn).requestBoB(JidCreate.domainBareFrom(domain), ContentId.fromCid(cid))
            }.getOrNull()
        }
    }

    companion object {
        private const val FIELD_USERNAME = "username"
        private const val FIELD_PASSWORD = "password"

        /** Open an unauthenticated connection to [domain] for one sign-up attempt. */
        suspend fun begin(domain: String, port: Int = 5222): XmppRegistration = io {
            XmppRegistration(XmppConnector.connect(domain.trim().lowercase(), port, null, null), domain.trim().lowercase())
        }

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: Throwable) {
                throw e.toUserError()
            }
        }
    }
}

/**
 * Turn a registration form IQ into what the account screen renders. Split
 * out of [XmppRegistration] so it is tested against captured server XML.
 * [requestBoB] fetches a CAPTCHA image the server referenced but did not
 * inline.
 */
internal fun parseRegistration(
    domain: String,
    response: Registration,
    requestBoB: (cid: String) -> org.jivesoftware.smackx.bob.BoBData?,
): RegistrationForm {
    val form = DataForm.from(response)
    val lastAttributes = response.attributes.orEmpty()
    val webPage = (response.getExtensionElement("x", "jabber:x:oob") as? StandardExtensionElement)
        ?.getFirstElement("url")?.text
    val bob = BoBDataExtension.from(response)

    if (form == null) {
        // Legacy form: bare element names, no labels, no CAPTCHA.
        val extra = lastAttributes.keys
            .filter { it !in setOf("username", "password") && it !in LEGACY_REGISTRATION_KEYS }
            .map { RegistrationField(it, it.replaceFirstChar(Char::uppercase), RegistrationField.Kind.TEXT, true, null) }
        return RegistrationForm(domain, response.instructions, extra, null, webPage)
    }

    var captcha: RegistrationCaptcha? = null
    val extra = mutableListOf<RegistrationField>()
    form.fields.forEach { field ->
        val name = field.fieldName ?: return@forEach
        if (name == "username" || name == "password" || field.type == FormField.Type.hidden) return@forEach
        val media = field.getFormFieldChildElements(MediaElement.QNAME).filterIsInstance<MediaElement>().firstOrNull()
        if (media != null) {
            captcha = captchaFrom(name, field.label ?: "Type the characters you see", media, bob, requestBoB)
            return@forEach
        }
        extra += RegistrationField(
            name = name,
            label = field.label ?: field.firstValue ?: name,
            kind = when (field.type) {
                FormField.Type.text_private -> RegistrationField.Kind.SECRET
                FormField.Type.bool -> RegistrationField.Kind.BOOLEAN
                FormField.Type.list_single -> RegistrationField.Kind.CHOICE
                FormField.Type.fixed -> RegistrationField.Kind.INFO
                else -> RegistrationField.Kind.TEXT
            },
            required = field.isRequired,
            defaultValue = field.firstValue,
            options = (field as? org.jivesoftware.smackx.xdata.ListSingleFormField)
                ?.options?.map { (it.value?.toString() ?: "") to (it.label ?: it.value?.toString() ?: "") }
                .orEmpty(),
        )
    }
    // ejabberd adds fixed "can't see the image?" fields next to the CAPTCHA.
    // They belong to the CAPTCHA, not to the list of things to fill in.
    val fallbackUrl = extra.firstOrNull { it.name == "captcha-fallback-url" }?.defaultValue
    extra.removeAll { it.name.startsWith("captcha-fallback") }
    val withFallback = captcha?.let {
        if (it.imageUrl != null || fallbackUrl == null) it
        else RegistrationCaptcha(it.answerField, it.label, it.image, it.mimeType, fallbackUrl)
    }
    val instructions = form.instructions.joinToString("\n").ifBlank { response.instructions }
    return RegistrationForm(domain, instructions, extra, withFallback, webPage)
}

private fun captchaFrom(
    name: String,
    label: String,
    media: MediaElement,
    inline: BoBDataExtension?,
    requestBoB: (String) -> org.jivesoftware.smackx.bob.BoBData?,
): RegistrationCaptcha {
    media.uris.forEach { uri ->
        val text = uri.uri.toString()
        if (text.startsWith("cid:")) {
            val cid = text.removePrefix("cid:")
            val data = inline?.takeIf { it.contentId.cid == cid }?.bobData
                ?: requestBoB(cid)
            if (data != null) return RegistrationCaptcha(name, label, data.content, data.type, null)
        } else if (text.startsWith("https://") || text.startsWith("http://")) {
            val bytes = fetchImage(text)
            return RegistrationCaptcha(name, label, bytes, uri.type, text)
        }
    }
    return RegistrationCaptcha(name, label, null, null, null)
}

private fun fetchImage(url: String): ByteArray? = runCatching {
    val http = URI(url).toURL().openConnection() as HttpURLConnection
    http.connectTimeout = 10_000
    http.readTimeout = 10_000
    try {
        if (http.responseCode != 200) return@runCatching null
        http.inputStream.use { input ->
            val out = input.readNBytes(MAX_CAPTCHA_BYTES + 1)
            if (out.size > MAX_CAPTCHA_BYTES) null else out
        }
    } finally {
        http.disconnect()
    }
}.getOrNull()

internal val LEGACY_REGISTRATION_KEYS = setOf("instructions", "registered", "remove")
private const val MAX_CAPTCHA_BYTES = 1 shl 20
