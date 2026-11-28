package dev.lifeos.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.Dates
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.Note
import dev.lifeos.app.data.NoteList
import dev.lifeos.app.data.LocalItems
import dev.lifeos.app.data.NoteLists
import dev.lifeos.app.data.listAtMillis
import dev.lifeos.app.data.NoteRow
import dev.lifeos.app.data.NoteSummary
import dev.lifeos.app.data.OutboxItem
import dev.lifeos.app.data.Problem
import dev.lifeos.app.data.Reminders
import dev.lifeos.app.data.UiState
import dev.lifeos.app.data.UiStates
import dev.lifeos.app.ui.components.*
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.Locale

private val NOTE_KINDS = setOf(OutboxItem.Kind.NOTE, OutboxItem.Kind.NOTE_EDIT, OutboxItem.Kind.NOTE_DELETE)

/** Each search is a round trip through the relay: wait until the owner stops typing. */
private const val SEARCH_DELAY_MILLIS = 450L

/** A search: asked fresh each time; while the computer cannot be asked, the saved list is searched. */
private sealed interface SearchState {
    data object Running : SearchState
    data class Done(val notes: List<NoteSummary>, val fromSavedCopy: Boolean) : SearchState
    data class Failed(val problem: Problem, val status: Int) : SearchState
}

/** "Mon 3 Nov, 14:05" for a capture's moment, "Mon 3 Nov" for a topic note's date. */
fun noteMoment(at: String?, locale: Locale): String? {
    if (at.isNullOrBlank()) return null
    return try {
        val moment = LocalDateTime.parse(at.trim().replace(' ', 'T'))
        "${dayLabel(moment.toLocalDate(), locale)}, %02d:%02d".format(moment.hour, moment.minute)
    } catch (_: Exception) {
        Dates.parse(at.take(10))?.let { dayLabel(it, locale) } ?: at
    }
}

/**
 * Notes: the computer's notes (captures newest first, then topic notes) with a search field.
 * Notes captured here that have not been sent yet are on top; a capture's edit that is on its
 * way is shown at once with "Waiting to sync".
 */
