package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
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
import dev.lifeos.app.data.BridgeJson
import dev.lifeos.app.data.CoachPlan
import dev.lifeos.app.data.Dates
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.LocalItems
import dev.lifeos.app.data.Looks
import dev.lifeos.app.data.OutboxItem
import dev.lifeos.app.data.listAtMillis
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
import kotlinx.coroutines.launch

private const val MOST_IMPORTANT = 5
private val TASK_KINDS = setOf(OutboxItem.Kind.TASK, OutboxItem.Kind.COMPLETE, OutboxItem.Kind.TASK_EDIT)

@Composable
fun TaskSection.title(): String = stringResource(
    when (this) {
        TaskSection.OVERDUE -> R.string.tasks_overdue
        TaskSection.TODAY -> R.string.tasks_today
        TaskSection.UPCOMING -> R.string.tasks_upcoming
        TaskSection.NO_DATE -> R.string.tasks_no_date
    },
)

/**
 * A task of the lists (with its waiting edit applied, see [TaskLists.open]), also one made on
 * this phone that has not been sent yet (#167, marked "Waiting to sync"): the check ticks it (it
 * leaves the list at once and can be undone for a moment); the rest of the row opens it
 * ([LocalOpenTask]): a course assignment's task its assignment's page, any other task its card.
 */
@Composable
fun OpenTaskRow(core: LifeOsCore, task: Task) {
    val undo = LocalUndo.current
    val open = LocalOpenTask.current
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val message = stringResource(R.string.tasks_done_message)
    val overdue = Dates.sectionOf(task.due, today()) == TaskSection.OVERDUE
    val pending = LocalItems.isWaiting(task) || TaskLists.pendingEdit(task.id, outbox) != null
    // The course or project: a small label in its colour (the same colour as on Courses).
    val looks = LocalLooks.current
    val parts = listOfNotNull(
        dueLabel(task.due).takeIf { !overdue || task.due != null },
        stringResource(R.string.outbox_waiting).takeIf { pending },
    )
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
        onOpen = { open(task) },
        look = looks.forTask(task),
        lookName = looks.nameFor(task),
    )
}

/** The name on a task's label: its course as the course list calls it, or its project. */
fun Looks.nameFor(task: Task): String? =
    task.course?.takeIf { it.isNotBlank() }?.let { name -> match(name)?.name?.ifBlank { null } ?: name } ?: task.project?.takeIf { it.isNotBlank() }

/** Keeps a task's open card across turning the phone: the task as JSON. */
val TaskSaver: Saver<Task?, String> = Saver(
    save = { task -> task?.let { BridgeJson.encodeToString(Task.serializer(), it) } },
    restore = { text -> runCatching { BridgeJson.decodeFromString(Task.serializer(), text) }.getOrNull() },
)

/**
 * The card of a tapped task (#167), shown by the app's root above every screen: the task as it
 * was tapped. Edit and Mark as done are the lists' own (the outbox, the undo of a tick), so they
 * work offline and for a task that is still waiting to be sent. [scope] outlives the sheet, so
 * closing it does not cut a save short.
 */
@Composable
fun TaskCardSheet(core: LifeOsCore, task: Task, scope: kotlinx.coroutines.CoroutineScope, onClose: () -> Unit) {
    val undo = LocalUndo.current
    val snackbar = LocalSnackbar.current
    val looks = LocalLooks.current
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val message = stringResource(R.string.tasks_done_message)
    val savedText = stringResource(R.string.capture_saved)
    val waitingText = stringResource(R.string.capture_waiting)
    TaskSheet(
        title = task.title,
        due = task.due,
        overdue = Dates.sectionOf(task.due, today()) == TaskSection.OVERDUE,
        note = task.note,
        next = task.next,
        look = looks.forTask(task),
        lookName = looks.nameFor(task),
        waiting = LocalItems.isWaiting(task) || TaskLists.pendingEdit(task.id, outbox) != null,
        onDismiss = onClose,
        onSave = { title, due, note ->
            onClose()
            scope.launch { snackbar(if (core.editTask(task, title, due, note)) savedText else waitingText) }
        },
        onDone = {
            onClose()
            core.tick(task)
            undo(message) { core.undoTick(task) }
        },
    )
}

