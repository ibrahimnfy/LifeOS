package dev.lifeos.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.ui.components.IconMark
import dev.lifeos.app.ui.components.MarkRow
import dev.lifeos.app.ui.components.RowDivider
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.nav.MoreSection
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

@StringRes
fun MoreSection.titleRes(): Int = when (this) {
    MoreSection.SESSIONS -> R.string.more_sessions
    MoreSection.NOTES -> R.string.more_notes
    MoreSection.PROJECTS -> R.string.more_projects
    MoreSection.CALENDAR -> R.string.more_calendar
    MoreSection.COURSES -> R.string.more_courses
    MoreSection.ACTIVITY -> R.string.more_activity
}

private fun MoreSection.icon(): ImageVector = when (this) {
    MoreSection.SESSIONS -> LifeIcons.Sessions
    MoreSection.NOTES -> LifeIcons.Notes
    MoreSection.PROJECTS -> LifeIcons.Projects
    MoreSection.CALENDAR -> LifeIcons.Calendar
    MoreSection.COURSES -> LifeIcons.Courses
    MoreSection.ACTIVITY -> LifeIcons.Activity
}

@Composable
fun MoreScreen(
    onConnectionClick: () -> Unit,
    onOpenSection: (MoreSection) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.nav_more), onConnectionClick = onConnectionClick)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 24.dp),
        ) {
            MoreSection.entries.forEach { section ->
                MoreRow(stringResource(section.titleRes()), section.icon()) { onOpenSection(section) }
                RowDivider(Modifier.padding(horizontal = LifeDimens.pad))
            }
            MoreRow(stringResource(R.string.more_settings), LifeIcons.Settings, onOpenSettings)
        }
    }
}

@Composable
private fun MoreRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    MarkRow(
        title = title,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LifeDimens.pad),
        trailing = {
            Icon(
                LifeIcons.Chevron,
                contentDescription = null,
                tint = LifeTheme.colors.muted,
                modifier = Modifier.size(20.dp),
            )
        },
    ) {
        IconMark(icon)
    }
}

/** A screen that is not built yet: its title and one line. */
@Composable
fun PlaceholderScreen(title: String, onBack: () -> Unit, onConnectionClick: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = title, onBack = onBack, onConnectionClick = onConnectionClick)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(LifeDimens.pad),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.coming_later),
                style = LifeType.body,
                color = LifeTheme.colors.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
