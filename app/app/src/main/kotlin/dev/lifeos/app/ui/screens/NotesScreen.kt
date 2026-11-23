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

private val NOTE_KINDS = setOf(OutboxItem.Kind.NOTE, OutboxItem.Kind.NOTE_EDIT)

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
    val deletedText = stringResource(R.string.note_deleted)

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
                            if (index > 0) RowDivider()
                            val name = LocalItems.PREFIX + item.id
                            MarkRow(
                                title = item.text,
                                subtitle = relativeTime(item.createdAtMillis),
                                trailing = {
                                    // A note that waits here can be deleted too (#184): it never reaches the computer.
                                    TopBarAction(LifeIcons.Close, stringResource(R.string.note_delete_label, item.text.take(60))) {
                                        if (core.deleteNote(name)) showUndo(deletedText) { core.undoDeleteNote(name) }
                                    }
                                },
                            ) {
                                Icon(LifeIcons.Clock, contentDescription = null, tint = colors.muted)
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
                        NoteCard(NoteLists.rows(added, outbox, hidden), onOpenNote)
                    } else {
                        StateHost(state, stringResource(R.string.notes_empty), onRetry = model::refresh, onPair = onPair, skeletonRows = 5) { list: NoteList ->
                            NoteCard(NoteLists.rows(added + list.notes, outbox, hidden), onOpenNote)
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
                                NoteCard(NoteLists.rows(current.notes, outbox, hidden), onOpenNote)
                            }
                        }
                    }
                }
            }
        }
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

@Composable
private fun NoteCard(rows: List<NoteRow>, onOpen: (String) -> Unit) {
    val colors = LifeTheme.colors
    val locale = appLocale()
    val topicLabel = stringResource(R.string.notes_topic)
    val waitingLabel = stringResource(R.string.outbox_waiting)
    LifeCard {
        rows.forEachIndexed { index, row ->
            if (index > 0) RowDivider(startIndent = 0.dp)
            val (title, rest) = NoteLists.headline(row.note.title, row.text)
            val meta = listOfNotNull(
                noteMoment(row.note.at, locale),
                topicLabel.takeIf { !row.note.editable },
                waitingLabel.takeIf { row.pending != null },
            ).joinToString(" · ")
            Column(
                modifier = Modifier
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

/** What the note screen shows: the note from the computer, or the saved list's preview of it. */
private sealed interface NoteState {
    data object Loading : NoteState
    data object NotPaired : NoteState
    data class Shown(val note: Note, val savedAtMillis: Long?) : NoteState
    data class Failed(val problem: Problem, val status: Int) : NoteState
}

/**
 * One note in full. A capture can be edited (the change goes through the outbox); a topic note
 * is read-only here. After saving, the screen closes: the computer gives the note a new id.
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

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.note_title), onBack = onBack, onConnectionClick = onConnectionClick)
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
                    current.savedAtMillis?.let { SavedCopyLine(it) }
                    OutboxNotices(core, setOf(OutboxItem.Kind.NOTE_EDIT))
                    LifeCard {
                        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            note.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = LifeType.sectionTitle, color = colors.text) }
                            val meta = listOfNotNull(
                                noteMoment(note.at, locale),
                                stringResource(R.string.notes_topic).takeIf { !note.editable },
                                stringResource(R.string.outbox_waiting).takeIf { pending != null },
                            ).joinToString(" · ")
                            if (meta.isNotEmpty()) {
                                Text(meta, style = LifeType.small, color = colors.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            }
                            SelectionContainer { Text(pending?.text ?: note.text, style = LifeType.body, color = colors.text) }
                            // Its reminder (#185): set with the note in "+" or by the command bar.
                            note.reminder?.let { reminder ->
                                val moment = reminder.moment?.let { momentLabel(it.epochSecond, locale) } ?: reminder.at
                                val line = if (reminder.scheduled) R.string.note_reminder else R.string.note_reminder_fired
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(LifeIcons.Bell, contentDescription = null, tint = colors.muted)
                                    Text(stringResource(line, moment), style = LifeType.small, color = colors.muted)
                                }
                            }
                        }
                    }
                    when {
                        !note.editable -> Text(stringResource(R.string.note_topic_read_only), style = LifeType.small, color = colors.muted)
                        current.savedAtMillis != null && pending == null -> Text(stringResource(R.string.note_edit_needs_full), style = LifeType.small, color = colors.muted)
                        NoteLists.canEdit(summary, outbox) -> PrimaryButton(text = stringResource(R.string.note_edit), onClick = { editing = true })
                        else -> Text(stringResource(R.string.note_edit_busy), style = LifeType.small, color = colors.muted)
                    }
                    // Captures and topic notes alike go to the computer's trash (#184), with a moment to
                    // undo. Not while an edit is on its way: the line above says so already.
                    if (NoteLists.canDelete(note.id, outbox)) {
                        SecondaryButton(
                            text = stringResource(R.string.note_delete),
                            danger = true,
                            onClick = {
                                if (core.deleteNote(note.id)) {
                                    showUndo(deletedText) { core.undoDeleteNote(note.id) }
                                    onBack()
                                }
                            },
                        )
                    }
                    if (editing) {
                        NoteEditSheet(
                            text = pending?.text ?: note.text,
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