@Composable
fun NotesScreen(
    core: LifeOsCore,
    onBack: () -> Unit,
    onConnectionClick: () -> Unit,
    onPair: () -> Unit,
    onOpenNote: (String) -> Unit,
    startQuery: String? = null,
    onQueryTaken: () -> Unit = {},
) {
    val colors = LifeTheme.colors
    val model = rememberLoad(core, Endpoints.notes) { list: NoteList -> list.notes.isEmpty() }
    val state by model.state.collectAsStateWithLifecycle()
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val confirmed by core.confirmed.items.collectAsStateWithLifecycle()
    val locked by core.locked.collectAsStateWithLifecycle()
    val reload by core.reload.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var search by remember { mutableStateOf<SearchState?>(null) }
    var searchAttempt by remember { mutableIntStateOf(0) }
    // A search the command bar asked for (#185): it fills the field once.
    LaunchedEffect(startQuery) {
        if (!startQuery.isNullOrBlank()) {
            query = startQuery.take(200)
            onQueryTaken()
        }
    }
    val justDeleted by core.justDeletedNotes.collectAsStateWithLifecycle()
    val hidden = NoteLists.hidden(outbox, justDeleted)
    val waiting = if (locked) emptyList() else NoteLists.waiting(outbox, hidden)
    val showUndo = LocalUndo.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val deletedText = stringResource(R.string.note_deleted)
    val busyText = stringResource(R.string.note_delete_busy)
    val editBusyText = stringResource(R.string.note_edit_busy)
    val needsFullText = stringResource(R.string.note_edit_needs_full)
    val savedText = stringResource(R.string.capture_saved)
    val waitingText = stringResource(R.string.capture_waiting)
    // Swipe left on a note (#202): it goes to the computer's trash, with a moment to undo.
    val deleteRow: (NoteRow) -> Unit = { row ->
        if (core.deleteNote(row.note.id, row.note.title ?: row.text)) {
            showUndo(deletedText) { core.undoDeleteNote(row.note.id) }
        } else {
            snackbar(busyText)
        }
    }
    // Swipe right: its editor, here in the list. The list has only the start of a long note,
    // so the whole text is asked of the computer first.
    var editing by remember { mutableStateOf<Pair<NoteSummary, String>?>(null) }
    val editRow: (NoteRow) -> Unit = { row ->
        if (!NoteLists.canEdit(row.note, outbox)) {
            snackbar(editBusyText)
        } else {
            val known = row.pending?.text ?: NoteLists.wholeText(row.note)
            if (known != null) {
                editing = row.note to known
            } else {
                scope.launch {
                    try {
                        editing = row.note to core.api.get(Endpoints.note(row.note.id)).first.text
                    } catch (_: ApiException) {
                        snackbar(needsFullText)
                    }
                }
            }
        }
    }
    // A note that still waits on this phone: its text is changed where it waits.
    var editingWaiting by remember { mutableStateOf<OutboxItem?>(null) }

    LaunchedEffect(query, reload, locked, searchAttempt) {
        val needle = query.trim()
        if (needle.isEmpty() || locked) {
            search = null
            return@LaunchedEffect
        }
        delay(SEARCH_DELAY_MILLIS)
        search = SearchState.Running
        search = try {
            SearchState.Done(core.api.get(Endpoints.noteSearch(needle)).first.notes, fromSavedCopy = false)
        } catch (error: ApiException) {
            val saved = core.saved(Endpoints.notes)?.first
            if (saved != null) {
                SearchState.Done(saved.notes.filter { NoteLists.matches(it, needle) }, fromSavedCopy = true)
            } else {
                SearchState.Failed(UiStates.problemOf(error), error.status)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.more_notes), onBack = onBack, onConnectionClick = onConnectionClick)
        RefreshBox(refreshing = state.isRefreshing, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LifeDimens.pad)
                    .padding(top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state !is UiState.NotPaired) SearchField(query) { query = it }
                OutboxNotices(core, NOTE_KINDS)
                if (waiting.isNotEmpty() && query.isBlank()) {
                    LifeCard {
                        SectionHeader(title = stringResource(R.string.outbox_waiting_title))
                        waiting.forEachIndexed { index, item ->
                            key(item.id) {
                                if (index > 0) RowDivider()
                                val name = LocalItems.PREFIX + item.id
                                // A note that waits here can be deleted too (#184): it never reaches the computer.
                                val delete = {
                                    if (core.deleteNote(name, item.text)) showUndo(deletedText) { core.undoDeleteNote(name) } else snackbar(busyText)
                                }
                                SwipeRow(
                                    right = SwipeAction(stringResource(R.string.swipe_edit), LifeIcons.Pen) { editingWaiting = item },
                                    left = SwipeAction(stringResource(R.string.swipe_delete), LifeIcons.Trash, danger = true, onAction = delete),
                                ) { actions ->
                                    MarkRow(
                                        title = item.text,
                                        subtitle = relativeTime(item.createdAtMillis),
                                        modifier = actions.clickable(role = Role.Button, onClickLabel = stringResource(R.string.swipe_edit)) { editingWaiting = item },
                                        trailing = {
                                            TopBarAction(LifeIcons.Close, stringResource(R.string.note_delete_label, item.text.take(60)), onClick = delete)
                                        },
                                    ) {
                                        Icon(LifeIcons.Clock, contentDescription = null, tint = colors.muted)
                                    }
                                }
                            }
                        }
                    }
                }
                val current = search
                if (query.isBlank() || state is UiState.NotPaired) {
                    // Notes the computer confirmed a moment ago come first, until its list has them.
                    val server = (state as? UiState.Content<NoteList>)?.data?.notes.orEmpty()
                    val added = LocalItems.notes(server, confirmed, state.listAtMillis)
                    if (state is UiState.Empty && added.isNotEmpty()) {
                        NoteCard(NoteLists.rows(added, outbox, hidden), onOpenNote, editRow, deleteRow)
                    } else {
                        StateHost(state, stringResource(R.string.notes_empty), onRetry = model::refresh, onPair = onPair, skeletonRows = 5) { list: NoteList ->
                            NoteCard(NoteLists.rows(added + list.notes, outbox, hidden), onOpenNote, editRow, deleteRow)
                        }
                    }
                } else {
                    when (current) {
                        null, SearchState.Running -> SkeletonCard(3)
                        is SearchState.Failed -> ErrorNotice(current.problem, current.status, onRetry = { searchAttempt++ })
                        is SearchState.Done -> {
                            if (current.fromSavedCopy) {
                                Text(stringResource(R.string.notes_search_saved), style = LifeType.small, color = colors.muted)
                            }
                            if (current.notes.isEmpty()) {
                                EmptyLine(stringResource(R.string.notes_search_empty))
                            } else {
                                NoteCard(NoteLists.rows(current.notes, outbox, hidden), onOpenNote, editRow, deleteRow)
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { (note, text) ->
        NoteEditSheet(
            text = text,
            max = NoteLists.maxText(note.id),
            onDismiss = { editing = null },
            onSave = { changed ->
                editing = null
                scope.launch { snackbar(if (core.editNote(note.id, changed)) savedText else waitingText) }
            },
        )
    }
    editingWaiting?.let { item ->
        NoteEditSheet(
            text = item.text,
            onDismiss = { editingWaiting = null },
            onSave = { changed ->
                editingWaiting = null
                // Still on the phone: it leaves with the new text. On its way already: not now.
                snackbar(if (core.reviseWaitingNote(item.id, changed)) waitingText else editBusyText)
            },
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val colors = LifeTheme.colors
    OutlinedTextField(
        value = query,
        onValueChange = { onChange(it.take(200)) },
        placeholder = { Text(stringResource(R.string.notes_search_hint), style = LifeType.body) },
        singleLine = true,
        textStyle = LifeType.body,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            { TopBarAction(LifeIcons.Close, stringResource(R.string.notes_search_clear)) { onChange("") } }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.divider,
            cursorColor = colors.primary,
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
            focusedPlaceholderColor = colors.muted,
            unfocusedPlaceholderColor = colors.muted,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The notes as rows. A tap opens a note; a swipe to the left deletes it (with Undo), a swipe
 * to the right opens its editor (#202): captures and topic notes alike.
 */
@Composable
private fun NoteCard(rows: List<NoteRow>, onOpen: (String) -> Unit, onEdit: (NoteRow) -> Unit, onDelete: (NoteRow) -> Unit) {
    val colors = LifeTheme.colors
    val locale = appLocale()
    val topicLabel = stringResource(R.string.notes_topic)
    val waitingLabel = stringResource(R.string.outbox_waiting)
    val editLabel = stringResource(R.string.swipe_edit)
    val deleteLabel = stringResource(R.string.swipe_delete)
    LifeCard {
        rows.forEachIndexed { index, row ->
            key(row.note.id) {
                if (index > 0) RowDivider(startIndent = 0.dp)
                val (title, rest) = NoteLists.headline(row.note.title, row.text)
                val meta = listOfNotNull(
                    noteMoment(row.note.at, locale),
                    topicLabel.takeIf { row.note.topic },
                    waitingLabel.takeIf { row.pending != null },
                ).joinToString(" · ")
                SwipeRow(
                    right = SwipeAction(editLabel, LifeIcons.Pen) { onEdit(row) },
                    left = SwipeAction(deleteLabel, LifeIcons.Trash, danger = true) { onDelete(row) },
                ) { actions ->
                    Column(
                        modifier = actions
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .clickable(role = Role.Button) { onOpen(row.note.id) }
                            .padding(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(title, style = LifeType.rowTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        rest?.let { Text(it, style = LifeType.body, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        if (meta.isNotEmpty()) Text(meta, style = LifeType.small, color = colors.muted)
                    }
                }
            }
        }
    }
}

/** What the note screen shows: the note from the computer, or the saved list's preview of it. */
private sealed interface NoteState {
    data object Loading : NoteState
    data object NotPaired : NoteState
    data class Shown(val note: Note, val savedAtMillis: Long?) : NoteState
    data class Failed(val problem: Problem, val status: Int) : NoteState
}

/**
 * One note in full. A capture and a topic note can be edited (the change goes through the
 * outbox) and deleted (to the computer's trash, with Undo; also from the top bar, #202).
 * After saving, the screen closes: the computer gives a capture a new id.
 */
@Composable
fun NoteScreen(core: LifeOsCore, noteId: String, onBack: () -> Unit, onConnectionClick: () -> Unit, onPair: () -> Unit) {
    val colors = LifeTheme.colors
    val locale = appLocale()
    val pairing by core.pairing.collectAsStateWithLifecycle()
    val locked by core.locked.collectAsStateWithLifecycle()
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.capture_saved)
    val waitingText = stringResource(R.string.capture_waiting)
    val deletedText = stringResource(R.string.note_deleted)
    val showUndo = LocalUndo.current
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<NoteState>(NoteState.Loading) }
    var editing by rememberSaveable { mutableStateOf(false) }
    // Its reminder, set, moved or removed by hand (#191): asked of the computer at once.
    var reminding by rememberSaveable { mutableStateOf(false) }
    val reminderSaved = stringResource(R.string.note_reminder_saved)
    val reminderRemoved = stringResource(R.string.note_reminder_removed)
    val reminderFailed = stringResource(R.string.note_reminder_failed)

    LaunchedEffect(noteId, pairing != null, locked, attempt) {
        if (pairing == null) {
            state = NoteState.NotPaired
            return@LaunchedEffect
        }
        if (locked) {
            state = NoteState.Loading
            return@LaunchedEffect
        }
        state = try {
            NoteState.Shown(core.api.get(Endpoints.note(noteId)).first, null)
        } catch (error: ApiException) {
            // The saved list has the start of every note: better than nothing while away.
            val saved = core.saved(Endpoints.notes)
            val summary = saved?.first?.notes?.firstOrNull { it.id == noteId }
            when {
                error.kind == ApiException.Kind.NOT_PAIRED || error.kind == ApiException.Kind.REFUSED -> NoteState.NotPaired
                summary != null && error.kind != ApiException.Kind.HTTP ->
                    NoteState.Shown(Note(summary.id, summary.kind, summary.title, summary.at, summary.preview), saved.second)
                else -> NoteState.Failed(UiStates.problemOf(error), error.status)
            }
        }
    }

    // Deleting is offered where it is seen at once (the top bar) and again below the note.
    val shownNote = (state as? NoteState.Shown)?.note?.takeIf { !it.filed }
    val busyText = stringResource(R.string.note_delete_busy)
    val delete: (Note) -> Unit = { note ->
        if (core.deleteNote(note.id, note.title ?: note.text)) {
            showUndo(deletedText) { core.undoDeleteNote(note.id) }
            onBack()
        } else {
            snackbar(busyText)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = stringResource(R.string.note_title),
            onBack = onBack,
            onConnectionClick = onConnectionClick,
            actions = {
                shownNote?.let { note -> TopBarAction(LifeIcons.Trash, stringResource(R.string.note_delete)) { delete(note) } }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LifeDimens.pad)
                .padding(top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val current = state) {
                NoteState.Loading -> SkeletonCard(4)
                NoteState.NotPaired -> NotPairedNotice(onPair)
                is NoteState.Failed -> {
                    val gone = current.status == 404
                    if (gone) EmptyLine(stringResource(R.string.note_gone)) else ErrorNotice(current.problem, current.status, onRetry = { attempt++ })
                }
                is NoteState.Shown -> {
                    val note = current.note
                    val summary = NoteSummary(note.id, note.kind, note.title, note.at, note.text)
                    val pending = NoteLists.pendingEdit(note.id, outbox)
                    // Read from the computer just now (not the saved copy): its reminder can be changed.
                    val live = current.savedAtMillis == null
                    val coming = note.reminder?.takeIf { it.scheduled }
                    current.savedAtMillis?.let { SavedCopyLine(it) }
                    OutboxNotices(core, setOf(OutboxItem.Kind.NOTE_EDIT))
                    LifeCard {
                        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            note.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = LifeType.sectionTitle, color = colors.text) }
                            val meta = listOfNotNull(
                                noteMoment(note.at, locale),
                                stringResource(R.string.notes_topic).takeIf { note.topic },
                                stringResource(R.string.notes_filed).takeIf { note.filed },
                                stringResource(R.string.outbox_waiting).takeIf { pending != null },
                            ).joinToString(" · ")
                            if (meta.isNotEmpty()) {
                                Text(meta, style = LifeType.small, color = colors.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            }
                            SelectionContainer { Text(pending?.text ?: note.text, style = LifeType.body, color = colors.text) }
                            // Its reminder (#185): set with the note in "+", by the command bar or here.
                            // One to come can be tapped to move or remove it (#191).
                            note.reminder?.let { reminder ->
                                val text = if (reminder.scheduled) {
                                    stringResource(R.string.note_reminder, reminderLabel(reminder))
                                } else {
                                    stringResource(R.string.note_reminder_fired, reminder.moment?.let { momentLabel(it.epochSecond, locale) } ?: reminder.at)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = if (reminder.scheduled && live) {
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.reminder_edit)) { reminding = true }
                                    } else {
                                        Modifier
                                    },
                                ) {
                                    Icon(LifeIcons.Bell, contentDescription = null, tint = colors.muted)
                                    Text(text, style = LifeType.small, color = colors.muted)
                                }
                            }
                        }
                    }
                    if (coming == null && live) SecondaryButton(text = stringResource(R.string.capture_remind), onClick = { reminding = true })
                    when {
                        note.filed -> Text(stringResource(R.string.note_filed_read_only, note.filedIn.orEmpty()), style = LifeType.small, color = colors.muted)
                        !note.editable -> Unit
                        // The saved copy has only the start of a long note: all of it is needed to edit.
                        current.savedAtMillis != null && pending == null && NoteLists.wholeText(summary) == null ->
                            Text(stringResource(R.string.note_edit_needs_full), style = LifeType.small, color = colors.muted)
                        NoteLists.canEdit(summary, outbox) -> PrimaryButton(text = stringResource(R.string.note_edit), onClick = { editing = true })
                        else -> Text(stringResource(R.string.note_edit_busy), style = LifeType.small, color = colors.muted)
                    }
                    // Captures and topic notes alike go to the computer's trash (#184), with a moment to
                    // undo. Always offered (#202): only in the seconds an edit is being sent the tap
                    // answers "try again in a moment". A filed capture is no note of the lists any
                    // more: there is nothing to delete here.
                    if (!note.filed) SecondaryButton(text = stringResource(R.string.note_delete), danger = true, onClick = { delete(note) })
                    if (reminding && live) {
                        // Sent at once, like a session's reminder: the computer keeps and fires it.
                        fun change(done: String, call: suspend () -> Unit) {
                            reminding = false
                            scope.launch {
                                try {
                                    call()
                                    snackbar(done)
                                } catch (_: ApiException) {
                                    snackbar(reminderFailed)
                                }
                                attempt++ // read the note again, with its reminder as it is now
                            }
                        }
                        ReminderEditSheet(
                            reminder = coming,
                            onDismiss = { reminding = false },
                            onSave = { at, _, repeat -> change(reminderSaved) { core.api.createReminder(Reminders.forNote(note.id, at, repeat)) } },
                            onDelete = coming?.let { { change(reminderRemoved) { core.api.deleteReminder(it.id) } } },
                            withNote = false,
                        )
                    }
                    if (editing) {
                        NoteEditSheet(
                            text = pending?.text ?: note.text,
                            max = NoteLists.maxText(note.id),
                            onDismiss = { editing = false },
                            onSave = { text ->
                                editing = false
                                scope.launch {
                                    snackbar(if (core.editNote(note.id, text)) savedText else waitingText)
                                    onBack()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
