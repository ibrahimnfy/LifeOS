package dev.lifeos.app.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.data.AskQuestion
import dev.lifeos.app.data.Interaction
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.delay

/**
 * A question or an approval in the middle of a run: the run on the computer waits until the
 * owner answers here, and counts a missing answer as "no" when the time shown has passed.
 */
@Composable
internal fun InteractionCard(
    interaction: Interaction,
    onApprove: (Boolean) -> Unit,
    onAnswer: (Map<String, List<String>>) -> Unit,
    onSkip: () -> Unit,
    onExpire: () -> Unit,
) {
    val colors = LifeTheme.colors
    // The phone's running clock: the wall clock of phone and computer need not agree.
    val now by produceState(SystemClock.elapsedRealtime(), interaction.id) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val waiting = interaction.status == Interaction.Status.WAITING
    val open = interaction.open(now)
    LaunchedEffect(waiting, open) { if (waiting && !open) onExpire() }

    val warning = interaction.kind == Interaction.Kind.APPROVAL && interaction.isDeletion
    // Approving an MCP server lets a program run outside the sandbox: the card stands out in amber.
    val outside = interaction.kind == Interaction.Kind.MCP_SERVER
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(if (warning || outside) 2.dp else LifeDimens.hairline, if (warning) colors.badge else if (outside) colors.warn else colors.divider, shape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (interaction.kind) {
            Interaction.Kind.APPROVAL -> Approval(interaction, open, onApprove)
            Interaction.Kind.QUESTION -> Question(interaction, open, onAnswer, onSkip)
            Interaction.Kind.SKILL_INSTALL -> SkillInstallCard(interaction, open, onApprove)
            Interaction.Kind.MCP_SERVER -> McpApprovalCard(interaction, open, onApprove)
            Interaction.Kind.MOVE_FILES -> MoveFilesCard(interaction, open, onApprove)
        }
        if (open) {
            val left = interaction.remainingMillis(now) / 1000
            // A card that waits across runs has hours; one of a run has minutes.
            val time = if (left >= 3600) stringResource(R.string.time_hours_minutes, left / 3600, left % 3600 / 60) else "%d:%02d".format(left / 60, left % 60)
            // Not a live region: the time is not read out every second.
            Text(
                text = stringResource(
                    when (interaction.kind) {
                        Interaction.Kind.QUESTION -> R.string.question_time_left
                        Interaction.Kind.SKILL_INSTALL -> R.string.install_time_left
                        Interaction.Kind.MCP_SERVER -> R.string.mcp_time_left
                        Interaction.Kind.MOVE_FILES -> R.string.move_time_left
                        Interaction.Kind.APPROVAL -> R.string.interaction_time_left
                    },
                    time,
                ),
                style = LifeType.small,
                color = colors.muted,
            )
        }
        if (interaction.sendFailed && open) Notice(stringResource(R.string.interaction_send_failed), alert = true)
        // The computer did not act on the yes; the card still waits there and can be answered again.
        if (open) interaction.refusal?.let { Notice(stringResource(it.textRes(interaction.kind)), alert = true) }
        interaction.status.resultRes(interaction.kind, personal = interaction.server?.personal == true)?.let { Notice(stringResource(it), alert = false) }
    }
}

/** What the card says once it is no longer open for an answer; [personal]: the MCP server is one of the owner's own. */
private fun Interaction.Status.resultRes(kind: Interaction.Kind, personal: Boolean = false): Int? {
    val install = kind == Interaction.Kind.SKILL_INSTALL
    if (kind == Interaction.Kind.MCP_SERVER) return mcpResultRes(personal)
    if (kind == Interaction.Kind.MOVE_FILES) return moveResultRes()
    return when (this) {
        Interaction.Status.WAITING -> null
        Interaction.Status.SENDING -> R.string.interaction_sending
        Interaction.Status.ALLOWED -> if (install) R.string.install_installed else R.string.approval_allowed
        Interaction.Status.REFUSED -> if (install) R.string.install_refused else R.string.approval_refused
        Interaction.Status.ANSWERED -> R.string.question_answered
        Interaction.Status.SKIPPED -> R.string.question_skipped
        Interaction.Status.EXPIRED -> when (kind) {
            Interaction.Kind.QUESTION -> R.string.question_expired
            Interaction.Kind.SKILL_INSTALL -> R.string.install_expired
            Interaction.Kind.APPROVAL, Interaction.Kind.MCP_SERVER, Interaction.Kind.MOVE_FILES -> R.string.interaction_expired
        }
        Interaction.Status.GONE -> R.string.interaction_gone
        Interaction.Status.NOT_DONE -> R.string.install_not_done
    }
}

