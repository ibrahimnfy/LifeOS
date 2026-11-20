package dev.lifeos.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.Dates
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.Event
import dev.lifeos.app.data.EventDraft
import dev.lifeos.app.data.EventList
import dev.lifeos.app.data.OutboxItem
import dev.lifeos.app.data.UiState
import dev.lifeos.app.ui.components.*
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private const val DAYS_BACK = 7L
private const val DAYS_AHEAD = 60L
private val EVENT_KINDS = setOf(OutboxItem.Kind.EVENT_CREATE, OutboxItem.Kind.EVENT_UPDATE)

/** A note that only marks where a deadline came from is not shown as text. */
private fun Event.visibleNote(): String? = note?.takeIf { it.isNotBlank() && !it.startsWith("moodle:") }

/**
 * Calendar: the agenda by day from a week ago to two months ahead, a month strip to jump,
 * and adding or changing an event. Course deadlines are ordinary events here.
 */
@Composable
fun CalendarScreen(core: LifeOsCore, onBack: () -> Unit, onConnectionClick: () -> Unit, onPair: () -> Unit) {
    val colors = LifeTheme.colors
    val locale = appLocale()
    val today = today()
    val first = today.minusDays(DAYS_BACK)
    val last = today.plusDays(DAYS_AHEAD)
    val model = rememberLoad(core, Endpoints.events(first.toString(), last.toString())) { list: EventList -> list.events.isEmpty() }
    val state by model.state.collectAsStateWithLifecycle()
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val locked by core.locked.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Event?>(null) }
    val snackbar = LocalSnackbar.current
    val savedText = stringResource(R.string.capture_saved)
    val waitingText = stringResource(R.string.capture_waiting)

    val days = (state as? UiState.Content<EventList>)?.data?.let { Dates.agenda(it.events, first, last) }.orEmpty()
    val months = generateSequence(YearMonth.from(first)) { it.plusMonths(1) }.takeWhile { it <= YearMonth.from(last) }.toList()
    val waiting = if (locked) emptyList() else outbox.filter { it.kind in EVENT_KINDS && it.state == OutboxItem.State.WAITING }
    val headerItems = 1

    // Open at today (or the next day that has something).
    var positioned by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(days.isNotEmpty()) {
        if (days.isNotEmpty() && !positioned) {
            positioned = true
            val index = days.indexOfFirst { it.first >= today }
            if (index > 0) listState.scrollToItem(index + headerItems)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = stringResource(R.string.more_calendar),
            onBack = onBack,
            onConnectionClick = onConnectionClick,
            actions = { TopBarAction(LifeIcons.Plus, stringResource(R.string.calendar_add)) { adding = true } },
        )
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = LifeDimens.pad),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            months.forEach { month ->
                val target = days.indexOfFirst { YearMonth.from(it.first) >= month }
                val current = days.getOrNull((listState.firstVisibleItemIndex - headerItems).coerceAtLeast(0))?.let { YearMonth.from(it.first) } == month
                Chip(
                    label = month.format(DateTimeFormatter.ofPattern("LLLL", locale)).replaceFirstChar { it.titlecase(locale) },
                    selected = current,
                ) {
                    if (target >= 0) scope.launch { listState.animateScrollToItem(target + headerItems) }
                }
            }
        }
        RefreshBox(refreshing = state.isRefreshing, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = LifeDimens.pad, end = LifeDimens.pad, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        OutboxNotices(core, EVENT_KINDS)
                        if (waiting.isNotEmpty()) {
                            LifeCard {
                                SectionHeader(title = stringResource(R.string.outbox_waiting_title))
                                waiting.forEachIndexed { index, item ->
                                    if (index > 0) RowDivider()
                                    MarkRow(
                                        title = item.event?.title.orEmpty(),
                                        subtitle = Dates.parse(item.event?.date)?.let { dayLabel(it, locale) },
                                    ) { Icon(LifeIcons.Clock, contentDescription = null, tint = colors.muted) }
                                }
                            }
                        }
                        if (state !is UiState.Content) {
                            StateHost(state, stringResource(R.string.calendar_empty), onRetry = model::refresh, onPair = onPair, skeletonRows = 4) {}
                        } else if ((state as UiState.Content).fromCache && !state.isRefreshing) {
                            SavedCopyLine((state as UiState.Content).savedAtMillis)
                        }
                    }
                }
                itemsIndexed(days, key = { _, day -> day.first.toString() }) { _, (day, events) ->
                    LifeCard {
                        SectionHeader(
                            title = dayLabel(day, locale),
                            note = when (day) {
                                today -> stringResource(R.string.day_today)
                                today.plusDays(1) -> stringResource(R.string.day_tomorrow)
                                else -> null
                            },
                        )
                        events.forEachIndexed { index, event ->
                            if (index > 0) RowDivider(startIndent = 72.dp)
                            EventRow(event, day, locale) { editing = event }
                        }
                    }
                }
            }
        }
    }

    if ((adding || editing != null) && !locked) {
        val original = editing
        EventEditor(
            original = original,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { draft ->
                adding = false
                editing = null
                scope.launch {
                    val sent = core.saveEvent(original?.id, draft)
                    snackbar(if (sent) savedText else waitingText)
                }
            },
        )
    }
}

