package dev.lumen.app.common

import dev.lumen.core.clock.UtcDay
import dev.lumen.core.crypto.Keychain
import dev.lumen.core.crypto.Omemo2E2EE
import dev.lumen.core.crypto.SecretStore
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.Setting
import dev.lumen.core.rollup.RollupEngine
import dev.lumen.core.store.LumenStore
import dev.lumen.core.sync.AccountTransport
import dev.lumen.core.sync.DeviceBundle
import dev.lumen.core.sync.DeviceTrust
import dev.lumen.core.sync.EncryptedTransport
import dev.lumen.core.sync.SyncEngine
import dev.lumen.core.sync.SyncReport
import dev.lumen.transport.RegistrationForm
import dev.lumen.transport.RegistrationField
import dev.lumen.transport.XmppRegistration
import dev.lumen.transport.XmppTransport
import dev.lumen.transport.XmppUserError
import dev.lumen.transport.providers.Providers
import dev.lumen.ui.AccountProviderOption
import dev.lumen.ui.AccountUiState
import dev.lumen.ui.DeviceRow
import dev.lumen.ui.SignUpChallenge
import dev.lumen.ui.SignUpField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/** Where this install syncs. The password is NOT here — it lives in the [SecretStore]. */
data class AccountConfig(val host: String, val port: Int, val jid: String)

/**
 * Account sign-up and the sync pass, shared by Linux and Android.
 *
 * ## The sync pass
 *
 *  1. Publish this device's bundle (id, X25519 identity key, label) to the
 *     account's device list, and read everyone else's.
 *  2. Pin new devices as PENDING ([DeviceTrust]); refuse changed keys.
 *     Only devices the user approved are encrypted to or accepted from;
 *     approving one re-publishes this device's retained history for it.
 *  3. Run [SyncEngine] over [EncryptedTransport] with [Omemo2E2EE]: every
 *     record is encrypted once and its key wrapped for each pinned device.
 *  4. Derive buckets and rollups for events pulled from other devices.
 *     They are derived data and never synced, so without this step pulled
 *     time sat in the events table and no screen ever showed it.
 *  5. Close the connection.
 */
