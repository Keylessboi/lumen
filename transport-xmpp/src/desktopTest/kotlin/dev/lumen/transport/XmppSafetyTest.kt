package dev.lumen.transport

import dev.lumen.transport.providers.Providers
import org.jivesoftware.smack.sasl.SASLErrorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XmppSafetyTest {

    @Test
    fun `certificate names match the domain exactly or by one wildcard label`() {
        assertTrue(XmppConnector.matchesDomain("yax.im", "yax.im"))
        assertTrue(XmppConnector.matchesDomain("YAX.IM.", "yax.im"))
        assertTrue(XmppConnector.matchesDomain("*.example.org", "chat.example.org"))
        assertFalse(XmppConnector.matchesDomain("*.example.org", "example.org"))
        assertFalse(XmppConnector.matchesDomain("*.example.org", "a.b.example.org"))
        assertFalse(XmppConnector.matchesDomain("evil.org", "yax.im"))
        assertFalse(XmppConnector.matchesDomain("*.org", "evilexample.org.attacker"))
    }

    @Test
    fun `a wrong password reads as a wrong password`() {
        val e = RuntimeException("login", SASLErrorException("SCRAM-SHA-1", org.jivesoftware.smack.sasl.packet.SaslNonza.SASLFailure("not-authorized")))
        assertEquals(XmppUserError.Kind.BAD_CREDENTIALS, e.toUserError().kind)
    }

    @Test
    fun `an unreachable server reads as unreachable`() {
        assertEquals(XmppUserError.Kind.UNREACHABLE, java.net.UnknownHostException("nope.example").toUserError().kind)
    }

    @Test
    fun `provider list is unique, captcha-free first, and current`() {
        val jids = Providers.all.map { it.jid }
        assertEquals(jids.distinct(), jids)
        assertFalse("jabber.fr" in jids, "jabber.fr left Category A")
        val firstCaptcha = Providers.all.indexOfFirst { it.captcha }
        assertTrue(Providers.all.drop(firstCaptcha).all { it.captcha })
        assertTrue(Providers.all.count { !it.captcha } >= 3)
    }

    @Test
    fun `locations read as plain language`() {
        assertEquals("Germany", Providers.describeLocations(listOf("de")))
        assertEquals("India and Switzerland", Providers.describeLocations(listOf("in", "ch")))
    }
}
