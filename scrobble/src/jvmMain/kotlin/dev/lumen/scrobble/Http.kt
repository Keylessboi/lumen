package dev.lumen.scrobble

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** The tiny HTTP surface the services need. An interface so tests fake it. */
interface Http {
    data class Response(val code: Int, val body: String)

    fun postForm(url: String, form: Map<String, String>): Response
    fun postJson(url: String, json: String, headers: Map<String, String>): Response
    fun get(url: String, headers: Map<String, String>): Response
}

/** `HttpURLConnection` — on the JDK and on Android, no extra dependency. */
class JvmHttp(private val userAgent: String = "Lumen/0.1 (+https://github.com/Keylessboi/lumen)") : Http {

    override fun postForm(url: String, form: Map<String, String>): Http.Response =
        send(url, "POST", mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=utf-8"), encode(form))

    override fun postJson(url: String, json: String, headers: Map<String, String>): Http.Response =
        send(url, "POST", headers + ("Content-Type" to "application/json; charset=utf-8"), json)

    override fun get(url: String, headers: Map<String, String>): Http.Response = send(url, "GET", headers, null)

    private fun send(url: String, method: String, headers: Map<String, String>, body: String?): Http.Response {
        require(url.startsWith("https://")) { "refusing a non-HTTPS endpoint: $url" }
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach(conn::setRequestProperty)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.encodeToByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().decodeToString() } ?: ""
            return Http.Response(code, text)
        } catch (e: IOException) {
            throw ScrobbleException(ScrobbleException.Kind.OFFLINE, "Could not reach the scrobbling service.", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun encode(form: Map<String, String>) =
        form.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
}

class ScrobbleException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** Network down or service unreachable — retry later, keep the queue. */
        OFFLINE,
        /** The service is temporarily refusing (rate limit, maintenance). */
        RETRY_LATER,
        /** The session/token is no longer valid — the user must sign in again. */
        SIGNED_OUT,
        /** Not yet approved in the browser (web sign-in polling). */
        PENDING_APPROVAL,
        /** Configuration missing (no API key) or a request the service rejects for good. */
        FATAL,
    }
}
