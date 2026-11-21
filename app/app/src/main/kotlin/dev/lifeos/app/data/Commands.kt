package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A skill or command a capable run of the session can be offered (`GET /sessions/{id}/commands`):
 * one of the session folder's own ([source] "folder") or one of the owner's personal ones
 * ([source] "personal", #162).
 */
@Serializable
data class SlashCommand(
    val name: String = "",
    /** "skill" or "command". */
    val kind: String = "",
    val description: String = "",
    @SerialName("argument_hint") val argumentHint: String? = null,
    /** What the owner should know before using it: "shell_lines", "requests_tools", "has_scripts". */
    val warnings: List<String> = emptyList(),
    /** "folder" or "personal"; anything else counts as the folder's (never offered without its switch). */
    val source: String = FOLDER,
) {
    /** One of the owner's personal skills or commands. */
    val personal: Boolean get() = source == PERSONAL

    companion object {
        const val FOLDER = "folder"
        const val PERSONAL = "personal"
    }
}

/**
 * The owner's personal skills and commands (#162): offered in every capable run, without the
 * session's switch, once the computer's check for them has passed ([enabled]); [reason] says
 * why not (the bridge's own words). [prefix] is the namespace a run knows them by.
 */
@Serializable
data class PersonalCommands(
    val enabled: Boolean = false,
    val reason: String? = null,
    val prefix: String = "personal:",
)

/**
 * The skills and commands of a session: the folder's own, then the owner's personal ones.
 * [enabled], [switchedOn], [reason] and [prefix] are about the folder's own only: [enabled]
 * when the owner switched them on for the session and the computer's check passed. The
 * personal ones have their own state in [personal].
 */
@Serializable
data class CommandList(
    val enabled: Boolean = false,
    @SerialName("switched_on") val switchedOn: Boolean = false,
    val reason: String? = null,
    val prefix: String = "",
    val personal: PersonalCommands = PersonalCommands(),
    val commands: List<SlashCommand> = emptyList(),
) {
    /** The folder's own entries, whether or not they can be used. */
    val folderCommands: List<SlashCommand> get() = commands.filter { !it.personal }

    /**
     * What a capable run of the session is offered: the folder's own when [enabled], the
     * personal ones when [PersonalCommands.enabled].
     */
    val usable: List<SlashCommand>
        get() = commands.filter { it.name.isNotEmpty() && if (it.personal) personal.enabled else enabled }
}

/** What the line above the "/" list says. */
enum class CommandNote {
    /** The conversation has no session on the computer yet. */
    NEW,

    /** Nothing can be used: the computer's check for skills has not passed (or its capable mode is off). */
    UNAVAILABLE,

    /** The folder's own are switched on but cannot be used yet (the personal ones are listed). */
    FOLDER_UNAVAILABLE,

    /** Nothing can be used: the folder's own are switched off for this session, and there are no personal ones. */
    FOLDER_OFF,

    /** Neither the folder nor the owner has a skill or command. */
    NONE,

    /** Nothing usable starts like what is typed. */
    NO_MATCH,

    /** The message starts like a command, but names none that can be used. */
    UNKNOWN,

    /** A usable command in a message without "Changes allowed": it would not run as one. */
    CAPABLE,
}

/** "/" commands in the composer: what is being typed, what completes it, what is recognised. */
object Commands {
    private val TYPING = Regex("^/([A-Za-z0-9_:.-]*)$")
    private val FIRST_WORD = Regex("^/([A-Za-z0-9_:.-]+)(?=\\s|$)")

    /**
     * The part of a command's name typed so far, while the message is only "/" and a
     * (possibly empty) name: the moment to offer the list. Null otherwise.
     */
    fun typing(draft: String): String? = TYPING.matchEntire(draft)?.groupValues?.get(1)

    /**
     * The usable commands whose name starts with what is typed, then those that contain it. A
     * typed prefix ("folder:", "personal:") narrows the list to that source.
     */
    fun matching(list: CommandList, draft: String): List<SlashCommand> {
        val raw = typing(draft)?.lowercase() ?: return emptyList()
        val (typed, candidates) = narrowed(list, raw)
        val (starts, rest) = candidates.partition { it.name.lowercase().startsWith(typed) }
        return starts + rest.filter { typed in it.name.lowercase() }
    }

    /** What is typed without a source prefix, and the usable entries that prefix allows. */
    private fun narrowed(list: CommandList, typed: String): Pair<String, List<SlashCommand>> {
        val folder = list.prefix.lowercase()
        val personal = list.personal.prefix.lowercase()
        return when {
            folder.isNotEmpty() && typed.startsWith(folder) -> typed.removePrefix(folder) to list.usable.filter { !it.personal }
            personal.isNotEmpty() && typed.startsWith(personal) -> typed.removePrefix(personal) to list.usable.filter { it.personal }
            else -> typed to list.usable
        }
    }

    /**
     * How [command] is named in a message: by its name alone, or with the personal prefix when a
     * usable folder entry has the same name (the computer gives a shared name to the folder's).
     */
    fun invocation(list: CommandList, command: SlashCommand): String {
        val shadowed = command.personal && list.usable.any { !it.personal && it.name == command.name }
        return "/" + (if (shadowed) list.personal.prefix else "") + command.name
    }

    /** The message after choosing [command] from the list: how it is named and a space, ready for arguments. */
    fun complete(list: CommandList, command: SlashCommand): String = invocation(list, command) + " "

    /**
     * The usable command the message starts with: by its prefixed name exactly, or by its name
     * alone (the folder's own first, as on the computer). Null for anything else.
     */
    fun recognised(list: CommandList?, draft: String): SlashCommand? {
        list ?: return null
        val word = FIRST_WORD.find(draft)?.groupValues?.get(1) ?: return null
        val usable = list.usable
        if (list.prefix.isNotEmpty() && word.startsWith(list.prefix)) {
            usable.firstOrNull { !it.personal && it.name == word.removePrefix(list.prefix) }?.let { return it }
        }
        if (list.personal.prefix.isNotEmpty() && word.startsWith(list.personal.prefix)) {
            usable.firstOrNull { it.personal && it.name == word.removePrefix(list.personal.prefix) }?.let { return it }
        }
        return usable.firstOrNull { !it.personal && it.name == word } ?: usable.firstOrNull { it.personal && it.name == word }
    }

    /** How many characters at the start of the message are a command the session can use (for highlighting); 0 if none. */
    fun recognisedLength(list: CommandList?, draft: String): Int =
        if (recognised(list, draft) == null) 0 else FIRST_WORD.find(draft)?.value?.length ?: 0

    /** A message that starts like a command ("/word") without being one the session can use. */
    fun unknown(list: CommandList?, draft: String): Boolean = FIRST_WORD.find(draft) != null && recognised(list, draft) == null

    /**
     * The line above the composer for a message that starts with "/", or null when the list (or
     * nothing) speaks for itself. [hasSession] is false for a conversation the computer does not
     * know yet, [list] null while it has not answered, [capable] whether the message would be
     * sent with "Changes allowed".
     */
    fun note(list: CommandList?, draft: String, hasSession: Boolean, capable: Boolean): CommandNote? {
        if (!hasSession) return CommandNote.NEW
        list ?: return null
        if (list.usable.isEmpty()) {
            return when {
                list.switchedOn && !list.enabled -> CommandNote.UNAVAILABLE
                !list.personal.enabled -> CommandNote.UNAVAILABLE
                !list.switchedOn && list.folderCommands.isNotEmpty() -> CommandNote.FOLDER_OFF
                else -> CommandNote.NONE
            }
        }
        if (typing(draft) != null) {
            return when {
                matching(list, draft).isEmpty() -> CommandNote.NO_MATCH
                // Personal ones are listed, but a capable message would be refused until the folder's check passed.
                list.switchedOn && !list.enabled -> CommandNote.FOLDER_UNAVAILABLE
                else -> null
            }
        }
        if (unknown(list, draft)) return CommandNote.UNKNOWN
        return if (!capable) CommandNote.CAPABLE else null
    }
}

/** The model and effort of a conversation: the owner's own choice per field, or null for the default. */
data class ModelChoice(val model: String? = null, val effort: String? = null) {
    /** "opus · high": the choice, with the computer's defaults filled in where nothing was chosen. */
    fun label(defaults: ModelChoices?): String = listOfNotNull(model ?: defaults?.defaultModel, effort ?: defaults?.defaultEffort).joinToString(" · ")
}
