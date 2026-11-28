package dev.lifeos.app.ui.screens

import android.content.Context
import android.graphics.BlurMaskFilter
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.lifeos.app.data.ChatPhase
import dev.lifeos.app.data.CommandBar
import dev.lifeos.app.ui.components.Ask
import dev.lifeos.app.ui.components.AskPhase
import dev.lifeos.app.ui.components.AskResult
import dev.lifeos.app.ui.components.CommandHooks
import dev.lifeos.app.ui.components.LocalSnackbar
import dev.lifeos.app.ui.components.MarkdownText
import dev.lifeos.app.ui.components.TopBarAction
import dev.lifeos.app.ui.components.rememberSpeech
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.Agenda
import dev.lifeos.app.data.ChatEndpoints
import dev.lifeos.app.data.CoachPlan
import dev.lifeos.app.data.CourseSummary
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.FolderKinds
import dev.lifeos.app.data.NoteList
import dev.lifeos.app.data.ProjectEndpoints
import dev.lifeos.app.data.ProjectList
import dev.lifeos.app.data.SessionList
import dev.lifeos.app.data.Tone
import dev.lifeos.app.data.UiState
import dev.lifeos.app.data.listAtMillis
import dev.lifeos.app.home.HomeField
import dev.lifeos.app.home.HomeTile
import dev.lifeos.app.home.TilePhysics
import dev.lifeos.app.settings.SettingsStore
import dev.lifeos.app.ui.components.LocalConnection
import dev.lifeos.app.ui.components.NotPairedNotice
import dev.lifeos.app.ui.components.SavedCopyLine
import dev.lifeos.app.ui.components.StatusDot
import dev.lifeos.app.ui.components.appLocale
import dev.lifeos.app.ui.components.descriptionRes
import dev.lifeos.app.ui.components.rememberLoad
import dev.lifeos.app.ui.components.today
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.nav.RevealOrigin
import dev.lifeos.app.ui.theme.LifeColors
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The colours the home field adds to the D3 tokens: prototype C's sci-fi layer, value for value.
 * The field is a little darker than the page, with faint grid and scan lines in the HUD colour;
 * tiles and the "Tidy up" pill are glass: the page colour, slightly see-through, with no blur
 * (the prototype has none either), so a tile under another shows through faintly.
 */
object HomeColors {
    val FieldLight = Color(0xFFF1F2F6)
    val FieldDark = Color(0xFF080B12)
    const val GRID_ALPHA = 0.07f
    const val SCAN_ALPHA_LIGHT = 0.025f
    const val SCAN_ALPHA_DARK = 0.03f

    /** How solid the glass is: 86 % in light, 78 % in dark. */
    const val GLASS_ALPHA_LIGHT = 0.86f
    const val GLASS_ALPHA_DARK = 0.78f

    /** A tile's edge: its tone's ink at 55 %, and a ring of the same ink at 70 % just outside it. */
    const val EDGE_ALPHA = 0.55f
    const val RING_ALPHA = 0.7f

    /** The glow: the ink at 38 %, blurred over 16 dp; on a bounce 70 % over 28 dp. */
    const val GLOW_ALPHA = 0.38f
    const val GLOW_ALPHA_HIT = 0.7f

    fun field(colors: LifeColors): Color = if (colors.isDark) FieldDark else FieldLight

    /** A tile's surface: glass. */
    fun glass(colors: LifeColors): Color = colors.bg.copy(alpha = if (colors.isDark) GLASS_ALPHA_DARK else GLASS_ALPHA_LIGHT)

    /** The colour of HUD lines, the first caption and the pill on the field. */
    fun hud(colors: LifeColors): Color = colors.link

    fun scan(colors: LifeColors): Color = hud(colors).copy(alpha = if (colors.isDark) SCAN_ALPHA_DARK else SCAN_ALPHA_LIGHT)
}

/** How one tile looks: its icon and its tone (null: the coach, with the gradient ring). */
private class TileLook(val icon: ImageVector, val tone: Tone?)

/** Icons and tones of prototype C. */
private fun HomeTile.look(): TileLook = when (this) {
    HomeTile.COACH -> TileLook(LifeIcons.Spark, null)
    HomeTile.TODAY -> TileLook(LifeIcons.Tasks, Tone.MINT)
    HomeTile.AGENDA -> TileLook(LifeIcons.Calendar, Tone.ROSE)
    HomeTile.COURSES -> TileLook(LifeIcons.Courses, Tone.SKY)
    HomeTile.NOTES -> TileLook(LifeIcons.Notes, Tone.SAND)
    HomeTile.CHATS -> TileLook(LifeIcons.Chat, Tone.VIOLET)
    HomeTile.ACTIVITY -> TileLook(LifeIcons.Moon, Tone.TEAL)
    HomeTile.SESSIONS -> TileLook(LifeIcons.Terminal, Tone.PEACH)
    HomeTile.PROJECTS -> TileLook(LifeIcons.Projects, Tone.ROSE)
}

