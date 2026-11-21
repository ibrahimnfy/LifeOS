package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.Assignment
import dev.lifeos.app.data.AssignmentDetail
import dev.lifeos.app.data.AssignmentMatch
import dev.lifeos.app.data.Dates
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.ui.components.MarkdownText
import dev.lifeos.app.data.UiState
import dev.lifeos.app.ui.components.*
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One assignment (P3 with the course's colour): a ticket with the due date and a stub with the
 * days left, the instructions, the attached files (downloaded like other materials) and the
 * submission. Opened from a deadline of the Courses summary (found by its name and due time,
 * [deadlineName] and [deadlineDue], when [assignmentId] is 0) or from the Assignments tab.
 */
@Composable
fun AssignmentScreen(
    core: LifeOsCore,
    courseId: Long,
    assignmentId: Long,
    deadlineName: String?,
    deadlineDue: Long?,
    onBack: () -> Unit,
    onConnectionClick: () -> Unit,
    onPair: () -> Unit,
    onOpenCourse: (Long) -> Unit,
) {
    var resolved by remember { mutableStateOf(assignmentId.takeIf { it > 0 }) }
    var unknown by remember { mutableStateOf(false) }
    val pairing by core.pairing.collectAsStateWithLifecycle()

    // A deadline names its assignment by name and time: look it up in the course's list.
    LaunchedEffect(courseId, deadlineName, deadlineDue, pairing != null) {
        if (resolved != null || pairing == null) return@LaunchedEffect
        val list = core.saved(Endpoints.assignments(courseId))?.first?.assignments ?: try {
            core.fetch(Endpoints.assignments(courseId)).assignments
        } catch (_: ApiException) {
            emptyList()
        }
        val found = AssignmentMatch.find(list, deadlineName.orEmpty(), deadlineDue)
        if (found != null) resolved = found.id else unknown = true
    }

    val id = resolved
    if (id == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title = deadlineName ?: stringResource(R.string.assignment_title), onBack = onBack, onConnectionClick = onConnectionClick)
            Column(Modifier.padding(horizontal = LifeDimens.pad).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (pairing == null) {
                    NotPairedNotice(onPair)
                } else if (unknown) {
                    EmptyLine(stringResource(R.string.assignment_not_found))
                    SecondaryButton(text = stringResource(R.string.assignment_open_course), onClick = { onOpenCourse(courseId) })
                } else {
                    SkeletonCard(3)
                }
            }
        }
        return
    }

    val model = rememberLoad(core, Endpoints.assignment(courseId, id)) { false }
    val state by model.state.collectAsStateWithLifecycle()
    val detail = (state as? UiState.Content<AssignmentDetail>)?.data
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = detail?.name ?: deadlineName ?: stringResource(R.string.assignment_title),
            subtitle = detail?.course?.ifBlank { null },
            onBack = onBack,
            onConnectionClick = onConnectionClick,
            actions = { TopBarAction(LifeIcons.Refresh, stringResource(R.string.assignment_refresh), onClick = model::refresh) },
        )
        RefreshBox(refreshing = state.isRefreshing, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LifeDimens.pad)
                    .padding(top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StateHost(state, stringResource(R.string.assignment_not_found), onRetry = model::refresh, onPair = onPair, skeletonRows = 4) { item: AssignmentDetail ->
                    DueTicket(courseId, item)
                    if (item.description.isNotBlank()) {
                        LifeCard {
                            SectionHeader(title = stringResource(R.string.assignment_instructions), icon = LifeIcons.Notes)
                            MarkdownText(item.description, Modifier.padding(top = 4.dp, bottom = 8.dp), site = true)
                        }
                    }
                    if (item.files.isNotEmpty()) {
                        LifeCard {
                            SectionHeader(title = stringResource(R.string.assignment_files), icon = LifeIcons.File)
                            item.files.forEachIndexed { index, file ->
                                if (index > 0) RowDivider(startIndent = 0.dp)
                                FileRow(core, courseId, file)
                            }
                        }
                    }
                    SubmissionCard(item)
                }
            }
        }
    }
}

private fun dueFormat(epochSeconds: Long, locale: java.util.Locale): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", locale))

