package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.CoachPlan
import dev.lifeos.app.data.Dates
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.OutboxItem
import dev.lifeos.app.data.Task
import dev.lifeos.app.data.TaskList
import dev.lifeos.app.data.TaskLists
import dev.lifeos.app.data.TaskSection
import dev.lifeos.app.data.UiState
import dev.lifeos.app.ui.components.*
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

private const val MOST_IMPORTANT = 5
private val TASK_KINDS = setOf(OutboxItem.Kind.TASK, OutboxItem.Kind.COMPLETE)

@Composable
fun TaskSection.title(): String = stringResource(
    when (this) {
        TaskSection.OVERDUE -> R.string.tasks_overdue
        TaskSection.TODAY -> R.string.tasks_today
        TaskSection.UPCOMING -> R.string.tasks_upcoming
        TaskSection.NO_DATE -> R.string.tasks_no_date
    },
)

/** A task of the computer's list: tapping the row ticks it; it leaves the list at once and can be undone for a moment. */
@Composable
fun OpenTaskRow(core: LifeOsCore, task: Task) {
    val undo = LocalUndo.current
    val message = stringResource(R.string.tasks_done_message)
    val overdue = Dates.sectionOf(task.due, today()) == TaskSection.OVERDUE
    val parts = listOfNotNull(dueLabel(task.due).takeIf { !overdue || task.due != null }, task.course ?: task.project)
    TaskRow(
        title = task.title,
        subtitle = parts.joinToString(" · ").ifEmpty { null },
        done = false,
        overdue = overdue,
        onToggle = {
            // The computer cannot reopen a task, so the tick is sent only after the undo time.
            core.tick(task)
            undo(message) { core.undoTick(task) }
        },
    )
}

/** A task captured on this phone that has not reached the computer yet: no tick, no id. */
@Composable
fun WaitingTaskRow(item: OutboxItem) {
    MarkRow(
        title = item.text,
        subtitle = listOfNotNull(stringResource(R.string.outbox_waiting), dueLabel(item.due)).joinToString(" · "),
    ) {
        Icon(LifeIcons.Clock, contentDescription = null, tint = LifeTheme.colors.muted)
    }
}

@Composable
private fun WaitingTasks(core: LifeOsCore) {
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val locked by core.locked.collectAsStateWithLifecycle()
    val waiting = if (locked) emptyList() else TaskLists.waiting(outbox)
    if (waiting.isEmpty()) return
    LifeCard {
        SectionHeader(title = stringResource(R.string.outbox_waiting_title))
        waiting.forEachIndexed { index, item ->
            if (index > 0) RowDivider()
            WaitingTaskRow(item)
        }
    }
}

@Composable
private fun TaskCard(core: LifeOsCore, title: String, tasks: List<Task>, note: String? = null) {
    LifeCard {
        SectionHeader(title = title, note = note)
        tasks.forEachIndexed { index, task ->
            if (index > 0) RowDivider()
            OpenTaskRow(core, task)
        }
    }
}

/**
 * Tasks. With a plan of the coach: "Most important" (the first five of its order) and
 * "Show all". Without a plan: the open tasks by date (overdue, today, upcoming, no date).
 */
@Composable
fun TasksScreen(core: LifeOsCore, onConnectionClick: () -> Unit, onPair: () -> Unit, onShowAll: () -> Unit) {
    val tasksModel = rememberLoad(core, Endpoints.tasks) { false }
    val planModel = rememberLoad(core, Endpoints.plan) { false }
    val tasksState by tasksModel.state.collectAsStateWithLifecycle()
    val planState by planModel.state.collectAsStateWithLifecycle()
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val ticked by core.justTicked.collectAsStateWithLifecycle()
    val refresh = {
        tasksModel.refresh()
        planModel.refresh()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.nav_tasks), onConnectionClick = onConnectionClick)
        RefreshBox(refreshing = tasksState.isRefreshing || planState.isRefreshing, onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LifeDimens.pad)
                    .padding(top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutboxNotices(core, TASK_KINDS)
                WaitingTasks(core)
                val plan = (planState as? UiState.Content<CoachPlan>)?.data?.takeIf { it.exists }
                if (plan != null) {
                    val ordered = TaskLists.open(plan.order, outbox, ticked)
                    if ((planState as UiState.Content).fromCache && !planState.isRefreshing) {
                        SavedCopyLine((planState as UiState.Content).savedAtMillis)
                    }
                    if (ordered.isEmpty()) {
                        EmptyLine(stringResource(R.string.tasks_empty))
                    } else {
                        TaskCard(core, stringResource(R.string.section_most_important), ordered.take(MOST_IMPORTANT), stringResource(R.string.section_most_important_note))
                        PrimaryButton(text = stringResource(R.string.tasks_show_all, ordered.size), onClick = onShowAll)
                        Text(
                            text = pluralStringResource(R.plurals.tasks_footnote, ordered.size, ordered.size, minOf(MOST_IMPORTANT, ordered.size)),
                            style = LifeType.small,
                            color = LifeTheme.colors.muted,
                        )
                    }
                } else {
                    StateHost(tasksState, stringResource(R.string.tasks_empty), onRetry = refresh, onPair = onPair, skeletonRows = 4) { list: TaskList ->
                        val open = TaskLists.open(list.tasks, outbox, ticked)
                        if (open.isEmpty()) EmptyLine(stringResource(R.string.tasks_empty))
                        Dates.sections(open, today()).forEach { (section, tasks) -> TaskCard(core, section.title(), tasks) }
                    }
                }
            }
        }
    }
}

/** "Show all": the whole list in the coach's order, with filters by date. */
@Composable
fun AllTasksScreen(core: LifeOsCore, onBack: () -> Unit, onConnectionClick: () -> Unit, onPair: () -> Unit) {
    val planModel = rememberLoad(core, Endpoints.plan) { false }
    val planState by planModel.state.collectAsStateWithLifecycle()
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val ticked by core.justTicked.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<TaskSection?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.tasks_all_title), onBack = onBack, onConnectionClick = onConnectionClick)
        RefreshBox(refreshing = planState.isRefreshing, onRefresh = planModel::refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = LifeDimens.pad),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TaskSection.entries.forEach { section ->
                        Chip(label = section.title(), selected = filter == section) { filter = if (filter == section) null else section }
                    }
                }
                StateHost(
                    planState,
                    stringResource(R.string.tasks_empty),
                    onRetry = planModel::refresh,
                    onPair = onPair,
                    modifier = Modifier.padding(horizontal = LifeDimens.pad),
                    skeletonRows = 6,
                ) { plan: CoachPlan ->
                    val today = today()
                    val shown = TaskLists.open(plan.order, outbox, ticked).filter { filter == null || Dates.sectionOf(it.due, today) == filter }
                    if (shown.isEmpty()) {
                        EmptyLine(stringResource(if (filter == null) R.string.tasks_empty else R.string.tasks_filter_empty))
                    } else {
                        LifeCard {
                            shown.forEachIndexed { index, task ->
                                if (index > 0) RowDivider()
                                OpenTaskRow(core, task)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A filter chip: selected or not is told by fill, weight and the selection state for screen readers. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    Box(
        modifier = Modifier
            .heightIn(min = LifeDimens.minTouch)
            .clip(RoundedCornerShape(50))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (selected) LifeType.label else LifeType.small,
            color = if (selected) colors.bg else colors.text,
            modifier = Modifier
                .background(if (selected) colors.text else colors.surface2, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
