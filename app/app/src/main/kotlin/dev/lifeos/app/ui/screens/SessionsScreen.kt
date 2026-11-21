package dev.lifeos.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.ChatEndpoints
import dev.lifeos.app.data.CoachEndpoints
import dev.lifeos.app.data.FolderKinds
import dev.lifeos.app.data.Looks
import dev.lifeos.app.data.Reminders
import dev.lifeos.app.data.SessionFolder
import dev.lifeos.app.data.SessionLists
import dev.lifeos.app.data.SessionSummary
import dev.lifeos.app.data.UiState
import dev.lifeos.app.ui.components.EmptyLine
import dev.lifeos.app.ui.components.LifeCard
import dev.lifeos.app.ui.components.RefreshBox
import dev.lifeos.app.ui.components.RowDivider
import dev.lifeos.app.ui.components.SectionHeader
import dev.lifeos.app.ui.components.StateHost
import dev.lifeos.app.ui.components.TileGrid
import dev.lifeos.app.ui.components.ToneTile
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.components.TopBarAction
import dev.lifeos.app.ui.components.isRefreshing
import dev.lifeos.app.ui.components.relativeIso
import dev.lifeos.app.ui.components.rememberLoad
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/**
 * The Claude Code sessions in the allowed folders and the blank chats, also those started in a
 * terminal on the computer (#168): the pinned ones first, then one coloured tile per folder in
 * the style of the Courses tiles: all blank chats are one tile, the coach conversation has one
 * of its own. Tapping a tile lists that folder's conversations (pinned first); the coach's
 * tile opens the coach conversation. Tapping a conversation continues it in Chat, in its own
 * folder. A search lists every match across the folders.
 */
