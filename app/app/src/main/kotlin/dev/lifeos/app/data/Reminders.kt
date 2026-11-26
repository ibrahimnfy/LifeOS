package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------- session reminders (#75, docs/bridge.md)

/**
 * "Remind me about this session at <time>", kept and fired by the computer. [at] is the next
 * time it fires (UTC); a one-off reminder that has fired stays listed with [state] "fired",
 * which is how a tapped notification still finds its session.
 */
@Serializable
data class Reminder(
    val id: String = "",
    @SerialName("session_id") val sessionId: String? = null,
    val at: String = "",
    val note: String? = null,
    val repeat: String = "none",
    val timezone: String? = null,
    val state: String = "scheduled",
    /** A note's reminder (#185) names the note instead of a session. */
    @SerialName("note_id") val noteId: String? = null,
) {
    val scheduled: Boolean get() = state == "scheduled"

    val moment: Instant? get() = try {
        Instant.parse(at)
    } catch (_: Exception) {
        null
    }
}

@Serializable
data class ReminderList(val reminders: List<Reminder> = emptyList())

/** What the owner sets for a reminder: the bodies of the `/reminders` requests, and which sessions have one. */
object Reminders {
    const val MAX_NOTE = 500

    enum class Repeat(val id: String) {
        ONCE("none"),
        DAILY("daily"),
        WEEKLY("weekly"),
        ;

        companion object {
            fun of(id: String): Repeat = entries.firstOrNull { it.id == id } ?: ONCE
        }
    }

    /** [day] (`YYYY-MM-DD`) at [time] (`HH:MM`) on the phone's clock, or null when either is unreadable. */
    fun moment(day: String, time: String, zone: ZoneId): ZonedDateTime? = try {
        ZonedDateTime.of(LocalDate.parse(day), LocalTime.parse(time), zone)
    } catch (_: Exception) {
        null
    }

    /**
     * A new reminder ([sessionId] given) or the changes to one. The time carries its offset, as
     * the computer demands; the zone's name goes along so that a repeating reminder keeps its
     * hour across clock changes (left out when the phone's zone has no name the computer knows).
     */
    fun body(sessionId: String?, at: ZonedDateTime, note: String, repeat: Repeat): JsonObject = buildJsonObject {
        if (sessionId != null) put("session_id", JsonPrimitive(sessionId))
        put("at", JsonPrimitive(at.withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
        put("note", note.trim().take(MAX_NOTE).takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        put("repeat", JsonPrimitive(repeat.id))
        val zone = at.zone.id
        if (REGION.matches(zone)) put("timezone", JsonPrimitive(zone))
    }

    /**
     * "Remind me about this note at <time>" (#191): `POST /reminders` with the note's id. A note
     * has one reminder to come, so the computer moves it when there is one already.
     */
    fun forNote(noteId: String, at: ZonedDateTime, repeat: Repeat): JsonObject = buildJsonObject {
        put("note_id", JsonPrimitive(noteId))
        put("at", JsonPrimitive(at.withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
        put("repeat", JsonPrimitive(repeat.id))
        val zone = at.zone.id
        if (REGION.matches(zone)) put("timezone", JsonPrimitive(zone))
    }

    private val REGION = Regex("[A-Za-z_]+(/[A-Za-z0-9_+-]+)+")

    /** A one-off reminder needs a time that lies ahead; a repeating one moves on to its next time by itself. */
    fun usable(at: ZonedDateTime?, repeat: Repeat, now: Instant): Boolean =
        at != null && (repeat != Repeat.ONCE || at.toInstant().isAfter(now))

    /** The reminders of [sessionId] that are still to come, the next one first. */
    fun scheduled(reminders: List<Reminder>, sessionId: String): List<Reminder> =
        reminders.filter { it.sessionId == sessionId && it.scheduled }.sortedBy { it.moment ?: Instant.MAX }

    /** The sessions that have a reminder to come (their rows show a bell). */
    fun sessionsWithOne(reminders: List<Reminder>): Set<String> = reminders.filter { it.scheduled }.mapNotNull { it.sessionId }.toSet()
}

/** `PATCH /sessions/{id}`: whether a finished answer of this session is announced with a notification (#75). */
fun SessionEdits.notifyReplies(on: Boolean): JsonObject = buildJsonObject { put("notify_replies", JsonPrimitive(on)) }

/**
 * `PATCH /sessions/{id}`: whether the session's capable runs may use the skills and commands of
 * its own folder (#97). Off unless the owner switches it on, per session.
 */
fun SessionEdits.skills(on: Boolean): JsonObject = buildJsonObject { put("skills", JsonPrimitive(on)) }

/**
 * Which conversation a notification about a session leads to (#75, #148): the session as the
 * computer lists it now, the coach's own screen, or nothing that can be opened.
 */
object SessionLookup {
    sealed interface Found {
        /** [memory]: it runs in the memory folder. */
        data class Session(val session: SessionSummary, val memory: Boolean) : Found
        data object Coach : Found

        /** The computer does not list it (any more): its folder is no longer allowed, or it was removed. */
        data object Missing : Found

        /** The computer could not be asked. */
        data object Unreachable : Found
    }

    suspend fun find(api: Api, sessionId: String): Found = try {
        // The session itself, read fresh (#154): the list has only the newest hundred.
        val listed = if (Chat.validId(sessionId)) api.session(sessionId) else null
        when {
            listed == null -> Found.Missing
            // The coach conversation is a session too, with its own screen and rules.
            orNull { api.coachChat().sessionId } == sessionId -> Found.Coach
            else -> {
                val folders = orNull { api.get(ChatEndpoints.folders).first.folders }.orEmpty()
                Found.Session(listed, folders.any { it.isMemory && it.path == listed.folder })
            }
        }
    } catch (error: ApiException) {
        if (error.kind == ApiException.Kind.HTTP && error.code == "session_not_found") Found.Missing else Found.Unreachable
    }

    private suspend fun <T> orNull(block: suspend () -> T): T? = try {
        block()
    } catch (_: ApiException) {
        null
    }

    /** The reminder a notification names, also after it has fired; null when the computer has none of that id. */
    suspend fun reminder(api: Api, reminderId: String): Reminder? =
        api.get(ChatEndpoints.reminders).first.reminders.firstOrNull { it.id == reminderId }
}
