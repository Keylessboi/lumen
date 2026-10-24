package dev.lumen.scrobble

import dev.lumen.core.clock.UtcDay
import dev.lumen.core.crypto.SecretStore
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.Setting
import dev.lumen.core.store.LumenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Everything scrobbling, for one install: which services are signed in,
 * what is playing, the offline queue, and sending.
 *
 * Usernames and preferences live in the local settings table; tokens and
 * session keys live in the [SecretStore] (keyring). Nothing here is ever
 * part of sync — listening history goes to the services the user chose,
 * not to the sync server.
 *
 * The queue holds each play until every signed-in service has it, so a
 * laptop offline on a train scrobbles when it is back online. Plays older
 * than [MAX_AGE_SEC] are dropped: Last.fm refuses timestamps over 14 days
 * old, and a queue that can never drain helps nobody.
 */
class ScrobbleManager(
    private val store: LumenStore,
    private val secrets: SecretStore,
    private val deviceId: DeviceId,
    private val http: Http = JvmHttp(),
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Where network sends run. With a scope, playback handling never waits on
     * the network — a black-holed connection used to stall the media
     * collector for the HTTP timeouts, so plays were timed late or missed.
     * Without one (tests), sends run inline.
     */
    private val background: CoroutineScope? = null,
) {
    data class Account(val service: ScrobbleService, val username: String)

    data class Status(
        val accounts: List<Account> = emptyList(),
        val nowPlaying: Track? = null,
        val nowPlayingPlayer: String? = null,
        val pending: Int = 0,
        val lastSentAtMs: Long? = null,
        val problem: String? = null,
        val includeBrowsers: Boolean = false,
        val lastFmAvailable: Boolean = false,
    )

    private val lastFm = Audioscrobbler.lastFm(http)
    private val libreFm = Audioscrobbler.libreFm(http)
    /** Guards the play tracker. Never held across network I/O. */
    private val trackerLock = Mutex()
    /** Guards the queue's read-modify-write, and serialises flushes. */
    private val queueLock = Mutex()
    private val tracker = PlayTracker(ignore = { player -> !includeBrowsers() && PlayTracker.isBrowser(player) })
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        refreshStatus()
    }

    fun accounts(): List<Account> = ScrobbleService.entries.mapNotNull { service ->
        val user = setting(userKey(service)) ?: return@mapNotNull null
        if (secrets.get(secretKey(service)) == null) null else Account(service, user)
    }

    fun includeBrowsers(): Boolean = setting(KEY_BROWSERS) == "1"

    fun setIncludeBrowsers(on: Boolean) {
        putSetting(KEY_BROWSERS, if (on) "1" else "0")
        refreshStatus()
    }

    // ---- sign-in ---------------------------------------------------------

    /** A started browser sign-in: open [url], then call [completeWebSignIn]. */
    data class WebSignIn(val service: ScrobbleService, val token: String, val url: String)

    suspend fun beginWebSignIn(service: ScrobbleService): WebSignIn = io {
        val api = audioscrobbler(service)
        val token = api.requestToken()
        WebSignIn(service, token, api.authorizeUrl(token))
    }

    /**
     * Wait for the user to approve in the browser. Polls `auth.getSession`
     * every few seconds until it succeeds or [timeoutMs] passes.
     */
    suspend fun completeWebSignIn(signIn: WebSignIn, timeoutMs: Long = 5 * 60_000L): Account {
        val api = audioscrobbler(signIn.service)
        val deadline = clock() + timeoutMs
        while (true) {
            try {
                val session = io { api.fetchSession(signIn.token) }
                return saveAccount(signIn.service, session.username, session.key)
            } catch (e: ScrobbleException) {
                // Keep waiting through "not approved yet" AND through network
                // blips: the user is in the browser, often switching networks
                // or apps, and one failed poll used to end the sign-in.
                val transient = e.kind == ScrobbleException.Kind.PENDING_APPROVAL ||
                    e.kind == ScrobbleException.Kind.OFFLINE || e.kind == ScrobbleException.Kind.RETRY_LATER
                if (!transient || clock() > deadline) throw e
            }
            delay(POLL_MS)
        }
    }

    suspend fun signInListenBrainz(token: String): Account {
        val user = io { ListenBrainz(http).validate(token) }
        return saveAccount(ScrobbleService.LISTENBRAINZ, user, token.trim())
    }

    fun signOut(service: ScrobbleService) {
        secrets.remove(secretKey(service))
        putSetting(userKey(service), "")
        refreshStatus()
    }

    // ---- playback --------------------------------------------------------

    /** Feed every change the media source reports. */
    suspend fun onPlayback(now: NowPlaying) {
        val atMs = clock()
        val (events, current) = trackerLock.withLock { tracker.update(now, atMs) to tracker.current }
        _status.update { it.copy(nowPlaying = current, nowPlayingPlayer = now.player.takeIf { current != null }) }
        handle(events)
    }

    /** Call periodically (every ~10 s) so plays qualify mid-song. */
    suspend fun tick() = handle(trackerLock.withLock { tracker.tick(clock()) })

    private suspend fun handle(events: List<PlayTracker.Event>) {
        if (events.isEmpty()) return
        val accounts = accounts()
        events.forEach { event ->
            when (event) {
                is PlayTracker.Event.Started -> accounts.forEach { account ->
                    // Best effort by design: a missed "now playing" is cosmetic.
                    send { runCatching { io { sendNowPlaying(account.service, event.track, event.player) } } }
                }
                is PlayTracker.Event.Qualified -> if (accounts.isNotEmpty()) {
                    queueLock.withLock { enqueue(event.scrobble, accounts.map { it.service }.toSet()) }
                }
            }
        }
        if (events.any { it is PlayTracker.Event.Qualified }) send { flush() }
    }

    private suspend fun send(block: suspend () -> Unit) {
        val scope = background
        if (scope == null) block() else scope.launch { block() }
    }

    // ---- queue -----------------------------------------------------------

    @Serializable
    private data class Pending(val scrobble: Scrobble, val services: Set<ScrobbleService>)

    /** Send everything queued. Safe to call any time; also runs after each play. */
    suspend fun flush() = queueLock.withLock { flushLocked() }

    private suspend fun flushLocked() {
        val accounts = accounts().associateBy { it.service }
        var queue = queue().filter { it.scrobble.startedAtSec >= clock() / 1000 - MAX_AGE_SEC }
            .map { p -> p.copy(services = p.services.filterTo(mutableSetOf()) { it in accounts }) }
            .filter { it.services.isNotEmpty() }
        var problem: String? = null
        var sent = false
        for (service in accounts.keys) {
            val mine = queue.filter { service in it.services }
            for (batch in mine.chunked(Audioscrobbler.MAX_BATCH)) {
                val result = runCatching { io { send(service, batch.map { it.scrobble }) } }
                val error = result.exceptionOrNull() as? ScrobbleException ?: result.exceptionOrNull()?.let {
                    ScrobbleException(ScrobbleException.Kind.OFFLINE, it.message ?: "send failed", it)
                }
                if (error == null) {
                    val done = batch.toSet()
                    queue = queue.map { if (it in done) it.copy(services = it.services - service) else it }
                    sent = true
                    continue
                }
                problem = error.message
                when (error.kind) {
                    // Will never succeed with these credentials: stop retrying them.
                    ScrobbleException.Kind.SIGNED_OUT -> {
                        signOutQuietly(service)
                        problem = "${service.displayName} signed Lumen out. Sign in again to keep scrobbling."
                    }
                    ScrobbleException.Kind.FATAL -> {
                        val done = batch.toSet()
                        queue = queue.map { if (it in done) it.copy(services = it.services - service) else it }
                    }
                    else -> Unit
                }
                break
            }
        }
        saveQueue(queue.filter { it.services.isNotEmpty() })
        _status.update {
            it.copy(problem = problem, lastSentAtMs = if (sent) clock() else it.lastSentAtMs)
        }
        refreshStatus(keepProblem = true)
    }

    private fun enqueue(scrobble: Scrobble, services: Set<ScrobbleService>) {
        saveQueue((queue() + Pending(scrobble, services)).takeLast(MAX_QUEUE))
    }

    private fun queue(): List<Pending> =
        setting(KEY_QUEUE)?.let { runCatching { json.decodeFromString(ListSerializer(Pending.serializer()), it) }.getOrNull() }
            .orEmpty()

    private fun saveQueue(queue: List<Pending>) =
        putSetting(KEY_QUEUE, json.encodeToString(ListSerializer(Pending.serializer()), queue))

    // ---- services --------------------------------------------------------

    private fun audioscrobbler(service: ScrobbleService) = when (service) {
        ScrobbleService.LASTFM -> lastFm
        ScrobbleService.LIBREFM -> libreFm
        ScrobbleService.LISTENBRAINZ -> error("ListenBrainz signs in with a token, not the browser")
    }

    private fun sendNowPlaying(service: ScrobbleService, track: Track, player: String) {
        val secret = secrets.get(secretKey(service)) ?: return
        when (service) {
            ScrobbleService.LISTENBRAINZ -> ListenBrainz(http).nowPlaying(secret, track, player)
            else -> audioscrobbler(service).nowPlaying(secret, track)
        }
    }

    private fun send(service: ScrobbleService, batch: List<Scrobble>) {
        val secret = secrets.get(secretKey(service))
            ?: throw ScrobbleException(ScrobbleException.Kind.SIGNED_OUT, "not signed in")
        when (service) {
            ScrobbleService.LISTENBRAINZ -> ListenBrainz(http).scrobble(secret, batch)
            else -> audioscrobbler(service).scrobble(secret, batch)
        }
    }

    private fun saveAccount(service: ScrobbleService, username: String, secret: String): Account {
        secrets.put(secretKey(service), secret)
        putSetting(userKey(service), username)
        refreshStatus()
        return Account(service, username)
    }

    private fun signOutQuietly(service: ScrobbleService) {
        secrets.remove(secretKey(service))
        putSetting(userKey(service), "")
    }

    private fun refreshStatus(keepProblem: Boolean = false) {
        _status.update {
            it.copy(
                accounts = accounts(),
                pending = queue().size,
                includeBrowsers = includeBrowsers(),
                lastFmAvailable = lastFm.configured,
                problem = if (keepProblem) it.problem else null,
            )
        }
    }

    private fun setting(key: String): String? =
        store.setting(key)?.value?.decodeToString()?.takeIf { it.isNotEmpty() }

    private fun putSetting(key: String, value: String) {
        val now = clock()
        store.upsertSetting(Setting(key, value.encodeToByteArray(), now, UtcDay.dayOf(now), deviceId))
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val KEY_QUEUE = "scrobble.queue"
        private const val KEY_BROWSERS = "scrobble.include_browsers"
        private fun userKey(service: ScrobbleService) = "scrobble.${service.name.lowercase()}.user"
        private fun secretKey(service: ScrobbleService) = "scrobble.${service.name.lowercase()}"
        private const val POLL_MS = 3_000L
        private const val MAX_QUEUE = 1_000
        private const val MAX_AGE_SEC = 14L * 24 * 3600
        private val json = Json { ignoreUnknownKeys = true }
    }
}
