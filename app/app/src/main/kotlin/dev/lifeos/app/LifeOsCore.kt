package dev.lifeos.app

import android.app.Application
import android.os.SystemClock
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import dev.lifeos.app.crypto.E2e
import dev.lifeos.app.crypto.LazySodiumBackend
import dev.lifeos.app.crypto.formatComparisonCode
import dev.lifeos.app.lock.LockTimeout
import dev.lifeos.app.pairing.Paired
import dev.lifeos.app.pairing.Pairing
import dev.lifeos.app.pairing.PairingFailure
import dev.lifeos.app.pairing.PairingOffer
import dev.lifeos.app.pairing.PairingRecord
import dev.lifeos.app.pairing.PairingStore
import dev.lifeos.app.pairing.SecretVault
import dev.lifeos.app.pairing.VaultException
import dev.lifeos.app.pairing.Vaults
import dev.lifeos.app.relay.Channel
import dev.lifeos.app.relay.FirebaseAuthRest
import dev.lifeos.app.relay.Http
import dev.lifeos.app.relay.Link
import dev.lifeos.app.relay.RelayClient
import dev.lifeos.app.relay.RelayException
import dev.lifeos.app.relay.RtdbMailbox
import dev.lifeos.app.relay.TokenSource
import dev.lifeos.app.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.io.File

/** The state of the path to the computer, as the dot in the top bar shows it. */
enum class ConnectionState { NOT_PAIRED, CONNECTING, CONNECTED, UNREACHABLE }

/** What the screens may know about the pairing. Never the secret. */
data class PairingSummary(val name: String, val pairedAtMillis: Long)

sealed interface PairState {
    data object Idle : PairState
    data object Working : PairState

    /** [code] is already formatted as `123 456`. */
    data class Compare(val code: String) : PairState
    data class Done(val name: String) : PairState
    data class Failed(val reason: PairProblem) : PairState
}

enum class PairProblem { NEEDS_CONFIRMATION, NO_SCREEN_LOCK, UNREACHABLE, REFUSED, TIMEOUT, STORAGE }

sealed interface TestResult {
    data class Ok(val version: String, val millis: Long) : TestResult
    data class Failed(val problem: TestProblem, val status: Int = 0) : TestResult
}

enum class TestProblem { NOT_PAIRED, RELAY_UNREACHABLE, REFUSED, NO_ANSWER, BAD_ANSWER }

enum class UnlockResult { UNLOCKED, NEEDS_CONFIRMATION, PAIRING_LOST }

/**
 * Everything that outlives a screen: the pairing, the app lock and the connection to the
 * computer. One instance per process, owned by [LifeOsApplication].
 */
class LifeOsCore(private val app: Application) {
    val settings = SettingsStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = PairingStore(File(app.filesDir, "pairing"))
    private val http: OkHttpClient by lazy { Http.client() }
    private val e2e: E2e by lazy { E2e(LazySodiumBackend(LazySodiumAndroid(SodiumAndroid()))) }

    private val pairingState = MutableStateFlow<PairingSummary?>(null)
    private val lockedState = MutableStateFlow(false)
    private val connectionState = MutableStateFlow(ConnectionState.NOT_PAIRED)
    private val lastContactState = MutableStateFlow<Long?>(null)
    private val pairState = MutableStateFlow<PairState>(PairState.Idle)

    val pairing: StateFlow<PairingSummary?> = pairingState
    val locked: StateFlow<Boolean> = lockedState
    val connection: StateFlow<ConnectionState> = connectionState
    val lastContactMillis: StateFlow<Long?> = lastContactState
    val pairProgress: StateFlow<PairState> = pairState

    private class Session(val job: Job, val channel: Channel, val client: RelayClient)

    private val lock = Any()
    private var record: PairingRecord? = null
    private var session: Session? = null
    private var pairJob: Job? = null
    private var hiddenAt: Long? = null
    private var lockTimer: Job? = null
    private var lastContactWrite = 0L

    init {
        val stored = store.load()
        if (stored != null && vaultFor(stored.vault) == null) {
            // A pairing this build cannot open (made with a debug test aid): it is removed.
            store.wipe()
        } else if (stored != null) {
            record = stored
            pairingState.value = PairingSummary(stored.name, stored.pairedAtMillis)
            lastContactState.value = store.lastContactMillis()
            connectionState.value = ConnectionState.CONNECTING
            if (needsLock(stored)) lockedState.value = true else unlock()
        }
    }

    private fun vaultFor(kind: String): SecretVault? =
        if (kind == Vaults.AUTH) Vaults.authBound() else TestAids.vault(kind)

    /** Real pairings are behind the app lock; a debug test pairing has no lock. */
    private fun needsLock(record: PairingRecord): Boolean = record.vault == Vaults.AUTH

    // ------------------------------------------------------------------ app lock

