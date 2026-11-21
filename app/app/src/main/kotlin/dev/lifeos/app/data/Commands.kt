package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A skill or command of a session's folder (`GET /sessions/{id}/commands`). */
@Serializable
data class SlashCommand(
    val name: String = "",
    /** "skill" or "command". */
    val kind: String = "",
    val description: String = "",
    @SerialName("argument_hint") val argumentHint: String? = null,
    /** What the owner should know before using it: "shell_lines", "requests_tools", "has_scripts". */
    val warnings: List<String> = emptyList(),
)

/**
 * The skills and commands of a session's folder. [enabled]: its capable runs may use them (the
 * owner switched them on for the session and the computer's check passed); [reason] says why
 * not (the bridge's own words). [prefix] is the namespace the bridge puts in front of a name.
 */
@Serializable
data class CommandList(
    val enabled: Boolean = false,
    @SerialName("switched_on") val switchedOn: Boolean = false,
    val reason: String? = null,
    val prefix: String = "",
    val commands: List<SlashCommand> = emptyList(),
)

/** "/" commands in the composer: what is being typed, what completes it, what is recognised. */
object Commands {
    private val TYPING = Regex("^/([A-Za-z0-9_:.-]*)$")
    private val FIRST_WORD = Regex("^/([A-Za-z0-9_:.-]+)(?=\\s|$)")

    /**
     * The part of a command's name typed so far, while the message is only "/" and a
     * (possibly empty) name: the moment to offer the list. Null otherwise.
     */
    fun typing(draft: String): String? = TYPING.matchEntire(draft)?.groupValues?.get(1)

    /** The commands whose name starts with what is typed (with or without the bridge's prefix), then those that contain it. */
    fun matching(list: CommandList, draft: String): List<SlashCommand> {
        val typed = typing(draft)?.removePrefix(list.prefix)?.lowercase() ?: return emptyList()
        val (starts, rest) = list.commands.filter { it.name.isNotEmpty() }.partition { it.name.lowercase().startsWith(typed) }
        return starts + rest.filter { typed in it.name.lowercase() }
    }

    /** The message after choosing [command] from the list: its name and a space, ready for arguments. */
    fun complete(command: SlashCommand): String = "/${command.name} "

    /** The command the message starts with, if it is one of the folder's. */
    fun recognised(list: CommandList?, draft: String): SlashCommand? {
        val word = FIRST_WORD.find(draft)?.groupValues?.get(1) ?: return null
        val name = word.removePrefix(list?.prefix.orEmpty())
        return list?.commands?.firstOrNull { it.name == name }
    }

    /** How many characters at the start of the message are the recognised command (for highlighting); 0 if none. */
    fun recognisedLength(list: CommandList?, draft: String): Int =
        if (recognised(list, draft) == null) 0 else FIRST_WORD.find(draft)?.value?.length ?: 0

    /** A message that starts like a command ("/word") without being one of the folder's. */
    fun unknown(list: CommandList?, draft: String): Boolean = FIRST_WORD.find(draft) != null && recognised(list, draft) == null
}

/** The model and effort of a conversation: the owner's own choice per field, or null for the default. */
data class ModelChoice(val model: String? = null, val effort: String? = null) {
    /** "opus · high": the choice, with the computer's defaults filled in where nothing was chosen. */
    fun label(defaults: ModelChoices?): String = listOfNotNull(model ?: defaults?.defaultModel, effort ?: defaults?.defaultEffort).joinToString(" · ")
}
