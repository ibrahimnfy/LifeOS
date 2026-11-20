package dev.lifeos.app.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.UNDO_MILLIS
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withTimeoutOrNull
import dev.lifeos.app.settings.ThemeSetting
import dev.lifeos.app.ui.components.BottomBar
import dev.lifeos.app.ui.components.CaptureKind
import dev.lifeos.app.ui.components.CaptureSheet
import dev.lifeos.app.ui.components.LocalSnackbar
import dev.lifeos.app.ui.components.LocalUndo
import dev.lifeos.app.ui.screens.ChatScreen
import dev.lifeos.app.ui.screens.ActivityScreen
import dev.lifeos.app.ui.screens.AllGradesScreen
import dev.lifeos.app.ui.screens.AllTasksScreen
import dev.lifeos.app.ui.screens.Authenticate
import dev.lifeos.app.ui.screens.CalendarScreen
import dev.lifeos.app.ui.screens.CourseScreen
import dev.lifeos.app.ui.screens.CoursesScreen
import dev.lifeos.app.ui.screens.NoteScreen
import dev.lifeos.app.ui.screens.NotesScreen
import dev.lifeos.app.ui.screens.MoreScreen
import dev.lifeos.app.ui.screens.PairScreen
import dev.lifeos.app.ui.screens.PlaceholderScreen
import dev.lifeos.app.ui.screens.SettingsScreen
import dev.lifeos.app.ui.screens.TasksScreen
import dev.lifeos.app.ui.screens.TodayScreen
import dev.lifeos.app.ui.screens.titleRes
import dev.lifeos.app.ui.theme.LifeTheme
import kotlinx.coroutines.launch