    /** The app went out of sight. */
    fun onHidden() {
        val current = record ?: return
        if (!needsLock(current) || lockedState.value) return
        hiddenAt = SystemClock.elapsedRealtime()
        lockTimer?.cancel()
        lockTimer = scope.launch {
            val timeout = settings.lockTimeout.first()
            delay(timeout.millis)
            lockNow()
        }
    }

    /** The app is visible again: lock if it was away for longer than the chosen time. */
    suspend fun onShown() {
        lockTimer?.cancel()
        val away = hiddenAt
        hiddenAt = null
        val current = record ?: return
        if (!needsLock(current) || lockedState.value) return
        val timeout: LockTimeout = settings.lockTimeout.first()
        if (timeout.shouldLock(away, SystemClock.elapsedRealtime())) lockNow()
    }

    /** Forget the secret and stop talking to the relay until the owner confirms again. */
    fun lockNow() {
        synchronized(lock) {
            val current = record ?: return
            if (!needsLock(current)) return
            stopSession()
            lockedState.value = true
            connectionState.value = ConnectionState.CONNECTING
        }
    }

    /**
     * Called after the owner confirmed with fingerprint or screen lock (or, for a debug test
     * pairing, directly): decrypt the secret with the Keystore key and connect.
     */
    fun unlock(): UnlockResult = synchronized(lock) {
        val current = record ?: return UnlockResult.PAIRING_LOST
        if (session != null) {
            lockedState.value = false
            return UnlockResult.UNLOCKED
        }
        val sealed = store.sealedSecret()
        val vault = vaultFor(current.vault)
        val secret = try {
            if (sealed == null || vault == null) throw VaultException(VaultException.Reason.LOST)
            vault.decrypt(sealed)
        } catch (error: VaultException) {
            if (error.reason == VaultException.Reason.NEEDS_AUTHENTICATION) return UnlockResult.NEEDS_CONFIRMATION
            forget(current)
            return UnlockResult.PAIRING_LOST
        }
        try {
            startSession(current, secret)
        } catch (_: Exception) {
            // The counters cannot be read: without them no message may be sent or accepted.
            forget(current)
            return UnlockResult.PAIRING_LOST
        } finally {
            secret.fill(0)
        }
        lockedState.value = false
        UnlockResult.UNLOCKED
    }

    // ------------------------------------------------------------------ connection

    private fun startSession(current: PairingRecord, secret: ByteArray) {
        val job = SupervisorJob(scope.coroutineContext[Job])
        val sessionScope = CoroutineScope(scope.coroutineContext + job)
        val now = System::currentTimeMillis
        val tokens = TokenSource(FirebaseAuthRest(http, current.relay.apiKey), null, current.refreshToken, now) {
            store.updateRefreshToken(it)
        }
        val mailbox = RtdbMailbox(http, current.relay.databaseUrl, tokens, now)
        val channel = Channel(e2e, current.deviceId, secret, store.counters()) { now() / 1000 }
        val client = RelayClient(mailbox, channel, current.deviceHex, sessionScope, now, e2e::randomBytes, ::contact)
        session = Session(job, channel, client)
        connectionState.value = ConnectionState.CONNECTING
        client.start()
        sessionScope.launch { check(client) }
        sessionScope.launch {
            var down = false
            client.link.collect { link ->
                if (link == Link.DOWN) {
                    down = true
                    connectionState.value = ConnectionState.UNREACHABLE
                } else if (link == Link.UP && down) {
                    down = false
                    launch { check(client) }
                }
            }
        }
    }

    private fun stopSession() {
        session?.let {
            it.client.stop()
            it.job.cancel()
            it.channel.close()
        }
        session = null
    }

    private fun contact() {
        val now = System.currentTimeMillis()
        lastContactState.value = now
        if (now - lastContactWrite > 30_000) {
            lastContactWrite = now
            store.recordContact(now)
        }
    }