@Composable
private fun TaskCard(core: LifeOsCore, title: String, tasks: List<Task>, note: String? = null) {
    LifeCard(watermark = Watermark(LifeIcons.TasksMark, SectionTones.TASKS)) {
        SectionHeader(title = title, note = note, icon = LifeIcons.Tasks, tone = SectionTones.TASKS)
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
    val confirmed by core.confirmed.items.collectAsStateWithLifecycle()
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
                val plan = (planState as? UiState.Content<CoachPlan>)?.data?.takeIf { it.exists }
                if (plan != null) {
                    val ordered = TaskLists.open(plan.order, outbox, ticked)
                    // New tasks of this phone that the plan does not have yet: on top, at once.
                    val known = plan.order.map { it.id }.toSet()
                    val added = TaskLists.open(LocalItems.tasks(outbox, confirmed, planState.listAtMillis), outbox, ticked).filter { it.id !in known }
                    if ((planState as UiState.Content).fromCache && !planState.isRefreshing) {
                        SavedCopyLine((planState as UiState.Content).savedAtMillis)
                    }
                    if (added.isNotEmpty()) TaskCard(core, stringResource(R.string.tasks_just_added), added)
                    if (ordered.isEmpty() && added.isEmpty()) {
                        EmptyLine(stringResource(R.string.tasks_empty))
                    } else if (ordered.isNotEmpty()) {
                        TaskCard(core, stringResource(R.string.section_most_important), ordered.take(MOST_IMPORTANT), stringResource(R.string.section_most_important_note))
                        PrimaryButton(text = stringResource(R.string.tasks_show_all, ordered.size), onClick = onShowAll)
                        Text(
                            text = pluralStringResource(R.plurals.tasks_footnote, ordered.size, ordered.size, minOf(MOST_IMPORTANT, ordered.size)),
                            style = LifeType.small,
                            color = LifeTheme.colors.muted,
                        )
                    }
                } else if (tasksState is UiState.Content || tasksState is UiState.Empty) {
                    // The computer's tasks with this phone's new ones, by date, each where it belongs.
                    val state = tasksState
                    if (state is UiState.Content && state.fromCache && !state.refreshing) SavedCopyLine(state.savedAtMillis)
                    val server = (state as? UiState.Content<TaskList>)?.data?.tasks.orEmpty()
                    val open = TaskLists.open(LocalItems.withTasks(server, LocalItems.tasks(outbox, confirmed, state.listAtMillis)), outbox, ticked)
                    if (open.isEmpty()) EmptyLine(stringResource(R.string.tasks_empty))
                    Dates.sections(open, today()).forEach { (section, tasks) -> TaskCard(core, section.title(), tasks) }
                } else {
                    StateHost(tasksState, stringResource(R.string.tasks_empty), onRetry = refresh, onPair = onPair, skeletonRows = 4) { _: TaskList -> }
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
    val confirmed by core.confirmed.items.collectAsStateWithLifecycle()
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
                    // This phone's new tasks first (the plan does not have them yet), then the plan's order.
                    val added = LocalItems.tasks(outbox, confirmed, planState.listAtMillis)
                    val all = LocalItems.withTasks(added, plan.order)
                    val shown = TaskLists.open(all, outbox, ticked).filter { filter == null || Dates.sectionOf(it.due, today) == filter }
                    if (shown.isEmpty()) {
                        EmptyLine(stringResource(if (filter == null) R.string.tasks_empty else R.string.tasks_filter_empty))
                    } else {
                        LifeCard(watermark = Watermark(LifeIcons.TasksMark, SectionTones.TASKS)) {
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
