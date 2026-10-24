package dev.lumen.scrobble

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * ListenBrainz (and anything that speaks its API, e.g. a self-hosted
 * instance). Sign-in is a user token: the user opens [TOKEN_PAGE], copies
 * the token shown there, and Lumen checks it with `validate-token`.
 */
class ListenBrainz(
    private val http: Http,
    val apiRoot: String = DEFAULT_API,
) {
    fun validate(token: String): String {
        val response = http.get("$apiRoot/1/validate-token", auth(token))
        val body = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        val valid = body?.get("valid")?.jsonPrimitive?.boolean == true
        if (!valid) throw ScrobbleException(ScrobbleException.Kind.SIGNED_OUT, "ListenBrainz did not accept that token.")
        return body!!["user_name"]?.jsonPrimitive?.content ?: "ListenBrainz user"
    }

    fun nowPlaying(token: String, track: Track, player: String) = submit(token, "playing_now", listOf(null to track), player)

    fun scrobble(token: String, batch: List<Scrobble>) {
        batch.groupBy { it.player }.forEach { (player, plays) ->
            submit(token, if (plays.size == 1) "single" else "import", plays.map { it.startedAtSec to it.track }, player)
        }
    }

    private fun submit(token: String, type: String, listens: List<Pair<Long?, Track>>, player: String) {
        val body = buildJsonObject {
            put("listen_type", type)
            put(
                "payload",
                buildJsonArray {
                    listens.forEach { (at, track) ->
                        add(
                            buildJsonObject {
                                at?.let { put("listened_at", it) }
                                putJsonObject("track_metadata") {
                                    put("artist_name", track.artist)
                                    put("track_name", track.title)
                                    track.album?.let { put("release_name", it) }
                                    putJsonObject("additional_info") {
                                        track.durationMs?.let { put("duration_ms", it) }
                                        put("media_player", player)
                                        put("submission_client", "Lumen")
                                    }
                                }
                            },
                        )
                    }
                },
            )
        }
        val response = http.postJson("$apiRoot/1/submit-listens", body.toString(), auth(token))
        when {
            response.code in 200..299 -> Unit
            response.code == 401 -> throw ScrobbleException(ScrobbleException.Kind.SIGNED_OUT, "ListenBrainz rejected the token.")
            response.code == 429 || response.code >= 500 ->
                throw ScrobbleException(ScrobbleException.Kind.RETRY_LATER, "ListenBrainz is busy (HTTP ${response.code}).")
            else -> throw ScrobbleException(ScrobbleException.Kind.FATAL, "ListenBrainz refused the listens (HTTP ${response.code}).")
        }
    }

    private fun auth(token: String) = mapOf("Authorization" to "Token ${token.trim()}")

    companion object {
        const val DEFAULT_API = "https://api.listenbrainz.org"
        /** Where a signed-in user finds their token. */
        const val TOKEN_PAGE = "https://listenbrainz.org/settings/"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
