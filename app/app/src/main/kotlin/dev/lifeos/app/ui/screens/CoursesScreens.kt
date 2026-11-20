package dev.lifeos.app.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.AllGrades
import dev.lifeos.app.data.Announcements
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.Assignment
import dev.lifeos.app.data.Assignments
import dev.lifeos.app.data.Course
import dev.lifeos.app.data.CourseFile
import dev.lifeos.app.data.CourseList
import dev.lifeos.app.data.CourseSummary
import dev.lifeos.app.data.Dates
import dev.lifeos.app.data.Deadline
import dev.lifeos.app.data.Pattern
import dev.lifeos.app.data.DownloadFailure
import dev.lifeos.app.data.DownloadState
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.GradeItem
import dev.lifeos.app.data.Grades
import dev.lifeos.app.data.Materials
import dev.lifeos.app.data.OpenResult
import dev.lifeos.app.data.SyncState
import dev.lifeos.app.data.UiState
import dev.lifeos.app.ui.components.*
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

private const val SYNC_POLL_MILLIS = 2_000L
private const val SYNC_POLLS = 60

/** What the screen knows about the sync itself (not saved: asked each time). */
private sealed interface SyncNotice {
    data object SignIn : SyncNotice
    data object NotSetUp : SyncNotice
    data object Failed : SyncNotice
}

private fun noticeOf(sync: SyncState?): SyncNotice? = when {
    sync == null -> null
    !sync.configured -> SyncNotice.NotSetUp
    sync.lastError == "token_expired" -> SyncNotice.SignIn
    sync.lastError != null -> SyncNotice.Failed
    else -> null
}

