package dev.lifeos.app.data

import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A call to the computer failed. Never carries content or addresses. */
class ApiException(val kind: Kind, val status: Int = 0, val code: String? = null) :
    Exception(if (kind == Kind.HTTP) "status $status" else kind.name) {
    enum class Kind {
        /** This phone is not paired. */
        NOT_PAIRED,

        /** Nothing left the phone (no network, app locked, relay unreachable). Safe to send later. */
        NOT_SENT,

        /** The relay refused this phone: it was probably unpaired on the computer. */
        REFUSED,

        /** The request was handed to the relay, but no answer came: it may or may not have been done. */
        UNCERTAIN,

        /** The computer answered with an error; [status] and [code] say which. */
        HTTP,
    }
}

/** The sealed channel to the computer, as the data layer needs it. */
interface Transport {
    suspend fun request(method: String, path: String, query: Map<String, String>, body: JsonElement?, timeoutMillis: Long): RelayResponse
    fun stream(method: String, path: String, query: Map<String, String>): Flow<AnswerEvent>

    /**
     * A request whose answer arrives as events while the computer works (a chat answer). The
     * flow ends after [AnswerEvent.Done] and fails with [ApiException]: NOT_SENT when nothing
     * left the phone, UNCERTAIN when the request left but the answer stopped arriving.
     */
    fun events(method: String, path: String, body: JsonElement?, idleTimeoutMillis: Long): Flow<AnswerEvent> =
        kotlinx.coroutines.flow.flow { throw ApiException(ApiException.Kind.NOT_SENT) }
}

/**
 * Typed calls to the bridge's API through the relay. Requests are made one at a time, so
 * answers can never overtake each other on the way back.
 */
class Api(private val transport: Transport) {
    private val oneAtATime = Mutex()
    private val planLane = Mutex()
    private val chatLane = Mutex()

    suspend fun call(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonElement? = null,
        timeoutMillis: Long = READ_TIMEOUT_MILLIS,
    ): JsonElement = oneAtATime.withLock { exchange(method, path, query, body, timeoutMillis) }

    private suspend fun exchange(method: String, path: String, query: Map<String, String>, body: JsonElement?, timeoutMillis: Long): JsonElement {
        val response = transport.request(method, path, query, body, timeoutMillis)
        if (!response.ok) {
            val code = ((response.body as? JsonObject)?.get("error") as? JsonPrimitive)?.content ?: response.error
            throw ApiException(ApiException.Kind.HTTP, response.status, code)
        }
        return response.body ?: JsonNull
    }

    suspend fun <T> get(endpoint: Endpoint<T>): Pair<T, JsonElement> {
        val raw = call("GET", endpoint.path, endpoint.query)
        return decode(endpoint, raw) to raw
    }

    fun <T> decode(endpoint: Endpoint<T>, raw: JsonElement): T = read(endpoint.serializer, raw)

    /** An answer of an unexpected shape is an error of the call, never a crash. */
    private fun <T> read(serializer: KSerializer<T>, raw: JsonElement): T = try {
        BridgeJson.decodeFromJsonElement(serializer, raw)
    } catch (_: Exception) {
        throw ApiException(ApiException.Kind.HTTP, 502, "bad_answer")
    }

    // ---- writes (sent through the outbox)

    /**
     * A new note. With [clientId] (the phone's own id for it) a repeated send is answered with
     * the note already stored, so sending again after an unclear attempt is safe.
     */
    suspend fun addNote(text: String, clientId: String? = null): NoteSaved {
        val body = buildJsonObject {
            put("text", JsonPrimitive(text))
            if (clientId != null) put("client_id", JsonPrimitive(clientId))
        }
        return read(NoteSaved.serializer(), call("POST", "/notes", body = body, timeoutMillis = WRITE_TIMEOUT_MILLIS))
    }

    /** A new task; [clientId] as for [addNote]. */
    suspend fun addTask(title: String, due: String?, clientId: String? = null): TaskSaved {
        val body = buildJsonObject {
            put("title", JsonPrimitive(title))
            if (due != null) put("due", JsonPrimitive(due))
            if (clientId != null) put("client_id", JsonPrimitive(clientId))
        }
        return read(TaskSaved.serializer(), call("POST", "/tasks", body = body, timeoutMillis = WRITE_TIMEOUT_MILLIS))
    }

