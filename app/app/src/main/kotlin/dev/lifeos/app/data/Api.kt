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
    private val commandLane = Mutex()
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
    suspend fun addNote(text: String, clientId: String? = null, remindAt: String? = null, timezone: String? = null): NoteSaved {
        val body = buildJsonObject {
            put("text", JsonPrimitive(text))
            if (clientId != null) put("client_id", JsonPrimitive(clientId))
            // The note's reminder (#185): a time with its offset, and the zone's name when known.
            if (remindAt != null) put("remind_at", JsonPrimitive(remindAt))
            if (remindAt != null && timezone != null) put("timezone", JsonPrimitive(timezone))
        }
        return read(NoteSaved.serializer(), call("POST", "/notes", body = body, timeoutMillis = WRITE_TIMEOUT_MILLIS))
    }

    /** A new task; [clientId] as for [addNote]; [note] the owner's note (#170), sent only when there is one. */
    suspend fun addTask(title: String, due: String?, clientId: String? = null, note: String? = null): TaskSaved {
        val body = buildJsonObject {
            put("title", JsonPrimitive(title))
            if (due != null) put("due", JsonPrimitive(due))
            TaskNote.of(note)?.let { put("note", JsonPrimitive(it)) }
            if (clientId != null) put("client_id", JsonPrimitive(clientId))
        }
        return read(TaskSaved.serializer(), call("POST", "/tasks", body = body, timeoutMillis = WRITE_TIMEOUT_MILLIS))
    }

    /**
     * Change a task's title and due date; a null [due] removes the date. [note] (#170): null
     * leaves the note as it is (not sent), blank removes it, other text replaces it.
     */
    suspend fun editTask(id: String, title: String, due: String?, note: String? = null) {
        val body = buildJsonObject {
            put("title", JsonPrimitive(title))
            put("due", if (due == null) JsonNull else JsonPrimitive(due))
            if (note != null) put("note", TaskNote.of(note)?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        call("PATCH", "/tasks/$id", body = body, timeoutMillis = WRITE_TIMEOUT_MILLIS)
    }

    /** Change the text of a captured note. The computer gives it a new id, returned here (null if unreadable). */
    suspend fun editNote(id: String, text: String): String? {
        val raw = call("PATCH", "/notes/$id", body = buildJsonObject { put("text", JsonPrimitive(text)) }, timeoutMillis = WRITE_TIMEOUT_MILLIS)
        return ((raw as? JsonObject)?.get("id") as? JsonPrimitive)?.content
    }

    /** Move a note to the computer's trash (#184). A note that is not there answers 404. */
    suspend fun deleteNote(id: String) {
        call("DELETE", "/notes/$id", timeoutMillis = WRITE_TIMEOUT_MILLIS)
    }

    /** Bring a deleted note back under the same id (the command bar's undo, #185). */
    suspend fun restoreNote(id: String) {
        call("POST", "/notes/$id/restore", timeoutMillis = WRITE_TIMEOUT_MILLIS)
    }

    suspend fun completeTask(id: String) {
        call("POST", "/tasks/$id/complete", timeoutMillis = WRITE_TIMEOUT_MILLIS)
    }

    /** A finished task back among the open ones (the undo of a tick that already left the phone). */
    suspend fun reopenTask(id: String) {
        call("POST", "/tasks/$id/reopen", timeoutMillis = WRITE_TIMEOUT_MILLIS)
    }

    /** Move an open task to the computer's trash (#197). A task that is not open answers 404. */
    suspend fun deleteTask(id: String) {
        call("DELETE", "/tasks/$id", timeoutMillis = WRITE_TIMEOUT_MILLIS)
    }

    /** Bring a deleted task back (the undo of a deletion that already left the phone). */
    suspend fun restoreTask(id: String) {
        call("POST", "/tasks/$id/restore", timeoutMillis = WRITE_TIMEOUT_MILLIS)
    }

    /** Mark a course assignment by hand (#196): "done", "removed", or null to take the mark off. */
    suspend fun markAssignment(courseId: Long, assignmentId: Long, mark: String?) {
        val body = buildJsonObject { put("mark", mark?.let { JsonPrimitive(it) } ?: JsonNull) }
        call("PUT", "/courses/$courseId/assignments/$assignmentId/mark", body = body, timeoutMillis = WRITE_TIMEOUT_MILLIS)
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

    /**
     * The command bar (#185): a request turned into actions on the computer (a short model run).
     * Its own lane, so it never holds up the other requests; a repeat with the same request id
     * is answered with the stored answer.
     */
    suspend fun command(body: JsonObject): CommandAnswer = commandLane.withLock {
        read(CommandAnswer.serializer(), exchange("POST", "/command", emptyMap(), body, COMMAND_TIMEOUT_MILLIS))
    }

    /** Take one action of the bar back (its undo token), once. */
    suspend fun commandUndo(token: String): CommandUndone =
        read(CommandUndone.serializer(), call("POST", "/command/undo", body = buildJsonObject { put("undo", JsonPrimitive(token)) }, timeoutMillis = WRITE_TIMEOUT_MILLIS))

    /** The folder the owner picked on a "choose_folder" card (#179): allowed if needed, then a new chat starts there. */
    suspend fun openFolder(path: String): OpenedFolder =
        read(OpenedFolder.serializer(), call("POST", "/folders/open", body = buildJsonObject { put("path", JsonPrimitive(path)) }))

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
    fun chat(
        prompt: String,
        sessionId: String?,
        folder: String?,
        options: Map<String, JsonElement> = emptyMap(),
        /** The coach conversation (#114): the computer knows its session and renews it by itself. */
        coach: Boolean = false,
    ): Flow<AnswerEvent> {
        val body = buildJsonObject {
            put("prompt", JsonPrimitive(prompt))
            put("stream", JsonPrimitive(true))
            if (!coach && sessionId == null && folder != null) put("folder", JsonPrimitive(folder))
            options.forEach { (name, value) -> put(name, value) }
        }
        val path = if (coach) COACH_MESSAGES else if (sessionId == null) "/ask" else "/sessions/$sessionId/messages"
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
     * Let a queue that is on hold go on. The computer answers at once (#154) and sends the
     * waiting messages one after the other in the background; [live] follows their answers.
     */
    suspend fun resumeQueue(sessionId: String) {
        chatCall("POST", "/sessions/$sessionId/queue/resume")
    }

    /**
     * "Send now" for a message that waits: it becomes the next one, a run in progress is
     * interrupted for it (as with `"when": "now"`), a hold on the queue ends. One request,
     * nothing is sent twice. `404 not_queued` once it was sent.
     */
    suspend fun sendQueuedNow(sessionId: String, queueId: String): SendNowResult =
        read(SendNowResult.serializer(), chatCall("POST", "/sessions/$sessionId/queue/$queueId/send-now"))

    /**
     * The session's current run as events, also one that began earlier or elsewhere (a
     * waiting message the computer sent by itself, an answer this app stopped hearing): first
     * `live {"running", "followable", "queue_id"}`, then what the run said so far, then the
     * rest as it comes, to its `done` or `error`. Read-only: it never starts a run. With
     * nothing to follow the stream is only `live` and ends.
     */
    fun live(sessionId: String): Flow<AnswerEvent> = transport.events("GET", "/sessions/$sessionId/live", null, CHAT_IDLE_MILLIS)

    /** The session's file offers that have not run out, so a card read again keeps its download. */
    suspend fun sessionFiles(sessionId: String): List<LiveOffer> =
        read(LiveOffers.serializer(), chatCall("GET", "/sessions/$sessionId/files")).files

    /**
     * One session as the computer has it now (not the list of the newest hundred): title,
     * folder, model, effort, switches, whether it runs here or is open elsewhere, and its
     * waiting messages. `404 session_not_found`.
     */
    suspend fun session(sessionId: String): SessionSummary =
        read(SessionSummary.serializer(), chatCall("GET", "/sessions/$sessionId"))

    /** Whether the computer is working on the session right now, and what waits behind that. */
    suspend fun runState(sessionId: String): RunState =
        read(RunState.serializer(), chatCall("GET", "/sessions/$sessionId/queue"))

    /**
     * What waits for the owner in the session: the questions and approvals of a running turn,
     * and the cards that wait across runs for up to a day (skill installs, MCP servers).
     */
    suspend fun interactions(sessionId: String): List<InteractionData> =
        read(PendingInteractions.serializer(), chatCall("GET", "/sessions/$sessionId/interactions")).pending

    /**
     * Answer a waiting question or approval ([Interaction.approvalBody] and its neighbours);
     * the computer's answer says what it did (`"installed"` for a skill install).
     * `404 not_pending` when the computer no longer waits for it. A yes it refuses leaves the
     * card waiting (`409 still_downloading`, `409 skill_exists`, `503 audit_unavailable`, `500
     * install_failed`; see [Interaction.refusal]); a no is always taken.
     */
    suspend fun answerInteraction(sessionId: String, interactionId: String, body: JsonElement): JsonElement =
        chatCall("POST", "/sessions/$sessionId/interactions/$interactionId", body)

    // ---- a session's MCP servers: the folder's and the owner's personal ones (#153, #162)

    /** The MCP servers of the session's folder, then the owner's personal ones, each with its approval. Starts nothing. */
    suspend fun mcpServers(sessionId: String): List<McpServer> =
        read(McpServerList.serializer(), chatCall("GET", "/sessions/$sessionId/mcp-servers")).servers

    /**
     * Ask for the approval card of one server of [scope] ("folder" or "personal"): exactly its
     * definition as it is now. The card waits in the session's interactions and is answered
     * like any approval. `404 mcp_server_not_found`, `409 mcp_server_unsupported`, `503
     * audit_unavailable`.
     */
    suspend fun requestMcpApproval(sessionId: String, name: String, scope: String): InteractionData =
        read(InteractionData.serializer(), chatCall("POST", "/sessions/$sessionId/mcp-servers/$name/approval", query = mapOf("scope" to scope)))

    /** Take the approval of the server of [scope] back; false when it had none. */
    suspend fun revokeMcp(sessionId: String, name: String, scope: String): Boolean =
        read(McpRevoked.serializer(), chatCall("DELETE", "/sessions/$sessionId/mcp-servers/$name", query = mapOf("scope" to scope))).revoked

    /** The skills and commands of the session (the folder's own, then the owner's personal ones), and which it may use. */
    suspend fun commands(sessionId: String): CommandList =
        read(CommandList.serializer(), chatCall("GET", "/sessions/$sessionId/commands"))

    /**
     * Change what the computer keeps about a session (`PATCH /sessions/{id}`): its model and
     * effort, title, group, pin and switches. A JSON null returns a field to its default.
     */
    suspend fun patchSession(sessionId: String, changes: JsonElement): SessionSummary =
        read(SessionSummary.serializer(), chatCall("PATCH", "/sessions/$sessionId", changes))

    // ---- the coach conversation (#114)

    /** The coach conversation's session, size, summary and plan usage. Never starts a run. */
    suspend fun coachChat(): CoachChatView = read(CoachChatView.serializer(), chatCall("GET", "/coach/chat"))

    /**
     * Tidy the coach conversation up now: one run on the computer writes what matters to the
     * memory and keeps a summary; a new session continues. `409 nothing_to_compact` while it
     * is empty, `409 session_in_use` while the coach is answering.
     */
    suspend fun compactCoach(): CoachCompacted = planLane.withLock {
        read(CoachCompacted.serializer(), exchange("POST", "/coach/chat/compact", emptyMap(), null, PLAN_TIMEOUT_MILLIS))
    }

    // ---- session reminders (#75): kept and fired by the computer

    /** A new reminder for a session; the body is [Reminders.body] with the session's id. */
    suspend fun createReminder(body: JsonElement): Reminder = read(Reminder.serializer(), chatCall("POST", "/reminders", body))

    /** Change a reminder's time, note or repeat; a new time schedules it again. */
    suspend fun updateReminder(reminderId: String, body: JsonElement): Reminder =
        read(Reminder.serializer(), chatCall("PATCH", "/reminders/$reminderId", body))

    suspend fun deleteReminder(reminderId: String) {
        chatCall("DELETE", "/reminders/$reminderId")
    }

    /** Chat's own lane: its small requests wait neither for the screens' loaders nor the other way round. */
    private suspend fun chatCall(method: String, path: String, body: JsonElement? = null, query: Map<String, String> = emptyMap()): JsonElement =
        chatLane.withLock { exchange(method, path, query, body, READ_TIMEOUT_MILLIS) }

    companion object {
        /**
         * How long a chat answer may stay quiet before the app stops listening and asks the
         * computer instead (the run itself goes on there). A run that has nothing to say sends
         * a keep-alive after 20 seconds, and every 30 seconds while it waits for the owner
         * (#148): three of them missed, and the stream counts as lost.
         */
        const val CHAT_IDLE_MILLIS = 75_000L

        /** Where a message to the coach goes (#114); its first answer may take as long as a run. */
        const val COACH_MESSAGES = "/coach/chat/messages"
        const val READ_TIMEOUT_MILLIS = 20_000L
        const val WRITE_TIMEOUT_MILLIS = 20_000L
        const val PLAN_TIMEOUT_MILLIS = 240_000L

        /** The bridge gives its interpreter 45 seconds; the actions themselves are quick. */
        const val COMMAND_TIMEOUT_MILLIS = 75_000L
    }
}
