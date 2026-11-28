package dev.lifeos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.data.Look
import dev.lifeos.app.data.TaskNote
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/** The longest note text the computer accepts (the bridge's MAX_TEXT). */
private const val MAX_NOTE = 4000

/** The longest task title the computer accepts (the bridge's MAX_TITLE). */
private const val MAX_TITLE = 200

/**
 * A task's card (#167): a bottom sheet with its title, due date, the owner's [note] (#170), the
 * coach's first step ([next], a separate line), its course or project, and **Mark as done** and
 * **Edit**. Edit turns the same sheet into the form for the title, the date and the note; saving
 * goes through the outbox, so it works the same for a task that is still waiting to be sent
 * ([waiting]). [onSave] gets the title, the date and the note as typed (blank: no note).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSheet(
    title: String,
    due: String?,
    overdue: Boolean,
    note: String?,
    next: String?,
    look: Look?,
    lookName: String?,
    waiting: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String?, String) -> Unit,
    onDone: () -> Unit,
    /** Delete the task (#197); null: not offered. */
    onDelete: (() -> Unit)? = null,
) {
    val colors = LifeTheme.colors
    var editing by rememberSaveable { mutableStateOf(false) }
    // The form's values; Cancel puts them back.
    var text by rememberSaveable { mutableStateOf(title) }
    var day by rememberSaveable { mutableStateOf(due) }
    var noteText by rememberSaveable { mutableStateOf(note.orEmpty()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val changed = text.trim() != title || day != due || TaskNote.of(noteText) != TaskNote.of(note)

    // One sheet: Edit changes what it shows, so the card does not close and open again.
    EditSheetFrame(if (editing) stringResource(R.string.task_edit_title) else title, onDismiss) {
        if (editing) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace("\n", " ").take(MAX_TITLE) },
                label = { Text(stringResource(R.string.task_edit_field)) },
                singleLine = true,
                textStyle = LifeType.body,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = day?.let { stringResource(R.string.capture_due, dueLabel(it) ?: it) } ?: stringResource(R.string.capture_due_none),
                    onClick = { picking = true },
                    modifier = Modifier.weight(1f),
                )
                if (day != null) {
                    SecondaryButton(text = stringResource(R.string.capture_due_clear), onClick = { day = null }, modifier = Modifier.weight(0.6f))
                }
            }
            TaskNoteField(noteText) { noteText = it }
            PrimaryButton(
                text = stringResource(R.string.capture_save),
                enabled = text.isNotBlank() && changed,
                onClick = { onSave(text.trim(), day, noteText.trim()) },
            )
            SecondaryButton(
                text = stringResource(R.string.action_cancel),
                onClick = {
                    text = title
                    day = due
                    noteText = note.orEmpty()
                    editing = false
                },
            )
            Text(stringResource(R.string.task_edit_hint), style = LifeType.small, color = colors.muted)
        } else {
            if (look != null && lookName != null) CourseLabel(look, lookName)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(LifeIcons.Calendar, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
                Text(
                    text = due?.let { stringResource(R.string.capture_due, dueLabel(it) ?: it) } ?: stringResource(R.string.capture_due_none),
                    style = LifeType.body,
                    color = colors.text,
                )
                if (overdue) {
                    Box(Modifier.size(6.dp).background(colors.badge, CircleShape))
                    Text(stringResource(R.string.task_overdue), style = LifeType.label, color = colors.text)
                }
            }
            if (waiting) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(LifeIcons.Clock, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.outbox_waiting), style = LifeType.small, color = colors.muted)
                }
            }
            // The owner's note and the coach's next step: two separate blocks, each with its label.
            TaskNote.of(note)?.let { CardBlock(stringResource(R.string.task_note), it) }
            next?.trim()?.takeIf { it.isNotEmpty() }?.let { CardBlock(stringResource(R.string.task_next), it) }
            PrimaryButton(text = stringResource(R.string.task_edit_done), onClick = onDone)
            SecondaryButton(text = stringResource(R.string.task_edit), onClick = { editing = true })
            onDelete?.let { SecondaryButton(text = stringResource(R.string.task_delete), danger = true, onClick = it) }
        }
    }

    if (picking) {
        DayPicker(initial = day, onDismiss = { picking = false }) {
            day = it
            picking = false
        }
    }
}

/** A labelled block of the task card: the owner's note, or the coach's next step. */
@Composable
private fun CardBlock(label: String, text: String) {
    val colors = LifeTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = LifeType.label, color = colors.muted)
        Text(text, style = LifeType.body, color = colors.text)
    }
}

/**
 * The optional note of a task (#170), in the card's edit form and in the capture sheet: one
 * paragraph (a line break becomes a space, as on the computer), at most [TaskNote.MAX] characters.
 */
@Composable
fun TaskNoteField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(TaskNote.typed(it)) },
        label = { Text(stringResource(R.string.task_note_field)) },
        textStyle = LifeType.body,
        minLines = 2,
        maxLines = 6,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Edit the text of a note: a capture, or the body of a topic note ([max]: the longest it may get). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditSheet(text: String, onDismiss: () -> Unit, max: Int = MAX_NOTE, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(text) }
    EditSheetFrame(stringResource(R.string.note_edit_title), onDismiss) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.take(max) },
            textStyle = LifeType.body,
            minLines = 4,
            maxLines = 12,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(
            text = stringResource(R.string.capture_save),
            enabled = value.isNotBlank() && value.trim() != text.trim(),
            onClick = { onSave(value.trim()) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheetFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = LifeTheme.colors
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
            Text(title, style = LifeType.sectionTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun fieldColors() = LifeTheme.colors.let { colors ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.divider,
        cursorColor = colors.primary,
        focusedTextColor = colors.text,
        unfocusedTextColor = colors.text,
        focusedLabelColor = colors.primary,
        unfocusedLabelColor = colors.muted,
        focusedPlaceholderColor = colors.muted,
        unfocusedPlaceholderColor = colors.muted,
    )
}