    /** Change a task's title and due date; a null [due] removes the date. */
    suspend fun editTask(id: String, title: String, due: String?) {
        val body = buildJsonObject {
            put("title", JsonPrimitive(title))
            put("due", if (due == null) JsonNull else JsonPrimitive(due))
        }
        call("PATCH", "/tasks/$id", body = body, timeoutMillis = WRITE_TIMEOUT_MILLIS)
    }

    /** Change the text of a captured note. The computer gives it a new id. */
    suspend fun editNote(id: String, text: String) {
        call("PATCH", "/notes/$id", body = buildJsonObject { put("text", JsonPrimitive(text)) }, timeoutMillis = WRITE_TIMEOUT_MILLIS)
    }

    suspend fun completeTask(id: String) {
        call("POST", "/tasks/$id/complete", timeoutMillis = WRITE_TIMEOUT_MILLIS)
    }

    suspend fun createEvent(draft: EventDraft) {
        call("POST", "/events", body = draft.createBody(), timeoutMillis = WRITE_TIMEOUT_MILLIS)
    }

    suspend fun updateEvent(id: String, draft: EventDraft) {
        call("PATCH", "/events/$id", body = draft.updateBody(), timeoutMillis = WRITE_TIMEOUT_MILLIS)
    }

    // ---- model routing (#124): a setting, sent directly (not through the outbox)

    /** Change one job's routing; [body] from [RoutingEdit]. Answers the new overview. */
    suspend fun changeRouting(body: JsonElement): ModelRouting =
        read(ModelRouting.serializer(), call("PUT", "/models/routing", body = body, timeoutMillis = WRITE_TIMEOUT_MILLIS))

    // ---- coach and courses

    /** A model run on the computer: slow, and limited to one in ten minutes there. */
    suspend fun refreshPlan(): Pair<CoachPlan, JsonElement> = planLane.withLock {
        // Its own lane: the run takes minutes and must not hold up every other request.
        val raw = exchange("POST", "/coach/plan/refresh", emptyMap(), null, PLAN_TIMEOUT_MILLIS)
        read(CoachPlan.serializer(), raw) to raw
    }

    suspend fun markSeen(courseId: Long) {
        call("POST", "/courses/$courseId/seen")
    }

    suspend fun startSync() {
        call("POST", "/courses/refresh")
    }

    suspend fun syncState(): SyncState = read(SyncState.serializer(), call("GET", "/courses/sync"))

    suspend fun fileAddress(courseId: Long, key: String): FileAddress =
        read(FileAddress.serializer(), call("POST", "/courses/$courseId/files/$key/address"))

    suspend fun fileOffer(courseId: Long, key: String): FileOffer =
        read(FileOffer.serializer(), call("POST", "/courses/$courseId/files/$key/offer"))

    fun fileChunks(offer: FileOffer, from: Int): Flow<AnswerEvent> {
        val query = buildMap {
            put("session_id", offer.sessionId)
            if (from > 0) put("from", from.toString())
        }
        return transport.stream("GET", "/files/${offer.offer}", query)
    }

    // ---- chat and sessions

    /**
     * Send [prompt] and receive the answer as it is written: a new conversation in [folder]
     * (null: the memory folder) when [sessionId] is null, otherwise the next message of that
     * session, which runs in its own folder. Not one of the one-at-a-time requests: an answer
     * takes minutes and nothing else may wait for it.
     */
    fun chat(prompt: String, sessionId: String?, folder: String?, options: Map<String, JsonElement> = emptyMap()): Flow<AnswerEvent> {
        val body = buildJsonObject {
            put("prompt", JsonPrimitive(prompt))
            put("stream", JsonPrimitive(true))
            if (sessionId == null && folder != null) put("folder", JsonPrimitive(folder))
            options.forEach { (name, value) -> put(name, value) }
        }
        val path = if (sessionId == null) "/ask" else "/sessions/$sessionId/messages"
        return transport.events("POST", path, body, CHAT_IDLE_MILLIS)
    }