/** The status chip: a dot (green when submitted or graded, red when not) and the words, never colour alone. */
@Composable
private fun StatusChip(item: AssignmentDetail) {
    val colors = LifeTheme.colors
    val summary = Assignment(item.id, item.name, item.due, item.status, submitted = item.submitted)
    val text = summary.statusText() ?: stringResource(if (item.submitted) R.string.assignment_submitted else R.string.assignment_not_submitted)
    val done = item.submitted || item.status?.graded == true
    Row(
        modifier = Modifier
            .background(colors.bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(if (done) colors.ok else colors.badge, CircleShape))
        Text(text, style = LifeType.label, color = colors.text, modifier = Modifier.padding(start = 6.dp))
    }
}

/** P3: the due date as a ticket in the course's colour, with a stub that counts the days. */
@Composable
private fun DueTicket(courseId: Long, item: AssignmentDetail) {
    val colors = LifeTheme.colors
    val locale = appLocale()
    val look = LocalLooks.current.course(courseId)
    val toned = colors.tone(look.tone)
    val shape = RoundedCornerShape(20.dp)
    val days = item.due?.let { Dates.daysUntil(it, today(), ZoneId.systemDefault()) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(toned.card)
            .border(LifeDimens.hairline, toned.second, shape),
    ) {
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.assignment_due_label), style = LifeType.label, color = colors.text)
            Text(item.due?.let { dueFormat(it, locale) } ?: stringResource(R.string.assignment_no_due), style = LifeType.sectionTitle, color = colors.text)
            val lines = listOfNotNull(
                days?.let { if (it < 0) stringResource(R.string.assignment_past) else if (it == 0L) stringResource(R.string.courses_due_today) else pluralStringResource(R.plurals.courses_in_days, it.toInt(), it.toInt()) },
                item.opens?.let { stringResource(R.string.assignment_opened, momentLabel(it, locale)) },
            )
            if (lines.isNotEmpty()) Text(lines.joinToString(" · "), style = LifeType.small, color = colors.muted)
            Box(Modifier.padding(top = 4.dp)) { StatusChip(item) }
        }
        // The stub: a dashed edge, the course's ornament, and the days left in large.
        Box(
            Modifier
                .width(96.dp)
                .fillMaxHeight()
                .drawBehind {
                    drawLine(
                        toned.second,
                        Offset(0f, 0f),
                        Offset(0f, size.height),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Ornament(look.pattern, look.tone, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(40.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 16.dp)) {
                when {
                    days == null -> Unit
                    days < 0 -> Text("–", style = LifeType.bigNumber, color = colors.muted)
                    days == 0L -> Text(stringResource(R.string.courses_due_today), style = LifeType.sectionTitle, color = colors.text)
                    else -> {
                        Text("%d".format(days), style = LifeType.bigNumber, color = colors.text)
                        Text(pluralStringResource(R.plurals.courses_days_unit, days.toInt()), style = LifeType.label, color = colors.muted)
                    }
                }
            }
        }
    }
}

/** The submission: its state, the dates around it, and its task in the list. */
@Composable
private fun SubmissionCard(item: AssignmentDetail) {
    val colors = LifeTheme.colors
    val locale = appLocale()
    LifeCard {
        SectionHeader(title = stringResource(R.string.assignment_submission), icon = LifeIcons.Send)
        val rows = listOfNotNull(
            stringResource(R.string.assignment_status) to (
                Assignment(item.id, item.name, item.due, item.status, submitted = item.submitted).statusText()
                    ?: stringResource(if (item.submitted) R.string.assignment_submitted else R.string.assignment_not_submitted)
                ),
            item.cutoff?.let { stringResource(R.string.assignment_cutoff) to momentLabel(it, locale) },
            item.gradingDue?.let { stringResource(R.string.assignment_grading_due) to momentLabel(it, locale) },
        )
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) RowDivider(startIndent = 0.dp)
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = LifeType.body, color = colors.muted, modifier = Modifier.weight(1f))
                Text(value, style = LifeType.rowTitle, color = colors.text)
            }
        }
        if (item.task != null) {
            RowDivider(startIndent = 0.dp)
            MarkRow(
                title = stringResource(R.string.assignment_in_tasks),
                subtitle = stringResource(R.string.assignment_in_tasks_text),
            ) { IconMark(LifeIcons.Tasks) }
        }
    }
}