@Composable
private fun HomeTile.title(): String = stringResource(
    when (this) {
        HomeTile.COACH -> R.string.coach_tag
        HomeTile.TODAY -> R.string.home_tile_today
        HomeTile.AGENDA -> R.string.home_tile_agenda
        HomeTile.COURSES -> R.string.more_courses
        HomeTile.NOTES -> R.string.more_notes
        HomeTile.CHATS -> R.string.home_tile_chats
        HomeTile.ACTIVITY -> R.string.home_tile_activity
        HomeTile.SESSIONS -> R.string.more_sessions
        HomeTile.PROJECTS -> R.string.more_projects
    },
)

/**
 * Keeps the field across a visit to another screen; reads and writes the saved layout. When
 * the stored layout is cleared elsewhere (Settings, "Reset the tile layout") the field follows.
 */
class HomeModel(private val settings: SettingsStore) : ViewModel() {
    val tiles = HomeField(HomeTile.spots) { layout -> viewModelScope.launch { settings.setHomeLayout(layout) } }

    init {
        viewModelScope.launch { settings.homeLayout.collect { tiles.loaded(it) } }
    }
}

/** Whether the system's "remove animations" is on (the animator scale is zero). */
private fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * The home of physics tiles (#192, made the same as prototype C in #195): the nine features as
 * glass tiles on a free field. Drag a tile and let go and it flies on, slows down and bounces
 * off the edges; tiles may overlap, the touched one is on top; two fingers resize; a tap grows
 * the tile into its screen. "Tidy up" slides all into a calm grid. The list button shows the
 * same nine as a plain list. The bar at the bottom is the command bar: it opens the app's own
 * (the capture sheet). Each tile shows one line of what the app knows (the endpoints of the
 * screens behind the tiles; nothing while it loads, is locked or failed).
 *
 * What only the app has stays small above the field, only while it applies: not paired, a
 * saved copy's age, a new release, a memory proposal.
 */
@Composable
fun HomeScreen(
    core: LifeOsCore,
    onConnectionClick: () -> Unit,
    onPair: () -> Unit,
    onOpen: (HomeTile, RevealOrigin?) -> Unit,
    onOpenConsolidation: () -> Unit,
    /** The command interpreter and the app's way to do what it hands over (#205). */
    hooks: CommandHooks,
    onOpenCoach: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    val colors = LifeTheme.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current
    val tiles = viewModel<HomeModel>(key = "home-field") { HomeModel(core.settings) }.tiles
    var listMode by rememberSaveable { mutableStateOf(false) }

    // "Remove animations": known before the first layout (it decides the default one), and read
    // again on every return, because it may change while the app is in the background.
    SideEffect { tiles.reducedMotion = animationsOff(context) }
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
    val tidied = stringResource(R.string.home_tidied)
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    var fieldOrigin by remember { mutableStateOf(Offset.Zero) }
    var dockHeight by remember { mutableIntStateOf(0) }
    val minSize = TilePhysics.MIN_SIZE * density.fontScale.coerceIn(1f, 1.6f)

    // The tiles' field ends 8 dp above the command bar, as in the prototype.
    LaunchedEffect(tiles, fieldSize, dockHeight, minSize, listMode) {
        if (!listMode && fieldSize.width > 0 && dockHeight > 0) {
            tiles.measured(fieldSize.width / density.density, (fieldSize.height - dockHeight) / density.density - 8f, minSize)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(HomeColors.field(colors)),
    ) {
        HomeHeader(
            listMode = listMode,
            onConnectionClick = onConnectionClick,
            onToggleTheme = onToggleTheme,
            onToggleList = {
                if (!listMode) tiles.hide()
                listMode = !listMode
            },
        )
        Column(Modifier.padding(horizontal = LifeDimens.pad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (lines.notPaired) NotPairedNotice(onPair)
            // The computer was not reached: the tiles show its last answer, and say from when.
            lines.savedAtMillis?.let { SavedCopyLine(it) }
            UpdateCard(core) // #138: only while a newer release is waiting
            ConsolidationCard(core, onOpen = onOpenConsolidation) // #151: only while a proposal waits
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { fieldSize = it }
                .onGloballyPositioned { fieldOrigin = it.positionInRoot() }
                .semantics { isTraversalGroup = true },
        ) {
            val dockDp = with(density) { dockHeight.toDp() }
            if (listMode) {
                FeatureList(lines, bottomSpace = dockDp + 16.dp, onOpen = { tile, origin -> open(tile, origin) })
            } else {
                FieldBackdrop(Modifier.matchParentSize(), bottomInset = dockDp + 4.dp)
                HudCaptions()
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(tiles) {
                            tileGestures(tiles) { id ->
                                val tile = HomeTile.fromId(id)
                                val body = tiles.field?.tile(id)
                                if (tile != null && body != null) open(tile, body.origin(fieldOrigin, density.density))
                            }
                        }
                        .semantics {
                            isTraversalGroup = true
                            traversalIndex = 0f
                        },
                ) {
                    HomeTile.entries.forEachIndexed { index, tile ->
                        key(tile) {
                            TileBox(tiles, tile, index, lines.of(tile)) {
                                open(tile, tiles.field?.tile(tile.id)?.origin(fieldOrigin, density.density))
                            }
                        }
                    }
                }
                TidyButton(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = dockDp + 4.dp)
                        .semantics { traversalIndex = 1f },
                ) {
                    tiles.tidy()
                    view.announceForAccessibility(tidied)
                }
            }
            // "Ask" (#205): a real message field with microphone and send. The answer shows in a
            // panel above it, on this page; talk goes to the coach and its answer comes back here.
            val languageTag = appLocale().toLanguageTag()
            val coachBusy = stringResource(R.string.command_coach_draft)
            val showMessage = LocalSnackbar.current
            // One Ask for as long as the home is shown: the app's hooks are a new object on every
            // recomposition of the root, so they are read through a state and never a key.
            val currentHooks by rememberUpdatedState(hooks)
            val currentLanguage by rememberUpdatedState(languageTag)
            val ask = remember {
                Ask(
                    launch = core::inBackground,
                    hooks = CommandHooks(
                        run = { currentHooks.run(it) },
                        newRequestId = { currentHooks.newRequestId() },
                        undo = { currentHooks.undo(it) },
                        perform = { currentHooks.perform(it) },
                        leave = {},
                    ),
                    languageTag = { currentLanguage },
                    perform = { currentHooks.perform(it) },
                    onTalk = { talk -> core.askCoach(talk) { showMessage(coachBusy) } },
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .semantics {
                        isTraversalGroup = true
                        traversalIndex = 2f
                    },
            ) {
                AskPanel(core, ask, onOpenCoach)
                CommandDock(
                    Modifier.onSizeChanged { dockHeight = it.height },
                    working = ask.working,
                    onSpeak = rememberSpeech(core::openingSystemScreen) { ask.send(it) },
                    onSend = { ask.send(it) },
                )
            }
        }
    }
}

/** A tile's bounds on the screen (root pixels): where its page grows from. */
private fun dev.lifeos.app.home.Tile.origin(field: Offset, density: Float): RevealOrigin = RevealOrigin(
    left = field.x + (x - size / 2) * density,
    top = field.y + (y - height / 2) * density,
    right = field.x + (x + size / 2) * density,
    bottom = field.y + (y + height / 2) * density,
)

// ---------------------------------------------------------------- the top bar

/**
 * The prototype's top bar: "Home" with the date under it, the connection dot, and three round
 * buttons: the language (it shows the one it switches to), light/dark, and field/list. In the
 * app the first two change the app's own settings (they are kept, unlike in the prototype).
 */
@Composable
private fun HomeHeader(listMode: Boolean, onConnectionClick: () -> Unit, onToggleTheme: () -> Unit, onToggleList: () -> Unit) {
    val colors = LifeTheme.colors
    val locale = appLocale()
    val turkish = locale.language == "tr"
    val date = remember(locale, today()) {
        LocalDate.now().format(DateTimeFormatter.ofPattern(if (turkish) "d MMMM EEEE" else "EEEE d MMMM", locale))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(start = LifeDimens.pad, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_title),
                style = LifeType.screenTitle.copy(lineHeight = 1.15.em),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = date, style = LifeType.small, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val connection = stringResource(LocalConnection.current.descriptionRes())
        HeaderButton(label = connection, onClickLabel = stringResource(R.string.action_open_settings), onClick = onConnectionClick) { StatusDot() }
        HeaderButton(
            label = stringResource(if (turkish) R.string.home_language_english else R.string.home_language_turkish),
            onClick = {
                // The app language, as Settings changes it; the activity is recreated.
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(if (turkish) "en" else "tr"))
            },
        ) {
            Text(if (turkish) "EN" else "TR", style = LifeType.small.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.02.em), color = colors.text)
        }
        HeaderButton(label = stringResource(R.string.home_theme), onClick = onToggleTheme) {
            Icon(LifeIcons.Contrast, contentDescription = null, tint = colors.text)
        }
        HeaderButton(
            label = stringResource(if (listMode) R.string.home_show_field else R.string.home_show_list),
            pressed = listMode,
            onClick = onToggleList,
        ) {
            Icon(if (listMode) LifeIcons.Grid else LifeIcons.List, contentDescription = null, tint = colors.text)
        }
    }
}

