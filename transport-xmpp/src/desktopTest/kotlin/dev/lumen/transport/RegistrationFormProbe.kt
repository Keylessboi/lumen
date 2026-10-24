package dev.lumen.transport

import dev.lumen.transport.providers.Providers
import kotlinx.coroutines.runBlocking

/**
 * MANUAL LIVE PROBE — not a @Test, and it creates nothing.
 *
 * Opens a TLS connection to each embedded provider and asks for its
 * sign-up form, then disconnects. Proves connection setup, certificate
 * checks and form/CAPTCHA parsing against the real servers the picker
 * offers, without registering an account anywhere.
 */
fun main(args: Array<String>) = runBlocking {
    val targets = args.toList().ifEmpty { Providers.all.map { it.jid } }
    targets.forEach { domain ->
        val line = runCatching {
            XmppRegistration.begin(domain).use { reg ->
                val form = reg.form()
                val captcha = form.captcha?.let { "captcha(${it.mimeType}, ${it.image?.size ?: 0} bytes, field=${it.answerField})" } ?: "no captcha"
                "OK   $captcha extra=${form.extraFields.map { it.name }} web=${form.webPage}"
            }
        }.getOrElse { e -> "FAIL ${(e as? XmppUserError)?.kind}: ${e.message} / ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}" }
        println("%-24s %s".format(domain, line))
    }
}
