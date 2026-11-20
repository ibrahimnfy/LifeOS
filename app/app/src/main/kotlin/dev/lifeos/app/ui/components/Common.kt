package dev.lifeos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import dev.lifeos.app.ConnectionState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/** An icon-only action in the top bar: 48 dp target, named for screen readers. */
@Composable
fun TopBarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    Box(
        modifier = Modifier
            .size(LifeDimens.minTouch)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) colors.text else colors.muted)
    }
}

/** The connection state every top bar shows; provided once at the root. */
val LocalConnection = compositionLocalOf { ConnectionState.NOT_PAIRED }

@StringRes
fun ConnectionState.descriptionRes(): Int = when (this) {
    ConnectionState.NOT_PAIRED -> R.string.connection_not_paired
    ConnectionState.CONNECTING -> R.string.connection_connecting
    ConnectionState.CONNECTED -> R.string.connection_connected
    ConnectionState.UNREACHABLE -> R.string.connection_unreachable
}

/**
 * Compact top bar: bold title on the left, the connection dot on the right.
 * [onBack] adds a back arrow; [onConnectionClick] shows the dot (it opens settings).
 */
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onConnectionClick: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LifeTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(start = if (onBack != null) 4.dp else LifeDimens.pad, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(LifeDimens.minTouch)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LifeIcons.Back, contentDescription = stringResource(R.string.action_back), tint = colors.text)
            }
        }
        Text(
            text = title,
            style = LifeType.screenTitle,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (actions != null) actions()
        if (onConnectionClick != null) {
            val description = stringResource(LocalConnection.current.descriptionRes())
            val action = stringResource(R.string.action_open_settings)
            Box(
                modifier = Modifier
                    .size(LifeDimens.minTouch)
                    .clip(CircleShape)
                    .clickable(onClickLabel = action, role = Role.Button, onClick = onConnectionClick)
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                StatusDot()
            }
        }
    }
}

/**
 * The connection dot: grey = not paired, hollow = connecting, green = connected,
 * amber = the computer cannot be reached. The state is also given as text wherever it matters.
 */
@Composable
fun StatusDot(modifier: Modifier = Modifier, state: ConnectionState = LocalConnection.current) {
    val colors = LifeTheme.colors
    val shape = when (state) {
        ConnectionState.NOT_PAIRED -> Modifier.background(colors.muted, CircleShape)
        ConnectionState.CONNECTING -> Modifier.border(2.dp, colors.muted, CircleShape)
        ConnectionState.CONNECTED -> Modifier.background(colors.ok, CircleShape)
        ConnectionState.UNREACHABLE -> Modifier.background(colors.warn, CircleShape)
    }
    Box(modifier.size(10.dp).then(shape))
}

/**
 * A card: the surface colour, rounded corners and a hairline border, so each section of a
 * screen is clearly its own block in both themes.
 */
@Composable
fun LifeCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(LifeDimens.hairline, colors.divider, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, note: String? = null) {
    val colors = LifeTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = LifeType.sectionTitle,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        if (note != null) {
            Text(text = note, style = LifeType.small, color = colors.muted)
        }
    }
}

/** A list row: a round mark on the left, title and optional second line, optional trailing content. */
@Composable
fun MarkRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    done: Boolean = false,
    overdue: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    mark: @Composable () -> Unit,
) {
    val colors = LifeTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) { mark() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (done) LifeType.body else LifeType.rowTitle,
                color = if (done) colors.muted else colors.text,
                textDecoration = if (done) TextDecoration.LineThrough else null,
            )
            if (subtitle != null || overdue) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (overdue) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(colors.badge, CircleShape),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.task_overdue),
                            style = LifeType.label,
                            color = colors.text,
                        )
                        if (subtitle != null) Spacer(Modifier.width(8.dp))
                    }
                    if (subtitle != null) {
                        Text(text = subtitle, style = LifeType.small, color = colors.muted)
                    }
                }
            }
        }
        if (trailing != null) trailing()
    }
}

/** Hairline divider between rows; it starts after the round mark. */
@Composable
fun RowDivider(modifier: Modifier = Modifier, startIndent: Dp = 58.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .heightIn(min = LifeDimens.hairline)
            .background(LifeTheme.colors.divider),
    )
}

/** Round mark with a number (rank) in it. */
@Composable
fun RankMark(rank: Int, modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    Box(
        modifier
            .defaultMinSize(40.dp, 40.dp)
            .background(colors.surface2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "%d".format(rank), style = LifeType.rowTitle, color = colors.text)
    }
}

/** Round mark with an icon in it (decorative: the row's title names it). */
@Composable
fun IconMark(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    Box(
        modifier
            .size(44.dp)
            .background(colors.surface2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.text)
    }
}

/** The round check of a task row. Purely visual; the whole row is the toggle. */
@Composable
fun CheckMark(done: Boolean, modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    Box(
        modifier = if (done) {
            modifier
                .size(24.dp)
                .background(colors.text, CircleShape)
        } else {
            modifier
                .size(24.dp)
                .border(1.5.dp, colors.muted, CircleShape)
        },
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(LifeIcons.Check, contentDescription = null, tint = colors.bg, modifier = Modifier.size(13.dp))
        }
    }
}

/** A task row with a round check; tapping anywhere on the row ticks it. */
@Composable
fun TaskRow(
    title: String,
    subtitle: String?,
    done: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    overdue: Boolean = false,
) {
    val state = stringResource(if (done) R.string.state_done else R.string.state_not_done)
    MarkRow(
        title = title,
        subtitle = subtitle,
        done = done,
        overdue = overdue,
        modifier = modifier
            .toggleable(value = done, role = Role.Checkbox, onValueChange = onToggle)
            .semantics { stateDescription = state },
    ) {
        CheckMark(done)
    }
}

/** Full-width rounded primary button (blue fill). */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LifeDimens.minTouch)
            .clip(shape)
            .background(if (enabled) colors.primary else colors.surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = LifeType.button,
            color = if (enabled) colors.onPrimary else colors.muted,
        )
    }
}

/** Full-width rounded secondary button (outlined). [danger] colours the text for destructive actions. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LifeDimens.minTouch)
            .clip(shape)
            .border(1.5.dp, colors.divider, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = LifeType.button,
            color = when {
                !enabled -> colors.muted
                danger -> colors.badge
                else -> colors.text
            },
        )
    }
}
