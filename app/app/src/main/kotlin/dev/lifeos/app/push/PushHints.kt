package dev.lifeos.app.push

import dev.lifeos.app.crypto.Direction
import dev.lifeos.app.crypto.E2e
import dev.lifeos.app.crypto.Kind
import dev.lifeos.app.crypto.Rejected
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * What a push says (bridge `push.hint_for`): the event `e`, its channel `c`, the screen to open
 * `o`, the item `r`, counts per kind `n` (course syncs only) and, for a notice, its title `t`
 * and link `l`. Older hints carry only `e` and `r`; channel and screen then follow the event.
 */
data class Hint(
    val event: String,
    val ref: String = "",
    val title: String? = null,
    val link: String? = null,
    val channel: String? = null,
    val opens: String? = null,
    val counts: Map<String, Int> = emptyMap(),
)

/** The Android notification channels: one per kind, so the owner can mute a kind in the system settings. */
enum class PushChannel(val id: String) {
    CHAT("chat"),
    COURSES("courses"),
    COACH("coach"),
    REMINDERS("reminders"),
    ;

    companion object {
        fun of(id: String?): PushChannel? = entries.firstOrNull { it.id == id }
    }
}

/** Where a tap on the notification leads. */
sealed interface Destination {
    data class Chat(val sessionId: String) : Destination
    data class Course(val courseId: Long) : Destination
    data class Reminder(val reminderId: String) : Destination

    /** The coach's daily note or a nudge (#76): [messageId] is the message the push named. */
    data class Coach(val messageId: String? = null) : Destination
    data object Courses : Destination
    data object CourseSignIn : Destination
    data object Sessions : Destination
    data object Today : Destination
    data object Notice : Destination
    data object Sleep : Destination

    /** The "update ready" prompt (#138). */
    data object AppUpdate : Destination

    /** A memory consolidation proposal waits for approval (#151). */
    data class Consolidation(val proposalId: String? = null) : Destination

    fun encode(): String = when (this) {
        is Chat -> "chat:$sessionId"
        is Course -> "course:$courseId"
        is Reminder -> "reminder:$reminderId"
        is Coach -> if (messageId.isNullOrEmpty()) "coach" else "coach:$messageId"
        Courses -> "courses"
        CourseSignIn -> "moodle_signin"
        Sessions -> "sessions"
        Today -> "today"
        Notice -> "notice"
        Sleep -> "sleep"
        AppUpdate -> "app_update"
        is Consolidation -> if (proposalId.isNullOrEmpty()) "consolidation" else "consolidation:$proposalId"
    }

    companion object {
        fun decode(text: String?): Destination? = when {
            text == null -> null
            text.startsWith("chat:") -> Chat(text.removePrefix("chat:").take(MAX_REF))
            text.startsWith("course:") -> text.removePrefix("course:").toLongOrNull()?.let { Course(it) }
            text.startsWith("reminder:") -> Reminder(text.removePrefix("reminder:").take(MAX_REF))
            text == "coach" -> Coach()
            text.startsWith("coach:") -> Coach(text.removePrefix("coach:").take(MAX_REF).ifEmpty { null })
            text == "courses" -> Courses
            text == "moodle_signin" -> CourseSignIn
            text == "sessions" -> Sessions
            text == "today" -> Today
            text == "notice" -> Notice
            text == "sleep" -> Sleep
            text == "app_update" -> AppUpdate
            text == "consolidation" -> Consolidation()
            text.startsWith("consolidation:") -> Consolidation(text.removePrefix("consolidation:").take(MAX_REF).ifEmpty { null })
            else -> null
        }

        const val MAX_REF = 300
    }
}

/** A tapped push: where it leads, and for a notice its title and link (shown only in the unlocked app). */
data class PushOpen(val destination: Destination, val noticeTitle: String? = null, val noticeLink: String? = null)

object PushHints {
    private const val MAX_FIELD = 300
    private const val MAX_COUNT = 9999
    val EVENTS = setOf("answer", "reminder", "coach", "courses", "signin", "notice", "approval", "question", "update", "cleared")

