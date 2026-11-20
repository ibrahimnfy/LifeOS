package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.Agenda
import dev.lifeos.app.data.CoachMessage
import dev.lifeos.app.data.CoachPlan
import dev.lifeos.app.data.CoachRerun
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.OutboxItem
import dev.lifeos.app.data.Task
import dev.lifeos.app.data.TaskLists
import dev.lifeos.app.data.TodayReload
import dev.lifeos.app.data.UiState
import dev.lifeos.app.ui.components.*
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/** How many of today's tasks the Today card shows without scrolling inside it. */
private const val VISIBLE_TODAY_ROWS = 3

/**
 * Today: the coach card (one message of the morning's plan, another one each time the app is
 * opened), "Assistant's picks" (the plan's top three) and "Today" (overdue and today's tasks).
 * Pulling down reads the plan and the agenda again; only the coach card's "Rerun" asks the
 * computer for a new plan (a model run).
 */
@Composable
fun TodayScreen(core: LifeOsCore, onConnectionClick: () -> Unit, onOpenCoach: () -> Unit, onPair: () -> Unit) {
    val planModel = rememberLoad(core, Endpoints.plan) { false }
    val agendaModel = rememberLoad(core, Endpoints.agenda) { false }
    val planState by planModel.state.collectAsStateWithLifecycle()
    val agendaState by agendaModel.state.collectAsStateWithLifecycle()
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val ticked by core.justTicked.collectAsStateWithLifecycle()
    val rerun by core.coachRerun.collectAsStateWithLifecycle()

    // Pull to refresh reads the plan and the agenda again. It never starts a model run: that is
    // the coach card's "Rerun".
    val models = mapOf(Endpoints.plan.key to planModel, Endpoints.agenda.key to agendaModel)
    val refresh: () -> Unit = { TodayReload.reload { endpoint -> models[endpoint.key]?.refresh() } }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.nav_today), onConnectionClick = onConnectionClick)
        RefreshBox(refreshing = planState.isRefreshing || agendaState.isRefreshing, onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LifeDimens.pad)
                    .padding(top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (planState is UiState.NotPaired || agendaState is UiState.NotPaired) {
                    NotPairedNotice(onPair)
                    return@Column
                }
                val plan = (planState as? UiState.Content<CoachPlan>)?.data
                val planCopy = (planState as? UiState.Content<CoachPlan>)?.takeIf { it.fromCache && !it.refreshing }
                val agendaCopy = (agendaState as? UiState.Content<Agenda>)?.takeIf { it.fromCache && !it.refreshing }
                (planCopy?.savedAtMillis ?: agendaCopy?.savedAtMillis)?.let { SavedCopyLine(it) }

                if (planState is UiState.Loading) SkeletonCard(1)
                // Without a plan (or one without messages) the card still offers the run.
                if (plan != null && (!plan.exists || plan.messages.isEmpty())) NoPlanCard(rerun, onRerun = core::rerunCoach)
                if (plan != null && plan.exists) {
                    val message by produceState<CoachMessage?>(null, plan.messages.map { it.id }) { value = core.coachMessage(plan) }
                    message?.let { CoachCard(it, plan, rerun, onRerun = core::rerunCoach, onClick = onOpenCoach) }
                    val picks = plan.topThree.filter { top -> TaskLists.open(listOf(top.task), outbox, ticked).isNotEmpty() }
                    if (picks.isNotEmpty()) {
                        val looks = LocalLooks.current
                        LifeCard(watermark = Watermark(LifeIcons.SparkMark, SectionTones.PICKS)) {
                            SectionHeader(title = stringResource(R.string.section_picks), icon = LifeIcons.Spark, tone = SectionTones.PICKS)
                            picks.forEachIndexed { index, top ->
                                if (index > 0) RowDivider()
                                // The rank takes the colour of the task's course or project.
                                MarkRow(title = top.task.title, subtitle = top.reason?.takeIf { it.isNotBlank() }) {
                                    RankMark(index + 1, tone = looks.forTask(top.task)?.tone)
                                }
                            }
                        }
                    }
                }

                when (val state = agendaState) {
                    UiState.Loading -> SkeletonCard(VISIBLE_TODAY_ROWS)
                    is UiState.Error -> ErrorNotice(state.problem, state.status, onRetry = agendaModel::refresh)
                    is UiState.Content -> {
                        val tasks = TaskLists.open(state.data.tasksOverdue + state.data.tasksToday, outbox, ticked)
                        LifeCard(watermark = Watermark(LifeIcons.TodayMark, SectionTones.TODAY)) {
                            SectionHeader(
                                icon = LifeIcons.Today,
                                tone = SectionTones.TODAY,
                                title = stringResource(R.string.section_today),
                                note = if (tasks.isEmpty()) null else pluralStringResource(R.plurals.today_count, tasks.size, tasks.size),
                            )
                            if (tasks.isEmpty()) EmptyLine(stringResource(R.string.today_empty)) else TodayTasks(core, tasks)
                        }
                    }
                    else -> Unit
                }
                OutboxNotices(core, setOf(OutboxItem.Kind.COMPLETE, OutboxItem.Kind.TASK_EDIT))
            }
        }
    }
}

