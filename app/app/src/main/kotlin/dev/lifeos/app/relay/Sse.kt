package dev.lifeos.app.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class SseEvent(val event: String, val data: String)

/** A minimal server-sent-events line parser: feed it lines, it returns an event at each blank line. */
class SseParser {
    private var event = ""
    private val data = StringBuilder()
    private var hasData = false

    fun feed(line: String): SseEvent? {
        val text = line.removeSuffix("\r")
        if (text.isEmpty()) {
            val done = if (hasData || event.isNotEmpty()) SseEvent(event.ifEmpty { "message" }, data.toString()) else null
            event = ""
            data.setLength(0)
            hasData = false
            return done
        }
        if (text.startsWith(":")) return null // a comment
        val name = text.substringBefore(':')
        val value = if (':' in text) text.substringAfter(':').removePrefix(" ") else ""
        when (name) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }
}

/** What one event of the database's stream means for a mailbox folder. */
sealed interface StreamSignal {
    data class Entries(val entries: List<MailboxEntry>) : StreamSignal
    data object KeepAlive : StreamSignal

    /** The server ended the stream: access was withdrawn or the token is no longer accepted. */
    data class Closed(val authRevoked: Boolean) : StreamSignal
    data object Nothing : StreamSignal
}

object RtdbStream {
    /**
     * `put` and `patch` carry `{"path": ..., "data": ...}`. The first event has path "/" and
     * everything that is there; later ones name one child. Deletions and deeper paths are ignored.
     */
    fun interpret(event: SseEvent): StreamSignal = when (event.event) {
        "keep-alive" -> StreamSignal.KeepAlive
        "cancel" -> StreamSignal.Closed(authRevoked = false)
        "auth_revoked" -> StreamSignal.Closed(authRevoked = true)
        "put", "patch" -> entries(event.data)
        else -> StreamSignal.Nothing
    }

    private fun entries(data: String): StreamSignal {
        val change = try {
            Json.parseToJsonElement(data) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return StreamSignal.Nothing
        val path = ((change["path"] as? JsonPrimitive)?.content ?: "/").trim('/')
        val value = change["data"]
        if (value == null || value is JsonNull) return StreamSignal.Nothing
        val found = when {
            path.isEmpty() && value is JsonObject ->
                value.entries.sortedBy { it.key }.mapNotNull { (key, child) -> entry(key, child) }
            path.isNotEmpty() && '/' !in path -> listOfNotNull(entry(path, value))
            else -> emptyList()
        }
        return if (found.isEmpty()) StreamSignal.Nothing else StreamSignal.Entries(found)
    }

    private fun entry(key: String, value: Any?): MailboxEntry? = when {
        value is JsonNull -> null
        value is JsonPrimitive && value.isString -> MailboxEntry(key, value.content)
        else -> MailboxEntry(key, null) // not a message; the receiver removes it
    }
}
