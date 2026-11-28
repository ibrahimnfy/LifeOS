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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.AssignmentMarks
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
import dev.lifeos.app.data.TaskTarget
import dev.lifeos.app.data.UiState
import dev.lifeos.app.ui.components.*
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.launch

private const val MOST_IMPORTANT = 5
private val TASK_KINDS = setOf(
    OutboxItem.Kind.TASK, OutboxItem.Kind.COMPLETE, OutboxItem.Kind.TASK_EDIT, OutboxItem.Kind.TASK_DELETE, OutboxItem.Kind.ASSIGNMENT_MARK,
)

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
fun OpenTaskRow(core: LifeOsCore, task: Task, tall: Boolean = false) {
    val undo = LocalUndo.current
    val open = LocalOpenTask.current
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val message = stringResource(R.string.tasks_done_message)
    val deletedMessage = stringResource(R.string.task_deleted)
    val removedMessage = stringResource(R.string.assignment_removed_message)
    val overdue = Dates.sectionOf(task.due, today()) == TaskSection.OVERDUE
    val pending = LocalItems.isWaiting(task) || TaskLists.pendingEdit(task.id, outbox) != null
    // The course or project: a small label in its colour (the same colour as on Courses).
    val looks = LocalLooks.current
    val parts = listOfNotNull(
        dueLabel(task.due).takeIf { !overdue || task.due != null },
        stringResource(R.string.outbox_waiting).takeIf { pending },
    )
    // A course assignment's task (#196): done and delete are the assignment's marks, so its
    // deadline leaves the lists with it and the course sync does not bring the task back.
    val assignment = TaskTarget.of(task, looks::match) as? TaskTarget.AssignmentPage
    val complete: () -> Unit = {
        if (assignment != null) {
            core.markAssignment(assignment.courseId, assignment.assignmentId, AssignmentMarks.DONE, null, task.title)
            undo(message) { core.undoMark(assignment.courseId, assignment.assignmentId, null, task.title) }
        } else {
            // Sent only after the undo time: until then nothing has left the phone.
            core.tick(task)
            undo(message) { core.undoTick(task) }
        }
    }
    val delete: () -> Unit = {
        if (assignment != null) {
            core.markAssignment(assignment.courseId, assignment.assignmentId, AssignmentMarks.REMOVED, null, task.title)
            undo(removedMessage) { core.undoMark(assignment.courseId, assignment.assignmentId, null, task.title) }
        } else {
            core.deleteTask(task)
            undo(deletedMessage) { core.undoDeleteTask(task) }
        }
    }
    // Swipe right: done; swipe left: delete (to the computer's trash), each with Undo (#197, #207).
    SwipeRow(
        right = SwipeAction(stringResource(R.string.swipe_done), LifeIcons.Check, onAction = complete),
        left = SwipeAction(stringResource(if (assignment != null) R.string.assignment_remove else R.string.swipe_delete), LifeIcons.Trash, danger = true, onAction = delete),
    ) { actions ->
        TaskRow(
            title = task.title,
            subtitle = parts.joinToString(" · ").ifEmpty { null },
            done = false,
            overdue = overdue,
            onToggle = { complete() },
            onOpen = { open(task) },
            look = looks.forTask(task),
            lookName = looks.nameFor(task),
            // The tasks screen's rows are 64 dp at least (#210).
            modifier = if (tall) actions.heightIn(min = 64.dp) else actions,
        )
    }
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
    val deletedMessage = stringResource(R.string.task_deleted)
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
        onDelete = {
            onClose()
            core.deleteTask(task)
            undo(deletedMessage) { core.undoDeleteTask(task) }
        },
        editFirst = true,
    )
}