/**
 * Today's tasks: the first three are visible, the rest is reached by scrolling inside the
 * card. The height is the measured height of the first three rows, so it follows the font size.
 */
@Composable
private fun TodayTasks(core: LifeOsCore, tasks: List<Task>) {
    val colors = LifeTheme.colors
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val rowHeights = remember { mutableStateMapOf<String, Int>() }
    val hidden = (tasks.size - VISIBLE_TODAY_ROWS).coerceAtLeast(0)
    val first = tasks.take(VISIBLE_TODAY_ROWS).map { it.id }
    // Until the rows are measured (first frame) an estimate is used.
    val maxHeight = if (first.all { it in rowHeights }) {
        with(density) { first.sumOf { rowHeights.getValue(it) }.toDp() }
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
                key(task.id) {
                    Column(Modifier.onSizeChanged { rowHeights[task.id] = it.height }) {
                        if (index > 0) RowDivider()
                        OpenTaskRow(core, task)
                    }
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

/** The gradient ring with a grey surface inside, the frame of the coach card. */
@Composable
private fun CoachFrame(onClickLabel: String? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = LifeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .background(colors.gradient, RoundedCornerShape(20.dp))
            .padding(LifeDimens.ring)
            .background(colors.surface, RoundedCornerShape(17.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * "Rerun": a new plan from the computer (a model run of a few minutes). Shows the progress
 * and why it did not happen (a run in the last ten minutes, not reachable, failed).
 */
@Composable
private fun RerunLine(rerun: CoachRerun.State, onRerun: () -> Unit, label: Int = R.string.coach_rerun, idleText: String? = null) {
    val colors = LifeTheme.colors
    val running = rerun == CoachRerun.State.Running
    val note = when (rerun) {
        CoachRerun.State.Running -> R.string.coach_rerun_running
        CoachRerun.State.TooSoon -> R.string.coach_rerun_too_soon
        CoachRerun.State.Unreachable -> R.string.coach_rerun_unreachable
        CoachRerun.State.NoAnswer -> R.string.coach_rerun_no_answer
        CoachRerun.State.Failed -> R.string.coach_rerun_failed
        CoachRerun.State.Idle, CoachRerun.State.Done -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = note?.let { stringResource(it) } ?: idleText.orEmpty(),
            style = LifeType.small,
            color = colors.muted,
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        PillButton(text = stringResource(label), onClick = onRerun, icon = LifeIcons.Refresh, enabled = !running)
    }
}

/** The coach card when there is no plan to show yet: it offers the first run. */
@Composable
private fun NoPlanCard(rerun: CoachRerun.State, onRerun: () -> Unit) {
    CoachFrame {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoachTag()
        }
        Text(stringResource(R.string.coach_no_plan), style = LifeType.body, color = LifeTheme.colors.text)
        RerunLine(rerun, onRerun, label = R.string.coach_prepare)
    }
}

/** The coach card: one message of the plan; tapping it opens the coach conversation. */
@Composable
private fun CoachCard(message: CoachMessage, plan: CoachPlan, rerun: CoachRerun.State, onRerun: () -> Unit, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    val action = stringResource(R.string.coach_open)
    CoachFrame(onClickLabel = action, onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CoachTag()
            Text(
                text = stringResource(
                    when (message.kind) {
                        "observation" -> R.string.coach_kind_observation
                        "encouragement" -> R.string.coach_kind_encouragement
                        "question" -> R.string.coach_kind_question
                        else -> R.string.coach_label
                    },
                ),
                style = LifeType.label,
                color = colors.text,
            )
        }
        Text(text = message.text, style = LifeType.featured, color = colors.text)
        message.why?.takeIf { it.isNotBlank() }?.let { why ->
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.text)) {
                        append(stringResource(R.string.coach_why))
                    }
                    append("  ")
                    append(why)
                },
                style = LifeType.small,
                color = colors.muted,
            )
        }
        val age = plan.ageSeconds
        val prepared = if (age == null) {
            null
        } else {
            val hours = (age / 3600).toInt()
            val text = if (hours < 1) {
                stringResource(R.string.coach_prepared_recently)
            } else {
                pluralStringResource(R.plurals.coach_prepared_hours, hours, hours)
            }
            if (plan.stale) stringResource(R.string.coach_stale, text) else text
        }
        RerunLine(rerun, onRerun, idleText = prepared)
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