/** A round 48 dp button of the top bar; [pressed] marks a switch that is on. */
@Composable
private fun HeaderButton(label: String, onClick: () -> Unit, onClickLabel: String? = null, pressed: Boolean = false, content: @Composable () -> Unit) {
    val colors = LifeTheme.colors
    Box(
        modifier = Modifier
            .size(LifeDimens.minTouch)
            .clip(CircleShape)
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (pressed) selected = true
            },
        contentAlignment = Alignment.Center,
    ) {
        if (pressed) Box(Modifier.size(36.dp).background(colors.surface2, CircleShape))
        content()
    }
}

// ---------------------------------------------------------------- what the tiles say

/** One live line per tile (null: nothing to say right now). */
private class LiveLines(
    val notPaired: Boolean,
    /** Set while the lines come from a saved copy (the computer did not answer): when it was saved. */
    val savedAtMillis: Long?,
    private val lines: Map<HomeTile, String?>,
) {
    fun of(tile: HomeTile): String? = lines[tile]?.takeIf { it.isNotBlank() }
}

@Suppress("UNCHECKED_CAST")
private fun <T> UiState<T>.content(): T? = (this as? UiState.Content<T>)?.data

private val CLOCK = Regex("""\d{2}:\d{2}""")

/**
 * The prototype's live lines with the app's own numbers, read from the endpoints of the screens
 * behind the tiles. A value is shown only when the computer (or its saved copy) gave it:
 * loading, locked, not paired and failed leave the line out, so no number is ever made up.
 */
