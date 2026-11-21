package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.ChatEndpoints
import dev.lifeos.app.data.CoachEndpoints
import dev.lifeos.app.data.ChatState
import dev.lifeos.app.data.Folder
import dev.lifeos.app.data.Reminders
import dev.lifeos.app.data.SessionLists
import dev.lifeos.app.data.SessionSummary
import dev.lifeos.app.data.UiState
import dev.lifeos.app.ui.components.LifeCard
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.RefreshBox
import dev.lifeos.app.ui.components.RowDivider
import dev.lifeos.app.ui.components.SectionHeader
import dev.lifeos.app.ui.components.StateHost
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.components.TopBarAction
import dev.lifeos.app.ui.components.isRefreshing
import dev.lifeos.app.ui.components.rememberLoad
import dev.lifeos.app.ui.components.relativeIso
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

private const val RECENT = 3

/**
 * The Chat tab: the coach's conversation on top, the chat in progress, the most recent
 * sessions and "New chat". Once per app start it opens the conversation that was open last.
 */
@Composable
fun ChatListScreen(
    core: LifeOsCore,
    onConnectionClick: () -> Unit,
    onPair: () -> Unit,
    onOpenChat: () -> Unit,
    onSessions: () -> Unit,
    onOpenCoach: () -> Unit,
    onNewChat: () -> Unit,
) {
    val chat by core.chat.state.collectAsStateWithLifecycle()
    val locked by core.locked.collectAsStateWithLifecycle()
    val sessions = rememberLoad(core, ChatEndpoints.sessions) { it.sessions.isEmpty() }
    val state by sessions.state.collectAsStateWithLifecycle()
    val folders = rememberLoad(core, ChatEndpoints.folders) { it.folders.isEmpty() }
    val folderState by folders.state.collectAsStateWithLifecycle()
    RefreshOnSessionChange(core, sessions)
    // The coach conversation is the pinned entry on top (#114), not one of the recent sessions.
    val coachId = (rememberLoad(core, CoachEndpoints.chat).state.collectAsStateWithLifecycle().value as? UiState.Content)?.data?.sessionId
    // What can be done with a session (#74): the same sheet as on the Sessions screen.
    val change = rememberSessionChange(core)
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    SessionSheetHost(core, menuFor, change, onDismiss = { menuFor = null })
    // Which sessions have a reminder to come (#75).
    val reminders = rememberLoad(core, ChatEndpoints.reminders)
    RefreshOnSessionChange(core, reminders)
    val reminded = Reminders.sessionsWithOne((reminders.state.collectAsStateWithLifecycle().value as? UiState.Content)?.data?.reminders.orEmpty())

    // "The chat continues the same conversation": after a start the tab opens it directly.
    LaunchedEffect(locked, chat.sessionId) {
        if (!locked && core.chat.takeResume()) onOpenChat()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = stringResource(R.string.nav_chat),
            onConnectionClick = onConnectionClick,
            actions = {
                TopBarAction(LifeIcons.Sessions, stringResource(R.string.more_sessions), onClick = onSessions)
                TopBarAction(LifeIcons.Compose, stringResource(R.string.chat_new), onClick = onNewChat)
            },
        )
        RefreshBox(refreshing = state.isRefreshing, onRefresh = sessions::refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = LifeDimens.pad, end = LifeDimens.pad, top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state !is UiState.NotPaired) {
                    CoachPin(onOpenCoach)
                    if (chat.sessionId != null || chat.messages.isNotEmpty()) CurrentChat(chat, onOpenChat)
                }
                StateHost(state, stringResource(R.string.chat_list_empty), sessions::refresh, onPair) { list ->
                    LifeCard {
                        SectionHeader(title = stringResource(R.string.chat_recent))
                        val recent = SessionLists.newestFirst(list.sessions.filter { it.id != coachId }).take(RECENT)
                        recent.forEachIndexed { index, session ->
                            if (index > 0) RowDivider()
                            SessionRow(session, onMenu = { menuFor = session.id }, reminder = session.id in reminded) {
                                core.chat.open(session, memory = isMemory(folderState, session))
                                onOpenChat()
                            }
                        }
                        Text(
                            text = stringResource(R.string.chat_all_sessions),
                            style = LifeType.label,
                            color = LifeTheme.colors.link,
                            modifier = Modifier
                                .heightIn(min = LifeDimens.minTouch)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button, onClick = onSessions)
                                .padding(vertical = 14.dp, horizontal = 2.dp),
                        )
                    }
                }
                if (state !is UiState.NotPaired && state !is UiState.Loading) {
                    PrimaryButton(text = stringResource(R.string.chat_new), onClick = onNewChat)
                }
            }
        }
    }
}

