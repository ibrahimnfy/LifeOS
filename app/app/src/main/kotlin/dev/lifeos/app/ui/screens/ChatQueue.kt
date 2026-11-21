package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.data.Chat
import dev.lifeos.app.data.ChatState
import dev.lifeos.app.data.QueuedMessage
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/**
 * The messages that wait on the computer for the current run to end, above the composer:
 * each can be changed, removed or sent now (which interrupts the run). When the queue is on
 * hold, it says why and offers to let it go on.
 */
@Composable
internal fun QueueList(state: ChatState, chat: Chat, locked: Boolean) {
    val colors = LifeTheme.colors
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var sendingNow by rememberSaveable { mutableStateOf<Long?>(null) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LifeDimens.pad)
            .clip(shape)
            .background(colors.surface)
            .border(LifeDimens.hairline, colors.divider, shape)
            .heightIn(max = 230.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.chat_queue_title, state.queue.size),
            style = LifeType.label,
            color = colors.muted,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
        )
        val hold = state.queueHold
        Text(
            text = stringResource(
                when (hold) {
                    null -> R.string.chat_queue_note
                    Chat.HOLD_CANCEL -> R.string.chat_queue_hold_cancel
                    "error" -> R.string.chat_queue_hold_error
                    else -> R.string.chat_queue_hold_restart
                },
            ),
            style = LifeType.small,
            color = colors.muted,
        )
        if (hold != null && state.phase == dev.lifeos.app.data.ChatPhase.IDLE) {
            LinkAction(stringResource(R.string.chat_queue_resume), onClick = chat::resumeQueue)
        }
        state.queue.forEach { row ->
            QueuedRow(row, canInterrupt = state.canStop, onEdit = { editing = row.key }, onRemove = { chat.removeQueued(row.key) }, onSendNow = { sendingNow = row.key })
        }
    }

    val edited = state.queue.firstOrNull { it.key == editing && !it.busy }
    if (edited != null && !locked) {
        MessageEditSheet(
            text = edited.prompt,
            onDismiss = { editing = null },
            onSave = {
                editing = null
                chat.editQueued(edited.key, it)
            },
        )
    }
    val urgent = state.queue.firstOrNull { it.key == sendingNow && !it.busy }
    if (urgent != null && !locked) {
        ConfirmDialog(
            title = stringResource(R.string.chat_send_now_title),
            text = stringResource(R.string.chat_send_now_text),
            confirm = stringResource(R.string.chat_send_now),
            onDismiss = { sendingNow = null },
            onConfirm = {
                sendingNow = null
                chat.sendNow(urgent.key)
            },
        )
    }
}

@Composable
private fun QueuedRow(row: QueuedMessage, canInterrupt: Boolean, onEdit: () -> Unit, onRemove: () -> Unit, onSendNow: () -> Unit) {
    val colors = LifeTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(text = row.prompt, style = LifeType.body, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (row.busy) {
            Text(
                text = stringResource(R.string.chat_queue_busy),
                style = LifeType.small,
                color = colors.muted,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (canInterrupt) LinkAction(stringResource(R.string.chat_send_now), onClick = onSendNow)
                LinkAction(stringResource(R.string.note_edit), onClick = onEdit)
                LinkAction(stringResource(R.string.chat_queue_remove), onClick = onRemove)
            }
        }
    }
}

/** A text action inside a card: link colour, a full touch target. */
@Composable
internal fun LinkAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = LifeTheme.colors
    Box(
        modifier = Modifier
            .heightIn(min = LifeDimens.minTouch)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, style = LifeType.label, color = if (enabled) colors.link else colors.muted)
    }
}

/** A yes-or-no question before an action that cannot be taken back. */
@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = LifeTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = colors.badge) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Change the text of a message that has not been sent to the assistant yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageEditSheet(text: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val colors = LifeTheme.colors
    var value by rememberSaveable { mutableStateOf(text) }
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
            Text(stringResource(R.string.chat_queue_edit_title), style = LifeType.sectionTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = LifeType.body,
                minLines = 3,
                maxLines = 10,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.divider,
                    cursorColor = colors.primary,
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(
                text = stringResource(R.string.capture_save),
                enabled = value.isNotBlank() && value.trim() != text.trim(),
                onClick = { onSave(value.trim()) },
            )
        }
    }
}