@Composable
private fun liveLines(core: LifeOsCore): LiveLines {
    val planModel = rememberLoad(core, Endpoints.plan) { false }
    val agendaModel = rememberLoad(core, Endpoints.agenda) { false }
    val notesModel = rememberLoad(core, Endpoints.notes) { false }
    val coursesModel = rememberLoad(core, Endpoints.courseSummary) { false }
    val sessionsModel = rememberLoad(core, ChatEndpoints.sessions) { false }
    val projectsModel = rememberLoad(core, ProjectEndpoints.list) { false }
    val planState by planModel.state.collectAsStateWithLifecycle()
    val agendaState by agendaModel.state.collectAsStateWithLifecycle()
    val notesState by notesModel.state.collectAsStateWithLifecycle()
    val coursesState by coursesModel.state.collectAsStateWithLifecycle()
    val sessionsState by sessionsModel.state.collectAsStateWithLifecycle()
    val projectsState by projectsModel.state.collectAsStateWithLifecycle()
    val outbox by core.outbox.items.collectAsStateWithLifecycle()
    val confirmed by core.confirmed.items.collectAsStateWithLifecycle()
    val ticked by core.justTicked.collectAsStateWithLifecycle()
    val locked by core.locked.collectAsStateWithLifecycle()
    val activityVersion by core.activityChanged.collectAsStateWithLifecycle()

    val lines = mutableMapOf<HomeTile, String?>()

    // Coach: how many tips today's plan holds ("1 new tip").
    val plan: CoachPlan? = planState.content()
    lines[HomeTile.COACH] = when {
        plan == null -> null
        !plan.exists -> stringResource(R.string.coach_no_plan)
        else -> plan.messages.count { it.kind != "question" }.let { pluralStringResource(R.plurals.home_live_coach, it, it) }
    }

    // Today's tasks: late and today's, as the classic Today card counts them; the next event of today.
    val agenda: Agenda? = agendaState.content()
    if (agenda != null) {
        val left = todaysTasks(agenda, agendaState.listAtMillis, outbox, confirmed, ticked).size
        lines[HomeTile.TODAY] = pluralStringResource(R.plurals.home_live_today, left, left)
        val now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        val next = agenda.eventsToday.mapNotNull { it.start?.take(5)?.takeIf(CLOCK::matches) }.sorted().firstOrNull { it >= now }
        lines[HomeTile.AGENDA] = next?.let { stringResource(R.string.home_live_agenda, it) }
    }

    // Courses: the nearest deadline and the days to it ("Lab report · 2 d").
    val summary: CourseSummary? = coursesState.content()
    if (summary != null && summary.configured) {
        val deadline = summary.upcoming(System.currentTimeMillis() / 1000).firstOrNull()
        val due = deadline?.due
        if (deadline != null && due != null) {
            val days = ChronoUnit.DAYS.between(today(), Instant.ofEpochSecond(due).atZone(ZoneId.systemDefault()).toLocalDate()).toInt().coerceAtLeast(0)
            lines[HomeTile.COURSES] = stringResource(R.string.home_live_courses, deadline.name, days)
        }
    }

    val noteList: NoteList? = notesState.content()
    if (noteList != null) lines[HomeTile.NOTES] = noteList.notes.size.let { pluralStringResource(R.plurals.home_live_notes, it, it) }

    // Chats are the conversations started as plain chats; Sessions is every session the computer lists.
    val sessions: SessionList? = sessionsState.content()
    if (sessions != null) {
        val chats = sessions.sessions.count { it.kind == FolderKinds.CHAT }
        lines[HomeTile.CHATS] = pluralStringResource(R.plurals.home_live_chats, chats, chats)
        val all = max(sessions.total, sessions.sessions.size)
        lines[HomeTile.SESSIONS] = pluralStringResource(R.plurals.home_live_sessions, all, all)
    }

    // Activity is on the phone only; like its own screen, nothing of it is read while locked.
    val night = remember(activityVersion, locked) { if (locked) null else core.activity.nights().maxByOrNull { it.date } }
    lines[HomeTile.ACTIVITY] = night?.let { stringResource(R.string.home_live_sleep, durationLabel(it.minutes)) }

    val projects: ProjectList? = projectsState.content()
    if (projects != null) lines[HomeTile.PROJECTS] = projects.projects.size.let { pluralStringResource(R.plurals.home_live_projects, it, it) }

    val notPaired = planState is UiState.NotPaired || agendaState is UiState.NotPaired
    // As on the classic Today: the age of the plan's or the agenda's saved copy.
    val copy = listOf(planState, agendaState).firstNotNullOfOrNull { state ->
        (state as? UiState.Content<*>)?.takeIf { it.fromCache && !it.refreshing }?.savedAtMillis
    }
    return LiveLines(notPaired, copy, lines)
}

