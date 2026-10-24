package dev.lumen.transport

import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.sasl.SASLErrorException
import org.jxmpp.stringprep.XmppStringprepException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A failure explained in the words the account screen shows.
 *
 * The account screen printed `e.message` from Smack, so a taken username
 * surfaced as "XMPPError: conflict - cancel" and a wrong password as
 * "SASLError using SCRAM-SHA-1: not-authorized". Both are true and neither
 * tells a person what to do next.
 */
class XmppUserError(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        USERNAME_TAKEN,
        BAD_CREDENTIALS,
        REJECTED,
        REGISTRATION_CLOSED,
        RATE_LIMITED,
        UNREACHABLE,
        INSECURE,
        INVALID_INPUT,
        OTHER,
    }
}

internal fun Throwable.toUserError(): XmppUserError {
    if (this is XmppUserError) return this
    val chain = generateSequence(this) { it.cause }.take(8).toList()
    chain.firstNotNullOfOrNull { it as? XMPPException.XMPPErrorException }?.let { e ->
        val serverText = e.stanzaError?.descriptiveText?.takeIf { it.isNotBlank() }
        fun msg(base: String) = if (serverText != null) "$base The server said: \"$serverText\"" else base
        return when (e.stanzaError?.condition) {
            StanzaError.Condition.conflict -> XmppUserError(
                XmppUserError.Kind.USERNAME_TAKEN, "That username is already taken on this server. Try another one.", e,
            )
            StanzaError.Condition.not_acceptable, StanzaError.Condition.bad_request -> XmppUserError(
                XmppUserError.Kind.REJECTED,
                msg("The server did not accept those details. Check the CAPTCHA answer, or try a longer password."),
                e,
            )
            StanzaError.Condition.not_allowed, StanzaError.Condition.forbidden,
            StanzaError.Condition.registration_required, StanzaError.Condition.not_authorized,
            -> XmppUserError(
                XmppUserError.Kind.REGISTRATION_CLOSED,
                msg("This server is not accepting sign-ups from apps right now. Pick another server."),
                e,
            )
            StanzaError.Condition.resource_constraint, StanzaError.Condition.policy_violation -> XmppUserError(
                XmppUserError.Kind.RATE_LIMITED,
                msg("This server is limiting sign-ups from your network. Wait a while or pick another server."),
                e,
            )
            StanzaError.Condition.service_unavailable, StanzaError.Condition.feature_not_implemented -> XmppUserError(
                XmppUserError.Kind.REGISTRATION_CLOSED,
                "This server does not offer sign-up from apps. Pick another server, or create the account on its website.",
                e,
            )
            StanzaError.Condition.jid_malformed -> XmppUserError(
                XmppUserError.Kind.INVALID_INPUT, "That username has characters this server does not allow.", e,
            )
            else -> XmppUserError(XmppUserError.Kind.OTHER, msg("The server refused the request."), e)
        }
    }
    if (chain.any { it is SASLErrorException }) {
        return XmppUserError(XmppUserError.Kind.BAD_CREDENTIALS, "Wrong username or password for this server.", this)
    }
    if (chain.any { it is SmackException.SecurityRequiredByClientException || it is SSLException }) {
        return XmppUserError(
            XmppUserError.Kind.INSECURE,
            "Could not set up a secure connection to this server, so nothing was sent.",
            this,
        )
    }
    if (chain.any { it is XmppStringprepException }) {
        return XmppUserError(XmppUserError.Kind.INVALID_INPUT, "That username or server name is not valid.", this)
    }
    if (chain.any {
            it is UnknownHostException || it is SocketTimeoutException ||
                it is SmackException.EndpointConnectionException || it is SmackException.NoResponseException ||
                it is SmackException.NotConnectedException || it is java.net.ConnectException
        }
    ) {
        return XmppUserError(
            XmppUserError.Kind.UNREACHABLE,
            "Could not reach the server. Check your connection and the server name.",
            this,
        )
    }
    return XmppUserError(XmppUserError.Kind.OTHER, message ?: this::class.simpleName ?: "Unknown error", this)
}
