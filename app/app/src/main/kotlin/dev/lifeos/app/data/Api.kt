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
}

/**
 * Typed calls to the bridge's API through the relay. Requests are made one at a time, so
 * answers can never overtake each other on the way back.
 */
class Api(private val transport: Transport) {
    private val oneAtATime = Mutex()
    private val planLane = Mutex()

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

    suspend fun addNote(text: String) {
        call("POST", "/notes", body = buildJsonObject { put("text", JsonPrimitive(text)) }, timeoutMillis = WRITE_TIMEOUT_MILLIS)
    }

    suspend fun addTask(title: String, due: String?) {
        val body = buildJsonObject {
            put("title", JsonPrimitive(title))
            if (due != null) put("due", JsonPrimitive(due))
        }
        call("POST", "/tasks", body = body, timeoutMillis = WRITE_TIMEOUT_MILLIS)
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

    companion object {
        const val READ_TIMEOUT_MILLIS = 20_000L
        const val WRITE_TIMEOUT_MILLIS = 20_000L
        const val PLAN_TIMEOUT_MILLIS = 240_000L
    }
}
