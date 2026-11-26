package dev.lifeos.app.ui.screens

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.Agenda
import dev.lifeos.app.data.ChatPhase
import dev.lifeos.app.data.CoachMessage
import dev.lifeos.app.data.CoachPlan
import dev.lifeos.app.data.CourseSummary
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.LocalItems
import dev.lifeos.app.data.NoteList
import dev.lifeos.app.data.ProjectEndpoints
import dev.lifeos.app.data.ProjectList
import dev.lifeos.app.data.TaskList
import dev.lifeos.app.data.TaskLists
import dev.lifeos.app.data.Tone
import dev.lifeos.app.data.UiState
import dev.lifeos.app.data.listAtMillis
import dev.lifeos.app.home.HomeField
import dev.lifeos.app.home.HomeTile
import dev.lifeos.app.home.TilePhysics
import dev.lifeos.app.settings.SettingsStore
import dev.lifeos.app.ui.components.LocalUndo
import dev.lifeos.app.ui.components.NotPairedNotice
import dev.lifeos.app.ui.components.SavedCopyLine
import dev.lifeos.app.ui.components.SectionTones
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.components.TopBarAction
import dev.lifeos.app.ui.components.appLocale
import dev.lifeos.app.ui.components.rememberLoad
import dev.lifeos.app.ui.components.today
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeColors
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The colours the home field adds to the D3 tokens (prototype C's sci-fi layer): the field
 * itself, a little darker than the page, with faint grid and scan lines in the link colour.
 * Tiles are opaque (the page colour), so text on a tile never has another tile's text behind
 * it; HomeColorsTest checks the contrast of everything written on a tile or on the field.
 */
object HomeColors {
    val FieldLight = Color(0xFFF1F2F6)
    val FieldDark = Color(0xFF080B12)
    const val GRID_ALPHA = 0.07f
    const val SCAN_ALPHA = 0.03f

    /** A tile's edge: its tone's ink at this opacity on the tile (3 : 1 against the field, see the test). */
    const val EDGE_ALPHA = 0.72f

    fun field(colors: LifeColors): Color = if (colors.isDark) FieldDark else FieldLight

    /** A tile's surface. */
    fun tile(colors: LifeColors): Color = colors.bg

    /** The colour of HUD lines, captions and the buttons on the field. */
    fun hud(colors: LifeColors): Color = colors.link
}

/** How one tile looks: its icon and its tone (null: the coach, with the gradient ring). */
private class TileLook(val icon: ImageVector, val tone: Tone?)

private fun HomeTile.look(): TileLook = when (this) {
    HomeTile.COACH -> TileLook(LifeIcons.Spark, null)
    HomeTile.TODAY -> TileLook(LifeIcons.Today, SectionTones.TODAY)
    HomeTile.TASKS -> TileLook(LifeIcons.Tasks, SectionTones.TASKS)
    HomeTile.CALENDAR -> TileLook(LifeIcons.Calendar, Tone.SKY)
    HomeTile.CHAT -> TileLook(LifeIcons.Chat, Tone.VIOLET)
    HomeTile.NOTES -> TileLook(LifeIcons.Notes, Tone.SAND)
    HomeTile.COURSES -> TileLook(LifeIcons.Courses, Tone.ROSE)
    HomeTile.ACTIVITY -> TileLook(LifeIcons.Activity, Tone.TEAL)
    HomeTile.PROJECTS -> TileLook(LifeIcons.Projects, Tone.PEACH)
}

@Composable
private fun HomeTile.title(): String = stringResource(
    when (this) {
        HomeTile.COACH -> R.string.coach_tag
        HomeTile.TODAY -> R.string.home_tile_today
        HomeTile.TASKS -> R.string.nav_tasks
        HomeTile.CALENDAR -> R.string.more_calendar
        HomeTile.CHAT -> R.string.nav_chat
        HomeTile.NOTES -> R.string.more_notes
        HomeTile.COURSES -> R.string.more_courses
        HomeTile.ACTIVITY -> R.string.more_activity
        HomeTile.PROJECTS -> R.string.more_projects
    },
)

/** Keeps the field across a visit to another screen; reads and writes the saved layout. */
class HomeModel(private val settings: SettingsStore) : ViewModel() {
    val tiles = HomeField(HomeTile.ids) { layout -> viewModelScope.launch { settings.setHomeLayout(layout) } }

    init {
        viewModelScope.launch { tiles.loaded(settings.homeLayout.first()) }
    }
}

/** Whether the system's "remove animations" is on (the animator scale is zero). */
private fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * The home of physics tiles (#192, prototype C): every feature as a tile on a free field. Drag
 * a tile and let go and it flies on, slows down and bounces off the edges; tiles may overlap,
 * the touched one is on top; two fingers resize; a tap opens the feature. "Tidy up" slides all
 * into a calm grid, "Reset" brings the default layout back. Each tile shows one line of what the
 * app already knows (the same endpoints as the screens behind the tiles; nothing while it loads,
 * is locked or failed).
 *
 * What is waiting for the owner stays in sight above the field: not paired, a new release, a
 * memory proposal. The classic list of Today is the "Today's tasks" tile.
 */
@Composable
fun HomeScreen(
    core: LifeOsCore,
    onConnectionClick: () -> Unit,
    onPair: () -> Unit,
    onOpen: (HomeTile) -> Unit,
    onOpenConsolidation: () -> Unit,
) {
    val colors = LifeTheme.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val tiles = viewModel<HomeModel>(key = "home-field") { HomeModel(core.settings) }.tiles

    // "Remove animations" may change while the app is in the background: read it on every return.
    LifecycleResumeEffect(tiles) {
        tiles.reducedMotion = animationsOff(context)
        onPauseOrDispose { }
    }
    // Hidden (another screen, the app in the background): everything stops and the layout is saved.
    LifecycleStartEffect(tiles) {
        onStopOrDispose { tiles.hide() }
    }
    // The frame loop: only while something moves. It ends by itself when the tiles are at rest.
    val running = tiles.running
    LaunchedEffect(tiles, running) {
        if (running) {
            while (withFrameNanos { tiles.frame(it) }) Unit
        }
    }

    val lines = liveLines(core)
    val open by rememberUpdatedState(onOpen)
    val showUndo = LocalUndo.current
    val tidied = stringResource(R.string.home_tidied)
    val resetDone = stringResource(R.string.home_reset_done)

    Column(
        Modifier
            .fillMaxSize()
            .background(HomeColors.field(colors)),
    ) {
        TopBar(
            title = stringResource(R.string.nav_today),
            onConnectionClick = onConnectionClick,
            actions = { TopBarAction(LifeIcons.Refresh, stringResource(R.string.home_refresh), onClick = lines.refresh) },
        )
        Column(Modifier.padding(horizontal = LifeDimens.pad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (lines.notPaired) NotPairedNotice(onPair)
            // The computer was not reached: the tiles show its last answer, and say from when.
            lines.savedAtMillis?.let { SavedCopyLine(it) }
            UpdateCard(core) // #138: only while a newer release is waiting
            ConsolidationCard(core, onOpen = onOpenConsolidation) // #151: only while a proposal waits
        }
        val minSize = TilePhysics.MIN_SIZE * density.fontScale.coerceIn(1f, 1.6f)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { tiles.measured(it.width / density.density, it.height / density.density, minSize) }
                .semantics { isTraversalGroup = true },
        ) {
            FieldBackdrop(Modifier.matchParentSize())
            HudCaptions()
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(tiles) { tileGestures(tiles) { id -> HomeTile.fromId(id)?.let { open(it) } } }
                    .semantics {
                        isTraversalGroup = true
                        traversalIndex = 0f
                    },
            ) {
                HomeTile.entries.forEachIndexed { index, tile ->
                    key(tile) {
                        TileBox(tiles, tile, index, lines.of(tile)) { onOpen(tile) }
                    }
                }
            }
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics {
                        isTraversalGroup = true
                        traversalIndex = 1f
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HudButton(stringResource(R.string.home_reset), stringResource(R.string.home_reset_label), icon = null) {
                    tiles.reset()?.let { before -> showUndo(resetDone) { tiles.restore(before) } }
                }
                HudButton(stringResource(R.string.home_tidy), stringResource(R.string.home_tidy_label), LifeIcons.Grid) {
                    tiles.tidy()?.let { before -> showUndo(tidied) { tiles.restore(before) } }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- what the tiles say

/** One live line per tile (null: nothing to say right now), and how to read everything again. */
private class LiveLines(
    val notPaired: Boolean,
    /** Set while the lines come from a saved copy (the computer did not answer): when it was saved. */
    val savedAtMillis: Long?,
    val refresh: () -> Unit,
    private val lines: Map<HomeTile, String?>,
) {
    fun of(tile: HomeTile): String? = lines[tile]?.takeIf { it.isNotBlank() }
}

@Suppress("UNCHECKED_CAST")
private fun <T> UiState<T>.content(): T? = (this as? UiState.Content<T>)?.data

/**
 * Reads what the tiles show from the endpoints of the screens behind them. A value is shown only
 * when the computer (or its saved copy) gave it: loading, locked, not paired and failed leave
 * the line out, so no number is ever made up.
 */
@Composable
private fun liveLines(core: LifeOsCore): LiveLines {
    val planModel = rememberLoad(core, Endpoints.plan) { false }
    val agendaModel = rememberLoad(core, Endpoints.agenda) { false }
    val tasksModel = rememberLoad(core, Endpoints.tasks) { false }
    val notesModel = rememberLoad(core, Endpoints.notes) { false }
    val coursesModel = rememberLoad(core, Endpoints.courseSummary) { false }
    val projectsModel = rememberLoad(core, ProjectEndpoints.list) { false }
    val planState by planModel.state.collectAsStateWithLifecycle()
    val agendaState by agendaModel.state.collectAsStateWithLifecycle()
    val tasksState by tasksModel.state.collectAsStateWithLifecycle()
    val notesState by notesModel.state.collectAsStateWithLifecycle()
    val coursesState by coursesModel.state.collectAsStateWithLifecycle()
    val projectsState by projectsModel.state.collectAsStateWithLifecycle()
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val confirmed by core.confirmed.items.collectAsStateWithLifecycle()
    val ticked by core.justTicked.collectAsStateWithLifecycle()
    val locked by core.locked.collectAsStateWithLifecycle()
    val chat by core.chat.state.collectAsStateWithLifecycle()
    val activityVersion by core.activityChanged.collectAsStateWithLifecycle()

    val lines = mutableMapOf<HomeTile, String?>()

    // Coach: the message the classic card shows (one per app start, by the same rotation).
    val plan: CoachPlan? = planState.content()
    val notes = plan?.messages.orEmpty().filter { it.kind != "question" }
    val shown by core.coachShown.collectAsStateWithLifecycle()
    val message by produceState<CoachMessage?>(null, notes.map { it.id }, shown) {
        value = if (plan != null && notes.isNotEmpty()) core.coachMessage(plan.copy(messages = notes)) else null
    }
    lines[HomeTile.COACH] = when {
        plan == null -> null
        !plan.exists || plan.messages.isEmpty() -> stringResource(R.string.coach_no_plan)
        else -> message?.text
    }

    // Today's tasks: late and today's, as the classic Today card counts them.
    val agenda: Agenda? = agendaState.content()
    if (agenda != null) {
        val left = todaysTasks(agenda, agendaState.listAtMillis, outbox, confirmed, ticked).size
        lines[HomeTile.TODAY] = if (left == 0) stringResource(R.string.home_live_today_none) else pluralStringResource(R.plurals.home_live_today, left, left)
        val events = agenda.eventsToday.size
        lines[HomeTile.CALENDAR] = if (events == 0) stringResource(R.string.home_live_events_none) else pluralStringResource(R.plurals.home_live_events, events, events)
    }

    val taskList: TaskList? = tasksState.content()
    if (taskList != null) {
        val open = TaskLists.open(LocalItems.withTasks(taskList.tasks, LocalItems.tasks(outbox, confirmed, tasksState.listAtMillis)), outbox, ticked).size
        lines[HomeTile.TASKS] = if (open == 0) stringResource(R.string.home_live_tasks_none) else pluralStringResource(R.plurals.home_live_tasks, open, open)
    }

    lines[HomeTile.CHAT] = if (chat.phase != ChatPhase.IDLE) stringResource(R.string.home_live_chat_busy) else null

    val noteList: NoteList? = notesState.content()
    if (noteList != null) {
        val count = noteList.notes.size
        lines[HomeTile.NOTES] = if (count == 0) stringResource(R.string.home_live_notes_none) else pluralStringResource(R.plurals.home_live_notes, count, count)
    }

    val summary: CourseSummary? = coursesState.content()
    if (summary != null && summary.configured) {
        val zone = ZoneId.systemDefault()
        val next = summary.upcoming(System.currentTimeMillis() / 1000).firstOrNull()?.due
        lines[HomeTile.COURSES] = if (next == null) {
            stringResource(R.string.home_live_deadline_none)
        } else {
            val days = ChronoUnit.DAYS.between(today(), Instant.ofEpochSecond(next).atZone(zone).toLocalDate()).toInt().coerceAtLeast(0)
            if (days == 0) stringResource(R.string.home_live_deadline_today) else pluralStringResource(R.plurals.home_live_deadline, days, days)
        }
    }

    // Activity is on the phone only; like its own screen, nothing of it is read while locked.
    val night = remember(activityVersion, locked) { if (locked) null else core.activity.nights().maxByOrNull { it.date } }
    lines[HomeTile.ACTIVITY] = night?.let { stringResource(R.string.home_live_sleep, durationLabel(it.minutes)) }

    val projects: ProjectList? = projectsState.content()
    if (projects != null) {
        val count = projects.projects.size
        lines[HomeTile.PROJECTS] = if (count == 0) stringResource(R.string.home_live_projects_none) else pluralStringResource(R.plurals.home_live_projects, count, count)
    }

    val refresh: () -> Unit = {
        planModel.refresh()
        agendaModel.refresh()
        tasksModel.refresh()
        notesModel.refresh()
        coursesModel.refresh()
        projectsModel.refresh()
    }
    val notPaired = planState is UiState.NotPaired || agendaState is UiState.NotPaired
    // As on the classic Today: the age of the plan's or the agenda's saved copy.
    val copy = listOf(planState, agendaState).firstNotNullOfOrNull { state ->
        (state as? UiState.Content<*>)?.takeIf { it.fromCache && !it.refreshing }?.savedAtMillis
    }
    return LiveLines(notPaired, copy, refresh, lines)
}

// ---------------------------------------------------------------- one tile

/**
 * One tile. Its place is read only while it is placed ([offset] with a lambda) and it has a
 * layer of its own, so a flying tile is moved without composing or drawing it again. Its size
 * is composed (it changes only during a pinch or a slide).
 *
 * For a screen reader the tile is one button: "name, live line", with "open" as its action and
 * "larger"/"smaller" as custom actions (the pinch without two fingers). The order is [index],
 * the order of "Tidy up", wherever the tile is on the field.
 */
@Composable
private fun TileBox(tiles: HomeField, tile: HomeTile, index: Int, live: String?, onOpen: () -> Unit) {
    val id = tile.id
    val size by remember(tiles) { derivedStateOf { tiles.field?.tile(id)?.size?.let { (it * 2).roundToInt() / 2f } ?: 0f } }
    val z by remember(tiles) { derivedStateOf { tiles.field?.tile(id)?.z ?: 0 } }
    if (size <= 0f) return

    val colors = LifeTheme.colors
    val look = tile.look()
    val toned = look.tone?.let { colors.tone(it) }
    val ink = toned?.ink ?: colors.grad2
    val liveColor = toned?.ink ?: HomeColors.hud(colors)
    val name = tile.title()
    val held = tiles.held == id
    var focused by remember { mutableStateOf(false) }

    // A short flash when the tile bounces off an edge.
    val bounce = tiles.bounces[id] ?: 0
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(bounce) {
        if (bounce > 0) {
            lit = true
            delay(200)
            lit = false
        }
    }

    val openLabel = stringResource(R.string.home_open)
    val larger = stringResource(R.string.home_larger)
    val smaller = stringResource(R.string.home_smaller)
    val description = if (live == null) name else "$name. $live"
    val shape = RoundedCornerShape(18.dp)
    val strong = lit || held

    Box(
        Modifier
            .zIndex(z.toFloat())
            .offset {
                val now = tiles.field?.tile(id)
                if (now == null) IntOffset.Zero else IntOffset(((now.x - now.size / 2) * density).roundToInt(), ((now.y - now.height / 2) * density).roundToInt())
            }
            .size(size.dp, (size * TilePhysics.HEIGHT_RATIO).dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar
                if (enter && event.type == KeyEventType.KeyUp) onOpen()
                enter
            }
            .focusable()
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                traversalIndex = index.toFloat()
                onClick(openLabel) {
                    onOpen()
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(larger) {
                        tiles.scale(id, 1.25f)
                        true
                    },
                    CustomAccessibilityAction(smaller) {
                        tiles.scale(id, 0.8f)
                        true
                    },
                )
            }
            // The glow in the tile's own colour, outside its edge; stronger while held or bouncing.
            .drawBehind {
                val radius = 18.dp.toPx()
                val step = 2.5.dp.toPx()
                val rings = if (strong) 7 else 5
                for (ring in 1..rings) {
                    val grow = ring * step
                    val fade = 1f - ring / (rings + 1f)
                    drawRoundRect(
                        color = ink.copy(alpha = (if (strong) 0.34f else 0.2f) * fade * fade),
                        topLeft = Offset(-grow, -grow),
                        size = Size(this.size.width + 2 * grow, this.size.height + 2 * grow),
                        cornerRadius = CornerRadius(radius + grow),
                        style = Stroke(step),
                    )
                }
            }
            .clip(shape)
            .then(
                if (toned == null) {
                    // The coach: the gradient ring of featured elements.
                    Modifier.background(colors.gradient, shape).padding(LifeDimens.ring).background(HomeColors.tile(colors), RoundedCornerShape(15.dp))
                } else {
                    Modifier.background(HomeColors.tile(colors), shape).border(if (strong) 2.dp else 1.dp, ink.copy(alpha = if (strong) 1f else HomeColors.EDGE_ALPHA), shape)
                },
            )
            .then(if (focused) Modifier.border(3.dp, colors.primary, shape) else Modifier)
            // HUD corner ticks in the tile's colour.
            .drawWithContent {
                drawContent()
                val inset = 7.dp.toPx()
                val arm = 9.dp.toPx()
                val line = 2.dp.toPx()
                val tick = ink.copy(alpha = 0.8f)
                val w = this.size.width
                val h = this.size.height
                drawLine(tick, Offset(inset, inset), Offset(inset + arm, inset), line, StrokeCap.Round)
                drawLine(tick, Offset(inset, inset), Offset(inset, inset + arm), line, StrokeCap.Round)
                drawLine(tick, Offset(w - inset, h - inset), Offset(w - inset - arm, h - inset), line, StrokeCap.Round)
                drawLine(tick, Offset(w - inset, h - inset), Offset(w - inset, h - inset - arm), line, StrokeCap.Round)
            },
    ) {
        // What fits depends on the tile's size against the font size: the live line goes first,
        // then the name moves below the icon. The whole text is always in the tile's description.
        val unit = size / LocalDensity.current.fontScale.coerceAtLeast(1f)
        val compact = unit < 124f
        val nameStyle = LifeType.rowTitle.copy(
            fontSize = (if (compact) 12f else (size * 0.11f).coerceIn(13f, 17f)).sp,
            lineHeight = 1.2.em,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.01).em,
        )
        val liveStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size * 0.085f).coerceIn(11f, 13f).sp,
            lineHeight = 1.25.em,
            letterSpacing = 0.02.em,
        )
        val mark: @Composable (Float) -> Unit = { markSize ->
            Box(
                Modifier
                    .size(markSize.dp)
                    .background(toned?.second ?: colors.surface2, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(look.icon, contentDescription = null, tint = ink, modifier = Modifier.size((markSize * 0.6f).dp))
            }
        }
        if (compact) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 9.dp, end = 8.dp, top = 9.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                mark(if (unit < 96f) 22f else 24f)
                Text(name, style = nameStyle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (unit >= 96f && live != null) {
                    Text(live, style = liveStyle, color = liveColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 11.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    mark(30f)
                    Text(name, style = nameStyle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (live != null) {
                    Text(live, style = liveStyle, color = liveColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- the field

/** The grid and scan lines and the four corner marks. In a layer of its own: drawn once, not per frame. */
@Composable
private fun FieldBackdrop(modifier: Modifier = Modifier) {
    val hud = HomeColors.hud(LifeTheme.colors)
    Spacer(
        modifier
            .graphicsLayer()
            .drawBehind {
                val grid = hud.copy(alpha = HomeColors.GRID_ALPHA)
                val scan = hud.copy(alpha = HomeColors.SCAN_ALPHA)
                val hair = 1.dp.toPx()
                val cell = 32.dp.toPx()
                val scanStep = 4.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawLine(scan, Offset(0f, y), Offset(size.width, y), hair)
                    y += scanStep
                }
                var x = 0f
                while (x < size.width) {
                    drawLine(grid, Offset(x, 0f), Offset(x, size.height), hair)
                    x += cell
                }
                y = 0f
                while (y < size.height) {
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), hair)
                    y += cell
                }
                val corner = hud.copy(alpha = 0.45f)
                val inset = 8.dp.toPx()
                val arm = 18.dp.toPx()
                val line = 2.dp.toPx()
                for ((cx, dx) in listOf(inset to 1f, size.width - inset to -1f)) {
                    for ((cy, dy) in listOf(inset to 1f, size.height - inset to -1f)) {
                        drawLine(corner, Offset(cx, cy), Offset(cx + dx * arm, cy), line)
                        drawLine(corner, Offset(cx, cy), Offset(cx, cy + dy * arm), line)
                    }
                }
            },
    )
}

/** The two small captions of the field. The first is decoration; the second says how to play. */
@Composable
private fun HudCaptions() {
    val colors = LifeTheme.colors
    val locale = appLocale()
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.08.em)
    val hint = stringResource(R.string.home_cap_hint)
    Column(Modifier.padding(start = 16.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = pluralStringResource(R.plurals.home_cap_field, HomeTile.entries.size, HomeTile.entries.size).uppercase(locale),
            style = style,
            color = HomeColors.hud(colors),
            modifier = Modifier.clearAndSetSemantics { },
        )
        Text(
            text = hint.uppercase(locale),
            style = style,
            color = colors.muted,
            modifier = Modifier.clearAndSetSemantics { contentDescription = hint },
        )
    }
}

/** A pill on the field in the HUD's style: 48 dp high, an icon and a short word in capitals. */
@Composable
private fun HudButton(text: String, label: String, icon: ImageVector?, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    val hud = HomeColors.hud(colors)
    Row(
        modifier = Modifier
            .heightIn(min = LifeDimens.minTouch)
            .clip(RoundedCornerShape(50))
            .background(HomeColors.tile(colors))
            .border(1.dp, hud.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .padding(start = if (icon != null) 12.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = hud, modifier = Modifier.size(18.dp))
        Text(
            text = text.uppercase(appLocale()),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.08.em),
            color = hud,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- touch

/**
 * All touches of the field, in one place because tiles overlap and a pinch's second finger may
 * land anywhere: the first finger takes the topmost tile under it (a flying one is caught).
 * Moving farther than the system's touch slop is a drag, and letting go then throws the tile
 * with the finger's speed; a second finger resizes; lifting without having moved, within the
 * time of a tap, opens the tile. A tile that was caught in flight is never opened by that touch.
 */
private suspend fun PointerInputScope.tileGestures(tiles: HomeField, onTap: (String) -> Unit) {
    val slop = viewConfiguration.touchSlop
    val tapMillis = max(700L, viewConfiguration.longPressTimeoutMillis)
    val minPinch = 20.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        val press = tiles.press(down.position.x / density, down.position.y / density) ?: return@awaitEachGesture
        down.consume()
        val id = press.id

        fun centre(): Offset = tiles.field?.tile(id)?.let { Offset(it.x * density, it.y * density) } ?: down.position

        var first: PointerId = down.id
        var second: PointerId? = null
        var grab = centre() - down.position
        var moved = press.caught
        var dragged = false
        var pinchDistance = 1f
        var pinchSize = 1f
        var pinchOffset = Offset.Zero
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var tap = false
        var velocity = Velocity.Zero
        try {
            while (true) {
                val event = awaitPointerEvent()
                val a = event.changes.firstOrNull { it.id == first }
                val other = second
                var done = false
                if (other == null) {
                    val joined = event.changes.firstOrNull { it.id != first && it.pressed }
                    if (a == null || !a.pressed) {
                        // The finger left: a tap, or a throw with its speed.
                        if (a != null) {
                            tracker.addPointerInputChange(a)
                            tap = !moved && a.uptimeMillis - down.uptimeMillis < tapMillis
                            if (dragged) velocity = tracker.calculateVelocity()
                        }
                        done = true
                    } else if (joined != null) {
                        // A second finger: the pinch starts from the tile's size now.
                        val tile = tiles.field?.tile(id)
                        if (tile != null) {
                            second = joined.id
                            moved = true
                            dragged = false
                            pinchDistance = max(minPinch, (a.position - joined.position).getDistance())
                            pinchSize = tile.size
                            pinchOffset = centre() - (a.position + joined.position) / 2f
                        }
                    } else {
                        tracker.addPointerInputChange(a)
                        if (moved || (a.position - down.position).getDistance() >= slop) {
                            moved = true
                            dragged = true
                            val to = a.position + grab
                            tiles.drag(id, to.x / density, to.y / density)
                        }
                    }
                } else {
                    val b = event.changes.firstOrNull { it.id == other }
                    val aDown = a != null && a.pressed
                    val bDown = b != null && b.pressed
                    val current = tiles.field
                    if (a != null && b != null && aDown && bDown && current != null) {
                        val distance = max(minPinch, (a.position - b.position).getDistance())
                        val size = TilePhysics.sizeIn(current, pinchSize * distance / pinchDistance)
                        val to = (a.position + b.position) / 2f + pinchOffset * (size / pinchSize)
                        tiles.pinch(id, size, to.x / density, to.y / density)
                    } else if (!aDown && !bDown) {
                        done = true // both fingers left: the tile stays where it is
                    } else {
                        // One finger left: the other goes on dragging, from rest.
                        val keep = if (aDown) a else b
                        if (keep != null) {
                            first = keep.id
                            second = null
                            grab = centre() - keep.position
                            tracker.resetTracking()
                            tracker.addPointerInputChange(keep)
                        }
                    }
                }
                event.changes.forEach { it.consume() }
                if (done) break
            }
        } finally {
            // Also when the touch is taken away (the screen closes): the tile is let go, at rest.
            tiles.release(id, velocity.x / density / 1000f, velocity.y / density / 1000f)
        }
        if (tap) onTap(id)
    }
}
