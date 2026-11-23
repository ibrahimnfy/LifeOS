package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
 * A folder the bridge found by name (#179), offered on a "choose_folder" card: [allowed] says it is a
 * conversation folder already, [where] is the folder around it (`~/Documents`), to tell folders of one
 * name apart. A pick goes to `POST /folders/open`, which may add it to the allowed folders first.
 */
@Serializable
data class SwitchFolderChoice(
    val path: String = "",
    val label: String = "",
    val kind: String = "",
    val colour: Int = 0,
    val allowed: Boolean = false,
    val where: String = "",
)

/**
 * What a fresh answer asks the app to do (`switch` in the `done` event): [OPEN] one
 * conversation, offer one to five to [CHOOSE] from, or start a [NEW] one in [folder]. It is
 * never part of the history: only the answer of the run that wrote it carries it.
 *
 * A switch that starts a new chat ([NEW], [CHOOSE_FOLDER] and the owner's picks from them) may
 * carry the owner's request ([forward], #187): the new chat starts with it as its first message,
 * so the work begins without retyping. [origin] is the conversation the switch came from (set
 * by the app, never read from the item): the new chat names it, and its first message tells the
 * computer, so both chats can say so in their history.
 */
@Serializable
data class ChatSwitch(
    val action: String = "",
    val sessions: List<SwitchSession> = emptyList(),
    val folder: SwitchFolder? = null,
    val folders: List<SwitchFolderChoice> = emptyList(),
    val forward: String? = null,
    @Transient val origin: SwitchSession? = null,
) {
    /** A card for the owner to pick from, never done by itself. */
    val isChoice: Boolean get() = action == CHOOSE || action == CHOOSE_FOLDER

    /** The owner tapped one conversation of this card: open it (a request is never sent into it). */
    fun picked(session: SwitchSession): ChatSwitch = ChatSwitch(OPEN, listOf(session), origin = origin)

    /** The owner tapped the folder of this card: a new conversation there, with this card's request. */
    fun picked(folder: SwitchFolder): ChatSwitch = ChatSwitch(NEW, folder = folder, forward = forward, origin = origin)

    /** The owner tapped a found folder of this card: the bridge opens it first (it may add it). */
    fun picked(choice: SwitchFolderChoice): ChatSwitch = ChatSwitch(PICK_FOLDER, folders = listOf(choice), forward = forward, origin = origin)

    companion object {
        const val OPEN = "open"
        const val CHOOSE = "choose"
        const val NEW = "new"

        /** Folders found by name (#179): the owner picks one, and a new chat starts there. */
        const val CHOOSE_FOLDER = "choose_folder"

        /** The owner's pick on a [CHOOSE_FOLDER] card: the bridge opens the folder, then a new chat starts. */
        const val PICK_FOLDER = "pick_folder"

        /** The bridge offers at most five conversations to choose from. */
        const val MAX_CHOICES = 5

        /** The longest request a switch carries to a new chat (as on the computer, #187). */
        const val MAX_FORWARD = 2000

        /** Zero-width non-joiner and joiner: kept in a request, some scripts and emoji need them. */
        private val JOINERS = setOf(0x200C, 0x200D)

        /**
         * The item as the app can use it, or null. Ids that could not go into a request path are
         * dropped, and so is a repeat; "open" names exactly one conversation, "choose" one to
         * five, "new" a folder (blank chats need no path). A request to forward is kept only on
         * "new" and "choose_folder", cleaned ([forwardOf]).
         */
        fun of(element: JsonElement?): ChatSwitch? {
            val item = element as? JsonObject ?: return null
            val parsed = try {
                BridgeJson.decodeFromJsonElement(serializer(), item)
            } catch (_: Exception) {
                return null
            }
            val sessions = parsed.sessions.filter { Chat.validId(it.id) }.distinctBy { it.id }.take(MAX_CHOICES)
            val forward = forwardOf(parsed.forward)
            return when (parsed.action) {
                OPEN -> sessions.singleOrNull()?.let { ChatSwitch(OPEN, listOf(it)) }
                CHOOSE -> sessions.takeIf { it.isNotEmpty() }?.let { ChatSwitch(CHOOSE, it) }
                NEW -> parsed.folder?.takeIf { it.kind == FolderKinds.CHAT || it.path.isNotBlank() }?.let { ChatSwitch(NEW, folder = it, forward = forward) }
                CHOOSE_FOLDER -> parsed.folders.filter { it.path.startsWith("/") && it.label.isNotBlank() }
                    .distinctBy { it.path }.take(MAX_CHOICES).takeIf { it.isNotEmpty() }
                    ?.let { ChatSwitch(CHOOSE_FOLDER, folders = it, forward = forward) }
                else -> null
            }
        }

        /**
         * A request as it may be sent: control and format characters (direction overrides,
         * zero-width space) become spaces, except the zero-width joiners some scripts need;
         * runs of spaces become one. Null when nothing is left or it is longer than [MAX_FORWARD]
         * (never cut: a cut request could ask for something else).
         */
        fun forwardOf(raw: String?): String? {
            if (raw == null) return null
            val shown = raw.map { char ->
                val type = Character.getType(char).toByte()
                val hidden = (type == Character.CONTROL || type == Character.FORMAT) && char.code !in JOINERS
                if (hidden) ' ' else char
            }.joinToString("")
            val text = shown.trim().split(Regex("\\s+")).joinToString(" ")
            return text.takeIf { it.isNotEmpty() && it.length <= MAX_FORWARD }
        }

        /**
         * Open [session] (a "Continued in …" or "Started from …" line). A card's picks go through
         * [picked], which keeps the card's request and origin.
         */
        fun open(session: SwitchSession): ChatSwitch = ChatSwitch(OPEN, listOf(session))
    }
}

/** What the app does for a switch: which screen, which conversation. */
sealed interface SwitchStep {
    /** The coach conversation has its own screen. */
    data object Coach : SwitchStep

    /** Continue this conversation in its own folder. */
    data class Open(val session: SessionSummary) : SwitchStep

    /**
     * A new conversation whose first message is sent with this folder; null: a blank chat. With
     * [forward] (#187) that first message is the owner's request, sent at once; [origin] is the
     * conversation the switch came from.
     */
    data class New(val folder: Folder?, val forward: String? = null, val origin: SwitchSession? = null) : SwitchStep

    /** A found folder the owner picked (#179): `POST /folders/open` first, then [New] in the folder it answers. */
    data class Pick(val path: String, val forward: String? = null, val origin: SwitchSession? = null) : SwitchStep
}

/** The folder `POST /folders/open` answers with (#179). */
@Serializable
data class OpenedFolder(val added: Boolean = false, val folder: SwitchFolder = SwitchFolder())

/** The folder a new chat starts in, from a switch's folder (a blank chat has none). */
fun SwitchFolder.asFolder(): Folder? =
    if (kind == FolderKinds.CHAT) null else Folder(path, label, kind.ifEmpty { FolderKinds.PROJECT }, colour = colour)

/**
 * The step for an [ChatSwitch.OPEN] or [ChatSwitch.NEW] item, or for the owner's pick of a found folder
 * ([ChatSwitch.PICK_FOLDER]); a choice ([ChatSwitch.CHOOSE], [ChatSwitch.CHOOSE_FOLDER]) is the owner's to make on a card.
 */
fun ChatSwitch.step(): SwitchStep? = when (action) {
    ChatSwitch.OPEN -> sessions.singleOrNull()?.let { if (it.kind == FolderKinds.COACH) SwitchStep.Coach else SwitchStep.Open(it.summary()) }
    ChatSwitch.NEW -> folder?.let { target -> SwitchStep.New(target.asFolder(), forward, origin) }
    ChatSwitch.PICK_FOLDER -> folders.singleOrNull()?.takeIf { it.path.startsWith("/") }?.let { SwitchStep.Pick(it.path, forward, origin) }
    else -> null
}