@Composable
private fun EventRow(event: Event, day: LocalDate, locale: java.util.Locale, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    val start = Dates.parse(event.date)
    val end = Dates.parse(event.endDate)
    val allDay = Dates.isAllDay(event)
    // A timed event of several days starts on its first day; on the others it runs all day.
    val time = if (allDay || (start != null && day != start)) stringResource(R.string.calendar_all_day) else event.start.orEmpty()
    val details = listOfNotNull(
        if (!allDay && day == start && event.end != null && end == null) stringResource(R.string.calendar_until, event.end) else null,
        if (start != null && end != null && end > start) "${dayLabel(start, locale)} – ${dayLabel(end, locale)}" else null,
        event.visibleNote(),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(R.string.calendar_edit), role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = time, style = LifeType.label, color = colors.muted, modifier = Modifier.width(60.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(event.title, style = LifeType.rowTitle, color = colors.text)
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = LifeType.small, color = colors.muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditor(original: Event?, onDismiss: () -> Unit, onSave: (EventDraft) -> Unit) {
    val colors = LifeTheme.colors
    val locale = appLocale()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by rememberSaveable { mutableStateOf(original?.title.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(original?.date ?: today().toString()) }
    var endDate by rememberSaveable { mutableStateOf(original?.endDate) }
    var allDay by rememberSaveable { mutableStateOf(original?.let(Dates::isAllDay) ?: true) }
    var start by rememberSaveable { mutableStateOf(original?.start ?: "09:00") }
    var end by rememberSaveable { mutableStateOf(original?.end) }
    var note by rememberSaveable { mutableStateOf(original?.note.orEmpty()) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val keepsMarker = original?.note?.startsWith("moodle:") == true

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
                .verticalScroll(rememberScrollState())
                .padding(start = LifeDimens.pad, end = LifeDimens.pad, bottom = LifeDimens.pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (original == null) R.string.calendar_add else R.string.calendar_edit),
                style = LifeType.sectionTitle,
                color = colors.text,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(200) },
                label = { Text(stringResource(R.string.calendar_field_title)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.calendar_field_date, Dates.parse(date)?.let { dayLabel(it, locale) } ?: date),
                    onClick = { picking = "date" },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = endDate?.let { stringResource(R.string.calendar_field_until, Dates.parse(it)?.let { day -> dayLabel(day, locale) } ?: it) }
                        ?: stringResource(R.string.calendar_field_one_day),
                    onClick = { picking = "end_date" },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LifeDimens.minTouch)
                    .toggleable(value = allDay, role = Role.Switch, onValueChange = { allDay = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.calendar_all_day), style = LifeType.body, color = colors.text, modifier = Modifier.weight(1f))
                Switch(checked = allDay, onCheckedChange = null)
            }
            if (!allDay) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        text = stringResource(R.string.calendar_field_start, start),
                        onClick = { picking = "start" },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = end?.let { stringResource(R.string.calendar_field_end, it) } ?: stringResource(R.string.calendar_field_no_end),
                        onClick = { picking = "end" },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (!keepsMarker) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500).replace("\n", " ") },
                    label = { Text(stringResource(R.string.calendar_field_note)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PrimaryButton(
                text = stringResource(R.string.capture_save),
                enabled = title.isNotBlank(),
                onClick = {
                    val lastDay = endDate?.takeIf { (Dates.parse(it) ?: LocalDate.MIN) > (Dates.parse(date) ?: LocalDate.MAX) }
                    onSave(
                        EventDraft(
                            title = title.trim(),
                            date = date,
                            endDate = lastDay,
                            start = start.takeIf { !allDay },
                            // An end is kept only when it is later (or on a later day).
                            end = end.takeIf { !allDay && (lastDay != null || it == null || it > start) },
                            note = note.trim().ifEmpty { null },
                        ),
                    )
                },
            )
        }
    }

    when (picking) {
        "date" -> DayPicker(date, onDismiss = { picking = null }) {
            date = it
            picking = null
        }
        "end_date" -> DayPicker(endDate ?: date, onDismiss = {
            endDate = null
            picking = null
        }) {
            endDate = it.takeIf { day -> day != date }
            picking = null
        }
        "start" -> ClockPicker(start, onDismiss = { picking = null }) {
            start = it
            picking = null
        }
        "end" -> ClockPicker(end ?: start, onDismiss = {
            end = null
            picking = null
        }) {
            end = it
            picking = null
        }
    }
}

/** A time of day as `HH:MM`. Dismissing the end pickers clears the optional value. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockPicker(initial: String, onDismiss: () -> Unit, dismissLabel: Int = R.string.action_clear, onPicked: (String) -> Unit) {
    val parts = initial.split(":").mapNotNull { it.toIntOrNull() }
    val state = rememberTimePickerState(initialHour = parts.getOrElse(0) { 9 }.coerceIn(0, 23), initialMinute = parts.getOrElse(1) { 0 }.coerceIn(0, 59), is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPicked("%02d:%02d".format(state.hour, state.minute)) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(dismissLabel)) } },
        text = { TimePicker(state = state) },
    )
}
