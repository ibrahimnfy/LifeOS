package dev.lifeos.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.activity.DayScore
import dev.lifeos.app.activity.ScoreBand
import dev.lifeos.app.activity.ScorePart
import dev.lifeos.app.activity.Scores
import dev.lifeos.app.activity.TaskHistory
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.Tone
import dev.lifeos.app.data.UiState
import dev.lifeos.app.ui.components.*
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The name a score's page is opened with: a part's own name, or this for the day as a whole. */
private const val OVERALL = "day"

private fun ScorePart.routeName(): String = name.lowercase()

private fun partOf(routeName: String): ScorePart? = ScorePart.entries.firstOrNull { it.routeName() == routeName }

/**
 * The parts' colours: three of the app's tones, in an order whose neighbours stay apart for
 * every kind of colour vision (checked with the dataviz validator). A part is never told by
 * its colour alone: its icon, its name and its number are always there.
 */
private fun ScorePart.tone(): Tone = when (this) {
    ScorePart.SLEEP -> Tone.VIOLET
    ScorePart.DIGITAL -> Tone.PEACH
    ScorePart.TASKS -> Tone.SKY
}

private fun ScorePart.icon(): ImageVector = when (this) {
    ScorePart.SLEEP -> LifeIcons.Moon
    ScorePart.DIGITAL -> LifeIcons.Phone
    ScorePart.TASKS -> LifeIcons.Tasks
}

@Composable
private fun ScorePart.title(short: Boolean = false): String = stringResource(
    when (this) {
        ScorePart.SLEEP -> R.string.scores_part_sleep
        ScorePart.DIGITAL -> if (short) R.string.scores_part_digital_short else R.string.scores_part_digital
        ScorePart.TASKS -> R.string.scores_part_tasks
    },
)

@Composable
private fun ScoreBand.label(): String = stringResource(
    when (this) {
        ScoreBand.GOOD -> R.string.scores_band_good
        ScoreBand.FAIR -> R.string.scores_band_fair
        ScoreBand.LOW -> R.string.scores_band_low
        ScoreBand.VERY_LOW -> R.string.scores_band_very_low
    },
)

/** "Today", "Yesterday" or "Fri 15 Jan". */
@Composable
private fun scoreDayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> stringResource(R.string.activity_today)
    today.minusDays(1) -> stringResource(R.string.day_yesterday)
    else -> dayLabel(day, appLocale())
}

/** A score as it is read aloud: "82 of 100", or "No score". */
@Composable
private fun spoken(score: Int?): String = if (score == null) stringResource(R.string.scores_no_score) else stringResource(R.string.scores_of_100, score)

/** Everything both score screens draw from: the days (today first) and where the task numbers stand. */
private class ScoreData(val days: List<DayScore>, val tasks: UiState<TaskHistory>, val access: Boolean, val refresh: () -> Unit)

/**
 * The days of the scores. Sleep and screen time come from what the phone keeps (so they work
 * offline); the task numbers from the computer, with a saved copy. Coming back to the screen
 * measures the phone's usage again, as the Activity screen does.
 */
@Composable
private fun rememberScoreData(core: LifeOsCore, today: LocalDate): ScoreData {
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(core.usageAccess()) }
    val version by core.activityChanged.collectAsStateWithLifecycle()
    val model = rememberLoad(core, Endpoints.taskHistory) { false }
    val tasks by model.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        access = core.usageAccess()
        if (access && !core.locked.value) scope.launch { core.measureUsage() }
        model.refreshIfOld()
        onPauseOrDispose { }
    }
    val history = (tasks as? UiState.Content<TaskHistory>)?.data
    // Nothing of the content is read while the app is locked (the screens draw nothing then).
    val locked by core.locked.collectAsStateWithLifecycle()
    val days = remember(version, history, today, locked) {
        if (locked) {
            Scores.days(today, Scores.TREND_DAYS, emptyList(), emptyList(), emptyList())
        } else {
            Scores.days(today, Scores.TREND_DAYS, core.activity.nights(), core.activity.days(), history?.days.orEmpty())
        }
    }
    return ScoreData(days, tasks, access, model::refresh)
}

/**
 * Scores (#206), the middle tab: one card per day of the last week, today first, each with the
 * day's score and its three parts (sleep, digital balance, tasks). A tap on a score opens it in
 * full. The "+" in the top bar opens the command bar, which the middle button used to open.
 */