// ---------------------------------------------------------------- one tile

/**
 * One tile, drawn as the prototype's `.ptile`: glass, a 1 dp edge in the tone's ink, a ring and
 * a soft glow of the same ink outside it, two corner ticks, the icon on its round mark, the name
 * and the live line in the monospace font. A bounce flashes the glow (it fades in 220 ms); the
 * tile under the finger gets a drop shadow instead.
 *
 * Its place is read only while it is placed ([offset] with a lambda) and it has a layer of its
 * own, so a flying tile is moved without composing or drawing it again. Its size is composed
 * (it changes only during a pinch or a slide).
 *
 * For a screen reader the tile is one button: "name. live line", with "open" as its action and
 * "larger"/"smaller" as custom actions (the pinch without two fingers). The order is [index],
 * the order of "Tidy up", wherever the tile is on the field. With a keyboard: Enter opens, the
 * arrow keys move (24 dp, with Shift 64), plus and minus resize (16 dp).
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
    val glass = HomeColors.glass(colors)
    val name = tile.title()
    var focused by remember { mutableStateOf(false) }

    // The flash of a bounce: on at once, 200 ms long, then it fades out. The held tile is lifted.
    val bounce = tiles.bounces[id] ?: 0
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(bounce) {
        if (bounce > 0) {
            lit = true
            delay(200)
            lit = false
        }
    }
    val hit = animateFloatAsState(if (lit) 1f else 0f, if (lit) snap() else tween(220, easing = EaseOut), label = "hit")
    val lift = animateFloatAsState(if (tiles.held == id) 1f else 0f, tween(220, easing = EaseOut), label = "lift")

    val openLabel = stringResource(R.string.home_open)
    val larger = stringResource(R.string.home_larger)
    val smaller = stringResource(R.string.home_smaller)
    val description = if (live == null) name else "$name. $live"
    val shape = RoundedCornerShape(18.dp)
    val edge = if (toned == null) LifeDimens.ring else 1.dp

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
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = if (event.isShiftPressed) 64f else 24f
                when (event.key) {
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> onOpen()
                    Key.DirectionLeft -> tiles.nudge(id, -step, 0f)
                    Key.DirectionRight -> tiles.nudge(id, step, 0f)
                    Key.DirectionUp -> tiles.nudge(id, 0f, -step)
                    Key.DirectionDown -> tiles.nudge(id, 0f, step)
                    Key.Plus, Key.Equals, Key.NumPadAdd -> tiles.grow(id, 16f)
                    Key.Minus, Key.NumPadSubtract -> tiles.grow(id, -16f)
                    else -> return@onKeyEvent false
                }
                true
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
            // Outside the tile: the ring, the glow or the drop shadow (never under the glass).
            .drawWithCache {
                val radius = 18.dp.toPx()
                val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, this@drawWithCache.size.width, this@drawWithCache.size.height, CornerRadius(radius))) }
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                onDrawBehind {
                    val h = hit.value
                    val l = lift.value
                    val strong = max(h, l)
                    clipPath(outline, ClipOp.Difference) {
                        val glowAlpha = (HomeColors.GLOW_ALPHA + (HomeColors.GLOW_ALPHA_HIT - HomeColors.GLOW_ALPHA) * h) * (1f - l)
                        if (glowAlpha > 0f) softShadow(paint, ink.copy(alpha = glowAlpha), (16f + 12f * h).dp.toPx(), 0f, radius)
                        if (l > 0f) softShadow(paint, Color.Black.copy(alpha = 0.35f * l), 36.dp.toPx(), 14.dp.toPx(), radius)
                        val spread = (1f + strong).dp.toPx()
                        drawRoundRect(
                            color = ink.copy(alpha = HomeColors.RING_ALPHA + (1f - HomeColors.RING_ALPHA) * strong),
                            topLeft = Offset(-spread, -spread),
                            size = Size(this.size.width + 2 * spread, this.size.height + 2 * spread),
                            cornerRadius = CornerRadius(radius + spread),
                        )
                    }
                }
            }
            .clip(shape)
            .background(glass, shape)
            .then(
                if (toned == null) {
                    Modifier.border(edge, colors.gradient, shape) // the coach: the gradient ring of featured elements
                } else {
                    Modifier.border(edge, ink.copy(alpha = HomeColors.EDGE_ALPHA), shape)
                },
            )
            .then(if (focused) Modifier.border(3.dp, colors.primary, shape) else Modifier)
            // HUD corner ticks in the tile's colour: top left and bottom right, 5 dp inside the edge.
            .drawWithContent {
                drawContent()
                val line = 2.dp.toPx()
                val near = edge.toPx() + 5.dp.toPx() + line / 2
                val far = edge.toPx() + 15.dp.toPx()
                val curve = 5.dp.toPx()
                val w = this.size.width
                val h = this.size.height
                val ticks = Path().apply {
                    moveTo(near, far)
                    lineTo(near, near + curve)
                    arcTo(Rect(near, near, near + 2 * curve, near + 2 * curve), 180f, 90f, false)
                    lineTo(far, near)
                    moveTo(w - near, h - far)
                    lineTo(w - near, h - near - curve)
                    arcTo(Rect(w - near - 2 * curve, h - near - 2 * curve, w - near, h - near), 0f, 90f, false)
                    lineTo(w - far, h - near)
                }
                drawPath(ticks, ink.copy(alpha = 0.8f), style = Stroke(line))
            },
    ) {
        // The prototype's container rules, measured against the font size: at most 124 dp of
        // content width the name moves below the icon; at most 62 dp of content height the live
        // line is left out. The whole text is always in the tile's description.
        val unit = size / LocalDensity.current.fontScale.coerceAtLeast(1f)
        val compact = unit - 22f <= 124f
        val showLive = live != null && (unit * TilePhysics.HEIGHT_RATIO).roundToInt() - 19f > 62f
        val nameStyle = TextStyle(
            fontSize = (if (compact) 12f else ((size - 22f) * 0.11f).coerceIn(12f, 17f)).sp,
            lineHeight = 1.15.em,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.01).em,
        )
        val liveStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = ((size - 22f) * 0.085f).coerceIn(10f, 13f).sp,
            lineHeight = 1.25.em,
            letterSpacing = 0.02.em,
        )
        // 30 dp in the prototype; on the very smallest tiles a little less, so the name is not cut.
        val markSize = if (unit < 92f) 22.dp else 30.dp
        val mark: @Composable () -> Unit = {
            Box(
                Modifier
                    .size(markSize)
                    .background(toned?.second ?: colors.surface2, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(look.icon, contentDescription = null, tint = ink, modifier = Modifier.size(markSize * 0.6f))
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = edge + 10.dp, end = edge + 10.dp, top = edge + 9.dp, bottom = edge + 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    mark()
                    Text(name, style = nameStyle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    mark()
                    Text(name, style = nameStyle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (showLive && live != null) {
                Text(live, style = liveStyle, color = liveColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A blurred rounded rectangle the size of the tile, moved down by [dy]: CSS `box-shadow` with a
 * blur of [blur]. From Android 9 it is a real blur (a mask filter, which the GPU draws for a
 * rounded rectangle without an off-screen pass); before that, a few fading rings.
 */