    /**
     * A few exchanges of a session (the computer keeps the whole conversation): the latest
     * ones, or with [before] (the `older` cursor of a page) the ones before that page.
     */
    suspend fun history(sessionId: String, before: String? = null): HistoryPage =
        read(HistoryPage.serializer(), chatCall("GET", "/sessions/$sessionId/history", query = before?.let { mapOf("before" to it) }.orEmpty()))

    /** Stop the session's current run (interrupt first). What it already changed stays; its queue is put on hold. */
    suspend fun cancelRun(sessionId: String): CancelResult =
        read(CancelResult.serializer(), chatCall("POST", "/sessions/$sessionId/cancel"))

    /** Change the text of a message that waits in the session's queue. `404 not_queued` once it was sent. */
    suspend fun editQueued(sessionId: String, queueId: String, prompt: String) {
        chatCall("PATCH", "/sessions/$sessionId/queue/$queueId", buildJsonObject { put("prompt", JsonPrimitive(prompt)) })
    }

    suspend fun removeQueued(sessionId: String, queueId: String) {
        chatCall("DELETE", "/sessions/$sessionId/queue/$queueId")
    }

    /**
     * Let a queue that is on hold go on. The computer answers only when the first waiting
     * message has been answered, which takes as long as a run: the caller starts this without
     * waiting for it and asks for the state meanwhile. Hence the long limit (a short one would
     * count every use as "the computer did not answer") and no place in chat's lane.
     */
    suspend fun resumeQueue(sessionId: String) {
        exchange("POST", "/sessions/$sessionId/queue/resume", emptyMap(), null, CHAT_IDLE_MILLIS)
    }

    /** Whether the computer is working on the session right now, and what waits behind that. */
    suspend fun runState(sessionId: String): RunState =
        read(RunState.serializer(), chatCall("GET", "/sessions/$sessionId/queue"))

    /** The questions and approvals a run of the session waits for right now. */
    suspend fun interactions(sessionId: String): List<InteractionData> =
        read(PendingInteractions.serializer(), chatCall("GET", "/sessions/$sessionId/interactions")).pending

    /**
     * Answer a waiting question or approval ([Interaction.approvalBody] and its neighbours).
     * `404 not_pending` when the computer no longer waits for it.
     */
    suspend fun answerInteraction(sessionId: String, interactionId: String, body: JsonElement) {
        chatCall("POST", "/sessions/$sessionId/interactions/$interactionId", body)
    }

    /** The skills and commands of the session's folder, and whether the session may use them. */
    suspend fun commands(sessionId: String): CommandList =
        read(CommandList.serializer(), chatCall("GET", "/sessions/$sessionId/commands"))

    /**
     * Change what the computer keeps about a session (`PATCH /sessions/{id}`): its model and
     * effort, title, group, pin and switches. A JSON null returns a field to its default.
     */
    suspend fun patchSession(sessionId: String, changes: JsonElement): SessionSummary =
        read(SessionSummary.serializer(), chatCall("PATCH", "/sessions/$sessionId", changes))

    /** Chat's own lane: its small requests wait neither for the screens' loaders nor the other way round. */
    private suspend fun chatCall(method: String, path: String, body: JsonElement? = null, query: Map<String, String> = emptyMap()): JsonElement =
        chatLane.withLock { exchange(method, path, query, body, READ_TIMEOUT_MILLIS) }

    companion object {
        /**
         * How long a chat answer may stay quiet before the app stops listening and asks the
         * computer instead (the run itself goes on there). Longer than the computer's default
         * limits for one run (5 minutes) and for one question to the owner (10 minutes).
         */
        const val CHAT_IDLE_MILLIS = 11 * 60_000L
        const val READ_TIMEOUT_MILLIS = 20_000L
        const val WRITE_TIMEOUT_MILLIS = 20_000L
        const val PLAN_TIMEOUT_MILLIS = 240_000L
    }
}