@Composable
fun ScoresScreen(
    core: LifeOsCore,
    onConnectionClick: () -> Unit,
    onCapture: () -> Unit,
    onPair: () -> Unit,
    onOpenScore: (part: String, day: String) -> Unit,
    onOpenSleep: () -> Unit,
) {
    val colors = LifeTheme.colors
    val locked by core.locked.collectAsStateWithLifecycle()
    val today = today()
    val data = rememberScoreData(core, today)
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = stringResource(R.string.nav_scores),
            onConnectionClick = onConnectionClick,
            actions = { TopBarAction(LifeIcons.Plus, stringResource(R.string.action_capture), onClick = onCapture) },
        )
        RefreshBox(refreshing = data.tasks.isRefreshing, onRefresh = data.refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LifeDimens.pad)
                    .padding(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Like every other screen: nothing of the content is read or drawn while the app is locked.
                if (locked) return@Column
                Text(stringResource(R.string.scores_intro), style = LifeType.small, color = colors.muted)
                data.days.take(Scores.LIST_DAYS).forEach { day ->
                    DayScoreCard(day, today) { part -> onOpenScore(part, day.date.toString()) }
                }
                MoreScores(data, today, onPair, onOpenSleep)
            }
        }
    }
}

/** One day: the ring with the day's score, and the three parts side by side. Each is a button to its page. */
@Composable
private fun DayScoreCard(day: DayScore, today: LocalDate, onOpen: (part: String) -> Unit) {
    val colors = LifeTheme.colors
    val title = scoreDayLabel(day.date, today)
    val overallName = stringResource(R.string.scores_overall)
    val overallSpoken = stringResource(R.string.scores_named, "$title, $overallName", spoken(day.overall))
    LifeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClickLabel = overallName, role = Role.Button) { onOpen(OVERALL) }
                    .padding(4.dp)
                    .clearAndSetSemantics { contentDescription = overallSpoken },
            ) {
                ScoreRing(day.overall, 60.dp, 6.dp, colors.gradient, LifeType.featured)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = LifeType.rowTitle,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .semantics { heading() },
                    )
                    if (day.date == today) {
                        Text(
                            " · " + stringResource(R.string.scores_so_far),
                            style = LifeType.small,
                            color = colors.muted,
                            maxLines = 1,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScorePart.entries.forEach { part -> PartCell(part, day.part(part), Modifier.weight(1f)) { onOpen(part.routeName()) } }
                }
            }
        }
    }
}