private fun DrawScope.softShadow(paint: android.graphics.Paint, color: Color, blur: Float, dy: Float, radius: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        paint.color = color.toArgb()
        // CSS blur b is a Gaussian of sigma b / 2; the mask filter's sigma is 0.577 r + 0.5.
        paint.maskFilter = BlurMaskFilter(max(1f, (blur / 2 - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL)
        drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, dy, size.width, size.height + dy, radius, radius, paint) }
    } else {
        val rings = 6
        val step = blur / rings
        for (ring in 1..rings) {
            val fade = 1f - ring / (rings + 1f)
            val grow = ring * step - step / 2
            drawRoundRect(
                color = color.copy(alpha = color.alpha * 0.5f * fade * fade),
                topLeft = Offset(-grow, dy - grow),
                size = Size(size.width + 2 * grow, size.height + 2 * grow),
                cornerRadius = CornerRadius(radius + grow),
                style = Stroke(step),
            )
        }
    }
}

// ---------------------------------------------------------------- the field

/**
 * The grid and scan lines and the four corner marks (the lower two just above the command bar).
 * In a layer of its own: drawn once, not per frame.
 */
@Composable
private fun FieldBackdrop(modifier: Modifier = Modifier, bottomInset: androidx.compose.ui.unit.Dp) {
    val colors = LifeTheme.colors
    val hud = HomeColors.hud(colors)
    val scan = HomeColors.scan(colors)
    Spacer(
        modifier
            .graphicsLayer()
            .drawBehind {
                val grid = hud.copy(alpha = HomeColors.GRID_ALPHA)
                val hair = 1.dp.toPx()
                val cell = 32.dp.toPx()
                val scanStep = 4.dp.toPx()
                var y = hair / 2
                while (y < size.height) {
                    drawLine(scan, Offset(0f, y), Offset(size.width, y), hair)
                    y += scanStep
                }
                var x = hair / 2
                while (x < size.width) {
                    drawLine(grid, Offset(x, 0f), Offset(x, size.height), hair)
                    x += cell
                }
                y = hair / 2
                while (y < size.height) {
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), hair)
                    y += cell
                }
                val corner = hud.copy(alpha = 0.45f)
                val inset = 8.dp.toPx()
                val arm = 18.dp.toPx()
                val line = 2.dp.toPx()
                val bottom = size.height - bottomInset.toPx()
                for ((cx, dx) in listOf(inset + line / 2 to 1f, size.width - inset - line / 2 to -1f)) {
                    for ((cy, dy) in listOf(inset + line / 2 to 1f, bottom - line / 2 to -1f)) {
                        drawLine(corner, Offset(cx - dx * line / 2, cy), Offset(cx + dx * arm, cy), line)
                        drawLine(corner, Offset(cx, cy - dy * line / 2), Offset(cx, cy + dy * arm), line)
                    }
                }
            },
    )
}

