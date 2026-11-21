package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.data.ChatState
import dev.lifeos.app.data.CommandList
import dev.lifeos.app.data.CommandNote
import dev.lifeos.app.data.Commands
import dev.lifeos.app.data.ModelChoice
import dev.lifeos.app.data.ModelChoices
import dev.lifeos.app.data.SlashCommand
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/**
 * What a message that starts with "/" gets above the composer: while the name is typed, the
 * usable skills and commands that match, the folder's own and the owner's personal ones (a tap
 * completes the name); otherwise one line that says where things stand (nothing usable, not
 * one that can be used, needs "Changes allowed").
 */
@Composable
internal fun CommandHelp(list: CommandList?, draft: String, state: ChatState, onPick: (SlashCommand) -> Unit) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val note: String? = when (Commands.note(list, draft, hasSession = state.sessionId != null, capable = state.capable)) {
        CommandNote.NEW -> stringResource(R.string.chat_commands_new)
        CommandNote.UNAVAILABLE -> stringResource(R.string.chat_commands_unavailable)
        CommandNote.FOLDER_UNAVAILABLE -> stringResource(R.string.chat_commands_folder_unavailable)
        CommandNote.FOLDER_OFF -> stringResource(R.string.chat_commands_off)
        CommandNote.NONE -> stringResource(R.string.chat_commands_none)
        CommandNote.NO_MATCH -> stringResource(R.string.chat_commands_no_match)
        CommandNote.UNKNOWN -> stringResource(R.string.chat_commands_unknown)
        CommandNote.CAPABLE -> stringResource(R.string.chat_commands_capable)
        null -> null
    }
    val offered = if (list != null && state.sessionId != null) Commands.matching(list, draft) else emptyList()
    if (note == null && offered.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LifeDimens.pad)
            .clip(shape)
            .background(colors.surface)
            .border(LifeDimens.hairline, colors.divider, shape)
            .heightIn(max = 250.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (note != null) {
            Text(text = note, style = LifeType.small, color = colors.text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (offered.isNotEmpty()) {
            Text(
                text = stringResource(R.string.chat_commands_title),
                style = LifeType.label,
                color = colors.muted,
                modifier = Modifier.semantics { heading() },
            )
            offered.forEach { command -> CommandRow(Commands.invocation(checkNotNull(list), command), command) { onPick(command) } }
        }
    }
}

/** One entry: how it is named in a message, whether it is a skill or a command, the folder's or personal. */
@Composable
private fun CommandRow(invocation: String, command: SlashCommand, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LifeDimens.minTouch)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = invocation + command.argumentHint?.let { " $it" }.orEmpty(),
                style = LifeType.rowTitle.copy(fontFamily = FontFamily.Monospace),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = stringResource(
                    when {
                        command.personal && command.kind == "skill" -> R.string.chat_command_personal_skill
                        command.personal -> R.string.chat_command_personal_command
                        command.kind == "skill" -> R.string.chat_command_skill
                        else -> R.string.chat_command_command
                    },
                ),
                style = LifeType.small,
                color = if (command.personal) colors.link else colors.muted,
            )
        }
        if (command.description.isNotBlank()) {
            Text(text = command.description, style = LifeType.small, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // What the owner should know before using it.
        val warnings = command.warnings.mapNotNull {
            when (it) {
                "shell_lines" -> stringResource(R.string.chat_command_warn_shell)
                "requests_tools" -> stringResource(R.string.chat_command_warn_tools)
                "has_scripts" -> stringResource(R.string.chat_command_warn_scripts)
                else -> null
            }
        }
        if (warnings.isNotEmpty()) Text(text = warnings.joinToString(" · "), style = LifeType.small, color = colors.warn)
    }
}

/** Colours the recognised command at the start of the message; the text itself is unchanged. */
internal class CommandHighlight(private val length: Int, private val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (length <= 0 || length > text.length) return TransformedText(text, OffsetMapping.Identity)
        val styled = AnnotatedString.Builder(text).apply {
            addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), 0, length)
        }.toAnnotatedString()
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean = other is CommandHighlight && other.length == length && other.color == color

    override fun hashCode(): Int = 31 * length + color.hashCode()
}

/**
 * The model and effort of this conversation: the default for chat sessions (shown by name) or
 * one of the models and effort levels the computer offers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(choice: ModelChoice, choices: ModelChoices?, onDismiss: () -> Unit, onSave: (ModelChoice) -> Unit) {
    val colors = LifeTheme.colors
    var model by remember { mutableStateOf(choice.model) }
    var effort by remember { mutableStateOf(choice.effort) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bg,
        contentColor = colors.text,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = LifeDimens.pad, end = LifeDimens.pad, bottom = LifeDimens.pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.chat_model_title), style = LifeType.sectionTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            // A choice made earlier stays selectable even if the computer no longer lists it.
            Options(stringResource(R.string.models_field_model), choices?.defaultModel, (choices?.models.orEmpty() + listOfNotNull(choice.model)).distinct(), model) { model = it }
            Options(stringResource(R.string.models_field_effort), choices?.defaultEffort, (choices?.efforts.orEmpty() + listOfNotNull(choice.effort)).distinct(), effort) { effort = it }
            Text(stringResource(R.string.chat_model_note), style = LifeType.small, color = colors.muted)
            PrimaryButton(text = stringResource(R.string.capture_save), onClick = { onSave(ModelChoice(model, effort)) })
        }
    }
}

@Composable
private fun Options(label: String, default: String?, options: List<String>, selected: String?, onPick: (String?) -> Unit) {
    val colors = LifeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = LifeType.label, color = colors.muted)
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip(label = default?.let { stringResource(R.string.models_default_is, it) } ?: stringResource(R.string.models_default), selected = selected == null) { onPick(null) }
            options.forEach { option -> Chip(label = option, selected = selected == option) { onPick(option) } }
        }
    }
}
