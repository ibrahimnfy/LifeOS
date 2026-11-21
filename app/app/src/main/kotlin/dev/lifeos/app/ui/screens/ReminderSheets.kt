package dev.lifeos.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.data.Reminder
import dev.lifeos.app.data.Reminders
import dev.lifeos.app.ui.components.DayPicker
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.components.appLocale
import dev.lifeos.app.ui.components.dayLabel
import dev.lifeos.app.ui.components.momentLabel
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// Session reminders (#75): "remind me about this session at <time>", with an optional note,
// once or repeating. The computer keeps and fires them; its notification opens the session.

/** When [reminder] fires next, and how it repeats, in one line. */
@Composable
internal fun reminderLabel(reminder: Reminder): String {
    val moment = reminder.moment?.let { momentLabel(it.epochSecond, appLocale()) } ?: reminder.at
    return when (Reminders.Repeat.of(reminder.repeat)) {
        Reminders.Repeat.ONCE -> moment
        Reminders.Repeat.DAILY -> stringResource(R.string.reminder_next_daily, moment)
        Reminders.Repeat.WEEKLY -> stringResource(R.string.reminder_next_weekly, moment)
    }
}

/** The reminders of one session that are still to come, and the way to a new one. */
@Composable
internal fun RemindersSheet(reminders: List<Reminder>, onDismiss: () -> Unit, onNew: () -> Unit, onEdit: (Reminder) -> Unit) {
    SheetFrame(stringResource(R.string.reminder_title), onDismiss) {
        if (reminders.isEmpty()) {
            Text(stringResource(R.string.reminder_none), style = LifeType.small, color = LifeTheme.colors.muted, modifier = Modifier.padding(bottom = 8.dp))
        }
        reminders.forEach { reminder ->
            SheetAction(LifeIcons.Bell, reminderLabel(reminder), note = reminder.note) { onEdit(reminder) }
        }
        SheetAction(LifeIcons.Plus, stringResource(R.string.reminder_new), onClick = onNew)
    }
}

/**
 * A new reminder ([reminder] null) or one to change: the day, the time, once or repeating, and
 * a note. [onSave] gets the body for the computer ([Reminders.body] without the session).
 */
@Composable
internal fun ReminderEditSheet(reminder: Reminder?, onDismiss: () -> Unit, onSave: (ZonedDateTime, String, Reminders.Repeat) -> Unit, onDelete: (() -> Unit)?) {
    val colors = LifeTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    // New: the next full hour. An existing one: its next time, on the phone's clock.
    val start = remember(reminder?.id) {
        reminder?.moment?.atZone(zone) ?: ZonedDateTime.now(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
    }
    var day by rememberSaveable { mutableStateOf(start.toLocalDate().toString()) }
    var time by rememberSaveable { mutableStateOf("%02d:%02d".format(start.hour, start.minute)) }
    var repeat by rememberSaveable { mutableStateOf(Reminders.Repeat.of(reminder?.repeat.orEmpty())) }
    var note by rememberSaveable { mutableStateOf(reminder?.note.orEmpty()) }
    var pickingDay by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    val at = Reminders.moment(day, time, zone)
    val usable = Reminders.usable(at, repeat, Instant.now())

    SheetFrame(stringResource(if (reminder == null) R.string.reminder_new else R.string.reminder_edit), onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val locale = appLocale()
            val shownDay = dev.lifeos.app.data.Dates.parse(day)?.let { dayLabel(it, locale) } ?: day
            SecondaryButton(text = stringResource(R.string.reminder_day, shownDay), onClick = { pickingDay = true }, modifier = Modifier.weight(1.4f))
            SecondaryButton(text = stringResource(R.string.reminder_time, time), onClick = { pickingTime = true }, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip(stringResource(R.string.reminder_once), selected = repeat == Reminders.Repeat.ONCE) { repeat = Reminders.Repeat.ONCE }
            Chip(stringResource(R.string.reminder_daily), selected = repeat == Reminders.Repeat.DAILY) { repeat = Reminders.Repeat.DAILY }
            Chip(stringResource(R.string.reminder_weekly), selected = repeat == Reminders.Repeat.WEEKLY) { repeat = Reminders.Repeat.WEEKLY }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(Reminders.MAX_NOTE) },
            label = { Text(stringResource(R.string.reminder_note)) },
            textStyle = LifeType.body,
            minLines = 2,
            maxLines = 5,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = colors.divider,
                cursorColor = colors.primary,
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                focusedLabelColor = colors.primary,
                unfocusedLabelColor = colors.muted,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
        Text(
            text = stringResource(if (usable) R.string.reminder_hint else R.string.reminder_past),
            style = LifeType.small,
            color = if (usable) colors.muted else colors.badge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        PrimaryButton(text = stringResource(R.string.capture_save), enabled = usable, onClick = { at?.let { onSave(it, note, repeat) } })
        if (onDelete != null) {
            SecondaryButton(text = stringResource(R.string.reminder_delete), onClick = onDelete, danger = true, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (pickingDay) {
        DayPicker(initial = day, onDismiss = { pickingDay = false }) {
            day = it
            pickingDay = false
        }
    }
    if (pickingTime) {
        ClockPicker(initial = time, onDismiss = { pickingTime = false }, dismissLabel = R.string.action_cancel) {
            time = it
            pickingTime = false
        }
    }
}

/** A switch inside a sheet: what it does, a line of explanation, the whole row to tap. */
@Composable
internal fun SheetSwitch(text: String, note: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = LifeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LifeDimens.minTouch)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(text, style = LifeType.body, color = if (enabled) colors.text else colors.muted)
            if (note != null) Text(note, style = LifeType.small, color = colors.muted)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