    /** Ask the computer who it is; the dot follows the result. */
    private suspend fun check(client: RelayClient): TestResult {
        val started = SystemClock.elapsedRealtime()
        val result = try {
            val response = client.request("GET", "/whoami", timeoutMillis = CHECK_TIMEOUT_MILLIS)
            val version = ((response.body as? JsonObject)?.get("version") as? JsonPrimitive)?.content
            if (response.ok && version != null) {
                TestResult.Ok(version, SystemClock.elapsedRealtime() - started)
            } else {
                TestResult.Failed(TestProblem.BAD_ANSWER, response.status)
            }
        } catch (error: RelayException) {
            TestResult.Failed(
                when (error.reason) {
                    RelayException.Reason.TIMEOUT -> TestProblem.NO_ANSWER
                    RelayException.Reason.DENIED -> TestProblem.REFUSED
                    else -> TestProblem.RELAY_UNREACHABLE
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            TestResult.Failed(TestProblem.RELAY_UNREACHABLE)
        }
        if (session?.client === client) {
            connectionState.value = if (result is TestResult.Ok) ConnectionState.CONNECTED else ConnectionState.UNREACHABLE
        }
        return result
    }

    /** "Test connection" in settings: one `GET /whoami` through the relay. */
    suspend fun testConnection(): TestResult {
        val client = session?.client ?: return TestResult.Failed(TestProblem.NOT_PAIRED)
        return withContext(Dispatchers.Default) { check(client) }
    }

    // ------------------------------------------------------------------ pairing

    /**
     * Pair with the computer whose QR code gave [offer]. With [vaultKind] = [Vaults.AUTH] the
     * owner must have confirmed with fingerprint or screen lock just before.
     */
    fun startPairing(offer: PairingOffer, label: String, vaultKind: String = Vaults.AUTH) {
        if (record != null || pairJob?.isActive == true) return
        pairState.value = PairState.Working
        pairJob = scope.launch {
            val vault = vaultFor(vaultKind)
            try {
                // First of all: can the secret be stored at all? Nothing is sent before that.
                val sealed = try {
                    checkNotNull(vault).encrypt(offer.secret)
                } catch (error: VaultException) {
                    vault?.destroy()
                    pairState.value = PairState.Failed(
                        when (error.reason) {
                            VaultException.Reason.NEEDS_AUTHENTICATION -> PairProblem.NEEDS_CONFIRMATION
                            else -> PairProblem.NO_SCREEN_LOCK
                        },
                    )
                    return@launch
                }
                val auth = FirebaseAuthRest(http, offer.relay.apiKey)
                val now = System::currentTimeMillis
                val paired: Paired = Pairing(e2e, now).pair(
                    offer = offer,
                    label = label,
                    auth = auth,
                    mailboxFor = { tokens ->
                        RtdbMailbox(http, offer.relay.databaseUrl, TokenSource(auth, tokens, tokens.refreshToken, now), now)
                    },
                    onCode = { pairState.value = PairState.Compare(formatComparisonCode(it)) },
                )
                val stored = PairingRecord(
                    offer.deviceId, paired.name, offer.relay, paired.tokens.uid, paired.tokens.refreshToken, now(), vaultKind,
                )
                try {
                    store.save(stored, sealed, paired.sendCounter, paired.receivedCounter)
                    synchronized(lock) {
                        record = stored
                        pairingState.value = PairingSummary(stored.name, stored.pairedAtMillis)
                        contact()
                        startSession(stored, offer.secret)
                    }
                } catch (_: Exception) {
                    forget(stored)
                    pairState.value = PairState.Failed(PairProblem.STORAGE)
                    return@launch
                }
                settings.setWelcomed()
                pairState.value = PairState.Done(stored.name)
            } catch (error: PairingFailure) {
                vault?.destroy()
                pairState.value = PairState.Failed(
                    when (error.reason) {
                        PairingFailure.Reason.UNREACHABLE -> PairProblem.UNREACHABLE
                        PairingFailure.Reason.REFUSED -> PairProblem.REFUSED
                        PairingFailure.Reason.TIMEOUT -> PairProblem.TIMEOUT
                    },
                )
            } catch (error: CancellationException) {
                vault?.destroy()
                throw error
            } finally {
                // Whatever happened, this copy of the secret is no longer needed.
                offer.secret.fill(0)
            }
        }
    }

    /** Leave the pairing screens: a pairing still in progress is abandoned and forgotten. */
    fun resetPairing() {
        pairJob?.cancel()
        pairJob = null
        pairState.value = PairState.Idle
    }

    /** "Unpair this phone": wipe everything of the pairing from this phone. */
    suspend fun unpair() {
        val current: PairingRecord
        val tokens: TokenSource
        synchronized(lock) {
            current = record ?: return
            stopSession()
            tokens = TokenSource(FirebaseAuthRest(http, current.relay.apiKey), null, current.refreshToken, System::currentTimeMillis)
            forget(current)
        }
        // Best effort: remove this phone's relay account too. It guards no content.
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(8_000) {
                try {
                    FirebaseAuthRest(http, current.relay.apiKey).deleteAccount(tokens.token())
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun forget(current: PairingRecord) = synchronized(lock) {
        stopSession()
        store.wipe()
        vaultFor(current.vault)?.destroy()
        record = null
        hiddenAt = null
        pairingState.value = null
        lastContactState.value = null
        lockedState.value = false
        connectionState.value = ConnectionState.NOT_PAIRED
    }

    @Suppress("unused")
    fun close() = scope.cancel()

    private companion object {
        /** The computer notices a newly paired phone within a few seconds; allow for that. */
        const val CHECK_TIMEOUT_MILLIS = 20_000L
    }
}

class LifeOsApplication : Application() {
    val core: LifeOsCore by lazy { LifeOsCore(this) }
}