@Composable
fun SessionsScreen(
    core: LifeOsCore,
    onBack: () -> Unit,
    onConnectionClick: () -> Unit,
    onPair: () -> Unit,
    onOpenChat: () -> Unit,
    onNewChat: () -> Unit,
    onOpenCoach: () -> Unit = {},
) {
    val sessions = rememberLoad(core, ChatEndpoints.sessions) { it.sessions.isEmpty() }
    val state by sessions.state.collectAsStateWithLifecycle()
    val folders = rememberLoad(core, ChatEndpoints.folders) { it.folders.isEmpty() }
    val folderState by folders.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    // The folder whose conversations are listed (its tile's key); null: the tiles.
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    // The owner's own organisation (#74): which session's sheet is open.
    val change = rememberSessionChange(core)
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    RefreshOnSessionChange(core, sessions)
    // Which sessions have a reminder to come (#75).
    val reminders = rememberLoad(core, ChatEndpoints.reminders)
    RefreshOnSessionChange(core, reminders)
    val reminded = Reminders.sessionsWithOne((reminders.state.collectAsStateWithLifecycle().value as? UiState.Content)?.data?.reminders.orEmpty())
    // The coach conversation is a session too (#114), but it has its own screen and its own rules.
    val coachId = core.coach.state.collectAsStateWithLifecycle().value.sessionId
        ?: (rememberLoad(core, CoachEndpoints.chat).state.collectAsStateWithLifecycle().value as? UiState.Content)?.data?.sessionId
    val open: (SessionSummary) -> Unit = { session ->
        if (session.id == coachId || session.kind == FolderKinds.COACH) {
            onOpenCoach()
        } else {
            core.chat.open(session, memory = isMemory(folderState, session))
            onOpenChat()
        }
    }
    val list = (state as? UiState.Content)?.data
    val tile = openKey?.let { key -> list?.let { SessionLists.tiles(it) }?.firstOrNull { it.key == key } }
    // Back from a folder's list returns to the tiles first.
    BackHandler(enabled = openKey != null) { openKey = null }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = tile?.let { tileName(it) } ?: stringResource(R.string.more_sessions),
            onBack = { if (openKey != null) openKey = null else onBack() },
            onConnectionClick = onConnectionClick,
            actions = { TopBarAction(LifeIcons.Compose, stringResource(R.string.chat_new), onClick = onNewChat) },
        )
        RefreshBox(refreshing = state.isRefreshing, onRefresh = sessions::refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = LifeDimens.pad, end = LifeDimens.pad, top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StateHost(state, stringResource(R.string.sessions_empty), sessions::refresh, onPair) { listed ->
                    val menu: (SessionSummary) -> Unit = { menuFor = it.id }
                    val key = openKey
                    if (key != null) {
                        // One folder: its conversations, the pinned ones first.
                        val inside = SessionLists.inFolder(listed.sessions, key)
                        if (inside.isEmpty()) {
                            EmptyLine(stringResource(R.string.sessions_folder_empty))
                        } else {
                            LifeCard { Rows(inside, showFolder = false, reminded, coachId, onOpen = open, onMenu = menu) }
                        }
                        // The computer lists at most 200 sessions; the tile counts all of them.
                        if (tile != null && tile.count > inside.size && inside.isNotEmpty()) {
                            Text(stringResource(R.string.sessions_folder_partial, inside.size, tile.count), style = LifeType.small, color = LifeTheme.colors.muted)
                        }
                    } else {
                        SearchField(query) { query = it }
                        if (query.isNotBlank()) {
                            // A search lists every match, whichever folder it is in.
                            val found = SessionLists.newestFirst(SessionLists.matching(listed.sessions, query))
                            if (found.isEmpty()) {
                                EmptyLine(stringResource(R.string.sessions_none_found))
                            } else {
                                LifeCard { Rows(found, showFolder = true, reminded, coachId, onOpen = open, onMenu = menu) }
                            }
                        } else {
                            val pinned = SessionLists.pinned(listed.sessions)
                            if (pinned.isNotEmpty()) {
                                LifeCard {
                                    SectionHeader(title = stringResource(R.string.chat_pinned))
                                    Rows(pinned, showFolder = true, reminded, coachId, onOpen = open, onMenu = menu)
                                }
                            }
                            val tiles = SessionLists.tiles(listed)
                            if (tiles.isNotEmpty()) {
                                SectionHeader(title = stringResource(R.string.sessions_by_folder), icon = LifeIcons.Folder, iconTile = true)
                                TileGrid(tiles) { item, shape, modifier ->
                                    FolderTile(item, shape, modifier) {
                                        // The coach's tile is its conversation (#114); every other tile lists its folder.
                                        if (item.kind == FolderKinds.COACH) onOpenCoach() else openKey = item.key
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Sheets are windows of their own; the host shows none above the lock screen.
    SessionSheetHost(core, menuFor, change, onDismiss = { menuFor = null })
}

/** A folder's name as its tile and the top bar show it: "Chats" and "Coach" are the app's own words. */
@Composable
private fun tileName(tile: SessionFolder): String = when (tile.kind) {
    FolderKinds.CHAT -> stringResource(R.string.sessions_tile_chats)
    FolderKinds.COACH -> stringResource(R.string.sessions_tile_coach)
    else -> tile.name.ifBlank { tile.path.trimEnd('/').substringAfterLast('/') }
}

/** One folder as a P3 tile in its stable colour (#168): its kind's icon, its name, how many conversations and when the last one was. */
@Composable
private fun FolderTile(tile: SessionFolder, shape: Shape, modifier: Modifier, onClick: () -> Unit) {
    val count = if (tile.count > 0) pluralStringResource(R.plurals.sessions_tile_count, tile.count, tile.count) else stringResource(R.string.sessions_tile_none)
    val line = relativeIso(tile.lastActivity)?.let { stringResource(R.string.sessions_tile_line, count, it) } ?: count
    ToneTile(Looks.folder(tile.colour), tileName(tile), line, shape, onClick, modifier, icon = folderIcon(tile.kind))
}

@Composable
private fun Rows(
    sessions: List<SessionSummary>,
    showFolder: Boolean,
    reminded: Set<String>,
    coachId: String?,
    onOpen: (SessionSummary) -> Unit,
    onMenu: (SessionSummary) -> Unit,
) {
    sessions.forEachIndexed { index, session ->
        if (index > 0) RowDivider()
        // The coach's session is not the owner's to rename or pin: no sheet.
        val coach = session.id == coachId || session.kind == FolderKinds.COACH
        val menu: (() -> Unit)? = if (coach) null else ({ onMenu(session) })
        SessionRow(session, showFolder = showFolder, onMenu = menu, reminder = session.id in reminded) { onOpen(session) }
    }
}

/** Filters the list on the phone: by title and folder name. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val colors = LifeTheme.colors
    val hint = stringResource(R.string.sessions_search)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LifeDimens.minTouch)
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(LifeIcons.Search, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(text = hint, style = LifeType.body, color = colors.muted)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = LifeType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = hint },
            )
        }
    }
}
