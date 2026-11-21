package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.ChatEndpoints
import dev.lifeos.app.data.CoachEndpoints
import dev.lifeos.app.data.CommandList
import dev.lifeos.app.data.skills
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import dev.lifeos.app.data.Reminder
import dev.lifeos.app.data.Reminders
import dev.lifeos.app.data.UiState
import dev.lifeos.app.data.notifyReplies
import dev.lifeos.app.ui.components.rememberLoad
import androidx.compose.runtime.remember
import dev.lifeos.app.data.SessionEdits
import dev.lifeos.app.data.SessionGroup
import dev.lifeos.app.data.SessionSummary
import dev.lifeos.app.ui.components.LoadModel
import dev.lifeos.app.ui.components.LocalSnackbar
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.launch

// The owner's own organisation of sessions (#74): groups, titles and pins. All of it is kept
// on the computer (`PATCH /sessions/{id}`, `/session-groups`); the phone only asks for changes.

/** Starts one change on the computer; see [rememberSessionChange]. */
internal typealias SessionChange = (suspend () -> Unit) -> Unit

/**
 * Runs one change on the computer. When it worked, every list of sessions is read again
 * ([LifeOsCore.sessionsChanged]); when it did not, a line says why and nothing has changed.
 * It belongs to the screen, not to a sheet: the sheet closes before the computer has answered.
 */
@Composable
internal fun rememberSessionChange(core: LifeOsCore): SessionChange {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val texts = SessionEdits.Problem.entries.associateWith { stringResource(it.textRes()) }
    return { change ->
        scope.launch {
            try {
                change()
                core.sessionsChanged()
            } catch (error: ApiException) {
                val problem = SessionEdits.problemOf(error)
                snackbar(texts.getValue(problem))
                // Whether it was done is not known: the lists show what the computer has.
                if (problem == SessionEdits.Problem.UNCERTAIN) core.sessionsChanged()
            }
        }
    }
}

private fun SessionEdits.Problem.textRes(): Int = when (this) {
    SessionEdits.Problem.NOT_REACHED -> R.string.session_edit_not_reached
    SessionEdits.Problem.UNCERTAIN -> R.string.session_edit_uncertain
    SessionEdits.Problem.GROUP_EXISTS -> R.string.session_edit_group_exists
    SessionEdits.Problem.GONE -> R.string.session_edit_gone
    SessionEdits.Problem.REFUSED -> R.string.session_edit_refused
    SessionEdits.Problem.FAILED -> R.string.session_edit_failed
}

/** Reads [model] again when sessions were changed from this phone since the screen last showed them. */
@Composable
internal fun RefreshOnSessionChange(core: LifeOsCore, model: LoadModel<*>) {
    val version by core.sessionsVersion.collectAsStateWithLifecycle()
    var seen by rememberSaveable { mutableStateOf(version) }
    LaunchedEffect(version) {
        if (version != seen) {
            seen = version
            model.refresh()
        }
    }
}

private enum class SessionSheetPage { MENU, RENAME, GROUP, REMINDERS, REMINDER, MCP }

/**
 * The sheet of the session [sessionId] (null: none is open), with what it needs from the
 * computer: the session as it is listed now, the owner's groups and the session's reminders.
 */