/** A part inside a day card: its icon and number, a thin bar, its short name. */
@Composable
private fun PartCell(part: ScorePart, score: Int?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    val ink = colors.tone(part.tone()).ink
    val name = part.title()
    val description = stringResource(R.string.scores_named, name, spoken(score))
    Column(
        modifier = modifier
            .heightIn(min = LifeDimens.minTouch)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = name, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(part.icon(), contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                score?.toString() ?: stringResource(R.string.scores_none),
                style = LifeType.label,
                color = if (score == null) colors.muted else colors.text,
            )
        }
        ScoreBar(score, ink)
        Text(part.title(short = true), style = LifeType.small, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A score as a length: a 4 dp track with the filled share; no fill at all without a score. */
@Composable
private fun ScoreBar(score: Int?, ink: Color, modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(colors.surface2, RoundedCornerShape(2.dp)),
    ) {
        if (score != null && score > 0) {
            Box(
                Modifier
                    .fillMaxWidth(score / 100f)
                    .fillMaxHeight()
                    .background(ink, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** A score as a ring that fills clockwise from the top, with the number in the middle ("–" without one). */
@Composable
private fun ScoreRing(score: Int?, size: Dp, stroke: Dp, brush: Brush, style: TextStyle) {
    val colors = LifeTheme.colors
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val inset = Offset(width / 2, width / 2)
            val arc = Size(this.size.width - width, this.size.height - width)
            drawArc(colors.surface2, 0f, 360f, useCenter = false, topLeft = inset, size = arc, style = Stroke(width))
            if (score != null && score > 0) {
                drawArc(brush, -90f, 360f * score / 100f, useCenter = false, topLeft = inset, size = arc, style = Stroke(width, cap = StrokeCap.Round))
            }
        }
        Text(
            score?.toString() ?: stringResource(R.string.scores_none),
            style = style,
            color = if (score == null) colors.muted else colors.text,
        )
    }
}

/** What is still missing for a fuller picture, with the way to fix each. Nothing when all is there. */
@Composable
private fun MoreScores(data: ScoreData, today: LocalDate, onPair: () -> Unit, onOpenSleep: () -> Unit) {
    val colors = LifeTheme.colors
    val todayScore = data.days.firstOrNull { it.date == today }
    val noNight = todayScore?.night == null
    val noTasks = data.tasks !is UiState.Content
    if (data.access && !noNight && !noTasks) return
    Text(
        stringResource(R.string.scores_more_title),
        style = LifeType.sectionTitle,
        color = colors.text,
        modifier = Modifier
            .padding(top = 6.dp)
            .semantics { heading() },
    )
    if (noNight) SleepMissingCard(onOpenSleep)
    if (!data.access) UsageAccessCard()
    if (noTasks) TasksWaiting(data, onPair)
}

@Composable
private fun SleepMissingCard(onOpenSleep: () -> Unit) {
    LifeCard {
        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.scores_sleep_missing), style = LifeType.body, color = LifeTheme.colors.text)
            SecondaryButton(text = stringResource(R.string.scores_sleep_enter), onClick = onOpenSleep)
        }
    }
}

/** Why there are no task numbers: not paired, the computer cannot be reached, or still on their way. */
@Composable
private fun TasksWaiting(data: ScoreData, onPair: () -> Unit) {
    when (val state = data.tasks) {
        UiState.NotPaired -> NotPairedNotice(onPair)
        is UiState.Error -> ErrorNotice(state.problem, state.status, data.refresh)
        else -> LifeCard { EmptyLine(stringResource(R.string.scores_tasks_waiting)) }
    }
}

/**
 * One score in full: the chosen day's number with its word, what went into it, the trend over
 * 7 or 14 days (a tap on a bar chooses that day) and the formula in plain words. [part] is a
 * part's name or "day" for the day as a whole; [startDay] the day it opens on.
 */
@Composable
fun ScoreDetailScreen(
    core: LifeOsCore,
    part: String,
    startDay: String,
    onBack: () -> Unit,
    onConnectionClick: () -> Unit,
    onPair: () -> Unit,
    onOpenScore: (part: String, day: String) -> Unit,
    onOpenSleep: () -> Unit,
) {
    val colors = LifeTheme.colors
    val locked by core.locked.collectAsStateWithLifecycle()
    val today = today()
    val data = rememberScoreData(core, today)
    val scorePart = partOf(part)
    var chosen by rememberSaveable { mutableStateOf(startDay) }
    var range by rememberSaveable { mutableIntStateOf(Scores.LIST_DAYS) }
    val day = data.days.firstOrNull { it.date.toString() == chosen } ?: data.days.first()
    val title = scorePart?.title() ?: stringResource(R.string.scores_overall)
    val score = if (scorePart == null) day.overall else day.part(scorePart)
    val ink = scorePart?.let { colors.tone(it.tone()).ink } ?: colors.primary

    Column(Modifier.fillMaxSize()) {
        TopBar(title = title, onBack = onBack, onConnectionClick = onConnectionClick)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LifeDimens.pad)
                .padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (locked) return@Column
            LifeCard {
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScoreRing(score, 76.dp, 8.dp, if (scorePart == null) colors.gradient else SolidColor(ink), LifeType.bigNumber)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.semantics(mergeDescendants = true) {}) {
                        Text(scoreDayLabel(day.date, today), style = LifeType.label, color = colors.muted)
                        Text(
                            if (score == null) stringResource(R.string.scores_no_score) else stringResource(R.string.scores_points, score),
                            style = LifeType.screenTitle,
                            color = colors.text,
                        )
                        if (score != null) Text(ScoreBand.of(score).label(), style = LifeType.body, color = colors.text)
                    }
                }
                if (day.date == today && score != null && scorePart != ScorePart.SLEEP) {
                    Text(stringResource(R.string.scores_today_note), style = LifeType.small, color = colors.muted, modifier = Modifier.padding(bottom = 6.dp))
                }
            }

            when (scorePart) {
                null -> OverallParts(day) { name -> onOpenScore(name, day.date.toString()) }
                ScorePart.SLEEP -> SleepParts(day, onOpenSleep)
                ScorePart.DIGITAL -> DigitalParts(day, data.access)
                ScorePart.TASKS -> TaskParts(day, data, onPair)
            }

            LifeCard {
                val window = data.days.take(range).reversed() // oldest first, today last
                val values = window.map { if (scorePart == null) it.overall else it.part(scorePart) }
                val average = Scores.overall(values)
                SectionHeader(
                    title = stringResource(R.string.scores_trend),
                    note = average?.let { stringResource(R.string.scores_average, it) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(label = stringResource(R.string.activity_range_week), selected = range == Scores.LIST_DAYS) { range = Scores.LIST_DAYS }
                    Chip(label = stringResource(R.string.scores_range_two_weeks), selected = range == Scores.TREND_DAYS) { range = Scores.TREND_DAYS }
                }
                if (values.all { it == null }) {
                    EmptyLine(stringResource(R.string.scores_trend_empty))
                } else {
                    ScoreBars(
                        days = window.map { it.date },
                        values = values,
                        selected = day.date,
                        average = average,
                        ink = ink,
                        description = pluralStringResource(R.plurals.scores_trend_description, range, range, title),
                        onSelect = { chosen = it.toString() },
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
                        Text(dayLabel(window.first().date, appLocale()), style = LifeType.small, color = colors.muted, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.activity_today), style = LifeType.small, color = colors.muted)
                    }
                }
            }

            LifeCard {
                SectionHeader(title = stringResource(R.string.scores_how))
                Text(
                    stringResource(
                        when (scorePart) {
                            null -> R.string.scores_overall_formula
                            ScorePart.SLEEP -> R.string.scores_sleep_formula
                            ScorePart.DIGITAL -> R.string.scores_digital_formula
                            ScorePart.TASKS -> R.string.scores_tasks_formula
                        },
                    ),
                    style = LifeType.body,
                    color = colors.text,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
}

/** A row of "what went into it": the name on the left, the value on the right. */
@Composable
private fun PartRow(label: String, value: String) {
    val colors = LifeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = LifeType.body, color = colors.muted, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = LifeType.rowTitle, color = colors.text, modifier = Modifier.weight(1.2f, fill = false))
    }
}

