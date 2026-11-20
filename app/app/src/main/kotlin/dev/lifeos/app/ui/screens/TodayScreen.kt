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
import androidx.compose.ui.semantics.Role
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
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.CoachMessage
import dev.lifeos.app.data.CoachPlan
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.OutboxItem
import dev.lifeos.app.data.Task
import dev.lifeos.app.data.TaskLists
import dev.lifeos.app.data.UiState
import dev.lifeos.app.ui.components.*
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.launch

/** How many of today's tasks the Today card shows without scrolling inside it. */
private const val VISIBLE_TODAY_ROWS = 3

/**
 * Today: the coach card (one message of the morning's plan, another one each time the app is
 * opened), "Assistant's picks" (the plan's top three) and "Today" (overdue and today's tasks).
 * Pulling down asks the computer for a fresh plan.
 */
@Composable
fun TodayScreen(core: LifeOsCore, onConnectionClick: () -> Unit, onOpenCoach: () -> Unit, onPair: () -> Unit) {
    val planModel = rememberLoad(core, Endpoints.plan) { false }
    val agendaModel = rememberLoad(core, Endpoints.agenda) { false }
    val planState by planModel.state.collectAsStateWithLifecycle()
    val agendaState by agendaModel.state.collectAsStateWithLifecycle()
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val ticked by core.justTicked.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }

    // Pull to refresh: a new plan (a run on the computer; it refuses quietly when one was just made).
    val refresh: () -> Unit = {
        if (!preparing && planState !is UiState.NotPaired) {
            preparing = true
            scope.launch {
                try {
                    val (plan, raw) = core.api.refreshPlan()
                    core.savePlan(raw)
                    planModel.show(plan)
                } catch (_: ApiException) {
                    // Too soon, busy, unusable result or unreachable: the previous plan stays.
                    planModel.refresh()
                } finally {
                    preparing = false
                    agendaModel.refresh()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.nav_today), onConnectionClick = onConnectionClick)
        RefreshBox(refreshing = preparing, onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
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
                if (plan != null && plan.exists) {
                    val message by produceState<CoachMessage?>(null, plan.messages.map { it.id }) { value = core.coachMessage(plan) }
                    message?.let { CoachCard(it, plan, onClick = onOpenCoach) }
                    val picks = plan.topThree.filter { top -> TaskLists.open(listOf(top.task), outbox, ticked).isNotEmpty() }
                    if (picks.isNotEmpty()) {
                        LifeCard {
                            SectionHeader(title = stringResource(R.string.section_picks))
                            picks.forEachIndexed { index, top ->
                                if (index > 0) RowDivider()
                                MarkRow(title = top.task.title, subtitle = top.reason?.takeIf { it.isNotBlank() }) { RankMark(index + 1) }
                            }
                        }
                    }
                }

                when (val state = agendaState) {
                    UiState.Loading -> SkeletonCard(VISIBLE_TODAY_ROWS)
                    is UiState.Error -> ErrorNotice(state.problem, state.status, onRetry = agendaModel::refresh)
                    is UiState.Content -> {
                        val tasks = TaskLists.open(state.data.tasksOverdue + state.data.tasksToday, outbox, ticked)
                        LifeCard {
                            SectionHeader(
                                title = stringResource(R.string.section_today),
                                note = if (tasks.isEmpty()) null else pluralStringResource(R.plurals.today_count, tasks.size, tasks.size),
                            )
                            if (tasks.isEmpty()) EmptyLine(stringResource(R.string.today_empty)) else TodayTasks(core, tasks)
                        }
                    }
                    else -> Unit
                }
                OutboxNotices(core, setOf(OutboxItem.Kind.COMPLETE))
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

/** The coach card: a grey surface inside the gradient ring. */
@Composable
private fun CoachCard(message: CoachMessage, plan: CoachPlan, onClick: () -> Unit) {
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
        if (age != null) {
            val hours = (age / 3600).toInt()
            val prepared = if (hours < 1) {
                stringResource(R.string.coach_prepared_recently)
            } else {
                pluralStringResource(R.plurals.coach_prepared_hours, hours, hours)
            }
            Text(
                text = if (plan.stale) stringResource(R.string.coach_stale, prepared) else prepared,
                style = LifeType.small,
                color = colors.muted,
            )
        }
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
