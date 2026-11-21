package dev.lifeos.app.ui.screens

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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.ChatEndpoints
import dev.lifeos.app.data.SessionLists
import dev.lifeos.app.data.SessionSummary
import dev.lifeos.app.ui.components.EmptyLine
import dev.lifeos.app.ui.components.LifeCard
import dev.lifeos.app.ui.components.RefreshBox
import dev.lifeos.app.ui.components.RowDivider
import dev.lifeos.app.ui.components.SectionHeader
import dev.lifeos.app.ui.components.StateHost
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.components.TopBarAction
import dev.lifeos.app.ui.components.isRefreshing
import dev.lifeos.app.ui.components.rememberLoad
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/**
 * The Claude Code sessions in the allowed folders, also those started in a terminal on the
 * computer: pinned ones first, then every folder with its sessions. Tapping one continues it
 * in Chat, in its own folder.
 */
@Composable
fun SessionsScreen(
    core: LifeOsCore,
    onBack: () -> Unit,
    onConnectionClick: () -> Unit,
    onPair: () -> Unit,
    onOpenChat: () -> Unit,
    onNewChat: () -> Unit,
) {
    val sessions = rememberLoad(core, ChatEndpoints.sessions) { it.sessions.isEmpty() }
    val state by sessions.state.collectAsStateWithLifecycle()
    val folders = rememberLoad(core, ChatEndpoints.folders) { it.folders.isEmpty() }
    val folderState by folders.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val open: (SessionSummary) -> Unit = { session ->
        core.chat.open(session, memory = isMemory(folderState, session))
        onOpenChat()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = stringResource(R.string.more_sessions),
            onBack = onBack,
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
                StateHost(state, stringResource(R.string.sessions_empty), sessions::refresh, onPair) { list ->
                    SearchField(query) { query = it }
                    val found = SessionLists.matching(list.sessions, query)
                    if (found.isEmpty()) EmptyLine(stringResource(R.string.sessions_none_found))
                    val pinned = SessionLists.pinned(found)
                    if (pinned.isNotEmpty()) {
                        LifeCard {
                            SectionHeader(title = stringResource(R.string.chat_pinned))
                            Rows(pinned, showFolder = true, onOpen = open)
                        }
                    }
                    val groups = SessionLists.byFolder(found)
                    if (groups.isNotEmpty()) {
                        SectionHeader(title = stringResource(R.string.sessions_by_folder), note = stringResource(R.string.sessions_automatic))
                    }
                    groups.forEach { group ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.semantics(mergeDescendants = true) {},
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(LifeIcons.Folder, contentDescription = null, tint = LifeTheme.colors.muted, modifier = Modifier.size(16.dp))
                                Text(
                                    text = group.label,
                                    style = LifeType.small.copy(fontFamily = FontFamily.Monospace),
                                    color = LifeTheme.colors.muted,
                                )
                            }
                            LifeCard { Rows(group.sessions, showFolder = false, onOpen = open) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Rows(sessions: List<SessionSummary>, showFolder: Boolean, onOpen: (SessionSummary) -> Unit) {
    sessions.forEachIndexed { index, session ->
        if (index > 0) RowDivider()
        SessionRow(session, showFolder = showFolder) { onOpen(session) }
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
