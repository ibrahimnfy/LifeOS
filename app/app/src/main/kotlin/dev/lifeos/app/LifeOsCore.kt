package dev.lifeos.app

import android.app.Application
import android.os.SystemClock
import dev.lifeos.app.activity.ActivityStore
import dev.lifeos.app.activity.ActivitySync
import dev.lifeos.app.activity.UsageReader
import dev.lifeos.app.activity.UsageWorker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import dev.lifeos.app.crypto.E2e
import dev.lifeos.app.data.Api
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.CoachMessage
import dev.lifeos.app.data.CoachPlan
import dev.lifeos.app.data.CoachRerun
import dev.lifeos.app.data.CoachRotation
import dev.lifeos.app.data.Downloads
import dev.lifeos.app.data.Endpoint
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.EventDraft
import dev.lifeos.app.data.NoteLists
import dev.lifeos.app.data.Task
import dev.lifeos.app.data.TaskLists
import dev.lifeos.app.data.Outbox
import dev.lifeos.app.data.OutboxItem
import dev.lifeos.app.data.OutboxStore
import dev.lifeos.app.data.ScreenCache
import dev.lifeos.app.data.Transport
import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
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

    private val activityState = MutableStateFlow(0)
    private val activityLock = Mutex()

    @Volatile
    private var lastActivitySync = 0L

    private class Session(val job: Job, val channel: Channel, val client: RelayClient)

    // ------------------------------------------------------------------ data

    /** The sealed channel as the data layer sees it; it fails cleanly while locked or unpaired. */
    private val relayTransport = object : Transport {
        override suspend fun request(
            method: String,
            path: String,
            query: Map<String, String>,
            body: JsonElement?,
            timeoutMillis: Long,
        ): RelayResponse {
            if (record == null) throw ApiException(ApiException.Kind.NOT_PAIRED)
            val client = session?.client ?: throw ApiException(ApiException.Kind.NOT_SENT)
            return try {
                client.request(method, path, query, body, timeoutMillis).also { reached(client, true) }
            } catch (error: RelayException) {
                // A refusal says nothing new about the computer; everything else means "not reached".
                if (error.reason != RelayException.Reason.DENIED) reached(client, false)
                throw ApiException(
                    when (error.reason) {
                        RelayException.Reason.NOT_SENT -> ApiException.Kind.NOT_SENT
                        RelayException.Reason.DENIED -> ApiException.Kind.REFUSED
                        else -> ApiException.Kind.UNCERTAIN
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Storage for the counters failed: nothing was sent.
                throw ApiException(ApiException.Kind.NOT_SENT)
            }
        }

        override fun stream(method: String, path: String, query: Map<String, String>): Flow<AnswerEvent> =
            session?.client?.stream(method, path, query, idleTimeoutMillis = 60_000)
                ?: flow { throw RelayException(RelayException.Reason.NOT_SENT) }
    }

    /**
     * Every request tells whether the computer answers: the dot follows it, screens ask again
     * when it changes, and what waits in the outbox is sent as soon as it answers.
     */
    private fun reached(client: RelayClient, answered: Boolean) {
        if (session?.client !== client) return
        val now = if (answered) ConnectionState.CONNECTED else ConnectionState.UNREACHABLE
        if (connectionState.value == now) return
        connectionState.value = now
        reloadState.value++
        if (answered) {
            drainOutbox()
            syncActivity()
        }
    }

    val api = Api(TestAids.transport(app, relayTransport))
    private val cache = ScreenCache(File(app.filesDir, "screens"))
    private val reloadState = MutableStateFlow(0)

    /** Changes whenever screens should ask again: after unlocking, reconnecting, or a sent write. */
    val reload: StateFlow<Int> = reloadState

    val outbox = Outbox(OutboxStore(File(app.filesDir, "outbox/outbox.json")), System::currentTimeMillis) { item ->
        when (item.kind) {
            OutboxItem.Kind.NOTE -> api.addNote(item.text)
            OutboxItem.Kind.TASK -> api.addTask(item.text, item.due)
            OutboxItem.Kind.COMPLETE -> api.completeTask(checkNotNull(item.taskId))
            OutboxItem.Kind.EVENT_CREATE -> api.createEvent(checkNotNull(item.event))
            OutboxItem.Kind.EVENT_UPDATE -> api.updateEvent(checkNotNull(item.eventId), checkNotNull(item.event))
            OutboxItem.Kind.TASK_EDIT -> api.editTask(checkNotNull(item.taskId), item.text, item.due)
            OutboxItem.Kind.NOTE_EDIT -> api.editNote(checkNotNull(item.noteId), item.text)
        }
    }

    /** The saved copy of an endpoint, or null. Nothing is read while the app is locked. */
    fun <T> saved(endpoint: Endpoint<T>): Pair<T, Long>? {
        if (lockedState.value || record == null) return null
        val copy = cache.get(endpoint.key) ?: return null
        return try {
            api.decode(endpoint, copy.raw) to copy.savedAtMillis
        } catch (_: ApiException) {
            null
        }
    }

    /** Ask the computer; a good answer becomes the new saved copy. Throws [ApiException]. */
    suspend fun <T> fetch(endpoint: Endpoint<T>): T {
        val (data, raw) = api.get(endpoint)
        cache.put(endpoint.key, raw, System.currentTimeMillis())
        return data
    }

    /** Keep a plan that was just prepared as the saved copy of the plan. */
    fun savePlan(raw: JsonElement) = cache.put(Endpoints.plan.key, raw, System.currentTimeMillis())

    private val rerunState = MutableStateFlow<CoachRerun.State>(CoachRerun.State.Idle)

    /** The coach card's "Rerun": its progress and outcome, kept while the owner moves around. */
    val coachRerun: StateFlow<CoachRerun.State> = rerunState

    /** Ask the computer for a new plan now (a model run; it refuses one within ten minutes of the last). */
    fun rerunCoach() {
        synchronized(rerunState) {
            if (rerunState.value == CoachRerun.State.Running) return
            rerunState.value = CoachRerun.State.Running
        }
        scope.launch {
            val outcome = CoachRerun.run(api, ::savePlan)
            rerunState.value = outcome
            // Every screen that shows the plan reads the new one (it is the saved copy already).
            if (outcome == CoachRerun.State.Done) reloadState.value++
        }
    }

    /**
     * Put a write into the outbox and, when the computer is connected, send it right away.
     * Returns true if it was sent, false if it waits (or needs a look).
     */
    suspend fun submit(item: OutboxItem): Boolean {
        if (!outbox.add(item)) return false
        return sendWaiting(item.id)
    }

    private val tickedState = MutableStateFlow<Set<String>>(emptySet())
    private val tickTimers = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Tasks ticked a moment ago whose tick can still be undone; they are hidden already. */
    val justTicked: StateFlow<Set<String>> = tickedState

    /**
     * Tick a task: it disappears at once, and after [UNDO_MILLIS] the tick is sent (or
     * queued). The computer cannot reopen a task, hence the moment to take it back. The
     * timer lives here, so turning the phone or leaving the screen changes nothing.
     */
    fun tick(task: Task) {
        if (task.id in tickedState.value) return
        tickedState.value += task.id
        tickTimers[task.id] = scope.launch {
            delay(UNDO_MILLIS)
            tickTimers.remove(task.id)
            val item = OutboxItem(newItemId(), OutboxItem.Kind.COMPLETE, System.currentTimeMillis(), text = task.title, taskId = task.id)
            val stored = outbox.add(item)
            tickedState.value -= task.id
            if (stored && connectionState.value == ConnectionState.CONNECTED && outbox.drain() > 0) reloadState.value++
        }
    }

    /** The owner took the tick back in time. False if it was already on its way. */
    fun undoTick(task: Task): Boolean {
        val timer = tickTimers.remove(task.id) ?: return false
        timer.cancel()
        tickedState.value -= task.id
        return true
    }

    suspend fun capture(note: Boolean, text: String, due: String?): Boolean = submit(
        OutboxItem(
            newItemId(),
            if (note) OutboxItem.Kind.NOTE else OutboxItem.Kind.TASK,
            System.currentTimeMillis(),
            text = text,
            due = if (note) null else due,
        ),
    )

    suspend fun saveEvent(eventId: String?, draft: EventDraft): Boolean = submit(
        OutboxItem(
            newItemId(),
            if (eventId == null) OutboxItem.Kind.EVENT_CREATE else OutboxItem.Kind.EVENT_UPDATE,
            System.currentTimeMillis(),
            eventId = eventId,
            event = draft,
        ),
    )

    /** Change a task's title and date ([due] null: no date). Shown changed at once (see [TaskLists.open]). */
    suspend fun editTask(task: Task, title: String, due: String?): Boolean {
        val waiting = TaskLists.pendingEdit(task.id, outbox.items.value)
        if (waiting != null && outbox.revise(waiting.id, title, due)) return sendWaiting(waiting.id)
        return submit(OutboxItem(newItemId(), OutboxItem.Kind.TASK_EDIT, System.currentTimeMillis(), text = title, due = due, taskId = task.id))
    }

    /**
     * Change the text of a captured note. An edit of the same note that is still waiting is
     * replaced instead (see [NoteLists.canEdit] for when editing is not possible).
     */
    suspend fun editNote(noteId: String, text: String): Boolean {
        val waiting = NoteLists.pendingEdit(noteId, outbox.items.value)
        if (waiting != null && outbox.revise(waiting.id, text)) return sendWaiting(waiting.id)
        return submit(OutboxItem(newItemId(), OutboxItem.Kind.NOTE_EDIT, System.currentTimeMillis(), text = text, noteId = noteId))
    }

    /** Send a waiting item now if the computer is connected. True if it was sent. */
    private suspend fun sendWaiting(id: String): Boolean {
        if (connectionState.value == ConnectionState.CONNECTED) {
            // The sending itself belongs to the app, not to the screen: leaving the screen or
            // turning the phone must not cut it off. Only the waiting here is bounded.
            val sending = scope.launch { if (outbox.drain() > 0) reloadState.value++ }
            withTimeoutOrNull(SUBMIT_WAIT_MILLIS) { sending.join() }
        }
        return outbox.items.value.none { it.id == id }
    }

    fun newItemId(): String = e2e.randomBytes(8).joinToString("") { "%02x".format(it) }

    /** Send what waits. Only while the computer answers: otherwise an attempt would be a guess. */
    fun drainOutbox() {
        scope.launch {
            if (connectionState.value == ConnectionState.CONNECTED && outbox.drain() > 0) reloadState.value++
        }
    }

    // ---- which coach message this run of the app shows

    private var coachPick: String? = null

    /** One message per app start, by the rotation rule; the same one while the app stays open. */
    suspend fun coachMessage(plan: CoachPlan): CoachMessage? {
        plan.messages.firstOrNull { it.id == coachPick }?.let { return it }
        val ids = plan.messages.map { it.id }
        val recent = settings.coachRecent.first()
        val picked = CoachRotation.pick(ids, recent) { java.util.Random().nextInt(it) } ?: return null
        coachPick = picked
        settings.setCoachRecent(CoachRotation.remember(recent, picked, ids))
        return plan.messages.first { it.id == picked }
    }

    // ---- screen time (usage access)

    /** Screen time per day, on the phone only (see [ActivityStore]). */
    val activity: ActivityStore get() = (app as LifeOsApplication).activityStore

    /** Changes whenever the stored screen time changed (measured, sent or wiped). */
    val activityChanged: StateFlow<Int> = activityState

    fun usageAccess(): Boolean = UsageReader.hasAccess(app)

    /** Measure the last days now; with usage access this also plans the daily background measurement. */
    suspend fun measureUsage() {
        val measured = withContext(Dispatchers.IO) {
            activityLock.withLock {
                if (!usageAccess()) return@withLock false
                activity.putDays(UsageReader(app).measure(), LocalDate.now(ZoneId.systemDefault()))
                UsageWorker.schedule(app)
                true
            }
        }
        if (measured) activityState.value++
    }

    /**
     * Send the completed days the computer does not have yet ("daily": each day is sent once,
     * when it is over). Runs when the connection comes back, at most once an hour, and when the
     * owner asks ([force]); only while sending is switched on. In the background it cannot run:
     * the device secret is available only while the app is unlocked.
     */
    fun syncActivity(force: Boolean = false) {
        scope.launch {
            if (connectionState.value != ConnectionState.CONNECTED || !settings.usageSync.first() || !usageAccess()) return@launch
            val now = System.currentTimeMillis()
            if (!force && now - lastActivitySync < ACTIVITY_SYNC_GAP_MILLIS) return@launch
            lastActivitySync = now
            measureUsage()
            withContext(Dispatchers.IO) { activityLock.withLock { ActivitySync.upload(activity, api, now) } }
            activityState.value++
        }
    }

    val downloads = Downloads(app, api, scope)

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
        reloadState.value++
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
        sessionScope.launch { check() }
        // The computer may go to sleep or wake up without the relay noticing: while it does not
        // answer, ask again from time to time; while it does, send whatever still waits.
        sessionScope.launch {
            while (true) {
                delay(RECHECK_MILLIS)
                if (connectionState.value != ConnectionState.CONNECTED) {
                    check()
                } else if (outbox.items.value.any { it.state == OutboxItem.State.WAITING }) {
                    drainOutbox()
                }
            }
        }
        sessionScope.launch {
            var down = false
            client.link.collect { link ->
                if (link == Link.DOWN) {
                    down = true
                    if (connectionState.value != ConnectionState.UNREACHABLE) reloadState.value++
                    connectionState.value = ConnectionState.UNREACHABLE
                } else if (link == Link.UP && down) {
                    down = false
                    launch { check() }
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

    /** Ask the computer who it is; the dot follows the result (see [reached]). */
    private suspend fun check(): TestResult {
        val started = SystemClock.elapsedRealtime()
        return try {
            val body = api.call("GET", "/whoami", timeoutMillis = CHECK_TIMEOUT_MILLIS)
            val version = ((body as? JsonObject)?.get("version") as? JsonPrimitive)?.content
            if (version != null) TestResult.Ok(version, SystemClock.elapsedRealtime() - started) else TestResult.Failed(TestProblem.BAD_ANSWER, 200)
        } catch (error: ApiException) {
            TestResult.Failed(
                when (error.kind) {
                    ApiException.Kind.NOT_PAIRED -> TestProblem.NOT_PAIRED
                    ApiException.Kind.NOT_SENT -> TestProblem.RELAY_UNREACHABLE
                    ApiException.Kind.REFUSED -> TestProblem.REFUSED
                    ApiException.Kind.UNCERTAIN -> TestProblem.NO_ANSWER
                    ApiException.Kind.HTTP -> TestProblem.BAD_ANSWER
                },
                error.status,
            )
        }
    }

    /** "Test connection" in settings: one `GET /whoami` through the relay. */
    suspend fun testConnection(): TestResult {
        if (session == null) return TestResult.Failed(TestProblem.NOT_PAIRED)
        return withContext(Dispatchers.Default) { check() }
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
            // Only when the owner unpairs: the screen time measured here goes too. A pairing that
            // is lost by itself keeps it (the system can measure only the last week again).
            activity.wipe()
            activityState.value++
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
        // Saved copies and unsent writes belong to the pairing: they go with it.
        cache.wipe()
        outbox.wipe()
        downloads.clear()
        rerunState.value = CoachRerun.State.Idle
        tickTimers.values.forEach { it.cancel() }
        tickTimers.clear()
        tickedState.value = emptySet()
        coachPick = null
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
        const val SUBMIT_WAIT_MILLIS = 8_000L
        const val RECHECK_MILLIS = 60_000L
        const val ACTIVITY_SYNC_GAP_MILLIS = 60 * 60_000L
    }
}

/** How long a tick can be taken back before it is sent. */
const val UNDO_MILLIS = 6_000L

class LifeOsApplication : Application() {
    val core: LifeOsCore by lazy { LifeOsCore(this) }

    /** Screen time per day, shared by the app and the daily background measurement (one instance per process). */
    val activityStore: ActivityStore by lazy { ActivityStore(File(filesDir, "activity/activity.json")) }
}