    /** Events that only remove a notification: a question or approval no longer waits. */
    fun clears(hint: Hint): Boolean = hint.event == "cleared"

    /**
     * Open the `h` field of a push with the push key (sub-key 3 of the pairing), or null.
     *
     * Checks, in order; the first failure drops the push without a trace: readable base64 and
     * header, this device, direction "push", kind "push hint", authentication with the push
     * key, a known event, a counter greater than every earlier one ([acceptCounter] stores it).
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
            Hint(
                event = event,
                ref = payload.text("r").orEmpty(),
                title = payload.text("t"),
                link = payload.text("l"),
                channel = payload.text("c"),
                opens = payload.text("o"),
                counts = payload.counts(),
            )
        } catch (_: Rejected) {
            null
        }
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(MAX_FIELD)

    private fun JsonObject.counts(): Map<String, Int> {
        val found = this["n"] as? JsonObject ?: return emptyMap()
        return found.entries.take(20).mapNotNull { (kind, value) ->
            val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return@mapNotNull null
            if (number <= 0) null else kind.take(20) to number.coerceAtMost(MAX_COUNT.toLong()).toInt()
        }.toMap()
    }

    /** The channel of a hint: the one it names, else the one of its event. */
    fun channelOf(hint: Hint): PushChannel = PushChannel.of(hint.channel) ?: when (hint.event) {
        "answer", "question", "approval", "cleared" -> PushChannel.CHAT
        "courses", "signin" -> PushChannel.COURSES
        "coach" -> PushChannel.COACH
        else -> PushChannel.REMINDERS
    }

    /** The screen a tap opens: from the hint's `o` (and `r`), else from its event. */
    fun destinationOf(hint: Hint): Destination {
        val opens = hint.opens ?: when (hint.event) {
            "answer", "question", "approval", "cleared" -> "session"
            "courses" -> "courses"
            "signin" -> "moodle_signin"
            "coach" -> "coach"
            "reminder" -> "reminder"
            "update" -> "app_update"
            else -> "notice"
        }
        return when (opens) {
            "session" -> if (hint.ref.isNotEmpty()) Destination.Chat(hint.ref) else Destination.Sessions
            "course" -> hint.ref.toLongOrNull()?.let { Destination.Course(it) } ?: Destination.Courses
            "courses" -> Destination.Courses
            "moodle_signin" -> Destination.CourseSignIn
            "coach" -> Destination.Coach(hint.ref.ifEmpty { null })
            "reminder" -> if (hint.ref.isNotEmpty()) Destination.Reminder(hint.ref) else Destination.Today
            "notice" -> Destination.Notice
            "app_update" -> Destination.AppUpdate
            "consolidation" -> Destination.Consolidation(hint.ref.ifEmpty { null })
            else -> Destination.Today
        }
    }

    private val ASKING = setOf("question", "approval", "cleared")

    /**
     * One notification per item: a later push about the same thing replaces the earlier one
     * (a session's replies, a reminder), and all course changes share one notification.
     */
    fun notificationKey(hint: Hint): String = when (channelOf(hint)) {
        // A question or approval has its own notification, removed when it no longer waits
        // ("cleared"); a ready reply never shares it, so clearing never removes a reply.
        PushChannel.CHAT -> if (hint.event in ASKING) "ask:${hint.ref}" else "chat:${hint.ref}"
        PushChannel.COURSES -> "courses"
        PushChannel.COACH -> "coach"
        // A newer release replaces the notice of an older one.
        PushChannel.REMINDERS -> if (hint.event == "update") "update" else "${hint.event}:${hint.ref}"
    }

    /**
     * The sealed title of a notice, one clean line. Never shown in a notification (its text is
     * fixed per event kind); the app shows it after it was opened and unlocked.
     */
    fun noticeTitle(hint: Hint): String? =
        hint.title?.takeIf { hint.event == "notice" }?.filterNot { it.isISOControl() }?.trim()?.take(120)?.ifEmpty { null }

    /** A notice's link, only a web address. */
    fun noticeLink(hint: Hint): String? =
        hint.link?.takeIf { hint.event == "notice" && (it.startsWith("https://") || it.startsWith("http://")) }
}