/**
 * The two captions at the top of the field, in capitals in the monospace font: how many tiles
 * there are (left, in the HUD colour) and how to play (right). The first is decoration.
 */
@Composable
private fun BoxScope.HudCaptions() {
    val colors = LifeTheme.colors
    val locale = appLocale()
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 1.2.em, letterSpacing = 0.08.em)
    val hint = stringResource(R.string.home_cap_hint)
    val help = stringResource(R.string.home_field_help) // the prototype's description of the field, for a screen reader
    Text(
        text = pluralStringResource(R.plurals.home_cap_field, HomeTile.entries.size, HomeTile.entries.size).uppercase(locale),
        style = style.copy(fontWeight = FontWeight.Bold),
        color = HomeColors.hud(colors),
        maxLines = 1,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 32.dp, top = 12.dp)
            .clearAndSetSemantics { },
    )
    Text(
        text = hint.uppercase(locale),
        style = style.copy(fontWeight = FontWeight.SemiBold),
        color = colors.muted,
        maxLines = 1,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(end = 32.dp, top = 12.dp)
            .clearAndSetSemantics { contentDescription = help },
    )
}

/** "Tidy up": a glass pill above the command bar, 48 dp high, the grid icon and the word in capitals. */
@Composable
private fun TidyButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    val hud = HomeColors.hud(colors)
    Row(
        modifier = modifier
            .heightIn(min = LifeDimens.minTouch)
            .clip(RoundedCornerShape(50))
            .background(HomeColors.glass(colors))
            .border(1.dp, hud.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClickLabel = stringResource(R.string.home_tidy_label), role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(LifeIcons.Grid, contentDescription = null, tint = hud, modifier = Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.home_tidy).uppercase(appLocale()),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.08.em),
            color = hud,
            maxLines = 1,
        )
    }
}

/**
 * The answer of the home's "Ask" (#205), above its bar: what was understood and done, each
 * with Undo, the one question, what cannot be done; and for talk the coach's answer, here.
 * Hidden while there is nothing to show.
 */
