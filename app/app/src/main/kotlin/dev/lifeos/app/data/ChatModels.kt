package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

// ---------------------------------------------------------------- chat and sessions (docs/bridge.md)

/** A folder a new conversation may run in: the memory folder ([kind] "memory") or an allowed folder. */
@Serializable
data class Folder(val path: String, val label: String = "", val kind: String = "allowed", val default: Boolean = false) {
    val isMemory: Boolean get() = kind == "memory"
}

@Serializable
data class FolderList(val folders: List<Folder> = emptyList())

/** What the bridge puts where a session has no title. */
private const val NO_TITLE = "(untitled)"

/**
 * A Claude Code session on the computer. [inUse] means that the computer is answering a
 * message of this session right now; a session that is open in a terminal there is only found
 * out when a message is sent (the computer then refuses it).
 */
@Serializable
data class SessionSummary(
    val id: String,
    val title: String = "",
    val folder: String = "",
    @SerialName("last_activity") val lastActivity: String? = null,
    @SerialName("in_use") val inUse: Boolean = false,
    @SerialName("auto_group") val autoGroup: String = "",
    val pinned: Boolean = false,
    /** The session's own model and effort; null: the default for chat sessions. */
    val model: String? = null,
    val effort: String? = null,
    /** The owner switched the folder's skills and commands on for this session. */
    val skills: Boolean = false,
    // ---- the owner's own organisation of sessions (#74), kept on the computer
    /** The id of the owner's group this session is in; null: it is shown under its folder. */
    val group: String? = null,
    /** What the computer found as the title, whatever the owner named the session. */
    @SerialName("detected_title") val detectedTitle: String = "",
    /**
     * Another Claude Code on the computer has this session open (a terminal, the desktop app):
     * a message from the phone would be refused. Null: the computer could not tell (#148).
     */
    @SerialName("open_elsewhere") val openElsewhere: Boolean? = null,
    /** A finished answer of this session is announced with a notification (#75); on unless switched off. */
    @SerialName("notify_replies") val notifyReplies: Boolean = true,
) {
    /** The folder's name as the rows show it. */
    val folderLabel: String get() = autoGroup.ifEmpty { folder.trimEnd('/').substringAfterLast('/') }

    /** The title, or empty when the computer found none (it then says "(untitled)"). */
    val shownTitle: String get() = title.trim().takeIf { it != NO_TITLE }.orEmpty()
}

/** A group the owner made for sessions (#74). */
@Serializable
data class SessionGroup(val id: String = "", val name: String = "")

@Serializable
data class SessionList(
    val sessions: List<SessionSummary> = emptyList(),
    val total: Int = 0,
    /** The owner's groups, in the order they were made. */
    val groups: List<SessionGroup> = emptyList(),
)

/** One message of the owner and what the assistant answered to it. */
@Serializable
data class Exchange(
    val at: String? = null,
    val user: String = "",
    val assistant: String = "",
    val complete: Boolean = true,
    val truncated: Boolean = false,
)

/** `POST /sessions/{id}/cancel`. */
@Serializable
data class CancelResult(val cancelled: Boolean = false, @SerialName("queue_paused") val queuePaused: Boolean = false)

/** [older] is set when the computer has earlier exchanges than these. */
@Serializable
data class HistoryPage(val exchanges: List<Exchange> = emptyList(), val older: String? = null)

/** A message that waits on the computer for the session's current run to end. */
@Serializable
data class QueuedData(val id: String = "", val prompt: String = "")

/**
 * `GET /sessions/{id}/queue`: is the computer working on the session, and what waits behind
 * that. [paused]: why the queue is on hold ("cancel", "error", "restart"), else null.
 */
@Serializable
data class RunState(val running: Boolean = false, val paused: String? = null, val queue: List<QueuedData> = emptyList()) {
    /** An answer is still to come: a run is in progress, or a message waits for its turn. */
    val busy: Boolean get() = running || (queue.isNotEmpty() && paused == null)
}

// ---------------------------------------------------------------- the coach conversation (#114)

/** When the coach conversation was last tidied up, and how many facts went to the memory. */
@Serializable
data class CoachCompaction(val at: String? = null, val result: String = "", val facts: Int = 0)

/**
 * `GET /coach/chat`: the one conversation with the coach. It is an ordinary session
 * ([sessionId]) that the computer renews when it has grown long ([transcriptBytes] against
 * [compactAtBytes]): what matters goes to the memory, a [summary] stays, and a new session
 * continues. [usage] is the plan usage of this conversation; [morningUsage] that of the
 * coach's morning run, counted apart.
 */
@Serializable
data class CoachChatView(
    @SerialName("session_id") val sessionId: String = "",
    val started: Boolean = false,
    @SerialName("transcript_bytes") val transcriptBytes: Long = 0,
    @SerialName("compact_at_bytes") val compactAtBytes: Long = 0,
    val compactions: Int = 0,
    val summary: String? = null,
    @SerialName("last_compaction") val lastCompaction: CoachCompaction? = null,
    @SerialName("earlier_sessions") val earlierSessions: List<String> = emptyList(),
    val model: String? = null,
    val effort: String? = null,
    val usage: JobUsagePeriods = JobUsagePeriods(),
    @SerialName("morning_usage") val morningUsage: JobUsagePeriods = JobUsagePeriods(),
) {
    /** The next message is answered only after the conversation was tidied up (one more run). */
    val dueForCompaction: Boolean get() = compactAtBytes > 0 && transcriptBytes >= compactAtBytes

    /** How full the conversation is before it is tidied up, 0 to 100. */
    val fullPercent: Int get() = if (compactAtBytes <= 0) 0 else (transcriptBytes * 100 / compactAtBytes).coerceIn(0, 100).toInt()
}

