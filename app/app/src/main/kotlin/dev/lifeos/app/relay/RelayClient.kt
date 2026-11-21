package dev.lifeos.app.relay

import dev.lifeos.app.crypto.Kind
import dev.lifeos.app.crypto.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel as Queue

/** A request could not be completed. Never carries addresses or content. */
class RelayException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** The relay could not be reached and the request was certainly not delivered. */
        NOT_SENT,

        /** The relay failed while sending; the request may or may not have been delivered. */
        UNREACHABLE,

        /** The relay refused this phone (unpaired on the computer, or access withdrawn). */
        DENIED,

        /** The request was delivered to the relay, but no (complete) answer came in time. */
        TIMEOUT,
    }
}

/** Whether the mailbox itself is being reached. */
enum class Link { UNKNOWN, UP, DOWN }

/**
 * Requests to the bridge through the mailbox: each request is sealed, written to the
 * to-bridge folder, and its answer messages are collected from the to-phone folder.
 *
 * Retry policy (one place, [retryable]): a request is tried at most twice. A second try is
 * a *new* message (new request id, new counter, newly sealed); a sealed blob is never sent
 * twice. It happens only when the first try certainly did not arrive, or, for GET requests
 * (which change nothing on the computer), also when it may have.
 */
class RelayClient(
    private val mailbox: Mailbox,
    private val channel: Channel,
    deviceHex: String,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val random: (Int) -> ByteArray,
    private val onContact: () -> Unit = {},
) {
    private val toBridge = MailboxKeys.toBridge(deviceHex)
    private val toPhone = MailboxKeys.toPhone(deviceHex)
    private val waiting = ConcurrentHashMap<String, Queue<Message>>()

    /**
     * The streams this phone is reading, by request id. A stream holds one of the relay's few
     * workers on the computer for as long as it runs, so one that is no longer read is told to
     * the bridge ([letGo], [detachAll]) unless it has said its own end.
     */
    private val reading = ConcurrentHashMap.newKeySet<String>()
    private val taking = ConcurrentHashMap.newKeySet<String>()
    private val linkState = MutableStateFlow(Link.UNKNOWN)
    private var listener: Job? = null
    private val sendMutex = Mutex()
    private var lastKeyMillis = 0L

    val link: StateFlow<Link> = linkState

    /** Start listening for messages from the bridge. */
    fun start() {
        if (listener != null) return
        listener = scope.launch {
            mailbox.listen(toPhone).collect { event ->
                when (event) {
                    is MailboxEvent.Entries -> take(event.entries)
                    MailboxEvent.Up -> linkState.value = Link.UP
                    MailboxEvent.Down -> linkState.value = Link.DOWN
                }
            }
        }
    }

    fun stop() {
        listener?.cancel()
        listener = null
        linkState.value = Link.UNKNOWN
    }

    /**
     * Check each entry, take it out of the mailbox (the acknowledgement) and hand it to the
     * request that waits for it. The bridge numbers messages before it writes them, so a batch
     * is handled in counter order, not in key order.
     */
    private fun take(entries: List<MailboxEntry>) {
        val batch = entries
            .filter { taking.add(it.key) }
            .map { it to MailboxKeys.decode(it.value) }
            .sortedBy { (_, blob) -> blob?.let(channel::counterOf) ?: Long.MAX_VALUE }
        for ((entry, blob) in batch) {
            val message = blob?.let(channel::accept)
            // Whatever it was, it leaves the mailbox. If the delete fails, a second
            // delivery of the same message is rejected as a repeat.
            scope.launch {
                try {
                    mailbox.delete("$toPhone/${entry.key}")
                } catch (_: MailboxException) {
                } finally {
                    taking.remove(entry.key)
                }
            }
            if (message != null) {
                onContact()
                waiting[message.requestId]?.trySend(message)
            }
        }
    }

    /** One request, one answer. Throws [RelayException]. */
    suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonElement? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): RelayResponse {
        var tries = 0
        while (true) {
            tries++
            try {
                val done = withTimeoutOrNull(timeoutMillis) {
                    exchange(method, path, query, body, timeoutMillis, streamed = false).first { it is AnswerEvent.Done }
                } ?: throw RelayException(RelayException.Reason.TIMEOUT)
                return (done as AnswerEvent.Done).response
            } catch (error: RelayException) {
                if (tries >= MAX_TRIES || !retryable(error.reason, method)) throw error
            }
        }
    }

    /**
     * A request whose answer arrives in pieces: text of a streamed answer, or a file. The flow
     * ends after [AnswerEvent.Done]; it fails with [RelayException] when nothing arrives for
     * [idleTimeoutMillis]. A started stream is never retried here. [firstTimeoutMillis] is how
     * long the first message may take: the computer answers a request within seconds even when
     * the rest of the answer (a model run) then stays quiet for minutes.
     *
     * When the collector stops before the stream has ended (cancelled, timed out, or simply not
     * read further), the bridge is told with `POST /relay/detach` (#157): a followed run's
     * stream is closed there at once; a chat message's run goes on unseen and can be followed
     * again. A stream that has said `done`, `error` or `queued` ends by itself.
     */
    fun stream(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonElement? = null,
        idleTimeoutMillis: Long = DEFAULT_IDLE_MILLIS,
        firstTimeoutMillis: Long = idleTimeoutMillis,
    ): Flow<AnswerEvent> = exchange(method, path, query, body, idleTimeoutMillis, firstTimeoutMillis, streamed = true)

    private fun payload(method: String, path: String, query: Map<String, String>, body: JsonElement?): JsonObject = buildJsonObject {
        put("m", JsonPrimitive(method))
        put("path", JsonPrimitive(path))
        put("q", JsonObject(query.mapValues { JsonPrimitive(it.value) }))
        put("body", body ?: JsonNull)
    }

    private fun newRequestId(): String = random(8).joinToString("") { "%02x".format(it) }

    private fun detachBody(requestId: String): JsonObject = buildJsonObject { put("r", JsonPrimitive(requestId)) }

    /** Tell the bridge that the stream [requestId] is no longer read. Best effort: without it the bridge lets go by itself, later. */
    private suspend fun letGo(requestId: String) {
        try {
            request("POST", DETACH_PATH, body = detachBody(requestId), timeoutMillis = DETACH_TIMEOUT_MILLIS)
        } catch (_: RelayException) {
            // Not reached: nothing more to do from here.
        }
    }

    /**
     * The app is about to forget the secret (it is being locked): every stream it still reads
     * is let go now. The messages are sealed here, at once, because nothing can be sealed
     * afterwards; they are written by [writer], which outlives this client. If a request is
     * being written at this very moment nothing is sent, and the bridge lets the streams go
     * by itself when their messages stay unread.
     */
    fun detachAll(writer: CoroutineScope) {
        val open = reading.toList()
        reading.clear()
        if (open.isEmpty() || !sendMutex.tryLock()) return
        val sealed = try {
            open.map { requestId ->
                val blob = channel.seal(Kind.REQUEST, newRequestId(), payload("POST", DETACH_PATH, emptyMap(), detachBody(requestId)))
                lastKeyMillis = maxOf(nowMillis(), lastKeyMillis + 1)
                "$toBridge/${MailboxKeys.newKey(lastKeyMillis, random(4))}" to MailboxKeys.encode(blob)
            }
        } catch (_: Exception) {
            // The counters could not be stored: nothing was sealed.
            emptyList()
        } finally {
            sendMutex.unlock()
        }
        writer.launch {
            for ((key, value) in sealed) {
                try {
                    mailbox.put(key, value)
                } catch (_: MailboxException) {
                    return@launch
                }
            }
        }
    }

    private fun exchange(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JsonElement?,
        idleTimeoutMillis: Long,
        firstTimeoutMillis: Long = idleTimeoutMillis,
        streamed: Boolean,
    ): Flow<AnswerEvent> = flow {
        val requestId = newRequestId()
        val inbox = Queue<Message>(UNLIMITED)
        waiting[requestId] = inbox
        try {
            val payload = payload(method, path, query, body)
            // A new counter and a new blob for every try; see the retry policy above.
            // Sealing and writing happen one request at a time, with rising key times, so the
            // bridge meets the counters in rising order and never takes a later request first.
            sendMutex.withLock {
                val blob = channel.seal(Kind.REQUEST, requestId, payload)
                lastKeyMillis = maxOf(nowMillis(), lastKeyMillis + 1)
                try {
                    mailbox.put("$toBridge/${MailboxKeys.newKey(lastKeyMillis, random(4))}", MailboxKeys.encode(blob))
                } catch (error: MailboxException) {
                    throw RelayException(
                        when (error.reason) {
                            MailboxException.Reason.NOT_SENT -> RelayException.Reason.NOT_SENT
                            MailboxException.Reason.DENIED -> RelayException.Reason.DENIED
                            else -> RelayException.Reason.UNREACHABLE
                        },
                    )
                }
            }
            // From here on the bridge may be working on it.
            if (streamed) reading.add(requestId)
            val assembler = AnswerAssembler()
            var limit = firstTimeoutMillis
            while (true) {
                val message = withTimeoutOrNull(limit) { inbox.receive() }
                    ?: throw RelayException(RelayException.Reason.TIMEOUT)
                limit = idleTimeoutMillis
                for (event in assembler.feed(message)) {
                    // It has said its own end: the bridge closes it by itself.
                    if (event is AnswerEvent.Done || (event is AnswerEvent.Stream && event.event in LAST_EVENTS)) reading.remove(requestId)
                    emit(event)
                    if (event is AnswerEvent.Done) return@flow
                }
            }
        } finally {
            waiting.remove(requestId)
            // Still running on the bridge and no longer read here: say so, or it keeps a relay worker.
            if (reading.remove(requestId)) scope.launch { letGo(requestId) }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        const val DEFAULT_IDLE_MILLIS = 300_000L
        const val MAX_TRIES = 2

        /** "I stopped reading this stream" (the bridge's relay answers it itself, also when all its workers are busy). */
        const val DETACH_PATH = "/relay/detach"
        const val DETACH_TIMEOUT_MILLIS = 15_000L

        /** After one of these events a stream ends by itself on the bridge. */
        private val LAST_EVENTS = setOf("done", "error", "queued")

        /** The one retry rule. */
        fun retryable(reason: RelayException.Reason, method: String): Boolean = when (reason) {
            RelayException.Reason.NOT_SENT -> true
            RelayException.Reason.UNREACHABLE, RelayException.Reason.TIMEOUT -> method == "GET"
            RelayException.Reason.DENIED -> false
        }
    }
}