@Composable
private fun SyncNoticeCard(notice: SyncNotice) {
    val colors = LifeTheme.colors
    Text(
        text = stringResource(
            when (notice) {
                SyncNotice.SignIn -> R.string.courses_sign_in_needed
                SyncNotice.NotSetUp -> R.string.courses_not_set_up
                SyncNotice.Failed -> R.string.courses_sync_failed
            },
        ),
        style = LifeType.body,
        color = colors.text,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Courses: the summary card with "All grades", then the course list with markers for new content. */
@Composable
fun CoursesScreen(
    core: LifeOsCore,
    onBack: () -> Unit,
    onConnectionClick: () -> Unit,
    onPair: () -> Unit,
    onOpenCourse: (Long) -> Unit,
    onGrades: () -> Unit,
    onOpenDeadline: (Deadline) -> Unit = { deadline -> deadline.courseId?.let(onOpenCourse) },
) {
    val colors = LifeTheme.colors
    val locale = appLocale()
    val summaryModel = rememberLoad(core, Endpoints.courseSummary) { false }
    val coursesModel = rememberLoad(core, Endpoints.courses) { list: CourseList -> list.courses.isEmpty() }
    val summary by summaryModel.state.collectAsStateWithLifecycle()
    val courses by coursesModel.state.collectAsStateWithLifecycle()
    val reload by core.reload.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    var sync by remember { mutableStateOf<SyncState?>(null) }
    val refreshLists = {
        summaryModel.refresh()
        coursesModel.refresh()
    }

    LaunchedEffect(reload) {
        sync = try {
            core.api.syncState()
        } catch (_: ApiException) {
            sync
        }
    }

    // The refresh action: start a sync on the computer, wait until it is over, then reload.
    val startSync: () -> Unit = {
        if (!syncing) {
            syncing = true
            scope.launch {
                try {
                    try {
                        core.api.startSync()
                    } catch (error: ApiException) {
                        // 429: one is running already, or one just ran. Both are fine.
                        if (error.status != 429) throw error
                    }
                    for (poll in 1..SYNC_POLLS) {
                        delay(SYNC_POLL_MILLIS)
                        val now = core.api.syncState()
                        sync = now
                        if (!now.running) break
                    }
                } catch (_: ApiException) {
                } finally {
                    syncing = false
                    refreshLists()
                }
            }
        }
    }

    val lastSync = (summary as? UiState.Content<CourseSummary>)?.data?.lastSync ?: (courses as? UiState.Content<CourseList>)?.data?.lastSync
    val synced = if (summary is UiState.Content || courses is UiState.Content) {
        relativeIso(lastSync)?.let { stringResource(R.string.courses_last_sync, it) } ?: stringResource(R.string.courses_never_synced)
    } else {
        null
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = stringResource(R.string.more_courses),
            subtitle = synced,
            onBack = onBack,
            onConnectionClick = onConnectionClick,
            actions = {
                PillButton(
                    text = stringResource(R.string.courses_all_grades),
                    enabled = summary !is UiState.NotPaired,
                    onClick = onGrades,
                )
                TopBarAction(
                    LifeIcons.Refresh,
                    stringResource(if (syncing) R.string.courses_syncing else R.string.courses_refresh),
                    enabled = !syncing && summary !is UiState.NotPaired,
                    onClick = startSync,
                )
            },
        )
        RefreshBox(refreshing = summary.isRefreshing || courses.isRefreshing, onRefresh = refreshLists, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LifeDimens.pad)
                    .padding(top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (syncing) {
                    Text(
                        stringResource(R.string.courses_syncing),
                        style = LifeType.small,
                        color = colors.muted,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (summary !is UiState.NotPaired) noticeOf(sync)?.let { SyncNoticeCard(it) }
                // The summary card holds the upcoming deadlines and nothing else.
                StateHost(summary, "", onRetry = refreshLists, onPair = onPair, skeletonRows = 2) { data: CourseSummary ->
                    DeadlineCard(data.upcoming(System.currentTimeMillis() / 1000), onOpenDeadline)
                }
                if (summary !is UiState.NotPaired && summary !is UiState.Error) {
                    // One "saved copy" line for the screen is enough: the summary above carries it.
                    StateHost(courses, stringResource(R.string.courses_empty), onRetry = refreshLists, onPair = onPair, skeletonRows = 4, savedLine = false) { list: CourseList ->
                        SectionHeader(
                            title = stringResource(R.string.courses_all),
                            note = pluralStringResource(R.plurals.courses_count, list.courses.size, list.courses.size),
                            icon = LifeIcons.Courses,
                            iconTile = true,
                        )
                        CourseGrid(list.courses, locale, onOpenCourse)
                    }
                }
            }
        }
    }
}

/** Days left until a deadline, as the ticket shows it: a big number and its unit, or "Today". */
@Composable
private fun DaysLeft(due: Long?, big: Boolean = true) {
    val colors = LifeTheme.colors
    val days = due?.let { Dates.daysUntil(it, today(), ZoneId.systemDefault()) } ?: return
    val number = if (big) LifeType.bigNumber else LifeType.screenTitle
    if (days <= 0) {
        Text(stringResource(R.string.courses_due_today), style = number, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else {
        Text("%d".format(days), style = number, color = colors.text)
        Text(pluralStringResource(R.plurals.courses_days_unit, days.toInt()), style = LifeType.label, color = colors.muted)
    }
}

/**
 * P3: the upcoming deadlines on a rose card with a dotted ornament. The nearest three are
 * tickets (a stripe in the course's colour on top, the days left in large, the name and the
 * course); further ones follow as rows. Tapping one opens its course.
 */
@Composable
private fun DeadlineCard(deadlines: List<Deadline>, onOpenDeadline: (Deadline) -> Unit) {
    val colors = LifeTheme.colors
    val looks = LocalLooks.current
    val locale = appLocale()
    LifeCard(
        tint = SectionTones.DEADLINES,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 26.dp),
        decoration = {
            Ornament(Pattern.DOTS, SectionTones.DEADLINES, Modifier.align(Alignment.TopEnd).size(width = 110.dp, height = 46.dp))
        },
    ) {
        SectionHeader(title = stringResource(R.string.courses_deadlines), icon = LifeIcons.Calendar, tone = SectionTones.DEADLINES, iconTile = true)
        if (deadlines.isEmpty()) {
            EmptyLine(stringResource(R.string.courses_no_deadlines))
            return@LifeCard
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            deadlines.take(TICKETS).forEach { deadline ->
                val ink = deadline.courseId?.let { colors.tone(looks.course(it).tone).ink } ?: colors.muted
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.bg)
                        .drawBehind { drawRect(ink, size = Size(size.width, 4.dp.toPx())) }
                        .clickable(enabled = deadline.courseId != null, role = Role.Button) { onOpenDeadline(deadline) }
                        .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 9.dp),
                ) {
                    DaysLeft(deadline.due)
                    Text(
                        deadline.name,
                        style = LifeType.label,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    deadline.course?.let { Text(it, style = LifeType.small, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            // Fewer than three: the tickets keep their width.
            repeat(TICKETS - deadlines.size.coerceAtMost(TICKETS)) { Spacer(Modifier.weight(1f)) }
        }
        deadlines.drop(TICKETS).forEach { deadline ->
            RowDivider(startIndent = 0.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(enabled = deadline.courseId != null, role = Role.Button) { onOpenDeadline(deadline) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                deadline.courseId?.let { CourseMark(looks.course(it), size = 32.dp, onTint = true) }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(deadline.name, style = LifeType.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(deadline.due?.let { momentLabel(it, locale) }, deadline.course).joinToString(" · "),
                        style = LifeType.small,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val TICKETS = 3

/**
 * P3: the courses as tiles of different sizes: the first one wide, the others two by two, each
 * in its course's colour with its icon and ornament.
 */
@Composable
private fun CourseGrid(courses: List<Course>, locale: java.util.Locale, onOpenCourse: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        courses.firstOrNull()?.let { CourseCell(it, locale, RoundedCornerShape(20.dp), onOpenCourse, Modifier.fillMaxWidth()) }
        courses.drop(1).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CourseCell(pair[0], locale, RoundedCornerShape(topStart = 20.dp, topEnd = 8.dp, bottomEnd = 20.dp, bottomStart = 20.dp), onOpenCourse, Modifier.weight(1f).fillMaxHeight())
                if (pair.size > 1) {
                    CourseCell(pair[1], locale, RoundedCornerShape(topStart = 8.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp), onOpenCourse, Modifier.weight(1f).fillMaxHeight())
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CourseCell(course: Course, locale: java.util.Locale, shape: RoundedCornerShape, onOpenCourse: (Long) -> Unit, modifier: Modifier) {
    val colors = LifeTheme.colors
    val look = LocalLooks.current.course(course.id)
    val toned = colors.tone(look.tone)
    Column(
        modifier = modifier
            .clip(shape)
            .background(toned.card)
            .border(LifeDimens.hairline, toned.second, shape)
            .clickable(role = Role.Button) { onOpenCourse(course.id) },
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Ornament(look.pattern, look.tone, Modifier.matchParentSize())
            CourseMark(look, Modifier.padding(start = 10.dp, top = 8.dp), size = 32.dp, onTint = true)
            if (course.hasNew) Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp)) { NewBadge(course.new.total) }
        }
        Text(
            course.name.ifEmpty { course.shortname },
            style = LifeType.rowTitle,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
        )
        val next = course.nextDeadline
        Text(
            text = if (next?.due != null) {
                stringResource(R.string.courses_next_deadline, next.name, momentLabel(next.due, locale))
            } else {
                stringResource(R.string.courses_no_next_deadline)
            },
            style = LifeType.small,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
        )
    }
}

/** "3 new": a red pill with the count in words, so colour is not the only signal. */
@Composable
private fun NewBadge(count: Int) {
    val colors = LifeTheme.colors
    Text(
        text = if (count > 0) pluralStringResource(R.plurals.courses_new, count, count) else stringResource(R.string.courses_new_mark),
        style = LifeType.label,
        color = colors.onBadge,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .background(colors.badge, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 2.dp),
    )
}

private enum class CourseTab(val title: Int) {
    MATERIALS(R.string.course_materials),
    ANNOUNCEMENTS(R.string.course_announcements),
    ASSIGNMENTS(R.string.course_assignments),
    GRADES(R.string.course_grades),
}

/** One course: Materials, Announcements, Assignments, Grades. Opening it clears its "new" markers. */
@Composable
fun CourseScreen(
    core: LifeOsCore,
    courseId: Long,
    onBack: () -> Unit,
    onConnectionClick: () -> Unit,
    onPair: () -> Unit,
    onOpenAssignment: (Long) -> Unit = {},
) {
    val coursesModel = rememberLoad(core, Endpoints.courses) { list: CourseList -> list.courses.isEmpty() }
    val courses by coursesModel.state.collectAsStateWithLifecycle()
    val pairing by core.pairing.collectAsStateWithLifecycle()
    val locked by core.locked.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(CourseTab.MATERIALS) }
    val course = (courses as? UiState.Content<CourseList>)?.data?.courses?.firstOrNull { it.id == courseId }
    val name = course?.name

    LaunchedEffect(courseId, pairing != null, locked) {
        if (pairing != null && !locked) {
            try {
                core.api.markSeen(courseId)
            } catch (_: ApiException) {
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = name ?: stringResource(R.string.course_title), onBack = onBack, onConnectionClick = onConnectionClick)
        if (course != null) CourseHero(course, Modifier.padding(horizontal = LifeDimens.pad).padding(top = 4.dp, bottom = 10.dp))
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = LifeDimens.pad),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CourseTab.entries.forEach { entry -> Chip(label = stringResource(entry.title), selected = tab == entry) { tab = entry } }
        }
        when (tab) {
            CourseTab.MATERIALS -> MaterialsTab(core, courseId, onPair)
            CourseTab.ANNOUNCEMENTS -> AnnouncementsTab(core, courseId, onPair)
            CourseTab.ASSIGNMENTS -> AssignmentsTab(core, courseId, onPair, onOpenAssignment)
            CourseTab.GRADES -> GradesTab(core, courseId, onPair)
        }
    }
}

/** P3: the course's header card in its colour, with its ornament, its icon and the next deadline. */
@Composable
private fun CourseHero(course: Course, modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    val look = LocalLooks.current.course(course.id)
    val toned = colors.tone(look.tone)
    val shape = RoundedCornerShape(20.dp)
    val next = course.nextDeadline
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(toned.card)
            .border(LifeDimens.hairline, toned.second, shape),
    ) {
        Ornament(look.pattern, look.tone, Modifier.fillMaxWidth().height(40.dp))
        Row(
            Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CourseMark(look, size = 44.dp, onTint = true)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = next?.let { stringResource(R.string.course_next, it.name) } ?: stringResource(R.string.courses_no_next_deadline),
                    style = LifeType.rowTitle,
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                next?.due?.let { due ->
                    val days = Dates.daysUntil(due, today(), ZoneId.systemDefault())
                    val left = if (days <= 0) stringResource(R.string.courses_due_today) else pluralStringResource(R.plurals.courses_in_days, days.toInt(), days.toInt())
                    Text(stringResource(R.string.course_due_line, momentLabel(due, appLocale()), left), style = LifeType.small, color = colors.muted)
                }
            }
            if (course.hasNew) NewBadge(course.new.total)
        }
    }
}

@Composable
private fun TabBody(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    RefreshBox(refreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LifeDimens.pad)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun MaterialsTab(core: LifeOsCore, courseId: Long, onPair: () -> Unit) {
    val colors = LifeTheme.colors
    val model = rememberLoad(core, Endpoints.materials(courseId)) { m: Materials -> m.sections.all { it.files.isEmpty() } && m.links.isEmpty() }
    val state by model.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    TabBody(state.isRefreshing, model::refresh) {
        StateHost(state, stringResource(R.string.course_materials_empty), onRetry = model::refresh, onPair = onPair, skeletonRows = 4) { materials: Materials ->
            Text(stringResource(R.string.course_materials_note), style = LifeType.small, color = colors.muted)
            materials.sections.filter { it.files.isNotEmpty() }.forEach { section ->
                LifeCard {
                    SectionHeader(title = section.name.ifEmpty { stringResource(R.string.course_materials) })
                    section.files.forEachIndexed { index, file ->
                        if (index > 0) RowDivider(startIndent = 0.dp)
                        FileRow(core, courseId, file)
                    }
                }
            }
            if (materials.links.isNotEmpty()) {
                LifeCard {
                    SectionHeader(title = stringResource(R.string.course_links))
                    materials.links.forEachIndexed { index, link ->
                        if (index > 0) RowDivider(startIndent = 0.dp)
                        // Only web addresses are opened, and in the browser, never inside the app.
                        val opens = link.url.startsWith("https://") || link.url.startsWith("http://")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(enabled = opens, onClickLabel = stringResource(R.string.course_open_link), role = Role.Button) {
                                    try {
                                        uriHandler.openUri(link.url)
                                    } catch (_: Exception) {
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CourseMark(LocalLooks.current.course(courseId), size = 40.dp, shape = RoundedCornerShape(12.dp), icon = LifeIcons.Link)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(link.name.ifEmpty { link.url }, style = LifeType.rowTitle, color = colors.link)
                                if (link.section.isNotEmpty()) Text(link.section, style = LifeType.small, color = colors.muted)
                            }
                            Icon(LifeIcons.Link, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** One file: name, kind, size and date, and the download control with its progress and result. */
@Composable
internal fun FileRow(core: LifeOsCore, courseId: Long, file: CourseFile) {
    val colors = LifeTheme.colors
    val context = LocalContext.current
    val locale = appLocale()
    val states by core.downloads.states.collectAsStateWithLifecycle()
    val state = states[file.key]
    // Before Android 10 the owner chooses where the file goes.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.type ?: "application/octet-stream")) { uri ->
        if (uri != null) core.downloads.start(courseId, file, uri)
    }
    val start = {
        if (core.downloads.needsPicker) picker.launch(dev.lifeos.app.data.DownloadRules.safeName(file.name)) else core.downloads.start(courseId, file)
    }
    val snackbar = LocalSnackbar.current
    val noApp = stringResource(R.string.download_open_no_app)
    val gone = stringResource(R.string.download_open_gone)
    val open = {
        when (core.downloads.open(context, file.key, file.name)) {
            OpenResult.NO_APP -> snackbar(noApp)
            OpenResult.GONE -> snackbar(gone)
            OpenResult.OPENED -> Unit
        }
    }
    val look = LocalLooks.current.course(courseId)
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The file icon in the course's colour.
            CourseMark(look, size = 40.dp, shape = RoundedCornerShape(12.dp), icon = LifeIcons.File)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = LifeType.rowTitle, color = colors.text)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (file.new) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(colors.badge, CircleShape),
                        )
                        Text(stringResource(R.string.courses_new_mark), style = LifeType.label, color = colors.text)
                    }
                    Text(
                        listOfNotNull(
                            file.name.substringAfterLast('.', "").takeIf { it.length in 1..5 }?.uppercase(),
                            Formatter.formatShortFileSize(context, file.size),
                            file.modified?.let { momentLabel(it, locale) },
                        ).joinToString(" · "),
                        style = LifeType.small,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when (state) {
                is DownloadState.Running -> TopBarAction(LifeIcons.Close, stringResource(R.string.download_cancel, file.name)) { core.downloads.cancel(file.key) }
                is DownloadState.Saved -> PillButton(
                    text = stringResource(R.string.download_open),
                    onClick = open,
                    onClickLabel = stringResource(R.string.download_open_label, file.name),
                )
                else -> TopBarAction(LifeIcons.Download, stringResource(R.string.download_start, file.name), onClick = start)
            }
        }
        val line = when (state) {
            is DownloadState.Running ->
                if (state.total > 0) {
                    stringResource(R.string.download_progress, (state.bytes * 100 / state.total).toInt().coerceIn(0, 100))
                } else {
                    stringResource(R.string.download_running)
                }
            is DownloadState.Saved -> stringResource(R.string.download_saved)
            is DownloadState.Failed -> stringResource(
                when (state.reason) {
                    DownloadFailure.Reason.NO_COPY, DownloadFailure.Reason.DIRECT -> R.string.download_failed_no_copy
                    DownloadFailure.Reason.SIGN_IN -> R.string.download_failed_sign_in
                    DownloadFailure.Reason.DAMAGED -> R.string.download_failed_damaged
                    DownloadFailure.Reason.UNREACHABLE -> R.string.download_failed_unreachable
                    DownloadFailure.Reason.STORAGE -> R.string.download_failed_storage
                },
            )
            null -> null
        }
        if (line != null) {
            Text(
                line,
                style = LifeType.small,
                color = if (state is DownloadState.Failed) colors.text else colors.muted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun AnnouncementsTab(core: LifeOsCore, courseId: Long, onPair: () -> Unit) {
    val colors = LifeTheme.colors
    val locale = appLocale()
    val model = rememberLoad(core, Endpoints.announcements(courseId)) { a: Announcements -> a.announcements.isEmpty() }
    val state by model.state.collectAsStateWithLifecycle()
    TabBody(state.isRefreshing, model::refresh) {
        StateHost(state, stringResource(R.string.course_announcements_empty), onRetry = model::refresh, onPair = onPair) { data: Announcements ->
            data.announcements.forEach { item ->
                var open by rememberSaveable(item.id) { mutableStateOf(false) }
                LifeCard(Modifier.clickable(onClickLabel = stringResource(R.string.course_show_all_text), role = Role.Button) { open = !open }) {
                    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.subject, style = LifeType.rowTitle, color = colors.text)
                        item.created?.let { Text(momentLabel(it, locale), style = LifeType.small, color = colors.muted) }
                        Text(
                            item.text,
                            style = LifeType.body,
                            color = colors.text,
                            maxLines = if (open) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun Assignment.statusText(): String? = when {
    status == null -> null
    status.graded -> stringResource(R.string.assignment_graded)
    status.submission == "submitted" -> stringResource(R.string.assignment_submitted)
    status.submission == "draft" -> stringResource(R.string.assignment_draft)
    status.submission == "reopened" -> stringResource(R.string.assignment_reopened)
    else -> stringResource(R.string.assignment_not_submitted)
}

@Composable
private fun AssignmentsTab(core: LifeOsCore, courseId: Long, onPair: () -> Unit, onOpenAssignment: (Long) -> Unit) {
    val colors = LifeTheme.colors
    val locale = appLocale()
    val model = rememberLoad(core, Endpoints.assignments(courseId)) { a: Assignments -> a.assignments.isEmpty() }
    val state by model.state.collectAsStateWithLifecycle()
    TabBody(state.isRefreshing, model::refresh) {
        StateHost(state, stringResource(R.string.course_assignments_empty), onRetry = model::refresh, onPair = onPair) { data: Assignments ->
            LifeCard {
                // Nearest deadline first; those without a date last.
                data.assignments.sortedWith(compareBy({ it.due == null }, { -(it.due ?: 0) })).forEachIndexed { index, item ->
                    if (index > 0) RowDivider(startIndent = 0.dp)
                    // Tapping an assignment opens its detail.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.Button) { onOpenAssignment(item.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = LifeType.rowTitle, color = colors.text)
                            Text(
                                listOfNotNull(
                                    item.due?.let { stringResource(R.string.assignment_due, momentLabel(it, locale)) } ?: stringResource(R.string.assignment_no_due),
                                    item.statusText(),
                                ).joinToString(" · "),
                                style = LifeType.small,
                                color = colors.muted,
                            )
                        }
                        Icon(LifeIcons.Chevron, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeRows(overall: String?, items: List<GradeItem>) {
    val colors = LifeTheme.colors
    if (overall != null) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.grades_overall), style = LifeType.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
            Text(overall, style = LifeType.rowTitle, color = colors.text)
        }
    }
    // The course total is shown above as "overall".
    val rows = items.filter { it.type != "course" }
    rows.forEachIndexed { index, item ->
        if (index > 0 || overall != null) RowDivider(startIndent = 0.dp)
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name.ifEmpty { stringResource(R.string.grades_item) }, style = LifeType.body, color = colors.text)
                val details = listOfNotNull(item.range?.let { stringResource(R.string.grades_range, it) }, item.percentage?.takeIf { it != "-" })
                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = LifeType.small, color = colors.muted)
                item.feedback?.takeIf { it.isNotBlank() }?.let { Text(it, style = LifeType.small, color = colors.muted) }
            }
            Text(
                item.grade?.takeIf { it != "-" } ?: stringResource(R.string.grades_none),
                style = LifeType.rowTitle,
                color = if (item.grade == null || item.grade == "-") colors.muted else colors.text,
            )
        }
    }
    if (rows.isEmpty() && overall == null) EmptyLine(stringResource(R.string.course_grades_empty))
}

@Composable
private fun GradesTab(core: LifeOsCore, courseId: Long, onPair: () -> Unit) {
    val model = rememberLoad(core, Endpoints.grades(courseId)) { g: Grades -> g.items.isEmpty() && g.overall == null }
    val state by model.state.collectAsStateWithLifecycle()
    TabBody(state.isRefreshing, model::refresh) {
        StateHost(state, stringResource(R.string.course_grades_empty), onRetry = model::refresh, onPair = onPair) { data: Grades ->
            LifeCard { GradeRows(data.overall, data.items) }
        }
    }
}

/** All grades: every course's grade items on one screen, grouped by course. */
@Composable
fun AllGradesScreen(core: LifeOsCore, onBack: () -> Unit, onConnectionClick: () -> Unit, onPair: () -> Unit) {
    val model = rememberLoad(core, Endpoints.allGrades) { g: AllGrades -> g.courses.isEmpty() }
    val state by model.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.courses_all_grades), onBack = onBack, onConnectionClick = onConnectionClick)
        TabBody(state.isRefreshing, model::refresh) {
            StateHost(state, stringResource(R.string.course_grades_empty), onRetry = model::refresh, onPair = onPair, skeletonRows = 5) { data: AllGrades ->
                val looks = LocalLooks.current
                data.courses.forEach { course ->
                    val look = looks.course(course.id)
                    LifeCard {
                        // Each course keeps its colour and icon here too.
                        SectionHeader(title = course.name, icon = look.glyph.icon(), tone = look.tone, iconTile = true)
                        GradeRows(course.overall, course.items)
                    }
                }
            }
        }
    }
}
