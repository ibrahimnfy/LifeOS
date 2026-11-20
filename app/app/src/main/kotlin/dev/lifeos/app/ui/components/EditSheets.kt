package dev.lifeos.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/** The longest note text the computer accepts (the bridge's MAX_TEXT). */
private const val MAX_NOTE = 4000

/** The longest task title the computer accepts (the bridge's MAX_TITLE). */
private const val MAX_TITLE = 200

/**
 * Edit a task: its title and due date. [onOpenDone] ticks it from here as well, so a task that
 * was tapped to be opened can still be finished in one more tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditSheet(title: String, due: String?, onDismiss: () -> Unit, onSave: (String, String?) -> Unit, onOpenDone: () -> Unit) {
    val colors = LifeTheme.colors
    var text by rememberSaveable { mutableStateOf(title) }
    var day by rememberSaveable { mutableStateOf(due) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val changed = text.trim() != title || day != due

    EditSheetFrame(stringResource(R.string.task_edit_title), onDismiss) {
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
        PrimaryButton(
            text = stringResource(R.string.capture_save),
            enabled = text.isNotBlank() && changed,
            onClick = { onSave(text.trim(), day) },
        )
        SecondaryButton(text = stringResource(R.string.task_edit_done), onClick = onOpenDone)
        Text(stringResource(R.string.task_edit_hint), style = LifeType.small, color = colors.muted)
    }

    if (picking) {
        DayPicker(initial = day, onDismiss = { picking = false }) {
            day = it
            picking = false
        }
    }
}

/** Edit the text of a captured note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditSheet(text: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(text) }
    EditSheetFrame(stringResource(R.string.note_edit_title), onDismiss) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.take(MAX_NOTE) },
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
