package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------- moving between conversations by asking (#164, #168)

/** A conversation a `switch` item names: enough to show it on a card and to open it. */
@Serializable
data class SwitchSession(
    val id: String = "",
    val title: String = "",
    @SerialName("folder_label") val folderLabel: String = "",
    val kind: String = "",
    val colour: Int = 0,
    @SerialName("last_activity") val lastActivity: String? = null,
) {
    /**
     * As a listed session, to open it. The item names no folder path: the conversation
     * continues in its own folder on the computer, and the fresh read of the session fills it in.
     */
    fun summary(): SessionSummary = SessionSummary(id, title, lastActivity = lastActivity, folderLabel = folderLabel, kind = kind, colour = colour)
}

/** The folder a `new` switch starts a conversation in: one of the folders of `GET /folders`. */
@Serializable
data class SwitchFolder(val path: String = "", val label: String = "", val kind: String = "", val colour: Int = 0)

/**
 * What a fresh answer asks the app to do (`switch` in the `done` event): [OPEN] one
 * conversation, offer one to five to [CHOOSE] from, or start a [NEW] one in [folder]. It is
 * never part of the history: only the answer of the run that wrote it carries it.
 */
@Serializable
data class ChatSwitch(val action: String = "", val sessions: List<SwitchSession> = emptyList(), val folder: SwitchFolder? = null) {
    companion object {
        const val OPEN = "open"
        const val CHOOSE = "choose"
        const val NEW = "new"

        /** The bridge offers at most five conversations to choose from. */
        const val MAX_CHOICES = 5

        /**
         * The item as the app can use it, or null. Ids that could not go into a request path are
         * dropped, and so is a repeat; "open" names exactly one conversation, "choose" one to
         * five, "new" a folder (blank chats need no path).
         */
        fun of(element: JsonElement?): ChatSwitch? {
            val item = element as? JsonObject ?: return null
            val parsed = try {
                BridgeJson.decodeFromJsonElement(serializer(), item)
            } catch (_: Exception) {
                return null
            }
            val sessions = parsed.sessions.filter { Chat.validId(it.id) }.distinctBy { it.id }.take(MAX_CHOICES)
            return when (parsed.action) {
                OPEN -> sessions.singleOrNull()?.let { ChatSwitch(OPEN, listOf(it)) }
                CHOOSE -> sessions.takeIf { it.isNotEmpty() }?.let { ChatSwitch(CHOOSE, it) }
                NEW -> parsed.folder?.takeIf { it.kind == FolderKinds.CHAT || it.path.isNotBlank() }?.let { ChatSwitch(NEW, folder = it) }
                else -> null
            }
        }

        /** The owner tapped one conversation of a card: open it. */
        fun open(session: SwitchSession): ChatSwitch = ChatSwitch(OPEN, listOf(session))

        /** The owner tapped the folder of a card: a new conversation there. */
        fun new(folder: SwitchFolder): ChatSwitch = ChatSwitch(NEW, folder = folder)
    }
}

/** What the app does for a switch: which screen, which conversation. */
sealed interface SwitchStep {
    /** The coach conversation has its own screen. */
    data object Coach : SwitchStep

    /** Continue this conversation in its own folder. */
    data class Open(val session: SessionSummary) : SwitchStep

    /** An empty conversation whose first message is sent with this folder; null: a blank chat. */
    data class New(val folder: Folder?) : SwitchStep
}

/** The step for an [ChatSwitch.OPEN] or [ChatSwitch.NEW] item; a choice ([ChatSwitch.CHOOSE]) is the owner's to make on a card. */
fun ChatSwitch.step(): SwitchStep? = when (action) {
    ChatSwitch.OPEN -> sessions.singleOrNull()?.let { if (it.kind == FolderKinds.COACH) SwitchStep.Coach else SwitchStep.Open(it.summary()) }
    ChatSwitch.NEW -> folder?.let { target ->
        if (target.kind == FolderKinds.CHAT) {
            SwitchStep.New(null)
        } else {
            SwitchStep.New(Folder(target.path, target.label, target.kind.ifEmpty { FolderKinds.PROJECT }, colour = target.colour))
        }
    }
    else -> null
}
