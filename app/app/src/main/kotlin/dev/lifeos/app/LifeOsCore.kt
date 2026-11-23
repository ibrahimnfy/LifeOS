package dev.lifeos.app

import android.app.Application
import android.os.SystemClock
import dev.lifeos.app.activity.ActivityStore
import dev.lifeos.app.activity.ActivitySync
import dev.lifeos.app.activity.SleepPrompt
import dev.lifeos.app.activity.UsageReader
import dev.lifeos.app.activity.UsageWorker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import dev.lifeos.app.crypto.Direction
import dev.lifeos.app.crypto.E2e
import dev.lifeos.app.push.PushKeyStore
import dev.lifeos.app.push.PushNotifier
import dev.lifeos.app.push.PushSetup
import kotlinx.serialization.json.buildJsonObject
import dev.lifeos.app.data.AppRelease
import dev.lifeos.app.data.Api
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.CoachMessage
import dev.lifeos.app.data.CoachPlan
import dev.lifeos.app.data.CoachRerun
import dev.lifeos.app.data.Chat
import dev.lifeos.app.data.ChatFile
import dev.lifeos.app.data.ChatPointer
import dev.lifeos.app.data.ChatStore
import dev.lifeos.app.data.CoachRotation
import dev.lifeos.app.data.Confirmed
import dev.lifeos.app.data.ConfirmedStore
import dev.lifeos.app.data.CourseList
import dev.lifeos.app.data.LocalItems
import dev.lifeos.app.data.BridgeJson
import dev.lifeos.app.data.MoodleSignIn
import dev.lifeos.app.data.SignInDone
import dev.lifeos.app.data.SignInStart
import dev.lifeos.app.data.NoteSummary
import dev.lifeos.app.data.LookAssignments
import dev.lifeos.app.data.LookStore
import dev.lifeos.app.data.Looks
import dev.lifeos.app.data.Downloads
import dev.lifeos.app.data.Endpoint
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.EventDraft
import dev.lifeos.app.data.NoteLists
import dev.lifeos.app.data.Task
import dev.lifeos.app.data.TaskLists
import dev.lifeos.app.data.TaskNote
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
import dev.lifeos.app.sync.NetworkWatch
import dev.lifeos.app.sync.OutboxWorker
import dev.lifeos.app.sync.SyncTrigger
import dev.lifeos.app.settings.SettingsStore
import dev.lifeos.app.update.AppUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** The renewal of the Moodle sign-in from the phone (#100). */
sealed interface MoodleSignInState {
    data object Idle : MoodleSignInState
    data object Starting : MoodleSignInState
    data object InBrowser : MoodleSignInState
    data object Sending : MoodleSignInState
    data class Done(val release: String?) : MoodleSignInState
    data class Failed(val problem: MoodleSignInProblem) : MoodleSignInState
}

enum class MoodleSignInProblem { UNREACHABLE, SITE, EXPIRED, NOT_THIS_ATTEMPT, REFUSED }

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

    private val looksState = MutableStateFlow(Looks.EMPTY)
    private val lookStore = LookStore(File(app.filesDir, "looks/looks.json"))

    /** The colour and icon of every known course (from the last course list), the same on every screen. */
    val looks: StateFlow<Looks> = looksState

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

        // Chat: the answer of a model run, as events. The first message comes within seconds
        // (the computer names the session or refuses); after that a run may stay quiet for minutes.
        override fun events(method: String, path: String, body: JsonElement?, idleTimeoutMillis: Long): Flow<AnswerEvent> = flow {
            if (record == null) throw ApiException(ApiException.Kind.NOT_PAIRED)
            val client = session?.client ?: throw ApiException(ApiException.Kind.NOT_SENT)
            var answered = false
            try {
                // Also for the coach: while it tidies the conversation up first, keep-alives arrive (#156).
                client.stream(method, path, emptyMap(), body, idleTimeoutMillis, firstTimeoutMillis = FIRST_ANSWER_MILLIS).collect { event ->
                    if (!answered) reached(client, true)
                    answered = true
                    emit(event)
                }
            } catch (error: RelayException) {
                // A run that falls silent says nothing about the computer; no answer at all does.
                if (!answered && error.reason != RelayException.Reason.DENIED) reached(client, false)
                throw ApiException(
                    when (error.reason) {
                        RelayException.Reason.NOT_SENT -> ApiException.Kind.NOT_SENT
                        RelayException.Reason.DENIED -> ApiException.Kind.REFUSED
                        else -> ApiException.Kind.UNCERTAIN
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                throw error
            } catch (_: Exception) {
                // Storage for the counters failed: nothing was sent.
                throw ApiException(if (answered) ApiException.Kind.UNCERTAIN else ApiException.Kind.NOT_SENT)
            }
        }
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
            registerPush()
            sendMoodleSignIn()
            checkUpdate()
        }
    }

    // ---- Moodle sign-in from the phone (#100)

    /** One sign-in attempt: its passport (memory only) and, once the browser came back, its address. */
    private class PendingSignIn(val wwwroot: String, val passport: String, val startedAtMillis: Long, val validMillis: Long) {
        @Volatile
        var address: String? = null
    }

    @Volatile
    private var pendingSignIn: PendingSignIn? = null
    private val signInState = MutableStateFlow<MoodleSignInState>(MoodleSignInState.Idle)

    /** How the renewal of the Moodle sign-in goes (shown on Courses and in Settings). */
    val moodleSignIn: StateFlow<MoodleSignInState> = signInState

    /**
     * Ask the computer for the site's sign-in link (with a one-time passport) and return it for
     * the browser tab, or null when the computer cannot be asked. The page itself is the site's:
     * nothing in it is filled in or read by the app.
     */
    suspend fun startMoodleSignIn(): String? {
        if (connectionState.value != ConnectionState.CONNECTED || locked.value) {
            signInState.value = MoodleSignInState.Failed(MoodleSignInProblem.UNREACHABLE)
            return null
        }
        signInState.value = MoodleSignInState.Starting
        return try {
            val start = BridgeJson.decodeFromJsonElement(SignInStart.serializer(), api.call("POST", "/moodle/signin/start"))
            if (!start.link.startsWith("https://") || start.passport.isEmpty()) throw ApiException(ApiException.Kind.HTTP, 502, "bad_answer")
            pendingSignIn = PendingSignIn(start.wwwroot, start.passport, System.currentTimeMillis(), start.expiresIn * 1000)
            signInState.value = MoodleSignInState.InBrowser
            start.link
        } catch (error: Exception) {
            val http = (error as? ApiException)?.kind == ApiException.Kind.HTTP
            signInState.value = MoodleSignInState.Failed(if (http) MoodleSignInProblem.SITE else MoodleSignInProblem.UNREACHABLE)
            null
        }
    }

    /**
     * The browser tab ended on the sign-in address. Accepted only while an attempt waits and
     * only when it belongs to that attempt; then sent (now, or as soon as the app is unlocked
     * and connected). The address lives in memory only, until it is sent.
     */
    fun onMoodleRedirect(address: String) {
        val pending = pendingSignIn ?: return
        if (System.currentTimeMillis() - pending.startedAtMillis > pending.validMillis) {
            pendingSignIn = null
            signInState.value = MoodleSignInState.Failed(MoodleSignInProblem.EXPIRED)
            return
        }
        if (!MoodleSignIn.belongsTo(address, pending.wwwroot, pending.passport)) {
            pendingSignIn = null
            signInState.value = MoodleSignInState.Failed(MoodleSignInProblem.NOT_THIS_ATTEMPT)
            return
        }
        pending.address = address
        signInState.value = MoodleSignInState.Sending
        sendMoodleSignIn()
    }

    private fun sendMoodleSignIn() {
        scope.launch {
            val pending = pendingSignIn ?: return@launch
            val address = pending.address ?: return@launch
            // Locked or not connected: it waits (in memory) for the next connection.
            if (locked.value || connectionState.value != ConnectionState.CONNECTED) return@launch
            pendingSignIn = null
            pending.address = null
            signInState.value = try {
                val body = buildJsonObject { put("address", JsonPrimitive(address)) }
                val done = BridgeJson.decodeFromJsonElement(SignInDone.serializer(), api.call("POST", "/moodle/signin/finish", body = body))
                reloadState.value++
                MoodleSignInState.Done(done.release)
            } catch (error: ApiException) {
                MoodleSignInState.Failed(
                    when {
                        error.kind != ApiException.Kind.HTTP -> MoodleSignInProblem.UNREACHABLE
                        error.code == "signin_not_started" -> MoodleSignInProblem.EXPIRED
                        error.code == "signin_mismatch" -> MoodleSignInProblem.NOT_THIS_ATTEMPT
                        error.code == "token_rejected" -> MoodleSignInProblem.REFUSED
                        else -> MoodleSignInProblem.SITE
                    },
                )
            } catch (_: Exception) {
                MoodleSignInState.Failed(MoodleSignInProblem.SITE)
            }
        }
    }

    fun dismissMoodleSignIn() {
        if (signInState.value !is MoodleSignInState.Sending) signInState.value = MoodleSignInState.Idle
    }

    // ---- push (#38)

    @Volatile
    private var pushToken: String? = null

    /** A new push address from Firebase: handed to the computer when it is connected. */
    fun onPushToken(token: String) {
        pushToken = token
        registerPush()
    }

    /** A push arrived while the app runs: the screens ask the computer again. */
    fun onPush() {
        if (connectionState.value == ConnectionState.CONNECTED) reloadState.value++
    }

    /**
     * Hand this phone's push address to the computer through the sealed channel (`/push/token`,
     * answered by the bridge's relay code), when it is new or changed since the last time.
     */
    fun registerPush() {
        if (!PushSetup.ready(app) || sample) return
        scope.launch {
            if (connectionState.value != ConnectionState.CONNECTED) return@launch
            val token = pushToken ?: currentPushToken() ?: return@launch
            pushToken = token
            val keys = (app as LifeOsApplication).pushKeys
            if (keys.sentToken() == token) return@launch
            try {
                api.call("POST", "/push/token", body = buildJsonObject { put("token", JsonPrimitive(token)) })
                keys.markTokenSent(token)
            } catch (_: ApiException) {
                // Tried again on the next connection.
            }
        }
    }

    private suspend fun currentPushToken(): String? = try {
        kotlinx.coroutines.suspendCancellableCoroutine { done ->
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                done.resumeWith(Result.success(if (task.isSuccessful) task.result else null))
            }
        }
    } catch (_: Exception) {
        null
    }

    val api = Api(TestAids.transport(app, relayTransport))
    private val cache = ScreenCache(File(app.filesDir, "screens"))
    private val reloadState = MutableStateFlow(0)

    /** Changes whenever screens should ask again: after unlocking, reconnecting, or a sent write. */
    val reload: StateFlow<Int> = reloadState

    /** New notes and tasks the computer confirmed a moment ago, until its lists show them (see [LocalItems]). */
    val confirmed = ConfirmedStore(File(app.filesDir, "outbox/confirmed.json"), System::currentTimeMillis)

    val outbox: Outbox = Outbox(OutboxStore(File(app.filesDir, "outbox/outbox.json")), System::currentTimeMillis) { item ->
        when (item.kind) {
            // Kept as confirmed before the item leaves the outbox, so it never disappears in between.
            OutboxItem.Kind.NOTE -> api.addNote(item.text, item.clientId).let { saved ->
                val id = saved.id
                if (id != null && item.clientId != null) {
                    val note = NoteSummary(id, "inbox", null, LocalItems.noteMoment(saved.at), item.text)
                    confirmed.add(Confirmed(item.clientId!!, System.currentTimeMillis(), note = note))
                }
                // A deletion that waits for this note (#184) now names the computer's id.
                if (id != null) {
                    confirmedNoteIds[LocalItems.PREFIX + item.id] = id
                    outbox.retargetNote(LocalItems.PREFIX + item.id, id)
                }
            }
            OutboxItem.Kind.TASK -> api.addTask(item.text, item.due, item.clientId, item.note).let { saved ->
                val task = saved.task()
                if (task != null && item.clientId != null) confirmed.add(Confirmed(item.clientId!!, System.currentTimeMillis(), task = task))
                if (task != null) taskConfirmed(LocalItems.PREFIX + item.id, task.id)
            }
            OutboxItem.Kind.COMPLETE -> toTask(checkNotNull(item.taskId)) { api.completeTask(it) }
            OutboxItem.Kind.EVENT_CREATE -> api.createEvent(checkNotNull(item.event))
            OutboxItem.Kind.EVENT_UPDATE -> api.updateEvent(checkNotNull(item.eventId), checkNotNull(item.event))
            OutboxItem.Kind.TASK_EDIT -> toTask(checkNotNull(item.taskId)) { api.editTask(it, item.text, item.due, item.note) }
            OutboxItem.Kind.NOTE_EDIT -> {
                val before = checkNotNull(item.noteId)
                // The note has a new id now: a deletion queued behind the edit follows it (#184).
                api.editNote(before, item.text)?.let { after -> if (after != before) outbox.retargetNote(before, after) }
            }
            OutboxItem.Kind.NOTE_DELETE -> {
                val id = checkNotNull(item.noteId)
                if (!LocalItems.isWaiting(id)) {
                    api.deleteNote(id)
                } else {
                    // A note made here that has not reached the computer: wait behind it while it
                    // still waits; once it was refused or dropped there is nothing to delete.
                    val own = outbox.items.value.firstOrNull { it.kind == OutboxItem.Kind.NOTE && LocalItems.PREFIX + it.id == id }
                    if (own != null && own.state != OutboxItem.State.FAILED) throw ApiException(ApiException.Kind.NOT_SENT)
                }
            }
        }
    }

    /** Tasks made on this phone that the computer confirmed in this run of the app: phone id → its id (#167). */
    private val confirmedTaskIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * A task made on this phone reached the computer: what waits for it (a tick, an edit) now
     * names its real id, and a tick still in its undo time hides it under that id too.
     */
    private fun taskConfirmed(localId: String, id: String) {
        confirmedTaskIds[localId] = id
        outbox.retarget(localId, id)
        if (localId in tickedState.value) tickedState.value += id
    }

    /** The id to name a task by now: a task made on this phone by the computer's id once it has one. */
    private fun taskIdNow(taskId: String): String = LocalItems.currentId(taskId, confirmedTaskIds, confirmed.items.value)

    /** Send a tick or an edit to its task (see [LocalItems.sendTo]). */
    private suspend fun toTask(taskId: String, send: suspend (String) -> Unit) {
        when (val to = LocalItems.sendTo(taskId, confirmedTaskIds, confirmed.items.value, outbox.items.value)) {
            is LocalItems.SendTo.Computer -> send(to.id)
            // Nothing left the phone: the item keeps waiting behind its task.
            LocalItems.SendTo.Later -> throw ApiException(ApiException.Kind.NOT_SENT)
            // The task never reached the computer: there is nothing to tick or change there.
            LocalItems.SendTo.Nowhere -> Unit
        }
    }

    /** The saved copy of an endpoint, or null. Nothing is read while the app is locked. */
    fun <T> saved(endpoint: Endpoint<T>): Pair<T, Long>? {
        if (lockedState.value || record == null) return null
        val copy = cache.get(endpoint.key) ?: return null
        return try {
            (api.decode(endpoint, copy.raw) to copy.savedAtMillis).also { noteCourses(endpoint, it.first) }
        } catch (_: ApiException) {
            null
        }
    }

    @Volatile
    private var sample = false

    /**
     * Debug test aid only (`TestAids.wantsSampleData`; release builds never call this with
     * effect): on a phone that is not paired, act as connected and show the invented sample
     * answers. Nothing is sent anywhere and nothing is kept as a saved copy.
     */
    fun useSampleData() = synchronized(lock) {
        if (record != null || sample || !TestAids.wantsSampleData) return@synchronized
        sample = true
        TestAids.startSampleData()
        pairingState.value = PairingSummary(app.getString(R.string.pair_label_default), System.currentTimeMillis())
        connectionState.value = ConnectionState.CONNECTED
        reloadState.value++
        checkUpdate()
    }

    // ---- self-update (#138)

    /** The "update ready" prompt: a newer release build registered on the computer. */
    val updater = AppUpdater(app, api).also { it.cleanUp() } // a file left by a run that died is removed

    /** Ask for a newer release: on every new connection (the app was opened) and on an "update" push. */
    fun checkUpdate() {
        scope.launch {
            if (connectionState.value == ConnectionState.CONNECTED) updater.check()
        }
    }

    /** One tap: download, verify and hand the release to the installer; runs on even when the screen closes. */
    fun installUpdate(release: AppRelease) {
        scope.launch { updater.install(release) }
    }

    /** Ask the computer; a good answer becomes the new saved copy. Throws [ApiException]. */
    suspend fun <T> fetch(endpoint: Endpoint<T>): T {
        val askedAt = System.currentTimeMillis()
        val (data, raw) = api.get(endpoint)
        if (!sample) cache.put(endpoint.key, raw, System.currentTimeMillis())
        noteCourses(endpoint, data)
        // A full list asked for after a confirmation holds the confirmed item (or it is done since).
        when (endpoint.key) {
            Endpoints.tasks.key, Endpoints.plan.key -> confirmed.caughtUp(tasks = true, askedAtMillis = askedAt)
            Endpoints.notes.key -> confirmed.caughtUp(tasks = false, askedAtMillis = askedAt)
        }
        return data
    }

    /**
     * Make the courses' colours known on every screen: from the saved course list, or (when
     * there is none yet) by asking the computer once for it.
     */
    fun loadLooks() {
        if (saved(Endpoints.courses) != null) return
        scope.launch {
            try {
                fetch(Endpoints.courses)
            } catch (_: ApiException) {
                // Not reachable or courses not set up: tasks keep colours from their names.
            }
        }
    }

    private fun noteCourses(endpoint: Endpoint<*>, data: Any?) {
        if (endpoint.key != Endpoints.courses.key) return
        val courses = (data as? CourseList)?.courses ?: return
        // A course keeps the colour it was first given: the assignment is stored and only extended.
        val slots = synchronized(lookStore) {
            val stored = lookStore.load()
            LookAssignments.update(stored, courses).also { if (it != stored && !sample) lookStore.save(it) }
        }
        val next = Looks.of(courses, slots)
        if (looksState.value != next) looksState.value = next
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
        // Hidden under both ids when a task made on this phone was confirmed meanwhile (its card shows it as tapped).
        tickedState.value += setOf(task.id, taskIdNow(task.id))
        tickTimers[task.id] = scope.launch {
            delay(UNDO_MILLIS)
            tickTimers.remove(task.id)
            // A task made on this phone (#167) is ticked by the computer's id once it has one;
            // until then the tick waits behind it in the outbox and is renamed when it arrives.
            val id = taskIdNow(task.id)
            val item = OutboxItem(newItemId(), OutboxItem.Kind.COMPLETE, System.currentTimeMillis(), text = task.title, taskId = id)
            val stored = outbox.add(item)
            tickedState.value -= setOf(task.id, id)
            if (stored && connectionState.value == ConnectionState.CONNECTED && outbox.drain() > 0) reloadState.value++
        }
    }

    /** The owner took the tick back in time. False if it was already on its way. */
    fun undoTick(task: Task): Boolean {
        val timer = tickTimers.remove(task.id) ?: return false
        timer.cancel()
        tickedState.value -= setOf(task.id, taskIdNow(task.id))
        return true
    }

    /** A new note, or a new task with its date and the owner's note on it ([taskNote], #170). */
    suspend fun capture(note: Boolean, text: String, due: String?, taskNote: String? = null): Boolean = submit(
        OutboxItem(
            newItemId(),
            if (note) OutboxItem.Kind.NOTE else OutboxItem.Kind.TASK,
            System.currentTimeMillis(),
            text = text,
            due = if (note) null else due,
            note = if (note) null else TaskNote.of(taskNote),
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

    /**
     * Change a task's title, date ([due] null: no date) and note ([note] blank: none, #170).
     * Shown changed at once (see [TaskLists.open]). A task made on this phone that has not left
     * it yet (#167) simply leaves with the new title, date and note; one on its way, or one that
     * may have arrived already (an attempt without an answer), gets the edit right after it.
     * The note is sent only when it differs from the one [task] shows (see [TaskNote.change]).
     */
    suspend fun editTask(task: Task, title: String, due: String?, note: String? = null): Boolean {
        val id = taskIdNow(task.id)
        val noteChange = TaskNote.change(task.note, note)
        if (LocalItems.isWaiting(id)) {
            val own = id.removePrefix(LocalItems.PREFIX)
            if (outbox.revise(own, title, due, unsentOnly = true, note = noteChange)) return sendWaiting(own)
        }
        val waiting = TaskLists.pendingEdit(id, outbox.items.value)
        if (waiting != null && outbox.revise(waiting.id, title, due, note = noteChange)) return sendWaiting(waiting.id)
        return submit(OutboxItem(newItemId(), OutboxItem.Kind.TASK_EDIT, System.currentTimeMillis(), text = title, due = due, taskId = id, note = noteChange))
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

    /** Notes made on this phone that the computer confirmed in this run of the app: "local-" name → its id. */
    private val confirmedNoteIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun noteIdNow(localName: String): String? =
        confirmedNoteIds[localName] ?: confirmed.items.value.firstOrNull { LocalItems.PREFIX + it.clientId == localName }?.note?.id

    private val deletedNotesState = MutableStateFlow<Set<String>>(emptySet())
    private val deleteTimers = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Notes deleted a moment ago whose deletion can still be undone; the lists hide them already. */
    val justDeletedNotes: StateFlow<Set<String>> = deletedNotesState

    /**
     * Delete a note (#184): it disappears at once, and after [UNDO_MILLIS] the deletion is
     * sent (or queued), as a tick is. [noteId] is the computer's id, or "local-" and the phone
     * id of a note captured here. See [NoteLists.deletion] for what happens to what waits:
     * a note that never left the phone is dropped, an edit that never left is dropped and
     * the original note deleted. False when it cannot be deleted now (an edit is on its way).
     */
    fun deleteNote(noteId: String): Boolean {
        if (noteId in deletedNotesState.value) return true
        if (!NoteLists.canDelete(noteId, outbox.items.value)) return false
        deletedNotesState.value += noteId
        deleteTimers[noteId] = scope.launch {
            delay(UNDO_MILLIS)
            deleteTimers.remove(noteId)
            val stored = when (val plan = NoteLists.deletion(noteId, outbox.items.value, ::noteIdNow)) {
                is NoteLists.Deletion.Discard -> {
                    outbox.discard(plan.itemId)
                    false
                }
                is NoteLists.Deletion.Delete -> {
                    plan.dropEdit?.let { outbox.discard(it) }
                    outbox.add(OutboxItem(newItemId(), OutboxItem.Kind.NOTE_DELETE, System.currentTimeMillis(), noteId = plan.noteId))
                }
                // An edit left meanwhile: the note stays; nothing is sent.
                NoteLists.Deletion.NotNow -> false
            }
            deletedNotesState.value -= noteId
            if (stored && connectionState.value == ConnectionState.CONNECTED && outbox.drain() > 0) reloadState.value++
        }
        return true
    }

    /** The owner took the deletion back in time. False if it was already on its way. */
    fun undoDeleteNote(noteId: String): Boolean {
        val timer = deleteTimers.remove(noteId) ?: return false
        timer.cancel()
        deletedNotesState.value -= noteId
        return true
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

    // ---- memory consolidation (#151)

    /** Approved changes rewrote the memory: every screen asks again (tasks and notes may have changed). */
    fun memoryChanged() {
        reloadState.value++
    }

    // ---- coach questions and nudges (#76)

    private val coachShownState = MutableStateFlow<String?>(null)

    /** The message a tapped nudge named: the coach card shows it (the push carries its id in `r`). */
    val coachShown: StateFlow<String?> = coachShownState

    fun showCoachMessage(id: String?) {
        if (id.isNullOrEmpty()) return
        coachPick = id
        coachShownState.value = id
    }

    private val coachQuestionsDone = MutableStateFlow<Set<String>>(emptySet())

    /** Questions answered or skipped on this phone: hidden at once, before the plan says so. */
    val coachQuestionsHandled: StateFlow<Set<String>> = coachQuestionsDone

    fun coachQuestionHandled(id: String) {
        coachQuestionsDone.value += id
    }

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
                val today = LocalDate.now(ZoneId.systemDefault())
                val reader = UsageReader(app)
                activity.putDays(reader.measure(), today)
                // Nights the owner did not enter are guessed from when the phone lay unused.
                reader.nights().forEach { activity.putEstimate(it, today) }
                UsageWorker.schedule(app)
                true
            }
        }
        if (measured) activityState.value++
    }

    /** The owner's own entry (or correction) for a night; [date] is the morning it ended on. Sent with the next upload. */
    suspend fun enterNight(date: String, bed: String, wake: String) {
        val today = LocalDate.now(ZoneId.systemDefault())
        withContext(Dispatchers.IO) { activityLock.withLock { activity.putManual(date, bed, wake, today) } }
        activityState.value++
        if (date == today.toString()) SleepPrompt.dismiss(app)
        syncActivity(force = true)
    }

    /** Plan the morning question again (after a change of its setting, and whenever the app is shown). */
    fun scheduleSleepPrompt() {
        scope.launch { SleepPrompt.schedule(app) }
    }

    /**
     * Send the completed days the computer does not have yet ("daily": each day is sent once,
     * when it is over). Runs when the connection comes back, at most once an hour, and when the
     * owner asks ([force]); only while sending is switched on. In the background it cannot run:
     * the device secret is available only while the app is unlocked.
     */
    fun syncActivity(force: Boolean = false) {
        scope.launch {
            if (connectionState.value != ConnectionState.CONNECTED || !settings.usageSync.first()) return@launch
            val now = System.currentTimeMillis()
            if (!force && now - lastActivitySync < ACTIVITY_SYNC_GAP_MILLIS) return@launch
            lastActivitySync = now
            // Without usage access there is nothing to measure, but entered nights are still sent.
            measureUsage()
            withContext(Dispatchers.IO) { activityLock.withLock { ActivitySync.upload(activity, api, now) } }
            activityState.value++
        }
    }

    val downloads = Downloads(app, api, scope)

    // ---- chat

    /** The pointer to the conversation in progress: not written in the sample mode, not read while locked. */
    private val chatStore = object : ChatStore {
        private val file = ChatFile(File(app.filesDir, "chat/current.json"))

        override fun load(): ChatPointer? = if (sample || record == null || lockedState.value) null else file.load()

        override fun save(pointer: ChatPointer?) {
            if (!sample) file.save(pointer)
        }
    }

    /** The conversation of the Chat tab; it outlives the screens (see [Chat]). */
    val chat = Chat(
        api,
        chatStore,
        scope,
        System::currentTimeMillis,
        reachable = { connectionState.value == ConnectionState.CONNECTED || (session != null && check() is TestResult.Ok) },
        uptimeMillis = SystemClock::elapsedRealtime,
    )

    // ---- the coach conversation (#114): its own conversation next to the Chat tab's

    /** The one conversation with the coach. Which session it is, the computer knows: nothing is stored here. */
    val coach = Chat(
        api,
        object : ChatStore {
            override fun load(): ChatPointer? = null

            override fun save(pointer: ChatPointer?) = Unit
        },
        scope,
        System::currentTimeMillis,
        reachable = { connectionState.value == ConnectionState.CONNECTED || (session != null && check() is TestResult.Ok) },
        uptimeMillis = SystemClock::elapsedRealtime,
        coach = true,
    )

    // ---- sessions: what the owner changed from this phone (#74)

    private val sessionsVersionState = MutableStateFlow(0)

    /** Counts the changes to sessions made from this phone; screens that list sessions read them again. */
    val sessionsVersion: StateFlow<Int> = sessionsVersionState

    fun sessionsChanged() {
        sessionsVersionState.value++
    }

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
        checkUpdate() // opening the app asks for a newer release, also when the connection stayed up
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
            // The streams that are being read (an answer, a followed run) are let go first (#157):
            // each would keep one of the relay's few workers on the computer until its run ends.
            session?.client?.detachAll(scope)
            stopSession()
            lockedState.value = true
            connectionState.value = ConnectionState.CONNECTING
            chat.onLocked()
            coach.onLocked()
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
        chat.restore()
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
        // The push key, so hints open while the app is locked or closed (kept once per pairing).
        val pushKeys = (app as LifeOsApplication).pushKeys
        if (!pushKeys.has()) {
            val pushKey = e2e.deriveKey(secret, Direction.PUSH)
            pushKeys.save(pushKey, current.deviceId)
            pushKey.fill(0)
        }
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

    // ---- sending as soon as the phone is online again (#165)

    private val syncTrigger = SyncTrigger(scope, NetworkWatch.validatedNow(app)) { networkBack() }

    init {
        NetworkWatch.register(app, syncTrigger::networkChanged)
        // Something waits and the computer cannot be reached: let the system start the sending
        // when the network is there, also if this process is paused or stopped by then.
        scope.launch {
            combine(outbox.items, connectionState, lockedState) { items, state, locked ->
                !locked && state == ConnectionState.UNREACHABLE && items.any { it.state == OutboxItem.State.WAITING }
            }.distinctUntilChanged().collect { if (it) OutboxWorker.schedule(app) }
        }
    }

    /** Send what waits now if the app is unlocked (the background job asks for this). */
    fun syncNow() = syncTrigger.request()

    /**
     * The phone is online again: drop connections that belonged to the old network, listen
     * again at once, ask the computer, and send what waits. A successful check also refreshes
     * the screens (see [reached]). The first check may come a moment too early; it is repeated.
     */
    private suspend fun networkBack() {
        val current = session ?: return
        // The stream first, so its connection is let go before the idle ones are closed.
        current.client.reconnect()
        http.connectionPool.evictAll()
        repeat(NETWORK_BACK_CHECKS) { attempt ->
            if (session !== current) return
            if (attempt > 0) delay(NETWORK_BACK_PAUSE_MILLIS)
            if (check() is TestResult.Ok) {
                if (outbox.drain() > 0) reloadState.value++
                return
            }
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
        // The push key belongs to the pairing too.
        (app as LifeOsApplication).pushKeys.wipe()
        // A sign-in attempt (memory only) ends with the pairing.
        pendingSignIn = null
        signInState.value = MoodleSignInState.Idle
        // Saved copies and unsent writes belong to the pairing: they go with it.
        cache.wipe()
        outbox.wipe()
        confirmed.wipe()
        downloads.clear()
        chat.forget()
        coach.forget()
        rerunState.value = CoachRerun.State.Idle
        looksState.value = Looks.EMPTY
        lookStore.wipe()
        tickTimers.values.forEach { it.cancel() }
        tickTimers.clear()
        tickedState.value = emptySet()
        coachPick = null
        coachShownState.value = null
        coachQuestionsDone.value = emptySet()
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

        /** When the network comes back: how often the computer is asked, and the pause in between. */
        const val NETWORK_BACK_CHECKS = 3
        const val NETWORK_BACK_PAUSE_MILLIS = 2_000L
        const val ACTIVITY_SYNC_GAP_MILLIS = 60 * 60_000L

        /** Chat: how long the computer may take to take a message (it answers at once, the model later). */
        const val FIRST_ANSWER_MILLIS = 45_000L
    }
}

/** How long a tick can be taken back before it is sent. */
const val UNDO_MILLIS = 6_000L

class LifeOsApplication : Application() {
    private val coreLazy = lazy { LifeOsCore(this) }
    val core: LifeOsCore by coreLazy

    /** The core if it was already started in this process (a push does not start it just to reload). */
    fun runningCore(): LifeOsCore? = if (coreLazy.isInitialized()) core else null

    /** Screen time per day, shared by the app and the daily background measurement (one instance per process). */
    val activityStore: ActivityStore by lazy { ActivityStore(File(filesDir, "activity/activity.json")) }

    /** The push key (#136 = A), shared by the app and the push service. */
    val pushKeys: PushKeyStore by lazy { PushKeyStore(File(filesDir, "push"), Vaults::push) }

    override fun onCreate() {
        super.onCreate()
        PushSetup.init(this)
        PushNotifier.createChannels(this)
    }
}