/** Why the computer did not act on a yes, for a card that still waits. */
internal fun Interaction.Refusal.textRes(kind: Interaction.Kind): Int = when (this) {
    Interaction.Refusal.STILL_DOWNLOADING -> R.string.install_refusal_downloading
    Interaction.Refusal.EXISTS -> R.string.install_refusal_exists
    Interaction.Refusal.UNRECORDED -> when (kind) {
        Interaction.Kind.MCP_SERVER -> R.string.mcp_refusal_unrecorded
        Interaction.Kind.MOVE_FILES -> R.string.move_refusal_unrecorded
        else -> R.string.install_refusal_unrecorded
    }
    Interaction.Refusal.WRITE_FAILED -> R.string.install_refusal_write
    Interaction.Refusal.FILE_EXISTS -> R.string.move_refusal_exists
    Interaction.Refusal.FILES_CHANGED -> R.string.move_refusal_changed
    Interaction.Refusal.CANNOT_MOVE -> R.string.move_refusal_other
}

@Composable
internal fun Notice(text: String, alert: Boolean) {
    val colors = LifeTheme.colors
    Text(
        text = text,
        style = LifeType.label,
        color = if (alert) colors.badge else colors.text,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
internal fun CardLabel(text: String, alert: Boolean = false) {
    val colors = LifeTheme.colors
    Text(
        text = text,
        style = LifeType.label,
        color = if (alert) colors.badge else colors.muted,
        // Announced when the card appears: the run is waiting for the owner.
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Fixed-width text on a darker ground, shown whole: nothing of a command or path is cut off. */
@Composable
internal fun Exact(caption: String, text: String) {
    val colors = LifeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(text = caption, style = LifeType.small, color = colors.muted)
        SelectionContainer {
            Text(
                text = text,
                style = LifeType.small.copy(fontFamily = FontFamily.Monospace),
                color = colors.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface2, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------- approval

/**
 * An action that runs only after the owner's explicit yes: what kind it is, exactly what
 * will run and where. A deletion is a warning.
 */
@Composable
private fun ColumnScope.Approval(interaction: Interaction, open: Boolean, onApprove: (Boolean) -> Unit) {
    val colors = LifeTheme.colors
    CardLabel(stringResource(R.string.approval_label))
    Text(text = stringResource(interaction.titleRes()), style = LifeType.rowTitle, color = colors.text)
    if (interaction.isDeletion) {
        Text(text = stringResource(R.string.approval_warning_deletion), style = LifeType.label, color = colors.badge)
    }
    Exact(stringResource(R.string.approval_command), interaction.command)
    if (interaction.folder.isNotBlank()) Exact(stringResource(R.string.approval_folder), interaction.folder)
    interaction.network?.let { push ->
        // #178: this one command will use the network; nothing else of the run does.
        Text(text = stringResource(R.string.approval_network, push.host), style = LifeType.label, color = colors.badge)
        val pushed = stringResource(R.string.approval_network_push, push.commit, push.branch) +
            (if (push.force) "\n" + stringResource(R.string.approval_network_forced) else "")
        Exact(stringResource(R.string.approval_network_target), push.url + "\n" + pushed)
    }
    if (open) {
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(text = stringResource(R.string.approval_allow), onClick = { onApprove(true) }, modifier = Modifier.weight(1f))
            SecondaryButton(text = stringResource(R.string.approval_deny), onClick = { onApprove(false) }, modifier = Modifier.weight(1f))
        }
        Text(text = stringResource(R.string.approval_kept_note), style = LifeType.small, color = colors.muted)
    }
}

/** The bridge names the kind of action with one word (its gate's categories). */
private fun Interaction.titleRes(): Int = when (category) {
    Interaction.DELETION -> R.string.approval_title_deletion
    "force" -> R.string.approval_title_force
    "push" -> R.string.approval_title_push
    "install" -> R.string.approval_title_install
    "message" -> R.string.approval_title_message
    "network" -> R.string.approval_title_network
    else -> R.string.approval_title_other
}

// ---------------------------------------------------------------------------- question

/** The assistant's questions with their choices; each can also be answered in the owner's own words. */
@Composable
private fun ColumnScope.Question(interaction: Interaction, open: Boolean, onAnswer: (Map<String, List<String>>) -> Unit, onSkip: () -> Unit) {
    val colors = LifeTheme.colors
    val chosen = remember(interaction.id) { mutableStateMapOf<String, List<String>>() }
    val own = remember(interaction.id) { mutableStateMapOf<String, String>() }
    CardLabel(stringResource(R.string.question_label))
    interaction.questions.forEach { question ->
        if (question.header.isNotBlank()) Text(text = question.header, style = LifeType.small, color = colors.muted)
        Text(text = question.question, style = LifeType.rowTitle, color = colors.text)
        // Afterwards the card keeps what was answered.
        val given = interaction.answers[question.question].orEmpty()
        if (given.isNotEmpty() && (interaction.status == Interaction.Status.SENDING || interaction.status == Interaction.Status.ANSWERED)) {
            Text(text = stringResource(R.string.question_your_answer, given.joinToString(", ")), style = LifeType.body, color = colors.text)
        }
        if (open) {
            if (question.multiSelect) Text(text = stringResource(R.string.question_multi), style = LifeType.small, color = colors.muted)
            val picked = chosen[question.question].orEmpty()
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                question.options.filter { it.label.isNotBlank() }.forEachIndexed { index, option ->
                    OptionRow(
                        letter = ('A' + index).toString(),
                        label = option.label,
                        description = option.description,
                        selected = option.label in picked,
                        multi = question.multiSelect,
                    ) {
                        chosen[question.question] = when {
                            !question.multiSelect -> listOf(option.label)
                            option.label in picked -> picked - option.label
                            else -> picked + option.label
                        }
                        // One answer per single-choice question: a choice replaces the owner's own words.
                        if (!question.multiSelect) own.remove(question.question)
                    }
                }
            }
            OwnAnswer(own[question.question].orEmpty()) { text ->
                own[question.question] = text
                if (!question.multiSelect && text.isNotBlank()) chosen.remove(question.question)
            }
        }
    }
    if (open) {
        val answers = answersOf(interaction.questions, chosen, own)
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                text = stringResource(R.string.chat_send),
                onClick = { onAnswer(answers) },
                modifier = Modifier.weight(1f),
                enabled = Interaction.complete(interaction.questions, answers),
            )
            SecondaryButton(text = stringResource(R.string.question_skip), onClick = onSkip, modifier = Modifier.weight(1f))
        }
    }
}

/** Per question: the chosen labels, and the owner's own words as one more answer (or the only one). */
private fun answersOf(questions: List<AskQuestion>, chosen: Map<String, List<String>>, own: Map<String, String>): Map<String, List<String>> =
    questions.associate { question ->
        val words = own[question.question].orEmpty().trim()
        val labels = chosen[question.question].orEmpty()
        question.question to when {
            words.isEmpty() -> labels
            question.multiSelect -> labels + words
            else -> listOf(words)
        }
    }

@Composable
private fun OptionRow(letter: String, label: String, description: String, selected: Boolean, multi: Boolean, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = LifeDimens.minTouch)
        .clip(shape)
        .background(colors.bg)
        .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.divider, shape)
    Row(
        modifier = (
            if (multi) {
                base.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
            } else {
                base.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            }
            ).padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(28.dp).background(colors.surface2, CircleShape), contentAlignment = Alignment.Center) {
            Text(text = letter, style = LifeType.label, color = colors.text)
        }
        Column(Modifier.weight(1f)) {
            Text(text = label, style = LifeType.body, color = colors.text)
            if (description.isNotBlank()) Text(text = description, style = LifeType.small, color = colors.muted)
        }
        // The mark: a ring (one choice) or a rounded square (several), filled when chosen.
        val mark = if (multi) RoundedCornerShape(6.dp) else CircleShape
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(if (selected) 2.dp else 1.5.dp, if (selected) colors.primary else colors.muted, mark),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).background(colors.primary, if (multi) RoundedCornerShape(3.dp) else CircleShape))
        }
    }
}

@Composable
private fun OwnAnswer(text: String, onChange: (String) -> Unit) {
    val colors = LifeTheme.colors
    val hint = stringResource(R.string.question_own)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LifeDimens.minTouch)
            .background(colors.bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text.isEmpty()) Text(text = hint, style = LifeType.body, color = colors.muted)
        BasicTextField(
            value = text,
            onValueChange = onChange,
            textStyle = LifeType.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.primary),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = hint },
        )
    }
}
