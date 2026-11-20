package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
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
fun TodayScreen(onConnectionClick: () -> Unit) {
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
            CoachCard()

            Column {
                SectionHeader(
                    title = stringResource(R.string.section_top_three),
                    note = stringResource(R.string.section_top_three_note),
                )
                SampleData.topThree.forEachIndexed { index, task ->
                    if (index > 0) RowDivider()
                    MarkRow(
                        title = stringResource(task.title),
                        subtitle = task.reason?.let { stringResource(it) },
                    ) {
                        RankMark(index + 1)
                    }
                }
            }

            Column {
                SectionHeader(
                    title = stringResource(R.string.section_today),
                    note = pluralStringResource(
                        R.plurals.section_today_note,
                        doneStates.size,
                        doneStates.size,
                        doneStates.count { it },
                    ),
                )
                SampleData.today.forEachIndexed { index, task ->
                    if (index > 0) RowDivider()
                    TaskRow(
                        title = stringResource(task.title),
                        subtitle = stringResource(task.subtitle),
                        done = doneStates[index],
                        onToggle = { doneStates[index] = it },
                    )
                }
            }

            SampleNote()
        }
    }
}

/** The coach card: a grey surface inside the gradient ring. */
@Composable
private fun CoachCard() {
    val colors = LifeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
