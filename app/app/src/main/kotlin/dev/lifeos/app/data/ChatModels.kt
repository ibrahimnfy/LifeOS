package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

// ---------------------------------------------------------------- chat and sessions (docs/bridge.md)

/** The kinds of folder a conversation belongs to (`kind` in `GET /folders` and `GET /sessions`, #163). */
object FolderKinds {
    /** Every blank chat: one entry for all of them. */
    const val CHAT = "chat"

    /** The coach conversation and its earlier sessions (only in the session list). */
    const val COACH = "coach"
    const val MEMORY = "memory"
    const val PROJECT = "project"
    const val COURSE = "course"
}

/**
 * A folder a new conversation may run in (`GET /folders`): the blank chats ([FolderKinds.CHAT],
 * the default; [usable] false with [reason] when the computer refuses them), the memory folder
 * and the allowed folders. [colour] is the bridge's stable colour number.
 */
@Serializable
data class Folder(
    val path: String,
    val label: String = "",
    val kind: String = FolderKinds.PROJECT,
    val default: Boolean = false,
    val colour: Int = 0,
    val usable: Boolean = true,
    val reason: String? = null,
) {
    val isMemory: Boolean get() = kind == FolderKinds.MEMORY
    val isChat: Boolean get() = kind == FolderKinds.CHAT
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
    /** The exact working directory (a blank chat's own folder under the chat root). */
    val folder: String = "",
    @SerialName("last_activity") val lastActivity: String? = null,
    @SerialName("in_use") val inUse: Boolean = false,
    // ---- the folder it belongs to (#163): the root that contains it; all blank chats are one
    @SerialName("folder_key") val folderKey: String = "",
    @SerialName("folder_label") val folderLabel: String = "",
    /** [FolderKinds]: chat, coach, memory, project or course. */
    val kind: String = "",
    /** The folder's stable colour number (an index into the app's palette). */
    val colour: Int = 0,
    val pinned: Boolean = false,
    /** The session's own model and effort; null: the default for chat sessions. */
    val model: String? = null,
    val effort: String? = null,
    /** The owner switched the folder's skills and commands on for this session. */
    val skills: Boolean = false,
    /** What the computer found as the title, whatever the owner named the session. */
    @SerialName("detected_title") val detectedTitle: String = "",
    /**
     * Another Claude Code on the computer has this session open (a terminal, the desktop app):
     * a message from the phone would be refused. Null: the computer could not tell (#148).
     */
    @SerialName("open_elsewhere") val openElsewhere: Boolean? = null,
    /** A finished answer of this session is announced with a notification (#75); on unless switched off. */
    @SerialName("notify_replies") val notifyReplies: Boolean = true,
    // ---- only in the fresh read of one session (`GET /sessions/{id}`, #154)
    /** Its current run can be followed live (`GET /sessions/{id}/live`). */
    val followable: Boolean = false,
    /** The messages that wait for its current run to end. */
    val queue: List<QueuedData> = emptyList(),
    @SerialName("queue_paused") val queuePaused: String? = null,
) {
    /** The folder's name as the rows show it: the bridge's label, else the last part of the path. */
    val folderName: String get() = folderLabel.ifEmpty { folder.trimEnd('/').substringAfterLast('/') }

    /** A blank chat (#163): it has no folder of its own to name. */
    val isChat: Boolean get() = kind == FolderKinds.CHAT

    /** The tile it is listed under: the bridge's key, else its folder. */
    val tileKey: String get() = folderKey.ifEmpty { folder }

    /** The title, or empty when the computer found none (it then says "(untitled)"). */
    val shownTitle: String get() = title.trim().takeIf { it != NO_TITLE }.orEmpty()
}

/**
 * One folder of the session list (#163), for the Sessions screen's tiles. [count] and
 * [lastActivity] are counted over all of its sessions, not only the listed ones.
 */
@Serializable
data class SessionFolder(
    val key: String = "",
    val name: String = "",
    val kind: String = "",
    val path: String = "",
    val colour: Int = 0,
    val count: Int = 0,
    @SerialName("last_activity") val lastActivity: String? = null,
)

@Serializable
data class SessionList(
    val sessions: List<SessionSummary> = emptyList(),
    val total: Int = 0,
    /** One entry per folder, in the bridge's order: chats, coach, memory, then the allowed folders. */
    val folders: List<SessionFolder> = emptyList(),
)

