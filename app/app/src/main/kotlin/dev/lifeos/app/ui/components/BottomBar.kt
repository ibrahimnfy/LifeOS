package dev.lifeos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 * Slim, icon-only bottom bar of direction D: Today, Chat, the capture button,
 * Tasks, More. The active item has a heavier icon and a short gradient bar.
 */
@Composable
fun BottomBar(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    onCapture: () -> Unit,
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
            NavItem(Tab.TODAY, selected, onSelect)
            NavItem(Tab.CHAT, selected, onSelect)
            CaptureButton(onCapture)
            NavItem(Tab.TASKS, selected, onSelect)
            NavItem(Tab.MORE, selected, onSelect)
        }
    }
}

private data class TabVisual(val label: Int, val icon: ImageVector, val activeIcon: ImageVector)

private fun Tab.visual(): TabVisual = when (this) {
    Tab.TODAY -> TabVisual(R.string.nav_today, LifeIcons.Today, LifeIcons.TodayActive)
    Tab.CHAT -> TabVisual(R.string.nav_chat, LifeIcons.Chat, LifeIcons.ChatActive)
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

/** Capture (+): gradient ring, a thin gap in the page colour, then a fill in the text colour. */
@Composable
private fun RowScope.CaptureButton(onClick: () -> Unit) {
    val colors = LifeTheme.colors
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(colors.gradient, RoundedCornerShape(16.dp))
                .padding(LifeDimens.ring)
                .background(colors.bg, RoundedCornerShape(13.dp))
                .padding(2.dp)
                .background(colors.text, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LifeIcons.Plus,
                contentDescription = stringResource(R.string.action_capture),
                tint = colors.bg,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
