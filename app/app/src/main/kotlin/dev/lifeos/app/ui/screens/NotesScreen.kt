package dev.lifeos.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.OutboxItem
import dev.lifeos.app.ui.components.*
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/**
 * Notes. The computer cannot list notes yet, so this screen shows only the notes captured on
 * this phone that still wait to be sent, and says where the others are.
 */
@Composable
fun NotesScreen(core: LifeOsCore, onBack: () -> Unit, onConnectionClick: () -> Unit) {
    val colors = LifeTheme.colors
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val locked by core.locked.collectAsStateWithLifecycle()
    val waiting = if (locked) emptyList() else outbox.filter { it.kind == OutboxItem.Kind.NOTE && it.state == OutboxItem.State.WAITING }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.more_notes), onBack = onBack, onConnectionClick = onConnectionClick)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LifeDimens.pad)
                .padding(top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.notes_explanation), style = LifeType.body, color = colors.muted)
            OutboxNotices(core, setOf(OutboxItem.Kind.NOTE))
            if (waiting.isEmpty()) {
                EmptyLine(stringResource(R.string.notes_none_waiting))
            } else {
                LifeCard {
                    SectionHeader(title = stringResource(R.string.outbox_waiting_title))
                    waiting.forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        MarkRow(title = item.text, subtitle = relativeTime(item.createdAtMillis)) {
                            Icon(LifeIcons.Clock, contentDescription = null, tint = colors.muted)
                        }
                    }
                }
            }
        }
    }
}
