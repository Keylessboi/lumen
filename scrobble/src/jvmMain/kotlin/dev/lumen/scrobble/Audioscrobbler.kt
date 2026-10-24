package dev.lumen.scrobble

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * The Audioscrobbler 2.0 API, which Last.fm defines and Libre.fm
 * implements: same calls, same signing, different hosts.
 *
 * ## Sign-in
 *
 * The documented desktop web-auth flow — no password ever reaches Lumen:
 *
 *  1. [requestToken] — `auth.getToken`.
 *  2. The user opens [authorizeUrl] and approves Lumen on the service.
 *  3. [fetchSession] — `auth.getSession` with that token; returns error 14
 *     ("not authorized yet") until step 2 is done, so the caller polls.
 *
 * The session key does not expire; it is stored in the platform keyring.
 */
class Audioscrobbler(
    val service: ScrobbleService,
    private val apiRoot: String,
    private val authRoot: String,
    private val apiKey: String,
    private val secret: String,
    private val http: Http,
) {
    data class Session(val username: String, val key: String)

    val configured: Boolean get() = apiKey.isNotBlank() && secret.isNotBlank()

    fun authorizeUrl(token: String): String = "$authRoot?api_key=$apiKey&token=$token"

    fun requestToken(): String = call("auth.getToken", emptyMap())["token"]!!.jsonPrimitive.content

    fun fetchSession(token: String): Session {
        val session = call("auth.getSession", mapOf("token" to token))["session"]!!.jsonObject
        return Session(session["name"]!!.jsonPrimitive.content, session["key"]!!.jsonPrimitive.content)
    }

    fun nowPlaying(sessionKey: String, track: Track) {
        call(
            "track.updateNowPlaying",
            buildMap {
                put("sk", sessionKey)
                put("artist", track.artist)
                put("track", track.title)
                track.album?.let { put("album", it) }
                track.durationMs?.let { put("duration", (it / 1000).toString()) }
            },
        )
    }

    /** Up to 50 per call, per the API. */
    fun scrobble(sessionKey: String, batch: List<Scrobble>) {
        require(batch.size <= MAX_BATCH)
        val params = mutableMapOf("sk" to sessionKey)
        batch.forEachIndexed { i, s ->
            params["artist[$i]"] = s.track.artist
            params["track[$i]"] = s.track.title
            params["timestamp[$i]"] = s.startedAtSec.toString()
            s.track.album?.let { params["album[$i]"] = it }
            s.track.durationMs?.let { params["duration[$i]"] = (it / 1000).toString() }
        }
        call("track.scrobble", params)
    }

    private fun call(method: String, args: Map<String, String>): JsonObject {
        if (!configured) {
            throw ScrobbleException(
                ScrobbleException.Kind.FATAL,
                "${service.displayName} sign-in is not set up in this build (no API key). " +
                    "Libre.fm and ListenBrainz still work.",
            )
        }
        val params = args + mapOf("method" to method, "api_key" to apiKey)
        val signed = params + mapOf("api_sig" to sign(params, secret), "format" to "json")
        val response = http.postForm(apiRoot, signed)
        val body = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: throw ScrobbleException(
                if (response.code >= 500) ScrobbleException.Kind.RETRY_LATER else ScrobbleException.Kind.FATAL,
                "${service.displayName} answered HTTP ${response.code}.",
            )
        body["error"]?.let { error ->
            val code = runCatching { error.jsonPrimitive.int }.getOrDefault(-1)
            val text = body["message"]?.jsonPrimitive?.content ?: "error $code"
            throw ScrobbleException(kindFor(code), "${service.displayName}: $text")
        }
        return body
    }

    companion object {
        const val MAX_BATCH = 50
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * `api_sig`: md5 of every parameter except `format`/`callback`, sorted by
         * name, as `name + value` with no separators, then the shared secret.
         */
        fun sign(params: Map<String, String>, secret: String): String {
            val base = params.filterKeys { it != "format" && it != "callback" }
                .toSortedMap()
                .entries.joinToString("") { it.key + it.value } + secret
            return MessageDigest.getInstance("MD5").digest(base.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        // https://www.last.fm/api/errorcodes
        private fun kindFor(code: Int) = when (code) {
            14 -> ScrobbleException.Kind.PENDING_APPROVAL
            4, 9, 15 -> ScrobbleException.Kind.SIGNED_OUT
            8, 11, 16, 29 -> ScrobbleException.Kind.RETRY_LATER
            else -> ScrobbleException.Kind.FATAL
        }

        fun lastFm(http: Http) = Audioscrobbler(
            ScrobbleService.LASTFM,
            apiRoot = "https://ws.audioscrobbler.com/2.0/",
            authRoot = "https://www.last.fm/api/auth/",
            apiKey = ScrobbleKeys.LASTFM_API_KEY,
            secret = ScrobbleKeys.LASTFM_SHARED_SECRET,
            http = http,
        )

        /**
         * Libre.fm does not issue keys: its API docs ask clients to send any
         * 32-character key and secret. These are identifiers, not secrets.
         */
        fun libreFm(http: Http) = Audioscrobbler(
            ScrobbleService.LIBREFM,
            apiRoot = "https://libre.fm/2.0/",
            authRoot = "https://libre.fm/api/auth/",
            apiKey = "6c756d656e2d73637262626c722d6b31",
            secret = "6c756d656e2d73637262626c722d7331",
            http = http,
        )
    }
}