@Composable
private fun points(score: Int): String = stringResource(R.string.scores_points, score)

/** The day as a whole: its three parts, each a button to its own page. */
@Composable
private fun OverallParts(day: DayScore, onOpen: (part: String) -> Unit) {
    val colors = LifeTheme.colors
    LifeCard {
        SectionHeader(title = stringResource(R.string.scores_made_of))
        if (day.overall == null) Text(stringResource(R.string.scores_overall_missing), style = LifeType.body, color = colors.muted)
        ScorePart.entries.forEach { part ->
            val score = day.part(part)
            val ink = colors.tone(part.tone()).ink
            val name = part.title()
            val description = stringResource(R.string.scores_named, name, spoken(score))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LifeDimens.minTouch)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = name, role = Role.Button) { onOpen(part.routeName()) }
                    .clearAndSetSemantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(part.icon(), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(name, style = LifeType.body, color = colors.text)
                    ScoreBar(score, ink)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    score?.toString() ?: stringResource(R.string.scores_none),
                    style = LifeType.rowTitle,
                    color = if (score == null) colors.muted else colors.text,
                )
                Icon(LifeIcons.Chevron, contentDescription = null, tint = colors.muted, modifier = Modifier.padding(start = 6.dp).size(16.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SleepParts(day: DayScore, onOpenSleep: () -> Unit) {
    val night = day.night
    val sleep = day.sleep
    if (night == null || sleep == null) {
        SleepMissingCard(onOpenSleep)
        return
    }
    LifeCard {
        SectionHeader(title = stringResource(R.string.scores_made_of), note = stringResource(R.string.sleep_estimated).takeIf { night.estimated })
        PartRow(stringResource(R.string.scores_sleep_slept), durationLabel(night.minutes))
        PartRow(stringResource(R.string.scores_sleep_times), stringResource(R.string.sleep_times, night.bed, night.wake))
        PartRow(stringResource(R.string.scores_sleep_length), points(sleep.duration))
        val regular = sleep.regularity
        val off = sleep.offMinutes
        PartRow(
            stringResource(R.string.scores_sleep_regular),
            if (regular != null && off != null) {
                stringResource(R.string.scores_sleep_regular_value, points(regular), durationLabel(off))
            } else {
                stringResource(R.string.scores_sleep_regular_unknown)
            },
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DigitalParts(day: DayScore, access: Boolean) {
    val usage = day.usage
    val digital = day.digital
    if (usage == null || digital == null) {
        // Without usage access nothing can be measured: the card says why and leads to the setting.
        if (!access) UsageAccessCard() else LifeCard { EmptyLine(stringResource(R.string.scores_digital_missing)) }
        return
    }
    LifeCard {
        SectionHeader(title = stringResource(R.string.scores_made_of))
        PartRow(
            stringResource(R.string.activity_tab_screen_time),
            stringResource(R.string.scores_digital_value, durationLabel(usage.screenMinutes), points(digital.screen)),
        )
        PartRow(
            stringResource(R.string.scores_digital_unlocks),
            stringResource(
                R.string.scores_digital_value,
                pluralStringResource(R.plurals.activity_unlocks, usage.unlocks, usage.unlocks),
                points(digital.unlocks),
            ),
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun TaskParts(day: DayScore, data: ScoreData, onPair: () -> Unit) {
    val counts = day.tasks
    if (counts == null) {
        TasksWaiting(data, onPair)
        return
    }
    val state = data.tasks
    if (state is UiState.Content && state.fromCache && !state.refreshing) SavedCopyLine(state.savedAtMillis)
    LifeCard {
        SectionHeader(title = stringResource(R.string.scores_made_of))
        PartRow(stringResource(R.string.scores_tasks_due), counts.due.toString())
        PartRow(stringResource(R.string.scores_tasks_due_done), counts.dueDone.toString())
        PartRow(stringResource(R.string.scores_tasks_other), counts.doneOther.toString())
        if (day.task == null) {
            Text(stringResource(R.string.scores_tasks_none), style = LifeType.small, color = LifeTheme.colors.muted, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * A score per day on a fixed scale of 0 to 100: one bar per day on a shared baseline, rounded
 * at the top, the chosen day in full colour with its number above it, the others lighter, the
 * average as a dashed line. A day without a score has no bar, only a dot on the baseline.
 * Tapping a day's column chooses it; the card above is the readable form of the same numbers.
 */
@Composable
private fun ScoreBars(
    days: List<LocalDate>,
    values: List<Int?>,
    selected: LocalDate,
    average: Int?,
    ink: Color,
    description: String,
    onSelect: (LocalDate) -> Unit,
) {
    val colors = LifeTheme.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = LifeType.label.copy(color = colors.text)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .pointerInput(days) {
                detectTapGestures { offset ->
                    val index = (offset.x / (size.width.toFloat() / days.size)).toInt().coerceIn(0, days.size - 1)
                    onSelect(days[index])
                }
            }
            .semantics { contentDescription = description },
    ) {
        val top = 22.dp.toPx() // room for the chosen day's number above a full bar
        val chartHeight = size.height - top
        val slot = size.width / days.size
        val gap = 2.dp.toPx()
        val barWidth = (slot - gap).coerceIn(2.dp.toPx(), 24.dp.toPx())
        val radius = minOf(4.dp.toPx(), barWidth / 2)
        days.forEachIndexed { index, day ->
            val centre = index * slot + slot / 2
            val value = values[index]
            if (value == null) {
                drawCircle(colors.divider, radius = 2.dp.toPx(), center = Offset(centre, size.height - 5.dp.toPx()))
                return@forEachIndexed
            }
            val height = (value / 100f * chartHeight).coerceAtLeast(2.dp.toPx())
            val left = centre - barWidth / 2
            val bar = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = left,
                        top = size.height - height,
                        right = left + barWidth,
                        bottom = size.height,
                        topLeftCornerRadius = CornerRadius(radius),
                        topRightCornerRadius = CornerRadius(radius),
                    ),
                )
            }
            val chosen = day == selected
            drawPath(bar, if (chosen) ink else ink.copy(alpha = 0.4f))
            if (chosen) {
                val label = measurer.measure(value.toString(), labelStyle)
                val x = (centre - label.size.width / 2).coerceIn(0f, size.width - label.size.width)
                drawText(label, topLeft = Offset(x, (size.height - height - label.size.height - 3.dp.toPx()).coerceAtLeast(0f)))
            }
        }
        drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        if (average != null && average > 0) {
            val y = size.height - average / 100f * chartHeight
            drawLine(
                colors.muted,
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }
    }
}