/** Whether [session] runs in the memory folder, as far as the folder list is known. */
internal fun isMemory(folders: UiState<dev.lifeos.app.data.FolderList>, session: SessionSummary): Boolean =
    (folders as? UiState.Content)?.data?.folders?.any { it.isMemory && it.path == session.folder } == true

/** The coach's conversation, pinned on top with the gradient ring. */
@Composable
private fun CoachPin(onClick: () -> Unit) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.gradient, shape)
            .padding(LifeDimens.ring)
            .clip(RoundedCornerShape(17.dp))
            .background(colors.surface)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Thumb(featured = true) { Icon(LifeIcons.Spark, contentDescription = null, tint = colors.text, modifier = Modifier.size(22.dp)) }
            Text(
                text = stringResource(R.string.coach_conversation),
                style = LifeType.rowTitle,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            Icon(LifeIcons.Pin, contentDescription = stringResource(R.string.chat_pinned), tint = colors.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** The conversation the composer continues, also while its answer is still being written. */
@Composable
private fun CurrentChat(chat: ChatState, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    LifeCard {
        SectionHeader(title = stringResource(R.string.chat_current))
        val folder = chat.folder?.let { folderName(it) }.orEmpty()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Thumb { Text(folder.take(1).uppercase(), style = LifeType.rowTitle, color = colors.text) }
            Column(Modifier.weight(1f)) {
                Text(
                    text = chat.messages.firstOrNull { it.fromOwner }?.text ?: stringResource(R.string.chat_current_new),
                    style = LifeType.rowTitle,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = folder, style = LifeType.small.copy(fontFamily = FontFamily.Monospace), color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(LifeIcons.Chevron, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** The round mark of a session row; [featured] draws the gradient ring. */
@Composable
internal fun Thumb(featured: Boolean = false, content: @Composable () -> Unit) {
    val colors = LifeTheme.colors
    Box(
        modifier = Modifier
            .size(44.dp)
            .then(if (featured) Modifier.background(colors.gradient, CircleShape).padding(LifeDimens.ring) else Modifier)
            .background(colors.surface2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * One session: the first letter of its folder, its title, folder and last activity, and
 * "Working" while the computer is answering a message of it.
 */
@Composable
internal fun SessionRow(
    session: SessionSummary,
    showFolder: Boolean = true,
    onMenu: (() -> Unit)? = null,
    /** The session has a reminder to come (#75). */
    reminder: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LifeTheme.colors
    val menuLabel = stringResource(R.string.session_menu)
    val pinnedLabel = stringResource(R.string.chat_pinned)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            // What can be done with the session (#74): a long press, or the dots at the end of the row.
            .combinedClickable(role = Role.Button, onLongClickLabel = menuLabel.takeIf { onMenu != null }, onLongClick = onMenu, onClick = onClick)
            .semantics { if (session.pinned && onMenu != null) stateDescription = pinnedLabel }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (onMenu != null) 10.dp else 14.dp),
    ) {
        Thumb(featured = session.pinned) {
            Text(session.folderLabel.take(1).uppercase(), style = LifeType.rowTitle, color = colors.text)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = session.shownTitle.ifEmpty { stringResource(R.string.sessions_untitled) },
                style = LifeType.rowTitle,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showFolder) {
                    Text(
                        text = session.folderLabel,
                        style = LifeType.small.copy(fontFamily = FontFamily.Monospace),
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                relativeIso(session.lastActivity)?.let { time ->
                    Text(
                        text = if (showFolder) "· $time" else time,
                        style = LifeType.small,
                        color = colors.muted,
                        maxLines = 1,
                    )
                }
                if (reminder) {
                    Icon(LifeIcons.Bell, contentDescription = stringResource(R.string.reminder_has), tint = colors.muted, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (session.inUse) {
            Row(
                modifier = Modifier
                    .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(6.dp).background(colors.ok, CircleShape))
                Text(stringResource(R.string.sessions_working), style = LifeType.label, color = colors.text)
            }
        } else if (session.openElsewhere == true) {
            // Open in a terminal or the desktop app on the computer (#148): it cannot be continued from here now.
            Text(
                text = stringResource(R.string.sessions_open_elsewhere),
                style = LifeType.label,
                color = colors.muted,
                modifier = Modifier
                    .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        // With the dots at the end, the ring around the letter is the pin mark (the title needs the room).
        if (session.pinned && onMenu == null) {
            Icon(LifeIcons.Pin, contentDescription = pinnedLabel, tint = colors.muted, modifier = Modifier.size(18.dp))
        }
        if (onMenu != null) {
            Box(
                modifier = Modifier
                    .offset(x = 10.dp)
                    .size(LifeDimens.minTouch)
                    .clip(CircleShape)
                    .clickable(onClickLabel = menuLabel, role = Role.Button, onClick = onMenu)
                    .semantics { contentDescription = menuLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(LifeIcons.More, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * "New chat": where should it run? The memory folder (the default) or one of the allowed
 * folders, as the computer lists them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatSheet(core: LifeOsCore, onDismiss: () -> Unit, onPair: () -> Unit, onStart: (Folder) -> Unit) {
    val colors = LifeTheme.colors
    val folders = rememberLoad(core, ChatEndpoints.folders) { it.folders.isEmpty() }
    val state by folders.state.collectAsStateWithLifecycle()
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val title = stringResource(R.string.chat_folder_title)

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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = LifeType.featured, color = colors.text, modifier = Modifier.semantics { heading() })
            StateHost(state, stringResource(R.string.chat_folder_none), folders::refresh, onPair = onPair, savedLine = false) { list ->
                val selected = list.folders.firstOrNull { it.path == chosen }
                    ?: list.folders.firstOrNull { it.default }
                    ?: list.folders.first()
                Column(Modifier.selectableGroup().semantics { contentDescription = title }) {
                    list.folders.forEach { folder ->
                        FolderChoice(folder, selected = folder.path == selected.path) { chosen = folder.path }
                    }
                }
                PrimaryButton(text = stringResource(R.string.chat_folder_start), onClick = { onStart(selected) })
            }
        }
    }
}

@Composable
private fun FolderChoice(folder: Folder, selected: Boolean, onSelect: () -> Unit) {
    val colors = LifeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Thumb { Icon(LifeIcons.Folder, contentDescription = null, tint = colors.text, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f)) {
            Text(
                text = if (folder.isMemory) stringResource(R.string.chat_folder_memory) else folder.label,
                style = if (folder.isMemory) LifeType.rowTitle else LifeType.rowTitle.copy(fontFamily = FontFamily.Monospace),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(if (folder.isMemory) R.string.chat_folder_memory_sub else R.string.chat_folder_allowed),
                style = LifeType.small,
                color = colors.muted,
            )
        }
        // The radio mark: a ring, filled when chosen.
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .size(22.dp)
                .border(if (selected) 2.dp else 1.5.dp, if (selected) colors.primary else colors.muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).background(colors.primary, CircleShape))
        }
    }
}
