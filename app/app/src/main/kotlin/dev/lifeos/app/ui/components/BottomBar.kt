package dev.lifeos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.nav.Tab
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme

/**
 * Slim, icon-only bottom bar of direction D: Today, Chat, Scores, Tasks, More. The active
 * item has a heavier icon and a short gradient bar. The middle place was the capture button
 * until #206; the command bar now opens from the home's own bar and from the Scores screen.
 */
@Composable
fun BottomBar(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LifeTheme.colors
    val label = stringResource(R.string.nav_main)
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.bg)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(LifeDimens.hairline)
                .background(colors.divider),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(LifeDimens.navHeight)
                .selectableGroup()
                .semantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab -> NavItem(tab, selected, onSelect) }
        }
    }
}

private data class TabVisual(val label: Int, val icon: ImageVector, val activeIcon: ImageVector)

private fun Tab.visual(): TabVisual = when (this) {
    Tab.TODAY -> TabVisual(R.string.nav_today, LifeIcons.Today, LifeIcons.TodayActive)
    Tab.CHAT -> TabVisual(R.string.nav_chat, LifeIcons.Chat, LifeIcons.ChatActive)
    Tab.SCORES -> TabVisual(R.string.nav_scores, LifeIcons.Scores, LifeIcons.ScoresActive)
    Tab.TASKS -> TabVisual(R.string.nav_tasks, LifeIcons.Tasks, LifeIcons.TasksActive)
    Tab.MORE -> TabVisual(R.string.nav_more, LifeIcons.More, LifeIcons.MoreActive)
}

@Composable
private fun RowScope.NavItem(tab: Tab, selected: Tab, onSelect: (Tab) -> Unit) {
    val colors = LifeTheme.colors
    val visual = tab.visual()
    val isSelected = tab == selected
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isSelected) visual.activeIcon else visual.icon,
            contentDescription = stringResource(visual.label),
            tint = colors.text,
            modifier = Modifier.size(26.dp),
        )
        if (isSelected) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .size(width = 18.dp, height = 3.dp)
                    .background(colors.gradient, RoundedCornerShape(2.dp)),
            )
        }
    }
}