@Composable
internal fun SessionSheetHost(core: LifeOsCore, sessionId: String?, change: SessionChange, onDismiss: () -> Unit) {
    if (sessionId == null) return
    val locked by core.locked.collectAsStateWithLifecycle()
    val sessions = rememberLoad(core, ChatEndpoints.sessions) { it.sessions.isEmpty() }
    val reminders = rememberLoad(core, ChatEndpoints.reminders)
    val list = (sessions.state.collectAsStateWithLifecycle().value as? UiState.Content)?.data
    // The session itself is read fresh (#154): the list has only the newest hundred, and a
    // conversation that has only just begun is not in the one the screen has. Until the
    // computer has answered, the sheet shows what the list knows.
    val version by core.sessionsVersion.collectAsStateWithLifecycle()
    val fresh by produceState<SessionSummary?>(null, sessionId, version, locked) {
        if (!locked) {
            try {
                value = core.api.session(sessionId)
            } catch (_: ApiException) {
                // Not reached: the list's copy stays.
            }
        }
    }
    val session = fresh ?: list?.sessions?.firstOrNull { it.id == sessionId }
    // The coach conversation is read-only and has no folder of its own to configure (#157).
    val coachId = core.coach.state.collectAsStateWithLifecycle().value.sessionId
        ?: (rememberLoad(core, CoachEndpoints.chat).state.collectAsStateWithLifecycle().value as? UiState.Content)?.data?.sessionId
    val known = (reminders.state.collectAsStateWithLifecycle().value as? UiState.Content)?.data?.reminders.orEmpty()
    LaunchedEffect(sessionId) { reminders.refresh() }
    // Sheets are windows of their own: none may be open above the lock screen.
    if (session == null || locked) return
    SessionSheet(core, session, list?.groups.orEmpty(), Reminders.scheduled(known, session.id), change, onDismiss, coach = session.id == coachId)
}

/**
 * What can be done with one session: rename it, pin it, put it into a group. Only the
 * computer's own notes about the session change; the session itself is never touched.
 */
@Composable
internal fun SessionSheet(
    core: LifeOsCore,
    session: SessionSummary,
    groups: List<SessionGroup>,
    reminders: List<Reminder>,
    change: SessionChange,
    onDismiss: () -> Unit,
    /** The coach conversation's session: always read-only, so neither skills nor MCP servers apply. */
    coach: Boolean = false,
) {
    var page by rememberSaveable { mutableStateOf(SessionSheetPage.MENU) }
    // The reminder that is being changed; null on the reminder page: a new one.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    // Shown at once; the list follows when the computer has answered.
    var notify by remember(session.notifyReplies) { mutableStateOf(session.notifyReplies) }
    val title = session.shownTitle.ifEmpty { stringResource(R.string.sessions_untitled) }
    val api = core.api
    when (page) {
        SessionSheetPage.MENU -> SheetFrame(title, onDismiss) {
            SheetAction(LifeIcons.Pen, stringResource(R.string.session_rename)) { page = SessionSheetPage.RENAME }
            SheetAction(LifeIcons.Pin, stringResource(if (session.pinned) R.string.session_unpin else R.string.session_pin)) {
                onDismiss()
                change { api.patchSession(session.id, SessionEdits.pinned(!session.pinned)) }
            }
            SheetAction(LifeIcons.Folder, stringResource(R.string.session_move)) { page = SessionSheetPage.GROUP }
            // Reminders and reply notifications (#75).
            SheetAction(LifeIcons.Bell, stringResource(R.string.reminder_action), note = reminders.firstOrNull()?.let { reminderLabel(it) }) {
                page = SessionSheetPage.REMINDERS
            }
            SheetSwitch(stringResource(R.string.session_notify), stringResource(R.string.session_notify_note), checked = notify) { on ->
                notify = on
                change {
                    try {
                        api.patchSession(session.id, SessionEdits.notifyReplies(on))
                    } catch (error: ApiException) {
                        notify = !on
                        throw error
                    }
                }
            }
            if (!coach) {
                // The folder's own skills and commands, for this session only (#97).
                SkillsSwitch(core, session, change)
                // The MCP servers the folder declares (#153): listed, approved one by one, revoked.
                SheetAction(LifeIcons.Chip, stringResource(R.string.mcp_action), note = stringResource(R.string.mcp_action_note)) { page = SessionSheetPage.MCP }
            }
        }
        SessionSheetPage.MCP -> McpSheet(core, session, onDismiss)
        SessionSheetPage.REMINDERS -> RemindersSheet(
            reminders = reminders,
            onDismiss = onDismiss,
            onNew = {
                editing = null
                page = SessionSheetPage.REMINDER
            },
            onEdit = {
                editing = it.id
                page = SessionSheetPage.REMINDER
            },
        )
        SessionSheetPage.REMINDER -> {
            val reminder = reminders.firstOrNull { it.id == editing }
            ReminderEditSheet(
                reminder = reminder,
                onDismiss = onDismiss,
                onSave = { at, note, repeat ->
                    onDismiss()
                    change {
                        if (reminder == null) {
                            api.createReminder(Reminders.body(session.id, at, note, repeat))
                        } else {
                            api.updateReminder(reminder.id, Reminders.body(null, at, note, repeat))
                        }
                    }
                },
                onDelete = reminder?.let {
                    {
                        onDismiss()
                        change { api.deleteReminder(it.id) }
                    }
                },
            )
        }
        SessionSheetPage.RENAME -> NameSheet(
            title = stringResource(R.string.session_rename),
            label = stringResource(R.string.session_rename_field),
            initial = session.shownTitle,
            limit = SessionEdits.MAX_TITLE,
            // The owner's own title can be dropped again: the computer's title returns.
            reset = stringResource(R.string.session_rename_reset).takeIf { session.detectedTitle.isNotEmpty() && session.detectedTitle != session.title },
            onDismiss = onDismiss,
            onReset = {
                onDismiss()
                change { api.patchSession(session.id, SessionEdits.title(null)) }
            },
            onSave = { name ->
                onDismiss()
                change { api.patchSession(session.id, SessionEdits.title(name)) }
            },
        )
        SessionSheetPage.GROUP -> GroupChoiceSheet(
            current = session.group?.takeIf { id -> groups.any { it.id == id } },
            groups = groups,
            onDismiss = onDismiss,
            onPick = { groupId ->
                onDismiss()
                if (groupId != session.group) change { api.patchSession(session.id, SessionEdits.group(groupId)) }
            },
            onNew = { name ->
                onDismiss()
                // Two requests: when the second fails the new group exists and is empty.
                change { api.patchSession(session.id, SessionEdits.group(api.createGroup(name).id)) }
            },
        )
    }
}

