package dev.lifeos.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.RowDivider
import dev.lifeos.app.ui.components.SampleNote
import dev.lifeos.app.ui.components.SectionHeader
import dev.lifeos.app.ui.components.TaskRow
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

@Composable
fun TasksScreen(onConnectionClick: () -> Unit, onShowAll: () -> Unit) {
    val colors = LifeTheme.colors
    val tasks = SampleData.mostImportant
    val doneStates = remember { mutableStateListOf(*tasks.map { it.done }.toTypedArray()) }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.nav_tasks), onConnectionClick = onConnectionClick)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LifeDimens.pad)
                .padding(top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                SectionHeader(
                    title = stringResource(R.string.section_most_important),
                    note = stringResource(R.string.section_most_important_note),
                )
                tasks.forEachIndexed { index, task ->
                    if (index > 0) RowDivider()
                    TaskRow(
                        title = stringResource(task.title),
                        subtitle = stringResource(task.subtitle),
                        done = doneStates[index],
                        overdue = task.overdue && !doneStates[index],
                        onToggle = { doneStates[index] = it },
                    )
                }
            }
            PrimaryButton(
                text = stringResource(R.string.tasks_show_all, SampleData.OPEN_TASK_COUNT),
                onClick = onShowAll,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.tasks_footnote,
                    SampleData.OPEN_TASK_COUNT,
                    SampleData.OPEN_TASK_COUNT,
                    tasks.size,
                ),
                style = LifeType.small,
                color = colors.muted,
            )
            SampleNote()
        }
    }
}
