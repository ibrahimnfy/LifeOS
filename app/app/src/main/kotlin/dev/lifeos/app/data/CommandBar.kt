package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------- the command bar (#185, docs/bridge.md)

/**
 * What one action of a request did on the computer, or what the app is to do: [action] names
 * it (bridge/src/lifeos_bridge/actions.py), [title] and [when] are for the confirmation line,
 * [undo] takes it back (null: it has no undo), [perform] is what the app does next.
 */
@Serializable
data class CommandOutcome(
    val action: String = "",
    val ok: Boolean = true,
    val title: String? = null,
    @SerialName("when") val moment: String? = null,
    val undo: String? = null,
    val error: String? = null,
    val perform: JsonObject? = null,
    /** The change permission of the chat it opens or continues (#190): "read_only" or "capable"; null for other actions. */
    val mode: String? = null,
    /** Why it did less than asked: "chat_not_found", "chat_busy" (the message waits in the chat's field), "not_sent". */
    val note: String? = null,
) {
    /** The chat starts with "may change files" on: the computer picked it from the request and allows it there. */
    val capable: Boolean get() = mode == ChatSwitch.MODE_CAPABLE

    fun flag(name: String): Boolean = (perform?.get(name) as? JsonPrimitive)?.booleanOrNull == true

    /** The app's part, e.g. `{"action": "open_screen", "screen": "notes"}`. */
    val performs: String? get() = perform?.get("action")?.jsonPrimitive?.contentOrNull

    fun field(name: String): String? = (perform?.get(name) as? JsonPrimitive)?.contentOrNull

    fun number(name: String): Long? = (perform?.get(name) as? JsonPrimitive)?.longOrNull

    /** The folder of `new_chat_in` (as `GET /folders` lists it). */
    fun folder(): JsonElement? = perform?.get("folder")
}

/** One answer to a question: picked by its [choice] token, or sent back as text ([answer]). */
@Serializable
data class CommandOption(val label: String = "", val detail: String? = null, val choice: String? = null, val answer: String? = null)

/**
 * The one question of a request: the model's ("clarify", with [text]) when the request was
 * ambiguous, or the bridge's when words matched several things ([kind] task, note, event,
 * chat, folder, course, assignment, project; [text] null: the app says it).
 */
@Serializable
data class CommandQuestion(val kind: String = "clarify", val action: String? = null, val text: String? = null, val options: List<CommandOption> = emptyList())

@Serializable
data class CommandPrevious(val request: String = "", val question: String = "")

@Serializable
data class CommandAnswer(
    @SerialName("request_id") val requestId: String = "",
    val results: List<CommandOutcome> = emptyList(),
    val question: CommandQuestion? = null,
    val previous: CommandPrevious? = null,
    val repeat: Boolean = false,
    /** One sentence of what the computer understood the owner wants (#204); null for a pick. */
    val understood: String? = null,
    /** The part of the request that is conversation for the coach, in the owner's words (#204). */
    val talk: String? = null,
    /** What was asked that no action can do, and why (#204). */
    val cannot: String? = null,
)

@Serializable
data class CommandUndone(val undone: Boolean = false, val action: String? = null)

/** The request bodies and the rules of the bar that need no screen. */
object CommandBar {
    /** The app's language as the bridge takes it: Turkish or English. */
    fun language(tag: String): String = if (tag.lowercase().startsWith("tr")) "tr" else "en"

    /** The speech recogniser's language: the app's (Turkish or English), with a region. */
    fun speechLanguage(tag: String): String = if (language(tag) == "tr") "tr-TR" else "en-US"

    /**
     * What the recogniser listens for (#204, the owner's choice): Turkish first, English as an
     * additional language, whatever the app's language is.
     */
    const val SPEECH_FIRST = "tr-TR"
    val SPEECH_ALSO = arrayOf("en-US")

    /** Zone names the computer knows (as for reminders); others are left out. */
    private val REGION = Regex("[A-Za-z_]+(/[A-Za-z0-9_+-]+)+")

    /**
     * A request: [text] typed or spoken, or the [choice] of a question's option. [requestId] is
     * the phone's: a repeat after a lost answer gets the stored answer, nothing is done twice.
     */
    fun body(
        requestId: String,
        text: String?,
        choice: String?,
        languageTag: String,
        now: ZonedDateTime,
        previous: CommandPrevious? = null,
        coachSaid: String? = null,
    ): JsonObject = buildJsonObject {
        put("request_id", JsonPrimitive(requestId))
        if (text != null) put("text", JsonPrimitive(text.trim().take(MAX_TEXT)))
        if (choice != null) put("choice", JsonPrimitive(choice))
        put("language", JsonPrimitive(language(languageTag)))
        put("now", JsonPrimitive(now.withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
        val zone = now.zone.id
        if (REGION.matches(zone)) put("timezone", JsonPrimitive(zone))
        if (previous != null) {
            put("previous", buildJsonObject {
                put("request", JsonPrimitive(previous.request))
                put("question", JsonPrimitive(previous.question))
            })
        }
        // A reply in the coach conversation (#204): the coach's last message, as context only.
        if (text != null && !coachSaid.isNullOrBlank()) put("coach_said", JsonPrimitive(coachSaid.trim().takeLast(MAX_SAID)))
    }

    /** Whether an answer did or asked anything the bar shows (results, a question, or what it cannot do). */
    fun shows(answer: CommandAnswer): Boolean = answer.results.isNotEmpty() || answer.question != null || !answer.cannot.isNullOrBlank()

    /** The outcome that moves the owner to another screen (the bar then closes), if any. */
    fun navigation(answer: CommandAnswer): CommandOutcome? = answer.results.firstOrNull { it.ok && it.performs in NAVIGATING }

    /**
     * What the bar does when a request could not be answered: a computer that cannot be reached
     * offers saving the text through the outbox; an answer that got lost offers asking again
     * with the same request id (it may have been done); a request the computer could not turn
     * into actions offers saving it as a note.
     */
    enum class Failure { OFFLINE, LOST, NOT_UNDERSTOOD, REFUSED }

    fun failureOf(error: ApiException): Failure = when {
        error.kind == ApiException.Kind.NOT_SENT || error.kind == ApiException.Kind.NOT_PAIRED || error.kind == ApiException.Kind.REFUSED -> Failure.OFFLINE
        error.kind == ApiException.Kind.UNCERTAIN || error.status == 504 -> Failure.LOST
        error.status == 422 -> Failure.NOT_UNDERSTOOD
        else -> Failure.REFUSED
    }

    /** Performed actions that open another screen. */
    val NAVIGATING = setOf(
        "open_screen", "search_notes", "new_chat", "ask_coach", "open_note", "open_chat",
        "new_chat_in", "open_course", "open_assignment", "open_project",
    )

    const val MAX_TEXT = 2000
    const val MAX_SAID = 2000

    fun now(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime = ZonedDateTime.now(zone)
}
