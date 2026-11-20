package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import dev.lifeos.app.ui.components.LifeCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.ui.components.MarkRow
import dev.lifeos.app.ui.components.RankMark
import dev.lifeos.app.ui.components.RowDivider
import dev.lifeos.app.ui.components.SampleNote
import dev.lifeos.app.ui.components.SectionHeader
import dev.lifeos.app.ui.components.TaskRow
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

@Composable
fun TodayScreen(onConnectionClick: () -> Unit, onOpenCoach: () -> Unit) {
    val doneStates = remember { mutableStateListOf(*SampleData.today.map { it.done }.toTypedArray()) }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.nav_today), onConnectionClick = onConnectionClick)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LifeDimens.pad)
                .padding(top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CoachCard(onClick = onOpenCoach)

            LifeCard {
                SectionHeader(title = stringResource(R.string.section_picks))
                SampleData.picks.forEachIndexed { index, task ->
                    if (index > 0) RowDivider()
                    MarkRow(
                        title = stringResource(task.title),
                        subtitle = task.reason?.let { stringResource(it) },
                    ) {
                        RankMark(index + 1)
                    }
                }
            }

            LifeCard {
                SectionHeader(
                    title = stringResource(R.string.section_today),
                    note = pluralStringResource(
                        R.plurals.section_today_note,
                        doneStates.size,
                        doneStates.size,
                        doneStates.count { it },
                    ),
                )
                TodayTasks(doneStates)
            }

            SampleNote()
        }
    }
}

/** How many of today's tasks the Today card shows without scrolling inside it. */
private const val VISIBLE_TODAY_ROWS = 3

/**
 * Today's tasks: the first three are visible, the rest is reached by scrolling inside the
 * card. The height is the measured height of the first three rows, so it follows the font size.
 */
@Composable
private fun TodayTasks(doneStates: MutableList<Boolean>) {
    val colors = LifeTheme.colors
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val rowHeights = remember { mutableStateMapOf<Int, Int>() }
    val tasks = SampleData.today
    val hidden = (tasks.size - VISIBLE_TODAY_ROWS).coerceAtLeast(0)
    // Until the rows are measured (first frame) an estimate is used.
    val maxHeight = if ((0 until minOf(VISIBLE_TODAY_ROWS, tasks.size)).all { it in rowHeights }) {
        with(density) { (0 until minOf(VISIBLE_TODAY_ROWS, tasks.size)).sumOf { rowHeights.getValue(it) }.toDp() }
    } else {
        61.dp * VISIBLE_TODAY_ROWS
    }

    Box {
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(scroll),
        ) {
            tasks.forEachIndexed { index, task ->
                Column(Modifier.onSizeChanged { rowHeights[index] = it.height }) {
                    if (index > 0) RowDivider()
                    TaskRow(
                        title = stringResource(task.title),
                        subtitle = stringResource(task.subtitle),
                        done = doneStates[index],
                        onToggle = { doneStates[index] = it },
                    )
                }
            }
        }
        // A soft fade over the last visible row while more tasks are below.
        if (scroll.canScrollForward) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(22.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, colors.surface))),
            )
        }
    }
    if (hidden > 0) {
        // The hint fades out at the end of the list but keeps its place, so the card does not jump.
        val more = scroll.canScrollForward || scroll.maxValue == 0
        Text(
            text = pluralStringResource(R.plurals.today_more, hidden, hidden),
            style = LifeType.small,
            color = colors.muted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .alpha(if (more) 1f else 0f),
            textAlign = TextAlign.Center,
        )
    }
}

/** The coach card: a grey surface inside the gradient ring. */
@Composable
private fun CoachCard(onClick: () -> Unit) {
    val colors = LifeTheme.colors
    val action = stringResource(R.string.coach_open)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClickLabel = action, role = Role.Button, onClick = onClick)
            .background(colors.gradient, RoundedCornerShape(20.dp))
            .padding(LifeDimens.ring)
            .background(colors.surface, RoundedCornerShape(17.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CoachTag()
            Text(text = stringResource(R.string.coach_label), style = LifeType.label, color = colors.text)
        }
        Text(text = stringResource(R.string.coach_text), style = LifeType.featured, color = colors.text)
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.text)) {
                    append(stringResource(R.string.coach_why))
                }
                append("  ")
                append(stringResource(R.string.coach_why_text))
            },
            style = LifeType.small,
            color = colors.muted,
        )
    }
}

/** The "Coach" label: a pill with a thin gradient ring. */
@Composable
private fun CoachTag() {
    val colors = LifeTheme.colors
    Text(
        text = stringResource(R.string.coach_tag),
        style = LifeType.label,
        color = colors.text,
        modifier = Modifier
            .background(colors.gradient, RoundedCornerShape(50))
            .padding(1.5.dp)
            .background(colors.surface, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 1.dp),
    )
}