/**
 * "Use this folder's skills and commands" for one session (#97), with what that means in plain
 * words, what the folder has, and whether the computer can use them right now. Off unless the
 * owner switches it on; the computer keeps it (`PATCH /sessions/{id}`).
 */
@Composable
private fun SkillsSwitch(core: LifeOsCore, session: SessionSummary, change: SessionChange) {
    val colors = LifeTheme.colors
    // Shown at once; the list follows when the computer has answered.
    var on by remember(session.skills) { mutableStateOf(session.skills) }
    // Counts the changes the computer has taken: what it says about the folder is asked again.
    var taken by remember { mutableStateOf(0) }
    val list by produceState<CommandList?>(null, session.id, taken) {
        value = try {
            core.api.commands(session.id)
        } catch (_: ApiException) {
            null
        }
    }
    SheetSwitch(stringResource(R.string.session_skills), stringResource(R.string.session_skills_note), checked = on) { wanted ->
        on = wanted
        change {
            try {
                core.api.patchSession(session.id, SessionEdits.skills(wanted))
                taken++
            } catch (error: ApiException) {
                on = !wanted
                throw error
            }
        }
    }
    val found = list ?: return
    val count = found.commands.size
    Text(
        text = if (count == 0) stringResource(R.string.session_skills_none) else pluralStringResource(R.plurals.session_skills_count, count, count),
        style = LifeType.small,
        color = colors.muted,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    if (on && found.switchedOn && !found.enabled) {
        Text(
            text = stringResource(R.string.session_skills_unavailable),
            style = LifeType.label,
            color = colors.warn,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private enum class GroupSheetPage { MENU, RENAME, DELETE }

/** One of the owner's groups: rename it or delete it (its sessions stay). */
@Composable
internal fun GroupSheet(core: LifeOsCore, group: SessionGroup, change: SessionChange, onDismiss: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(GroupSheetPage.MENU) }
    val api = core.api
    when (page) {
        GroupSheetPage.MENU -> SheetFrame(group.name, onDismiss) {
            SheetAction(LifeIcons.Pen, stringResource(R.string.group_rename)) { page = GroupSheetPage.RENAME }
            SheetAction(LifeIcons.Close, stringResource(R.string.group_delete)) { page = GroupSheetPage.DELETE }
        }
        GroupSheetPage.RENAME -> NameSheet(
            title = stringResource(R.string.group_rename),
            label = stringResource(R.string.group_name_field),
            initial = group.name,
            limit = SessionEdits.MAX_NAME,
            onDismiss = onDismiss,
            onSave = { name ->
                onDismiss()
                change { api.renameGroup(group.id, name) }
            },
        )
        GroupSheetPage.DELETE -> ConfirmDialog(
            title = stringResource(R.string.group_delete_title, group.name),
            text = stringResource(R.string.group_delete_text),
            confirm = stringResource(R.string.group_delete),
            onDismiss = onDismiss,
            onConfirm = {
                onDismiss()
                change { api.deleteGroup(group.id) }
            },
        )
    }
}

/** A new group, made from the Sessions screen (it starts empty). */
@Composable
internal fun NewGroupSheet(core: LifeOsCore, change: SessionChange, onDismiss: () -> Unit) {
    NameSheet(
        title = stringResource(R.string.group_new),
        label = stringResource(R.string.group_name_field),
        initial = "",
        limit = SessionEdits.MAX_NAME,
        onDismiss = onDismiss,
        onSave = { name ->
            onDismiss()
            change { core.api.createGroup(name) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetFrame(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = LifeType.sectionTitle,
                color = colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .semantics { heading() },
            )
            content()
        }
    }
}

/** One action of a sheet: an icon and what it does, a full row to tap. */
@Composable
internal fun SheetAction(icon: ImageVector, text: String, note: String? = null, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LifeDimens.minTouch)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.text, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = LifeType.body, color = colors.text)
            if (note != null) Text(note, style = LifeType.small, color = colors.muted)
        }
    }
}

