package dev.lumen.app.common

import dev.lumen.scrobble.ListenBrainz
import dev.lumen.scrobble.MediaSourceState
import dev.lumen.scrobble.NowPlaying
import dev.lumen.scrobble.ScrobbleManager
import dev.lumen.scrobble.ScrobbleService
import dev.lumen.ui.ScrobbleServiceRow
import dev.lumen.ui.ScrobbleSignIn
import dev.lumen.ui.ScrobbleUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Drives [ScrobbleManager] from a platform media source and turns its
 * status into what [dev.lumen.ui.ScrobbleSection] shows.
 */
class ScrobbleController(
    private val manager: ScrobbleManager,
    private val scope: CoroutineScope,
    /** Opens a link in the user's browser. */
    private val openUrl: (String) -> Unit,
    private val source: StateFlow<MediaSourceState>,
    /** Label of the button that fixes a [MediaSourceState.NeedsPermission]. */
    private val sourceActionLabel: String? = null,
) {
    private val waiting = MutableStateFlow<Map<ScrobbleService, String>>(emptyMap())
    private val busy = MutableStateFlow<String?>(null)
    private val localProblem = MutableStateFlow<String?>(null)
    private val signInJobs = mutableMapOf<ScrobbleService, Job>()
    private var attached = false

    /**
     * A viewer (Linux window while the service runs) signs in and out but
     * never sends: two processes flushing one queue send songs twice.
     */
    @Volatile var passive: Boolean = false

    val state: StateFlow<ScrobbleUiState> =
        combine(manager.status, waiting, busy, localProblem, source) { status, waits, busyText, problem, src ->
            ScrobbleUiState(
                services = ScrobbleService.entries.map { service ->
                    ScrobbleServiceRow(
                        id = service.name,
                        name = service.displayName,
                        signIn = if (service == ScrobbleService.LISTENBRAINZ) ScrobbleSignIn.TOKEN else ScrobbleSignIn.BROWSER,
                        connectedAs = status.accounts.firstOrNull { it.service == service }?.username,
                        waitingUrl = waits[service],
                        tokenPage = if (service == ScrobbleService.LISTENBRAINZ) ListenBrainz.TOKEN_PAGE else null,
                        unavailable = if (service == ScrobbleService.LASTFM && !status.lastFmAvailable) {
                            "This build has no Last.fm API key, so Last.fm sign-in is off. " +
                                "Libre.fm and ListenBrainz work without one."
                        } else {
                            null
                        },
                    )
                },
                sourceStatus = when (src) {
                    MediaSourceState.Ready -> if (status.accounts.isEmpty()) {
                        "Ready. Sign in to a service below to start scrobbling."
                    } else {
                        "Scrobbling what your media players play."
                    }
                    is MediaSourceState.NeedsPermission -> src.explanation
                    is MediaSourceState.Unavailable -> src.reason
                },
                sourceAction = if (src is MediaSourceState.NeedsPermission) sourceActionLabel else null,
                nowPlaying = status.nowPlaying?.let { "${it.artist} — ${it.title}" },
                pending = status.pending,
                lastSent = status.lastSentAtMs?.let(::formatTime),
                problem = problem ?: status.problem,
                includeBrowsers = status.includeBrowsers,
                busy = busyText,
            )
        }.stateIn(scope, SharingStarted.Eagerly, ScrobbleUiState(emptyList(), "Starting…"))

    /** Start consuming a media source. Call once. */
    fun attach(playback: Flow<NowPlaying>) {
        if (attached || passive) return
        attached = true
        scope.launch { playback.collect { manager.onPlayback(it) } }
        scope.launch {
            var sinceFlush = 0L
            while (true) {
                delay(TICK_MS)
                manager.tick()
                sinceFlush += TICK_MS
                if (sinceFlush >= FLUSH_MS) {
                    sinceFlush = 0
                    manager.flush()
                }
            }
        }
    }

    fun signIn(serviceId: String) {
        val service = ScrobbleService.valueOf(serviceId)
        signInJobs[service]?.cancel()
        signInJobs[service] = scope.launch {
            localProblem.value = null
            busy.value = "Opening ${service.displayName}…"
            try {
                val signIn = manager.beginWebSignIn(service)
                waiting.update { it + (service to signIn.url) }
                busy.value = null
                openUrl(signIn.url)
                manager.completeWebSignIn(signIn)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                localProblem.value = e.message ?: "Sign-in failed."
            } finally {
                busy.value = null
                waiting.update { it - service }
            }
        }
    }

    fun cancelSignIn(serviceId: String) {
        signInJobs.remove(ScrobbleService.valueOf(serviceId))?.cancel()
    }

    fun submitToken(serviceId: String, token: String) {
        require(serviceId == ScrobbleService.LISTENBRAINZ.name) { "only ListenBrainz signs in with a token" }
        scope.launch {
            localProblem.value = null
            busy.value = "Checking the token…"
            try {
                manager.signInListenBrainz(token)
            } catch (e: Throwable) {
                localProblem.value = e.message ?: "That token did not work."
            } finally {
                busy.value = null
            }
        }
    }

    // Keyring and store writes: off the UI thread.
    fun signOut(serviceId: String) {
        scope.launch { manager.signOut(ScrobbleService.valueOf(serviceId)) }
    }

    fun setIncludeBrowsers(on: Boolean) {
        scope.launch { manager.setIncludeBrowsers(on) }
    }

    fun sendNow() {
        if (passive) return
        scope.launch { manager.flush() }
    }

    private fun formatTime(ms: Long): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
        return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    }

    private companion object {
        const val TICK_MS = 10_000L
        const val FLUSH_MS = 10 * 60_000L
    }
}