/** `POST /coach/chat/compact`: the files that got new facts, whether a summary was kept, the new session. */
@Serializable
data class CoachCompacted(val facts: List<String> = emptyList(), val summary: Boolean = false, @SerialName("session_id") val sessionId: String = "")

object CoachEndpoints {
    /** The coach conversation; the lists of sessions need it to know which session is the coach's. */
    val chat = Endpoint("coach-chat", "/coach/chat", serializer = CoachChatView.serializer())
}

object ChatEndpoints {
    /** Where a new conversation may run. */
    val folders = Endpoint("folders", "/folders", serializer = FolderList.serializer())

    /** The sessions in the allowed folders, pinned first, then newest first. */
    val sessions = Endpoint("sessions", "/sessions", mapOf("limit" to "100"), SessionList.serializer())

    /** Every reminder of every session (#75), those that have fired too. */
    val reminders = Endpoint("reminders", "/reminders", serializer = ReminderList.serializer())
}

/** One of the owner's groups with its sessions (#74). */
data class OwnGroup(val group: SessionGroup, val sessions: List<SessionSummary>)

/**
 * What the owner changes about a session or a group (#74): the bodies of `PATCH /sessions/{id}`
 * and of the `/session-groups` requests, and what a refusal means. Titles and names are one line.
 */
object SessionEdits {
    const val MAX_TITLE = 120
    const val MAX_NAME = 60

    enum class Problem {
        /** The computer could not be reached: nothing was changed. */
        NOT_REACHED,

        /** The request left the phone but no answer came: it may or may not have been done. */
        UNCERTAIN,

        /** A group of this name exists already. */
        GROUP_EXISTS,

        /** The session or the group is no longer there. */
        GONE,

        /** The computer did not accept it (it keeps a limited number of groups and of reminders). */
        REFUSED,
        FAILED,
    }

    /** One clean line of at most [limit] characters, as the computer accepts it. */
    fun line(text: String, limit: Int): String = text.replace(Regex("\\s+"), " ").trim().take(limit).trim()

    /** A new title; null or blank returns the session to the title the computer found. */
    fun title(title: String?): JsonObject = buildJsonObject {
        put("title", title?.let { line(it, MAX_TITLE) }?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun pinned(pinned: Boolean): JsonObject = buildJsonObject { put("pinned", JsonPrimitive(pinned)) }

    /** Into the group [groupId]; null takes the session out of its group (it is shown under its folder). */
    fun group(groupId: String?): JsonObject = buildJsonObject { put("group", groupId?.let(::JsonPrimitive) ?: JsonNull) }

    fun name(name: String): JsonObject = buildJsonObject { put("name", JsonPrimitive(line(name, MAX_NAME))) }

    fun problemOf(error: ApiException): Problem = when {
        error.kind == ApiException.Kind.UNCERTAIN -> Problem.UNCERTAIN
        error.kind != ApiException.Kind.HTTP -> Problem.NOT_REACHED
        error.code == "group_exists" -> Problem.GROUP_EXISTS
        error.code == "session_not_found" || error.code == "group_not_found" || error.code == "reminder_not_found" -> Problem.GONE
        // What the app sends is well-formed: what is left are the computer's limits (50 groups, 200 reminders).
        error.code == "bad_request" -> Problem.REFUSED
        else -> Problem.FAILED
    }
}

/** The sessions of one folder. */
data class FolderGroup(val folder: String, val label: String, val sessions: List<SessionSummary>)

object SessionLists {
    private fun moment(session: SessionSummary): Long = try {
        Instant.parse(session.lastActivity).toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    /** Newest first; sessions without a readable time last, in the order they came. */
    fun newestFirst(sessions: List<SessionSummary>): List<SessionSummary> = sessions.sortedByDescending(::moment)

    /** Those the owner pinned (on the computer), newest first. */
    fun pinned(sessions: List<SessionSummary>): List<SessionSummary> = newestFirst(sessions.filter { it.pinned })

    /**
     * Every session that is neither pinned nor in one of the owner's [groups], grouped by its
     * folder: the folder with the most recent session first, and inside a folder the newest
     * session first. Two folders of the same name in different places stay two groups.
     */
    fun byFolder(sessions: List<SessionSummary>, groups: List<SessionGroup> = emptyList()): List<FolderGroup> {
        val known = groups.map { it.id }.toSet()
        return newestFirst(sessions.filter { !it.pinned && it.group !in known })
            .groupBy { it.folder }
            .map { (folder, items) -> FolderGroup(folder, items.first().folderLabel, items) }
    }

    /**
     * The owner's [groups] in their own order, each with its sessions that are not pinned,
     * newest first. A group without sessions is listed too (it can be renamed, deleted and
     * filled); a session whose group is not among [groups] is not here but under its folder.
     */
    fun inGroups(sessions: List<SessionSummary>, groups: List<SessionGroup>): List<OwnGroup> {
        val members = newestFirst(sessions.filter { !it.pinned && it.group != null }).groupBy { it.group }
        return groups.filter { it.id.isNotEmpty() }.map { OwnGroup(it, members[it.id].orEmpty()) }
    }

    /** Sessions whose title or folder name contains every word of [query]. */
    fun matching(sessions: List<SessionSummary>, query: String): List<SessionSummary> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return sessions
        return sessions.filter { session ->
            val text = (session.shownTitle + " " + session.folderLabel).lowercase()
            words.all { it in text }
        }
    }
}
