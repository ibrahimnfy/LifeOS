package dev.lifeos.app.relay

import dev.lifeos.app.crypto.Kind
import dev.lifeos.app.crypto.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** The answer to one request. [error] is the bridge's short error word, if it sent one. */
data class RelayResponse(val status: Int, val body: JsonElement?, val error: String? = null) {
    val ok: Boolean get() = status in 200..299
}

/** What arrives for one request, in order. Exactly one [Done] ends it. */
sealed interface AnswerEvent {
    /** One server-sent event of a streamed answer (for example a piece of text). */
    data class Stream(val event: String, val data: JsonElement) : AnswerEvent

    /** One piece of a file. */
    class FileChunk(val index: Int, val total: Int, val bytes: ByteArray) : AnswerEvent

    data class Done(val response: RelayResponse) : AnswerEvent
}

/**
 * Puts the messages of one answer together (the bridge's `relay.py`):
 * END `{"status","body"}` · CHUNK `{"i","part"}`… END `{"status","parts"}` ·
 * CHUNK `{"event","data"}`… END `{"status","stream":true}` · CHUNK `{"i","n","b64"}`… END
 * `{"status","file"}` · ERROR `{"status","error"}`.
 */
class AnswerAssembler {
    private val parts = HashMap<Int, String>()
    private var partChars = 0L

    /** The events one message leads to. Messages that fit no shape lead to nothing. */
    fun feed(message: Message): List<AnswerEvent> {
        val payload = message.payload as? JsonObject ?: return malformedIfLast(message)
        return when (message.header.kind) {
            Kind.CHUNK -> chunk(payload)
            Kind.END -> listOf(AnswerEvent.Done(end(payload)))
            Kind.ERROR -> listOf(
                AnswerEvent.Done(
                    RelayResponse(payload.int("status") ?: 502, null, payload.string("error") ?: "error"),
                ),
            )
            else -> emptyList()
        }
    }

    private fun malformedIfLast(message: Message): List<AnswerEvent> =
        if (message.header.kind == Kind.END || message.header.kind == Kind.ERROR) {
            listOf(AnswerEvent.Done(RelayResponse(502, null, "malformed_answer")))
        } else {
            emptyList()
        }

    private fun chunk(payload: JsonObject): List<AnswerEvent> {
        val index = payload.int("i")
        val part = payload.string("part")
        val event = payload.string("event")
        val file = payload.string("b64")
        return when {
            index != null && part != null -> {
                if (index in 0 until MAX_PARTS && partChars + part.length <= MAX_CHARS && index !in parts) {
                    parts[index] = part
                    partChars += part.length
                }
                emptyList()
            }
            event != null -> listOf(AnswerEvent.Stream(event, payload["data"] ?: JsonNull))
            index != null && file != null -> {
                val bytes = MailboxKeys.decode(file) ?: return emptyList()
                listOf(AnswerEvent.FileChunk(index, payload.int("n") ?: 0, bytes))
            }
            else -> emptyList()
        }
    }

    private fun end(payload: JsonObject): RelayResponse {
        val status = payload.int("status") ?: return RelayResponse(502, null, "malformed_answer")
        val count = payload.int("parts")
        return when {
            count != null -> {
                val text = StringBuilder()
                for (index in 0 until count) text.append(parts[index] ?: return RelayResponse(502, null, "incomplete_answer"))
                try {
                    RelayResponse(status, Json.parseToJsonElement(text.toString()))
                } catch (_: Exception) {
                    RelayResponse(502, null, "malformed_answer")
                }
            }
            "body" in payload -> RelayResponse(status, payload["body"])
            "file" in payload -> RelayResponse(status, payload["file"])
            else -> RelayResponse(status, null) // the end of a stream
        }
    }

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val MAX_PARTS = 400
        const val MAX_CHARS = 64_000_000L
    }
}
