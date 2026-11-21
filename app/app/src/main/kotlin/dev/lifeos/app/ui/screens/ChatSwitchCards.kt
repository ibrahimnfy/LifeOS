package dev.lifeos.app.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.data.ChatSwitch
import dev.lifeos.app.data.FileMove
import dev.lifeos.app.data.FolderKinds
import dev.lifeos.app.data.Interaction
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.RowDivider
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.components.relativeIso
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

// ---------------------------------------------------------------------------- switch card (#164, #168)

/**
 * The conversations an answer offers to go to: their title, folder and last activity; a tap
 * opens one, in its own folder. An answer that was followed rather than received here also
 * shows its "open" or "new" this way, so nothing switches without the owner's tap.
 */
@Composable
internal fun SwitchCard(offer: ChatSwitch, onPick: (ChatSwitch) -> Unit) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(LifeDimens.hairline, colors.divider, shape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CardLabel(stringResource(if (offer.action == ChatSwitch.CHOOSE) R.string.switch_choose_label else R.string.switch_offer_label))
        offer.sessions.forEachIndexed { index, session ->
            if (index > 0) RowDivider()
            val folder = if (session.kind == FolderKinds.CHAT) stringResource(R.string.chat_folder_chat) else session.folderLabel
            SwitchRow(
                kind = session.kind,
                colour = session.colour,
                letter = session.folderLabel,
                title = session.title.trim().ifEmpty { stringResource(R.string.sessions_untitled) },
                line = listOfNotNull(folder.ifBlank { null }, relativeIso(session.lastActivity)).joinToString(" · "),
            ) { onPick(ChatSwitch.open(session)) }
        }
        offer.folder?.let { folder ->
            if (offer.sessions.isNotEmpty()) RowDivider()
            val blank = folder.kind == FolderKinds.CHAT
            SwitchRow(
                kind = folder.kind,
                colour = folder.colour,
                letter = folder.label,
                title = if (blank) stringResource(R.string.switch_new_blank) else stringResource(R.string.switch_new_in, folder.label),
                line = null,
            ) { onPick(ChatSwitch.new(folder)) }
        }
        if (offer.sessions.isNotEmpty()) {
            Text(text = stringResource(R.string.switch_choose_note), style = LifeType.small, color = colors.muted, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun SwitchRow(kind: String, colour: Int, letter: String, title: String, line: String?, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FolderThumb(kind, colour, letter)
        Column(Modifier.weight(1f)) {
            Text(title, style = LifeType.rowTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!line.isNullOrBlank()) Text(line, style = LifeType.small, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(LifeIcons.Chevron, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------------------- moving a chat's files (#163, #168)

/**
 * A blank chat's files the assistant proposes to move into a project: every file with its
 * size, the target, and Allow / Don't allow, like the other approval cards. A proposal the
 * computer refused at once says why and has nothing to answer.
 */
@Composable
internal fun ColumnScope.MoveFilesCard(interaction: Interaction, open: Boolean, onApprove: (Boolean) -> Unit) {
    val colors = LifeTheme.colors
    val context = LocalContext.current
    val move = interaction.move ?: return
    CardLabel(stringResource(R.string.move_label))
    Text(
        text = if (move.targetLabel.isBlank()) stringResource(R.string.move_title_plain) else stringResource(R.string.move_title, move.targetLabel),
        style = LifeType.rowTitle,
        color = colors.text,
    )
    move.problemKind?.let { problem ->
        Text(text = stringResource(problem.textRes()), style = LifeType.label, color = colors.text)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = pluralStringResource(R.plurals.move_files, move.files.size, move.files.size, Formatter.formatShortFileSize(context, move.totalSize)),
            style = LifeType.small,
            color = colors.muted,
        )
        // Every file, whole: nothing of a path is cut off.
        move.files.forEach { file ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = file.path, style = LifeType.small.copy(fontFamily = FontFamily.Monospace), color = colors.text, modifier = Modifier.weight(1f))
                Text(text = Formatter.formatShortFileSize(context, file.size), style = LifeType.small, color = colors.muted)
            }
        }
    }
    if (move.target.isNotBlank()) Exact(stringResource(R.string.move_target), move.target)
    // What a yes did: the files that went, and those that stay in the chat.
    if (interaction.status == Interaction.Status.ALLOWED || interaction.status == Interaction.Status.NOT_DONE) {
        if (move.moved.isNotEmpty()) {
            Notice(pluralStringResource(R.plurals.move_done, move.moved.size, move.moved.size, move.shownTarget), alert = false)
        } else if (move.failed.isEmpty() && interaction.status == Interaction.Status.ALLOWED) {
            Notice(stringResource(R.string.approval_allowed), alert = false)
        }
        if (move.failed.isNotEmpty()) {
            Notice(pluralStringResource(R.plurals.move_failed, move.failed.size, move.failed.size, move.failed.joinToString(", ")), alert = true)
        }
    }
    if (!open) return
    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(text = stringResource(R.string.approval_allow), onClick = { onApprove(true) }, modifier = Modifier.weight(1f))
        SecondaryButton(text = stringResource(R.string.approval_deny), onClick = { onApprove(false) }, modifier = Modifier.weight(1f))
    }
    Text(text = stringResource(R.string.move_note), style = LifeType.small, color = colors.muted)
}

/** What the card says about a proposal the computer refused at once. */
private fun FileMove.Problem.textRes(): Int = when (this) {
    FileMove.Problem.TARGET -> R.string.move_problem_target
    FileMove.Problem.FILE -> R.string.move_problem_file
    FileMove.Problem.EXISTS -> R.string.move_problem_exists
    FileMove.Problem.TOO_MANY -> R.string.move_problem_too_many
    FileMove.Problem.NOT_A_CHAT -> R.string.move_problem_not_chat
    FileMove.Problem.UNRECORDED -> R.string.move_problem_unrecorded
    FileMove.Problem.OTHER -> R.string.move_problem_other
}

/** What a move card says once it is no longer open; the files moved are the card's own lines. */
internal fun Interaction.Status.moveResultRes(): Int? = when (this) {
    Interaction.Status.SENDING -> R.string.interaction_sending
    Interaction.Status.REFUSED -> R.string.move_refused
    Interaction.Status.EXPIRED -> R.string.move_expired
    Interaction.Status.GONE -> R.string.interaction_gone
    else -> null
}
