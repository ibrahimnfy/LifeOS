package dev.lifeos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import dev.lifeos.app.R
import dev.lifeos.app.data.Dates
import java.time.Instant
import java.time.ZoneOffset
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.launch

enum class CaptureKind { NOTE, TASK }

/**
 * Quick capture ("+"): one text field with the keyboard open, then the three things "+" offers
 * (#166), in this order: Note, Task (a switch; a task may get a due date; Save) and New chat,
 * which opens a blank chat at once and hands it what was typed. [prefill] is text shared from
 * another app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(prefill: String, onDismiss: () -> Unit, onSaved: (CaptureKind, String, String?) -> Unit, onNewChat: (String) -> Unit) {
    val colors = LifeTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf(prefill) }
    var kind by rememberSaveable { mutableStateOf(CaptureKind.NOTE) }
    var due by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bg,
        contentColor = colors.text,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = LifeDimens.pad, end = LifeDimens.pad, bottom = LifeDimens.pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.capture_title),
                style = LifeType.sectionTitle,
                color = colors.text,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.capture_hint), style = LifeType.body) },
                textStyle = LifeType.body,
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.divider,
                    cursorColor = colors.primary,
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    focusedPlaceholderColor = colors.muted,
                    unfocusedPlaceholderColor = colors.muted,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .padding(4.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KindOption(stringResource(R.string.capture_kind_note), kind == CaptureKind.NOTE) {
                    kind = CaptureKind.NOTE
                }
                KindOption(stringResource(R.string.capture_kind_task), kind == CaptureKind.TASK) {
                    kind = CaptureKind.TASK
                }
                // Not a third kind to save but a way out: a blank chat opens at once.
                KindOption(stringResource(R.string.chat_new), selected = false, role = Role.Button) {
                    val draft = text.trim()
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onNewChat(draft) }
                }
            }
            if (kind == CaptureKind.TASK) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        text = due?.let { stringResource(R.string.capture_due, dueLabel(it) ?: it) } ?: stringResource(R.string.capture_due_none),
                        onClick = { pickingDate = true },
                        modifier = Modifier.weight(1f),
                    )
                    if (due != null) {
                        SecondaryButton(
                            text = stringResource(R.string.capture_due_clear),
                            onClick = { due = null },
                            modifier = Modifier.weight(0.6f),
                        )
                    }
                }
            }
            PrimaryButton(
                text = stringResource(R.string.capture_save),
                enabled = text.isNotBlank(),
                onClick = {
                    val saved = text.trim()
                    val day = due.takeIf { kind == CaptureKind.TASK }
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onSaved(kind, saved, day) }
                },
            )
        }
    }

    if (pickingDate) {
        DayPicker(initial = due, onDismiss = { pickingDate = false }) {
            due = it
            pickingDate = false
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun RowScope.KindOption(label: String, selected: Boolean, role: Role = Role.RadioButton, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = LifeDimens.minTouch)
            .clip(shape)
            .background(if (selected) colors.text else colors.surface)
            .then(if (role == Role.RadioButton) Modifier.selectable(selected = selected, role = role, onClick = onClick) else Modifier.clickable(role = role, onClick = onClick))
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = LifeType.button, color = if (selected) colors.bg else colors.muted, textAlign = TextAlign.Center)
    }
}

/** The system-style date picker. Returns the day as `YYYY-MM-DD`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPicker(initial: String?, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val start = (Dates.parse(initial) ?: today()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = start)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                },
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}