/** One line of text to give or change: a title or a group's name. */
@Composable
private fun NameSheet(
    title: String,
    label: String,
    initial: String,
    limit: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    reset: String? = null,
    onReset: () -> Unit = {},
) {
    val colors = LifeTheme.colors
    var value by rememberSaveable { mutableStateOf(initial) }
    val clean = SessionEdits.line(value, limit)
    SheetFrame(title, onDismiss) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.replace("\n", " ").take(limit) },
            label = { Text(label) },
            singleLine = true,
            textStyle = LifeType.body,
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
                .padding(bottom = 8.dp),
        )
        PrimaryButton(text = stringResource(R.string.capture_save), enabled = clean.isNotEmpty() && clean != initial, onClick = { onSave(clean) })
        if (reset != null) SecondaryButton(text = reset, onClick = onReset, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Where a session belongs: one of the owner's groups, a new one, or none (then its folder). */
@Composable
private fun GroupChoiceSheet(current: String?, groups: List<SessionGroup>, onDismiss: () -> Unit, onPick: (String?) -> Unit, onNew: (String) -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    if (naming) {
        NameSheet(
            title = stringResource(R.string.group_new),
            label = stringResource(R.string.group_name_field),
            initial = "",
            limit = SessionEdits.MAX_NAME,
            onDismiss = onDismiss,
            onSave = onNew,
        )
        return
    }
    SheetFrame(stringResource(R.string.session_move), onDismiss) {
        Column(Modifier.selectableGroup()) {
            GroupChoice(stringResource(R.string.session_move_none), stringResource(R.string.session_move_none_note), selected = current == null) { onPick(null) }
            groups.forEach { group -> GroupChoice(group.name, null, selected = group.id == current) { onPick(group.id) } }
        }
        SheetAction(LifeIcons.Plus, stringResource(R.string.group_new)) { naming = true }
    }
}

@Composable
private fun GroupChoice(name: String, note: String?, selected: Boolean, onSelect: () -> Unit) {
    val colors = LifeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LifeDimens.minTouch)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The radio mark: a ring, filled when chosen.
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(if (selected) 2.dp else 1.5.dp, if (selected) colors.primary else colors.muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).background(colors.primary, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = LifeType.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = LifeType.small, color = colors.muted)
        }
    }
}
