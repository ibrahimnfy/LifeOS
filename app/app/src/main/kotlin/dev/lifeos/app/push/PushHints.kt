package dev.lifeos.app.push

import dev.lifeos.app.crypto.Direction
import dev.lifeos.app.crypto.E2e
import dev.lifeos.app.crypto.Kind
import dev.lifeos.app.crypto.Rejected
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/** What a push says: which kind of event, which item, and for a notice its title and link. */
data class Hint(val event: String, val ref: String = "", val title: String? = null, val link: String? = null)

/** The kinds of notification the app shows; each has its own channel and switch. */
enum class NotificationKind(val id: String) {
    ANSWER("answer"),
    REMINDER("reminder"),
    COACH("coach"),
    COURSES("courses"),
    WAITING("waiting"),
    NOTICE("notice"),
    SLEEP("sleep"),
}

/** Where a tap on the notification leads. */
sealed interface Destination {
    data class Chat(val sessionId: String) : Destination
    data object Coach : Destination
    data object Courses : Destination
    data object Sessions : Destination
    data object Today : Destination
    data object Sleep : Destination

    fun encode(): String = when (this) {
        is Chat -> "chat:$sessionId"
        Coach -> "coach"
        Courses -> "courses"
        Sessions -> "sessions"
        Today -> "today"
        Sleep -> "sleep"
    }

    companion object {
        fun decode(text: String?): Destination? = when {
            text == null -> null
            text.startsWith("chat:") -> Chat(text.removePrefix("chat:").take(MAX_REF))
            text == "coach" -> Coach
            text == "courses" -> Courses
            text == "sessions" -> Sessions
            text == "today" -> Today
            text == "sleep" -> Sleep
            else -> null
        }

        const val MAX_REF = 300
    }
}

object PushHints {
    private const val MAX_FIELD = 300
    val EVENTS = setOf("answer", "reminder", "coach", "courses", "signin", "notice", "approval", "question")

    /**
     * Open the `h` field of a push with the push key (sub-key 3 of the pairing), or null.
     *
     * Checks, in order; the first failure drops the push without a trace: readable base64 and
     * header, this device, direction "push", kind "push hint", authentication with the push
     * key, a counter greater than every earlier one ([acceptCounter] stores it), a known event.
     * The push key opens nothing but such hints: requests and answers use other keys.
     */
    fun open(e2e: E2e, pushKey: ByteArray, deviceId: ByteArray, field: String?, acceptCounter: (Long) -> Boolean): Hint? {
        val blob = try {
            Base64.getDecoder().decode(field ?: return null)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            val header = e2e.parseHeader(blob)
            if (!header.isFor(deviceId) || header.direction != Direction.PUSH || header.kind != Kind.PUSH_HINT) return null
            val message = e2e.openWithKey(pushKey, blob)
            val payload = message.payload as? JsonObject ?: return null
            val event = payload.text("e")?.takeIf { it in EVENTS } ?: return null
            if (!acceptCounter(header.counter)) return null
            Hint(event, payload.text("r").orEmpty(), payload.text("t"), payload.text("l"))
        } catch (_: Rejected) {
            null
        }
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(MAX_FIELD)

    /** Which notification an event becomes. The text is fixed per kind; see the string resources. */
    fun kindOf(hint: Hint): NotificationKind = when (hint.event) {
        "answer" -> NotificationKind.ANSWER
        "reminder" -> NotificationKind.REMINDER
        "coach" -> NotificationKind.COACH
        "courses", "signin" -> NotificationKind.COURSES
        "approval", "question" -> NotificationKind.WAITING
        else -> NotificationKind.NOTICE
    }

    fun destinationOf(hint: Hint): Destination = when (hint.event) {
        "answer", "approval", "question" -> if (hint.ref.isNotEmpty()) Destination.Chat(hint.ref) else Destination.Sessions
        "reminder" -> Destination.Sessions
        "coach" -> Destination.Coach
        "courses", "signin" -> Destination.Courses
        else -> Destination.Today
    }

    /**
     * The only text from the computer that may appear in a notification: the title of a
     * "notice", which arrived sealed inside the hint. One line, no control characters.
     */
    fun noticeTitle(hint: Hint): String? =
        hint.title?.takeIf { hint.event == "notice" }?.filterNot { it.isISOControl() }?.trim()?.take(120)?.ifEmpty { null }
}