@Composable
private fun AskPanel(core: LifeOsCore, ask: Ask, onOpenCoach: () -> Unit) {
    if (ask.phase == AskPhase.Idle) return
    val colors = LifeTheme.colors
    val talked = ask.talked
    val coach by core.coach.state.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(max = 340.dp)
            .background(colors.surface, shape)
            .border(1.dp, colors.divider, shape)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ask.asked.orEmpty(),
                style = LifeType.small,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TopBarAction(LifeIcons.Close, stringResource(R.string.command_panel_close), enabled = !ask.working, onClick = ask::clear)
        }
        AskResult(ask, Modifier.fillMaxWidth().padding(end = 10.dp))
        if (talked != null) {
            // The coach's answer to the talk: the message after the owner's, as it arrives.
            val at = coach.messages.indexOfLast { it.fromOwner && it.text == talked }
            val answer = if (at >= 0) coach.messages.drop(at + 1).firstOrNull { !it.fromOwner && it.text.isNotBlank() } else null
            Text(stringResource(R.string.command_coach_answer), style = LifeType.rowTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            if (answer != null) {
                MarkdownText(answer.text, Modifier.fillMaxWidth().padding(end = 10.dp))
            }
            if (answer == null || coach.phase != ChatPhase.IDLE) {
                Text(
                    stringResource(R.string.command_asking_coach),
                    style = LifeType.small,
                    color = colors.muted,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            LinkAction(stringResource(R.string.command_open_coach), onClick = onOpenCoach)
        }
    }
}

/**
 * The command bar at the bottom of the home (#205): the prototype's gradient ring and spark
 * around a real message field, with the microphone and send. What is typed or spoken goes to
 * the command interpreter; its answer shows in the panel above ([AskPanel]).
 */
@Composable
private fun CommandDock(modifier: Modifier = Modifier, working: Boolean, onSpeak: () -> Unit, onSend: (String) -> Unit) {
    val colors = LifeTheme.colors
    var text by rememberSaveable { mutableStateOf("") }
    val canSend = text.isNotBlank() && !working
    val send = {
        if (canSend) {
            onSend(text)
            text = ""
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(colors.bg.copy(alpha = 0f), colors.bg), startY = 0f, endY = 22.dp.toPx()))
            }
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.gradient, RoundedCornerShape(28.dp))
                .padding(LifeDimens.ring)
                .background(colors.surface, RoundedCornerShape(25.dp))
                .padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(LifeIcons.Spark, contentDescription = null, tint = colors.muted) }
            val label = stringResource(R.string.home_command_label)
            Box(
                Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_command_hint),
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(CommandBar.MAX_TEXT) },
                    textStyle = TextStyle(fontSize = 16.sp, color = colors.text),
                    cursorBrush = SolidColor(colors.primary),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = label },
                )
            }
            val speak = stringResource(R.string.command_speak)
            Box(
                Modifier
                    .size(LifeDimens.minTouch)
                    .clip(CircleShape)
                    .clickable(enabled = !working, onClickLabel = speak, role = Role.Button, onClick = onSpeak)
                    .semantics { contentDescription = speak },
                contentAlignment = Alignment.Center,
            ) { Icon(LifeIcons.Mic, contentDescription = null, tint = colors.text) }
            val sendLabel = stringResource(R.string.chat_send)
            Box(
                Modifier
                    .size(LifeDimens.minTouch)
                    .clip(CircleShape)
                    .clickable(enabled = canSend, onClickLabel = sendLabel, role = Role.Button, onClick = send)
                    .semantics {
                        contentDescription = sendLabel
                        if (!canSend) disabled()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .alpha(if (canSend) 1f else 0.45f)
                        .background(colors.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(LifeIcons.Send, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

// ---------------------------------------------------------------- the list view

/**
 * The same nine features as a plain list (the list button of the top bar): the accessible
 * alternative to the field. A row opens its screen in a circle growing from its mark.
 */
@Composable
private fun FeatureList(lines: LiveLines, bottomSpace: androidx.compose.ui.unit.Dp, onOpen: (HomeTile, RevealOrigin?) -> Unit) {
    val colors = LifeTheme.colors
    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = LifeDimens.pad, end = LifeDimens.pad, top = 6.dp, bottom = bottomSpace),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeTile.entries.forEach { tile ->
            val look = tile.look()
            val toned = look.tone?.let { colors.tone(it) }
            val shape = RoundedCornerShape(20.dp)
            var markX by remember { mutableFloatStateOf(0f) }
            var markY by remember { mutableFloatStateOf(0f) }
            val live = lines.of(tile)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .clip(shape)
                    .background(colors.surface, shape)
                    .then(if (toned == null) Modifier.border(LifeDimens.ring, colors.gradient, shape) else Modifier.border(1.dp, colors.divider, shape))
                    .clickable(onClickLabel = stringResource(R.string.home_open), role = Role.Button) {
                        val r = with(density) { 26.dp.toPx() }
                        onOpen(tile, RevealOrigin(markX - r, markY - r, markX + r, markY + r, circle = true))
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .onGloballyPositioned {
                            val at = it.positionInRoot()
                            markX = at.x + it.size.width / 2f
                            markY = at.y + it.size.height / 2f
                        }
                        .background(toned?.second ?: colors.surface2, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(look.icon, contentDescription = null, tint = toned?.ink ?: colors.grad2, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(tile.title(), style = LifeType.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (live != null) Text(live, style = LifeType.small, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                Icon(LifeIcons.Chevron, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- touch

/**
 * All touches of the field, in one place because tiles overlap and a pinch's second finger may
 * land anywhere: the first finger takes the topmost tile under it (a flying one stops; a touch
 * on the empty field only ends a slide). Moving farther than the system's touch slop is a drag,
 * and letting go then throws the tile with the finger's speed; a second finger resizes, and
 * when one of the two leaves the other goes on dragging; lifting without having moved, within
 * 700 ms, opens the tile.
 */
private suspend fun PointerInputScope.tileGestures(tiles: HomeField, onTap: (String) -> Unit) {
    val slop = viewConfiguration.touchSlop
    val tapMillis = 700L
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
        var moved = false
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