class SyncController(
    private val store: LumenStore,
    private val deviceId: DeviceId,
    private val keychain: Keychain,
    private val secrets: SecretStore,
    private val deviceLabel: String,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
    private val transportFor: (AccountConfig, String) -> AccountTransport = { c, password ->
        XmppTransport(host = c.host, port = c.port, jid = c.jid, password = password)
    },
) {
    val providers: List<AccountProviderOption> = Providers.all.map {
        AccountProviderOption(
            jid = it.jid,
            tier = it.tier,
            location = Providers.describeLocations(it.locations),
            captcha = it.captcha,
            passwordReset = it.passwordReset,
            since = it.since,
        )
    }

    private val trust = DeviceTrust(store, deviceId)
    private val syncLock = Mutex()
    private val _state = MutableStateFlow<AccountUiState>(AccountUiState.Working("Loading\u2026"))
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    /** Called after a pass applied other devices' data, so screens can re-read the store. */
    var onRemoteData: () -> Unit = {}

    private var registration: XmppRegistration? = null
    private var pendingSignUp: Triple<String, String, String>? = null
    private var loop: Job? = null

    /** Keyring reads can spawn a subprocess; keep the password once read. */
    @Volatile private var cachedPassword: String? = null

    /**
     * A viewer (Linux window while the background service runs) configures
     * but never syncs: two processes syncing one database race on the
     * watermarks and the device pins.
     */
    @Volatile var passive: Boolean = false

    init {
        // Off the caller's thread: this reads the keyring, which on Linux is a
        // subprocess with a timeout and on Android the Keystore.
        scope.launch {
            migratePlaintextPassword()
            _state.value = connectedState() ?: AccountUiState.Unconfigured
        }
    }

    fun isConfigured(): Boolean = account() != null && password() != null

    fun account(): AccountConfig? {
        val host = setting(KEY_HOST) ?: return null
        val port = setting(KEY_PORT)?.toIntOrNull() ?: return null
        val jid = setting(KEY_JID) ?: return null
        return AccountConfig(host, port, jid)
    }

    // ---- sign-up / sign-in ----------------------------------------------

    fun register(server: String, username: String, password: String) {
        scope.launch {
            _state.value = AccountUiState.Working("Contacting $server…")
            closeRegistration()
            try {
                val reg = XmppRegistration.begin(server)
                registration = reg
                pendingSignUp = Triple(reg.domain, username, password)
                val form = reg.form()
                if (form.captcha == null && form.extraFields.none { it.required && it.kind != RegistrationField.Kind.INFO }) {
                    finishSignUp(emptyMap())
                } else {
                    _state.value = AccountUiState.Challenge(form.toChallenge())
                }
            } catch (e: Throwable) {
                closeRegistration()
                _state.value = AccountUiState.Failed(e.userMessage())
            }
        }
    }

    fun answerChallenge(answers: Map<String, String>) {
        scope.launch {
            _state.value = AccountUiState.Working("Creating your account…")
            finishSignUp(answers)
        }
    }

    fun cancelSignUp() {
        _state.value = AccountUiState.Unconfigured
        scope.launch { closeRegistration() }
    }

    private suspend fun finishSignUp(answers: Map<String, String>) {
        val reg = registration
        val (domain, username, password) = pendingSignUp ?: return
        if (reg == null) {
            _state.value = AccountUiState.Failed("The sign-up timed out. Please try again.")
            return
        }
        try {
            val jid = reg.submit(username, password, answers)
            closeRegistration()
            saveAccount(AccountConfig(domain, PORT, jid), password)
            _state.value = connectedState() ?: AccountUiState.Unconfigured
            syncNow()
        } catch (e: Throwable) {
            val kind = (e as? XmppUserError)?.kind
            // A wrong CAPTCHA answer burns the challenge; ask for a fresh one
            // on the same connection rather than sending the user back to start.
            if (kind == XmppUserError.Kind.REJECTED) {
                val fresh = runCatching { reg.form() }.getOrNull()
                if (fresh?.captcha != null || fresh?.extraFields?.isNotEmpty() == true) {
                    _state.value = AccountUiState.Challenge(fresh.toChallenge(), e.userMessage())
                    return
                }
            }
            closeRegistration()
            _state.value = AccountUiState.Failed(e.userMessage())
        }
    }

    /** "I already have an account": verify the password before claiming success. */
    fun signIn(server: String, username: String, password: String) {
        scope.launch {
            val domain = server.trim().lowercase()
            val config = AccountConfig(domain, PORT, "${username.trim()}@$domain")
            _state.value = AccountUiState.Working("Signing in to $domain…")
            val transport = transportFor(config, password)
            try {
                transport.verifyLogin()
                saveAccount(config, password)
                _state.value = connectedState() ?: AccountUiState.Unconfigured
                syncNow()
            } catch (e: Throwable) {
                _state.value = AccountUiState.Failed(e.userMessage())
            } finally {
                withContext(NonCancellable) { transport.close() }
            }
        }
    }

    /**
     * Forget the account. Also resets what belongs to it: the device pins
     * (they are that account's devices) and the outbox marker, so signing in
     * to a DIFFERENT account publishes this device's history there instead
     * of starting after the old account's watermark.
     */
    fun disconnect() {
        _state.value = AccountUiState.Unconfigured
        scope.launch {
            for (key in listOf(KEY_HOST, KEY_PORT, KEY_JID, KEY_LAST_SYNC, KEY_WARNINGS, KEY_OUTBOX_READY, DeviceTrust.KEY)) {
                putSetting(key, "")
            }
            secrets.remove(SECRET_PASSWORD)
            cachedPassword = null
        }
    }

    /**
     * The user compared codes and approved [id]. Its key is now used, its
     * records are accepted, and this device re-publishes its retained
     * history (events are kept ~30 days) so the new device can read it —
     * items are keyed by seq range, so this overwrites rather than duplicates.
     */
    fun approveDevice(id: String) {
        scope.launch {
            if (!trust.approve(DeviceId(id), clock())) return@launch
            store.setAckedSeq(deviceId, -1)
            refreshConnected()
            syncOnce()
        }
    }

    fun forgetDevice(id: String) {
        scope.launch {
            trust.forget(DeviceId(id), clock())
            refreshConnected()
            // Also drop it from the account's list, or the next pass simply
            // pins it again. If it is a real device it re-announces itself.
            val config = account() ?: return@launch
            val password = password() ?: return@launch
            val transport = transportFor(config, password)
            runCatching { transport.deviceDirectory().remove(DeviceId(id)) }
            transport.close()
            refreshConnected()
        }
    }

    // ---- sync -----------------------------------------------------------

    fun syncNow() {
        scope.launch { syncOnce() }
    }

    /** Sync immediately, then every [intervalMs]. Idempotent. */
    fun startLoop(intervalMs: Long = SYNC_INTERVAL_MS) {
        if (passive || loop?.isActive == true) return
        loop = scope.launch {
            while (true) {
                if (isConfigured()) syncOnce()
                delay(intervalMs)
            }
        }
    }

    /**
     * One pass. Returns null when sync is not configured. Never throws: a
     * failure is reported in [state] and the next pass tries again.
     */
    suspend fun syncOnce(): SyncReport? = syncLock.withLock {
        if (passive) return null
        val config = account() ?: return null
        val password = password() ?: return null
        setSyncing(true, null)
        val transport = transportFor(config, password)
        try {
            val keyPair = keychain.deviceKeyPair()
            val sk = X25519PrivateKeyParameters(keyPair.privateKeyHandle)
            val pk = X25519PublicKeyParameters(keyPair.publicKey)

            val directory = transport.deviceDirectory()
            val own = DeviceBundle(deviceId, DeviceTrust.encodeKey(keyPair.publicKey), deviceLabel, clock())
            val bundles = directory.bundles()
            val published = bundles.firstOrNull { it.deviceId == deviceId }
            if (published == null || published.identityKey != own.identityKey || published.label != own.label) {
                directory.publish(own)
            }
            val reconciled = trust.reconcile(bundles, clock())
            val recipients = reconciled.trusted.map { it.deviceId }.toSet()

            initialiseOutbox()
            val e2ee = Omemo2E2EE(deviceId, sk, pk, publicKeyResolver = { id ->
                trust.keyFor(id)?.let { X25519PublicKeyParameters(it) }
            })
            val engine = SyncEngine(store, EncryptedTransport(transport, e2ee, recipients = { recipients }), deviceId)
            val report = engine.sync()
            deriveRemote(report.remoteEvents)

            putSetting(KEY_LAST_SYNC, clock().toString())
            // A pending device's records are held back until it is approved —
            // that is shown as an Approve prompt, not as an error.
            val pendingIds = reconciled.pending.map { it.deviceId.value }
            val shown = (reconciled.warnings + report.integrityWarnings)
                .filterNot { w -> pendingIds.any { w.contains(it) } }
            putSetting(KEY_WARNINGS, shown.distinct().take(MAX_WARNINGS).joinToString("\n"))
            setSyncing(false, null)
            if (report.remoteEvents.isNotEmpty()) onRemoteData()
            report
        } catch (e: kotlinx.coroutines.CancellationException) {
            setSyncing(false, null)
            throw e
        } catch (e: Throwable) {
            // Re-read next time: the password may have been changed elsewhere
            // (on Linux, by the window while this is the service).
            if ((e as? XmppUserError)?.kind == XmppUserError.Kind.BAD_CREDENTIALS) cachedPassword = null
            setSyncing(false, "Sync failed: ${e.userMessage()}")
            null
        } finally {
            withContext(NonCancellable) { transport.close() }
        }
    }

    /** Buckets for other devices' events, and the UTC rollups of their days. */
    private fun deriveRemote(events: List<FocusEvent>) {
        if (events.isEmpty()) return
        events.forEach { event -> RollupEngine.bucket(event).forEach(store::insertBucket) }
        events.map { it.deviceId to UtcDay.dayOf(it.startedAtMs) }.distinct().forEach { (device, day) ->
            val start = UtcDay.boundary(day)
            RollupEngine.rollup(device, day, store.bucketsForRange(device, start, start + DAY_MS)).forEach(store::upsertRollup)
        }
    }

    /**
     * The first pass after an account is set up must push everything. The
     * store's watermark default is 0, which would skip an event with seq 0;
     * Linux set -1 at device creation, Android never did.
     */
    private fun initialiseOutbox() {
        if (setting(KEY_OUTBOX_READY) == "1") return
        store.setAckedSeq(deviceId, -1)
        putSetting(KEY_OUTBOX_READY, "1")
    }

    /** Peer device ids that have delivered data — for "all devices" views. */
    fun peerDevices(): List<DeviceId> =
        store.setting(SyncEngine.PEERS_KEY)?.value?.decodeToString()?.split(',')
            ?.filter { it.isNotBlank() }?.map(::DeviceId).orEmpty()

    // ---- state ----------------------------------------------------------

    private fun connectedState(): AccountUiState.Connected? {
        val config = account() ?: return null
        if (password() == null) return null
        val ownKey = runCatching { keychain.deviceKeyPair().publicKey }.getOrNull()
        val devices = buildList {
            ownKey?.let { add(DeviceRow(deviceId.value, deviceLabel, Omemo2E2EE.fingerprintOf(it), thisDevice = true)) }
            trust.pinned().sortedBy { it.approved }.forEach { pin ->
                val key = trust.pinnedKey(pin.deviceId) ?: return@forEach
                add(
                    DeviceRow(
                        pin.deviceId.value, pin.label, Omemo2E2EE.fingerprintOf(key),
                        thisDevice = false, pending = !pin.approved,
                    ),
                )
            }
        }
        return AccountUiState.Connected(
            username = config.jid.substringBefore('@'),
            host = config.host,
            fingerprint = ownKey?.let(Omemo2E2EE::fingerprintOf),
            devices = devices,
            lastSync = setting(KEY_LAST_SYNC)?.toLongOrNull()?.let(::formatTime),
            warnings = setting(KEY_WARNINGS)?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
        )
    }

    private fun refreshConnected() {
        val current = _state.value as? AccountUiState.Connected ?: return
        connectedState()?.let { _state.value = it.copy(syncing = current.syncing, problem = current.problem) }
    }

    private fun setSyncing(syncing: Boolean, problem: String?) {
        // Computed OUTSIDE update {}: that lambda re-runs on contention, and
        // this reads the store and the keyring.
        val fresh = connectedState()?.copy(syncing = syncing, problem = problem) ?: return
        _state.update { current ->
            if (current is AccountUiState.Connected || current is AccountUiState.Unconfigured) fresh else current
        }
    }

    private fun formatTime(ms: Long): String {
        val now = clock()
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).date
        val hm = "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
        return if (t.date == today) "today at $hm" else "${t.date} at $hm"
    }

    // ---- storage --------------------------------------------------------

    private fun saveAccount(config: AccountConfig, password: String) {
        secrets.put(SECRET_PASSWORD, password)
        cachedPassword = password
        putSetting(KEY_HOST, config.host)
        putSetting(KEY_PORT, config.port.toString())
        putSetting(KEY_JID, config.jid)
        putSetting(KEY_PASSWORD_LEGACY, "")
    }

    /**
     * Cached once found. A miss is NOT cached: on Linux the window may sign
     * in while the service is running, and the service must pick that up.
     */
    private fun password(): String? =
        cachedPassword ?: secrets.get(SECRET_PASSWORD)?.also { cachedPassword = it }

    /** Builds before this one kept the account password in the settings table, in plaintext. */
    private fun migratePlaintextPassword() {
        val legacy = setting(KEY_PASSWORD_LEGACY) ?: return
        secrets.put(SECRET_PASSWORD, legacy)
        cachedPassword = legacy
        putSetting(KEY_PASSWORD_LEGACY, "")
    }

    private fun closeRegistration() {
        registration?.close()
        registration = null
    }

    private fun setting(key: String): String? =
        store.setting(key)?.value?.decodeToString()?.takeIf { it.isNotBlank() }

    private fun putSetting(key: String, value: String) {
        val now = clock()
        store.upsertSetting(Setting(key, value.encodeToByteArray(), now, UtcDay.dayOf(now), deviceId))
    }

    companion object {
        const val KEY_HOST = "sync.host"
        const val KEY_PORT = "sync.port"
        const val KEY_JID = "sync.jid"
        private const val KEY_PASSWORD_LEGACY = "sync.password"
        private const val KEY_LAST_SYNC = "sync.last_ok_ms"
        private const val KEY_WARNINGS = "sync.warnings"
        private const val KEY_OUTBOX_READY = "sync.outbox_initialised"
        private const val SECRET_PASSWORD = "sync.password"
        private const val PORT = 5222
        private const val MAX_WARNINGS = 8
        private const val DAY_MS = 86_400_000L
        const val SYNC_INTERVAL_MS = 5L * 60 * 1000
    }
}

private fun Throwable.userMessage(): String = (this as? XmppUserError)?.message ?: message ?: this::class.simpleName ?: "Unknown error"

internal fun RegistrationForm.toChallenge() = SignUpChallenge(
    domain = domain,
    instructions = instructions,
    captchaImage = captcha?.image,
    captchaField = captcha?.answerField,
    captchaLabel = captcha?.label,
    captchaLink = captcha?.imageUrl,
    fields = extraFields.map {
        SignUpField(
            name = it.name,
            label = it.label,
            secret = it.kind == RegistrationField.Kind.SECRET,
            required = it.required,
            info = it.kind == RegistrationField.Kind.INFO,
            boolean = it.kind == RegistrationField.Kind.BOOLEAN,
            defaultValue = it.defaultValue,
        )
    },
    webPage = webPage,
)
