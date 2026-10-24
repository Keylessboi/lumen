package dev.lumen.transport

import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.iqregister.packet.Registration
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Registration forms as real servers send them. The CAPTCHA case follows
 * XEP-0158 §6 / ejabberd's shape: a data form whose `ocr` field carries a
 * XEP-0221 media element pointing at inline Bits of Binary data.
 */
class XmppRegistrationParseTest {

    init {
        // Loads Smack's provider registry, as a live connection would.
        XmppConnector.matchesDomain("a", "a")
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    private val cid = "sha1+0123456789abcdef0123456789abcdef01234567@bob.xmpp.org"

    private fun parse(xml: String): Registration =
        PacketParserUtils.parseStanza<Registration>(xml)

    @Test
    fun `captcha form exposes the image, the answer field and nothing hidden`() {
        val xml = """
            <iq type='result' from='example.org' id='r1' xmlns='jabber:client'>
              <query xmlns='jabber:iq:register'>
                <instructions>Fill in the form</instructions>
                <x xmlns='jabber:x:data' type='form'>
                  <field type='hidden' var='FORM_TYPE'><value>urn:xmpp:captcha</value></field>
                  <field type='hidden' var='challenge'><value>abc</value></field>
                  <field type='text-single' var='username' label='User'><required/></field>
                  <field type='text-private' var='password' label='Password'><required/></field>
                  <field type='text-single' var='ocr' label='Enter the text you see'>
                    <media xmlns='urn:xmpp:media-element' height='60' width='180'>
                      <uri type='image/png'>cid:$cid</uri>
                    </media>
                    <required/>
                  </field>
                  <field type='fixed' var='captcha-fallback-text'><value>If you don't see the image, visit the page.</value></field>
                  <field type='fixed' var='captcha-fallback-url'><value>https://example.org/captcha/123</value></field>
                </x>
                <data xmlns='urn:xmpp:bob' cid='$cid' type='image/png'>${Base64.getEncoder().encodeToString(png)}</data>
              </query>
            </iq>
        """.trimIndent()

        val form = parseRegistration("example.org", parse(xml)) { null }

        val captcha = assertNotNull(form.captcha)
        assertEquals("ocr", captcha.answerField)
        assertEquals("image/png", captcha.mimeType)
        assertContentEquals(png, captcha.image)
        assertTrue(form.extraFields.isEmpty(), "username/password/hidden/fallback are not extra fields: ${form.extraFields}")
        assertEquals("https://example.org/captcha/123", captcha.imageUrl)
    }

    @Test
    fun `a referenced but not inlined image is fetched by cid`() {
        val xml = """
            <iq type='result' from='example.org' id='r2' xmlns='jabber:client'>
              <query xmlns='jabber:iq:register'>
                <x xmlns='jabber:x:data' type='form'>
                  <field type='text-single' var='username'/>
                  <field type='text-single' var='captcha' label='Code'>
                    <media xmlns='urn:xmpp:media-element'><uri type='image/png'>cid:$cid</uri></media>
                  </field>
                </x>
              </query>
            </iq>
        """.trimIndent()
        var asked: String? = null
        val form = parseRegistration("example.org", parse(xml)) {
            asked = it
            org.jivesoftware.smackx.bob.BoBData("image/png", png)
        }
        assertEquals(cid, asked)
        assertContentEquals(png, form.captcha?.image)
    }

    @Test
    fun `legacy form with an extra element asks for it`() {
        val xml = """
            <iq type='result' from='legacy.example' id='r3' xmlns='jabber:client'>
              <query xmlns='jabber:iq:register'>
                <instructions>Choose a username and password.</instructions>
                <username/><password/><email/>
              </query>
            </iq>
        """.trimIndent()
        val form = parseRegistration("legacy.example", parse(xml)) { null }
        assertNull(form.captcha)
        assertEquals(listOf("email"), form.extraFields.map { it.name })
        assertEquals("Choose a username and password.", form.instructions)
    }

    @Test
    fun `web sign-up link is surfaced`() {
        val xml = """
            <iq type='result' from='web.example' id='r4' xmlns='jabber:client'>
              <query xmlns='jabber:iq:register'>
                <instructions>Register on our website</instructions>
                <x xmlns='jabber:x:oob'><url>https://web.example/register</url></x>
              </query>
            </iq>
        """.trimIndent()
        assertEquals("https://web.example/register", parseRegistration("web.example", parse(xml)) { null }.webPage)
    }
}