/**
 * Tasks (#210, the owner's choice A of the mockups): a quick-add field on top, then the open
 * tasks in four groups (Overdue, Today, Upcoming, No date), one card per group under a header
 * that stays at the top while its group scrolls. Rows are 64 dp at least; a tap opens the
 * task's edit page, swipe right completes and swipe left deletes, each with Undo. Inside a
 * group the coach's order is kept when there is a plan; the plan's own list stays one tap
 * away ("In the coach's order").
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(core: LifeOsCore, onConnectionClick: () -> Unit, onPair: () -> Unit, onShowAll: () -> Unit) {
    val tasksModel = rememberLoad(core, Endpoints.tasks) { false }
    val planModel = rememberLoad(core, Endpoints.plan) { false }
    val tasksState by tasksModel.state.collectAsStateWithLifecycle()
    val planState by planModel.state.collectAsStateWithLifecycle()
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val confirmed by core.confirmed.items.collectAsStateWithLifecycle()
    val ticked by core.justTicked.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val savedText = stringResource(R.string.capture_saved)
    val waitingText = stringResource(R.string.capture_waiting)
    val colors = LifeTheme.colors
    val refresh = {
        tasksModel.refresh()
        planModel.refresh()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.nav_tasks), onConnectionClick = onConnectionClick)
        RefreshBox(refreshing = tasksState.isRefreshing || planState.isRefreshing, onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
            val state = tasksState
            val plan = (planState as? UiState.Content<CoachPlan>)?.data?.takeIf { it.exists }
            val ready = state is UiState.Content || state is UiState.Empty || plan != null
            // The plan's tasks first, in its order (they carry the coach's next step), then the
            // computer's other tasks, then this phone's new ones that neither has yet.
            val server = (state as? UiState.Content<TaskList>)?.data?.tasks.orEmpty()
            val known = TaskGroups.merged(plan?.order.orEmpty(), server)
            val listAt = if (state is UiState.Content) state.listAtMillis else planState.listAtMillis
            val open = TaskLists.open(LocalItems.withTasks(known, LocalItems.tasks(outbox, confirmed, listAt)), outbox, ticked)
            val groups = Dates.sections(open, today())
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = LifeDimens.pad, end = LifeDimens.pad, top = 10.dp, bottom = 24.dp),
            ) {
                item(key = "add") {
                    QuickAdd { title, due ->
                        scope.launch { snackbar(if (core.capture(note = false, text = title, due = due)) savedText else waitingText) }
                    }
                }
                item(key = "notices") {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutboxNotices(core, TASK_KINDS)
                        if (state is UiState.Content && state.fromCache && !state.refreshing) SavedCopyLine(state.savedAtMillis)
                    }
                }
                if (!ready) {
                    item(key = "state") {
                        StateHost(tasksState, stringResource(R.string.tasks_empty), onRetry = refresh, onPair = onPair, skeletonRows = 4) { _: TaskList -> }
                    }
                } else if (open.isEmpty()) {
                    item(key = "empty") { EmptyLine(stringResource(R.string.tasks_empty)) }
                }
                groups.forEach { (section, tasks) ->
                    stickyHeader(key = "head-${section.name}") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(colors.bg)
                                .padding(top = 14.dp, bottom = 8.dp, start = 4.dp, end = 4.dp)
                                .semantics(mergeDescendants = true) { heading() },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (section == TaskSection.OVERDUE) Box(Modifier.size(8.dp).background(colors.badge, androidx.compose.foundation.shape.CircleShape))
                            Text(section.title(), style = LifeType.sectionTitle, color = colors.text)
                            Text(tasks.size.toString(), style = LifeType.small, color = colors.muted)
                        }
                    }
                    item(key = "group-${section.name}") {
                        LifeCard(watermark = Watermark(LifeIcons.TasksMark, SectionTones.TASKS)) {
                            tasks.forEachIndexed { index, task ->
                                if (index > 0) RowDivider()
                                key(task.id) { OpenTaskRow(core, task, tall = true) }
                            }
                        }
                    }
                }
                if (plan != null && open.isNotEmpty()) {
                    item(key = "plan") {
                        Box(Modifier.padding(top = 14.dp)) { SecondaryButton(text = stringResource(R.string.tasks_coach_order), onClick = onShowAll) }
                    }
                }
            }
        }
    }
}

/** How the tasks screen puts its lists together (#210). */
object TaskGroups {
    /** The plan's tasks in its order, then those of [server] the plan does not have. */
    fun merged(plan: List<Task>, server: List<Task>): List<Task> {
        val planned = plan.mapTo(HashSet()) { it.id }
        return plan + server.filter { it.id !in planned }
    }

    /** The due date of a quick-add choice: today, tomorrow, a picked day, or none. */
    fun due(choice: String?, today: java.time.LocalDate): String? = when (choice) {
        null, "" -> null
        TODAY -> today.toString()
        TOMORROW -> today.plusDays(1).toString()
        else -> choice
    }

    const val TODAY = "today"
    const val TOMORROW = "tomorrow"
}

/**
 * The quick-add field (#210): the task's title, then Add (or the keyboard's Done). While a
 * title is being written, the date chips show under it: Today, Tomorrow, another day; none
 * picked means no date. Added through the outbox, so it works offline.
 */
@Composable
private fun QuickAdd(onAdd: (String, String?) -> Unit) {
    val colors = LifeTheme.colors
    var text by rememberSaveable { mutableStateOf("") }
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val add = {
        val title = text.trim().replace("\n", " ")
        if (title.isNotEmpty()) {
            onAdd(title.take(200), TaskGroups.due(choice, today()))
            text = ""
            choice = null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(colors.gradient, shape)
                .padding(LifeDimens.ring)
                .background(colors.surface, RoundedCornerShape(17.dp))
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = stringResource(R.string.tasks_add_hint)
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (text.isEmpty()) Text(label, style = LifeType.body, color = colors.muted)
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(200) },
                    textStyle = LifeType.body.copy(color = colors.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                )
            }
            TopBarAction(LifeIcons.Plus, stringResource(R.string.tasks_add), enabled = text.isNotBlank(), onClick = add)
        }
        if (text.isNotBlank()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.tasks_today), choice == TaskGroups.TODAY) { choice = if (choice == TaskGroups.TODAY) null else TaskGroups.TODAY }
                Chip(stringResource(R.string.tasks_add_tomorrow), choice == TaskGroups.TOMORROW) { choice = if (choice == TaskGroups.TOMORROW) null else TaskGroups.TOMORROW }
                val picked = choice?.takeIf { it != TaskGroups.TODAY && it != TaskGroups.TOMORROW }
                Chip(picked?.let { dueLabel(it) ?: it } ?: stringResource(R.string.tasks_add_pick), picked != null) { if (picked != null) choice = null else picking = true }
            }
        }
    }
    if (picking) {
        DayPicker(initial = null, onDismiss = { picking = false }) {
            choice = it
            picking = false
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
