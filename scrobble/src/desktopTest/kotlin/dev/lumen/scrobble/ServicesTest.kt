package dev.lumen.scrobble

import dev.lumen.core.crypto.InMemorySecretStore
import dev.lumen.core.model.DeviceId
import dev.lumen.core.store.JvmLumenStore
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServicesTest {

    private class FakeHttp : Http {
        val calls = mutableListOf<Pair<String, Any>>()
        val replies = ArrayDeque<Http.Response>()
        var offline = false
        private fun reply(): Http.Response {
            if (offline) throw ScrobbleException(ScrobbleException.Kind.OFFLINE, "offline")
            return replies.removeFirstOrNull() ?: Http.Response(200, "{}")
        }
        override fun postForm(url: String, form: Map<String, String>) = reply().also { calls += url to form }
        override fun postJson(url: String, json: String, headers: Map<String, String>) = reply().also { calls += url to json }
        override fun get(url: String, headers: Map<String, String>) = reply().also { calls += url to headers }
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `api_sig is md5 of sorted name-value pairs plus secret, without format`() {
        val sig = Audioscrobbler.sign(mapOf("method" to "auth.getToken", "api_key" to "k", "format" to "json"), "s")
        assertEquals(md5("api_keykmethodauth.getTokens"), sig)
    }

    @Test
    fun `web sign-in builds the approval link and polls until approved`() = runBlocking {
        val http = FakeHttp()
        http.replies += Http.Response(200, """{"token":"tok123"}""")
        http.replies += Http.Response(200, """{"error":14,"message":"Unauthorized Token"}""")
        http.replies += Http.Response(200, """{"session":{"name":"linus","key":"sk-1","subscriber":0}}""")
        var now = 0L
        val manager = ScrobbleManager(JvmLumenStore.inMemory(), InMemorySecretStore(), DeviceId("d"), http, clock = { now })

        val signIn = manager.beginWebSignIn(ScrobbleService.LIBREFM)
        assertTrue(signIn.url.startsWith("https://libre.fm/api/auth/?api_key="))
        assertTrue(signIn.url.endsWith("&token=tok123"))
        val account = manager.completeWebSignIn(signIn)
        assertEquals("linus", account.username)
        assertEquals(listOf(ScrobbleManager.Account(ScrobbleService.LIBREFM, "linus")), manager.accounts())
    }

    @Test
    fun `a network blip while waiting for approval does not end the sign-in`() = runBlocking {
        val http = object : Http {
            var calls = 0
            override fun postForm(url: String, form: Map<String, String>): Http.Response {
                calls++
                return when (calls) {
                    1 -> Http.Response(200, """{"token":"t"}""")
                    2 -> throw ScrobbleException(ScrobbleException.Kind.OFFLINE, "blip")
                    else -> Http.Response(200, """{"session":{"name":"u","key":"k"}}""")
                }
            }
            override fun postJson(url: String, json: String, headers: Map<String, String>) = Http.Response(200, "{}")
            override fun get(url: String, headers: Map<String, String>) = Http.Response(200, "{}")
        }
        val manager = ScrobbleManager(JvmLumenStore.inMemory(), InMemorySecretStore(), DeviceId("d"), http)
        assertEquals("u", manager.completeWebSignIn(manager.beginWebSignIn(ScrobbleService.LIBREFM)).username)
    }

    @Test
    fun `last fm without an api key explains instead of crashing`() = runBlocking {
        val manager = ScrobbleManager(JvmLumenStore.inMemory(), InMemorySecretStore(), DeviceId("d"), FakeHttp())
        val e = assertFailsWith<ScrobbleException> { manager.beginWebSignIn(ScrobbleService.LASTFM) }
        assertTrue(e.message!!.contains("API key"))
    }

    @Test
    fun `plays queue while offline and send when back`() = runBlocking {
        val http = FakeHttp()
        val secrets = InMemorySecretStore()
        var now = 1_000_000_000_000L
        http.replies += Http.Response(200, """{"valid":true,"user_name":"lb-user"}""")
        val manager = ScrobbleManager(JvmLumenStore.inMemory(), secrets, DeviceId("d"), http, clock = { now })
        manager.signInListenBrainz("  token-abc  ")

        http.offline = true
        manager.onPlayback(NowPlaying("mpd", Track("A", "T", durationMs = 100_000), playing = true))
        now += 60_000
        manager.tick()
        assertEquals(1, manager.status.value.pending)

        http.offline = false
        http.calls.clear()
        manager.flush()
        assertEquals(0, manager.status.value.pending)
        val (url, body) = http.calls.single()
        assertEquals("https://api.listenbrainz.org/1/submit-listens", url)
        assertTrue((body as String).contains("\"listen_type\":\"single\""))
        assertTrue(body.contains("\"artist_name\":\"A\""))
        assertEquals("token-abc", secrets.get("scrobble.listenbrainz"))
    }

    @Test
    fun `a revoked session signs out instead of retrying forever`() = runBlocking {
        val http = FakeHttp()
        var now = 1_000_000_000_000L
        http.replies += Http.Response(200, """{"token":"t"}""")
        http.replies += Http.Response(200, """{"session":{"name":"u","key":"k"}}""")
        val manager = ScrobbleManager(JvmLumenStore.inMemory(), InMemorySecretStore(), DeviceId("d"), http, clock = { now })
        manager.completeWebSignIn(manager.beginWebSignIn(ScrobbleService.LIBREFM))

        manager.onPlayback(NowPlaying("mpd", Track("A", "T", durationMs = 100_000), playing = true))
        http.replies += Http.Response(200, """{"error":9,"message":"Invalid session key"}""")
        now += 60_000
        manager.tick()

        assertTrue(manager.accounts().isEmpty())
        assertTrue(manager.status.value.problem!!.contains("Sign in again"))
    }
}
