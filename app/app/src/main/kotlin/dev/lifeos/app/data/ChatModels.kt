package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Instant

// ---------------------------------------------------------------- chat and sessions (docs/bridge.md)

/** A folder a new conversation may run in: the memory folder ([kind] "memory") or an allowed folder. */
@Serializable
data class Folder(val path: String, val label: String = "", val kind: String = "allowed", val default: Boolean = false) {
    val isMemory: Boolean get() = kind == "memory"
}

@Serializable
data class FolderList(val folders: List<Folder> = emptyList())

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
) {
    /** The folder's name as the rows show it. */
    val folderLabel: String get() = autoGroup.ifEmpty { folder.trimEnd('/').substringAfterLast('/') }
}

@Serializable
data class SessionList(val sessions: List<SessionSummary> = emptyList(), val total: Int = 0)

/** One message of the owner and what the assistant answered to it. */
@Serializable
data class Exchange(
    val at: String? = null,
    val user: String = "",
    val assistant: String = "",
    val complete: Boolean = true,
    val truncated: Boolean = false,
)

/** [older] is set when the computer has earlier exchanges than these. */
@Serializable
data class HistoryPage(val exchanges: List<Exchange> = emptyList(), val older: String? = null)

/** `GET /sessions/{id}/queue`: is the computer working on the session, and is anything waiting behind that. */
@Serializable
data class RunState(val running: Boolean = false, val paused: String? = null, val queue: List<JsonElement> = emptyList()) {
    /** An answer is still to come: a run is in progress, or a message waits for its turn. */
    val busy: Boolean get() = running || (queue.isNotEmpty() && paused == null)
}

object ChatEndpoints {
    /** Where a new conversation may run. */
    val folders = Endpoint("folders", "/folders", serializer = FolderList.serializer())

    /** The sessions in the allowed folders, pinned first, then newest first. */
    val sessions = Endpoint("sessions", "/sessions", mapOf("limit" to "100"), SessionList.serializer())
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
     * Every session that is not pinned, grouped by its folder: the folder with the most recent
     * session first, and inside a folder the newest session first. Two folders of the same
     * name in different places stay two groups.
     */
    fun byFolder(sessions: List<SessionSummary>): List<FolderGroup> =
        newestFirst(sessions.filter { !it.pinned })
            .groupBy { it.folder }
            .map { (folder, items) -> FolderGroup(folder, items.first().folderLabel, items) }

    /** Sessions whose title or folder name contains every word of [query]. */
    fun matching(sessions: List<SessionSummary>, query: String): List<SessionSummary> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return sessions
        return sessions.filter { session ->
            val text = (session.title + " " + session.folderLabel).lowercase()
            words.all { it in text }
        }
    }
}