/** The whole app: navigation host, bottom bar, capture sheet and snackbar. */
@Composable
fun LifeOsApp(
    core: LifeOsCore,
    theme: ThemeSetting,
    onThemeChange: (ThemeSetting) -> Unit,
    authenticate: Authenticate,
    openPairing: Boolean,
    onPairingOpened: () -> Unit,
    captureRequest: String?,
    onCaptureHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedTab = Tab.forRoute(backStackEntry?.destination?.route)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var captureOpen by rememberSaveable { mutableStateOf(false) }
    var capturePrefill by rememberSaveable { mutableStateOf("") }
    val savedMessage = stringResource(R.string.capture_saved)
    val waitingMessage = stringResource(R.string.capture_waiting)
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
    // The home-screen shortcut or another app's "share" asked for the capture sheet.
    LaunchedEffect(captureRequest) {
        if (captureRequest != null) {
            capturePrefill = captureRequest
            captureOpen = true
            onCaptureHandled()
        }
    }
    // Settings lives under More: switch to that tab first, so no other tab keeps it on its stack.
    val openSettings: () -> Unit = {
        navController.openTab(Tab.MORE)
        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
    }

    val openPair: () -> Unit = {
        navController.openTab(Tab.MORE)
        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
        navController.navigate(Routes.PAIR) { launchSingleTop = true }
    }
    // The welcome screen or a test aid asked for the pairing screen.
    LaunchedEffect(openPairing) {
        if (openPairing) {
            openPair()
            onPairingOpened()
        }
    }

    val undoLabel = stringResource(R.string.action_undo)
    val showUndo: (String, () -> Unit) -> Unit = { message, onUndo ->
        scope.launch {
            // Visible exactly as long as the tick can be taken back; the timer itself is the core's.
            val result = withTimeoutOrNull(UNDO_MILLIS - 500) {
                snackbarHostState.showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite)
            }
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }
    val locked by core.locked.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalSnackbar provides showMessage, LocalUndo provides showUndo) {
    Scaffold(
        containerColor = LifeTheme.colors.bg,
        contentColor = LifeTheme.colors.text,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomBar(
                selected = selectedTab,
                onSelect = { navController.openTab(it) },
                onCapture = {
                    capturePrefill = ""
                    captureOpen = true
                },
            )
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TODAY,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable(Routes.TODAY) {
                TodayScreen(
                    core = core,
                    onPair = openPair,
                    onConnectionClick = openSettings,
                    onOpenCoach = { navController.navigate(Routes.COACH) { launchSingleTop = true } },
                )
            }
            composable(Routes.COACH) {
                PlaceholderScreen(
                    title = stringResource(R.string.coach_conversation),
                    onBack = { navController.popBackStack() },
                    onConnectionClick = openSettings,
                )
            }
            composable(Routes.CHAT) { ChatScreen(onConnectionClick = openSettings) }
            composable(Routes.TASKS) {
                TasksScreen(
                    core = core,
                    onPair = openPair,
                    onConnectionClick = openSettings,
                    onShowAll = { navController.navigate(Routes.ALL_TASKS) },
                )
            }
            composable(Routes.ALL_TASKS) {
                AllTasksScreen(core, onBack = { navController.popBackStack() }, onConnectionClick = openSettings, onPair = openPair)
            }
            composable(Routes.NOTES) {
                NotesScreen(
                    core = core,
                    onBack = { navController.popBackStack() },
                    onConnectionClick = openSettings,
                    onPair = openPair,
                    onOpenNote = { navController.navigate(Routes.note(it)) { launchSingleTop = true } },
                )
            }
            composable(
                route = Routes.NOTE,
                arguments = listOf(navArgument(Routes.NOTE_ARG) { type = NavType.StringType }),
            ) { entry ->
                NoteScreen(
                    core = core,
                    noteId = entry.arguments?.getString(Routes.NOTE_ARG).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onConnectionClick = openSettings,
                    onPair = openPair,
                )
            }
            composable(Routes.ACTIVITY) {
                ActivityScreen(core, onBack = { navController.popBackStack() }, onConnectionClick = openSettings)
            }
            composable(Routes.CALENDAR) {
                CalendarScreen(core, onBack = { navController.popBackStack() }, onConnectionClick = openSettings, onPair = openPair)
            }
            composable(Routes.COURSES) {
                CoursesScreen(
                    core = core,
                    onBack = { navController.popBackStack() },
                    onConnectionClick = openSettings,
                    onPair = openPair,
                    onOpenCourse = { navController.navigate(Routes.course(it)) { launchSingleTop = true } },
                    onGrades = { navController.navigate(Routes.GRADES) { launchSingleTop = true } },
                )
            }
            composable(Routes.GRADES) {
                AllGradesScreen(core, onBack = { navController.popBackStack() }, onConnectionClick = openSettings, onPair = openPair)
            }
            composable(
                route = Routes.COURSE,
                arguments = listOf(navArgument(Routes.COURSE_ARG) { type = NavType.LongType }),
            ) { entry ->
                CourseScreen(
                    core = core,
                    courseId = entry.arguments?.getLong(Routes.COURSE_ARG) ?: 0,
                    onBack = { navController.popBackStack() },
                    onConnectionClick = openSettings,
                    onPair = openPair,
                )
            }
            composable(Routes.MORE) {
                MoreScreen(
                    onConnectionClick = openSettings,
                    onOpenSection = { navController.navigate(Routes.section(it)) },
                    onOpenSettings = openSettings,
                )
            }
            composable(
                route = Routes.SECTION,
                arguments = listOf(navArgument(Routes.SECTION_ARG) { type = NavType.StringType }),
            ) { entry ->
                val section = MoreSection.fromId(entry.arguments?.getString(Routes.SECTION_ARG))
                PlaceholderScreen(
                    title = stringResource(section?.titleRes() ?: R.string.nav_more),
                    onBack = { navController.popBackStack() },
                    onConnectionClick = openSettings,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    core = core,
                    theme = theme,
                    onThemeChange = onThemeChange,
                    onPair = { navController.navigate(Routes.PAIR) { launchSingleTop = true } },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.PAIR) {
                PairScreen(
                    core = core,
                    authenticate = authenticate,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    }

    // Sheets are windows of their own: while the app is locked none may be open above the lock screen.
    if (captureOpen && !locked) {
        CaptureSheet(
            prefill = capturePrefill,
            onDismiss = { captureOpen = false },
            onSaved = { kind, text, due ->
                captureOpen = false
                scope.launch {
                    val sent = core.capture(note = kind == CaptureKind.NOTE, text = text, due = due)
                    snackbarHostState.showSnackbar(if (sent) savedMessage else waitingMessage)
                }
            },
        )
    }
}

/** Switches the bottom-bar tab, keeping each tab's own back stack and state. */
private fun NavHostController.openTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