/** One message of the owner and what the assistant answered to it. */
@Serializable
data class Exchange(
    val at: String? = null,
    val user: String = "",
    val assistant: String = "",
    val complete: Boolean = true,
    val truncated: Boolean = false,
    /** The MCP servers this answer used (#157); they ran outside the sandbox. */
    @SerialName("mcp_servers") val mcpServers: List<UsedServer> = emptyList(),
)

/** `POST /sessions/{id}/cancel`. */
@Serializable
data class CancelResult(val cancelled: Boolean = false, @SerialName("queue_paused") val queuePaused: Boolean = false)

/**
 * `POST /sessions/{id}/queue/{qid}/send-now`: the waiting message is the next one now.
 * [interrupted]: a run was in progress and was stopped for it; [queue] is the line afterwards.
 */
@Serializable
data class SendNowResult(
    val promoted: Boolean = false,
    @SerialName("interrupted_current_run") val interrupted: Boolean = false,
    val queue: List<QueuedData> = emptyList(),
)

/** A file offer of a session that can still be fetched (`GET /sessions/{id}/files`). */
@Serializable
data class LiveOffer(
    val offer: String = "",
    val name: String = "",
    val type: String? = null,
    val size: Long = 0,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class LiveOffers(val files: List<LiveOffer> = emptyList())

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

    /**
     * The sessions in the allowed folders and the blank chats, pinned first, then newest first;
     * as many as the bridge lists at most (200), so a folder's list is as complete as it can be.
     */
    val sessions = Endpoint("sessions", "/sessions", mapOf("limit" to "200"), SessionList.serializer())

    /** Every reminder of every session (#75), those that have fired too. */
    val reminders = Endpoint("reminders", "/reminders", serializer = ReminderList.serializer())
}

/**
 * What the owner changes about a session (#74): the bodies of `PATCH /sessions/{id}`, and what
 * a refusal means. Titles are one line. Custom groups are gone (#168): pinning stays.
 */
object SessionEdits {
    const val MAX_TITLE = 120

    enum class Problem {
        /** The computer could not be reached: nothing was changed. */
        NOT_REACHED,

        /** The request left the phone but no answer came: it may or may not have been done. */
        UNCERTAIN,

        /** The session or the reminder is no longer there. */
        GONE,

        /** The computer did not accept it (it keeps a limited number of reminders). */
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

    fun problemOf(error: ApiException): Problem = when {
        error.kind == ApiException.Kind.UNCERTAIN -> Problem.UNCERTAIN
        error.kind != ApiException.Kind.HTTP -> Problem.NOT_REACHED
        error.code == "session_not_found" || error.code == "reminder_not_found" -> Problem.GONE
        // What the app sends is well-formed: what is left are the computer's limits (200 reminders).
        error.code == "bad_request" -> Problem.REFUSED
        else -> Problem.FAILED
    }
}

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
     * The Sessions screen's tiles (#168): the bridge's folders in its order (chats, coach,
     * memory, then the allowed folders). Should a bridge send no folders, the tiles are made
     * from the listed sessions, the folder with the newest session first.
     */
    fun tiles(list: SessionList): List<SessionFolder> {
        val listed = list.folders.filter { it.key.isNotEmpty() }
        if (listed.isNotEmpty() || list.sessions.isEmpty()) return listed
        return newestFirst(list.sessions).groupBy { it.tileKey }.map { (key, items) ->
            val first = items.first()
            SessionFolder(key, first.folderName, first.kind, first.folder, first.colour, items.size, first.lastActivity)
        }
    }

    /** The sessions of the tile [key]: the pinned ones first, each part newest first. */
    fun inFolder(sessions: List<SessionSummary>, key: String): List<SessionSummary> {
        val (pinned, others) = sessions.filter { it.tileKey == key }.partition { it.pinned }
        return newestFirst(pinned) + newestFirst(others)
    }

    /** Sessions whose title or folder name contains every word of [query]. */
    fun matching(sessions: List<SessionSummary>, query: String): List<SessionSummary> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return sessions
        return sessions.filter { session ->
            val text = (session.shownTitle + " " + session.folderName).lowercase()
            words.all { it in text }
        }
    }
}
