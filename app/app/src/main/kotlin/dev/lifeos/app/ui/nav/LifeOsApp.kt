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
import androidx.compose.runtime.key
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
import dev.lifeos.app.data.AssignmentMatch
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.SessionLookup
import dev.lifeos.app.data.Task
import dev.lifeos.app.data.TaskTarget
import dev.lifeos.app.push.Destination
import dev.lifeos.app.push.PushOpen
import dev.lifeos.app.ui.components.LocalLooks
import dev.lifeos.app.ui.components.LocalOpenTask
import dev.lifeos.app.ui.components.LocalSnackbar
import dev.lifeos.app.ui.components.LocalUndo
import dev.lifeos.app.ui.screens.ChatScreen
import dev.lifeos.app.ui.screens.CoachScreen
import dev.lifeos.app.ui.screens.ChatListScreen
import dev.lifeos.app.ui.screens.ConsolidationScreen
import dev.lifeos.app.ui.screens.ProjectScreen
import dev.lifeos.app.ui.screens.ProjectsScreen
import dev.lifeos.app.data.Folder
import dev.lifeos.app.data.SessionSummary
import dev.lifeos.app.ui.screens.SessionsScreen
import dev.lifeos.app.ui.screens.ActivityScreen
import dev.lifeos.app.ui.screens.AllGradesScreen
import dev.lifeos.app.ui.screens.AssignmentScreen
import dev.lifeos.app.ui.screens.AllTasksScreen
import dev.lifeos.app.ui.screens.Authenticate
import dev.lifeos.app.ui.screens.CalendarScreen
import dev.lifeos.app.ui.screens.CourseScreen
import dev.lifeos.app.ui.screens.CoursesScreen
import dev.lifeos.app.ui.screens.ModelsScreen
import dev.lifeos.app.ui.screens.renewMoodleSignIn
import dev.lifeos.app.ui.screens.NoteScreen
import dev.lifeos.app.ui.screens.NotesScreen
import dev.lifeos.app.ui.screens.MoreScreen
import dev.lifeos.app.ui.screens.PairScreen
import dev.lifeos.app.ui.screens.PlaceholderScreen
import dev.lifeos.app.ui.screens.SettingsScreen
import dev.lifeos.app.ui.screens.TaskCardSheet
import dev.lifeos.app.ui.screens.TaskSaver
import dev.lifeos.app.ui.screens.TasksScreen
import dev.lifeos.app.ui.screens.TodayScreen
import dev.lifeos.app.ui.screens.titleRes
import dev.lifeos.app.ui.theme.LifeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    openSleep: Boolean = false,
    onSleepOpened: () -> Unit = {},
    openPush: PushOpen? = null,
    onPushOpened: () -> Unit = {},
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
    // The morning question was tapped: Activity opens on its Sleep tab.
    LaunchedEffect(openSleep) {
        if (openSleep) {
            navController.navigate(Routes.ACTIVITY) { launchSingleTop = true }
            onSleepOpened()
        }
    }

    // Chat: the conversation is one for the whole app; it opens on the Chat tab, above the list.
    val openChat: () -> Unit = {
        if (Tab.forRoute(navController.currentDestination?.route) != Tab.CHAT) navController.openTab(Tab.CHAT)
        navController.navigate(Routes.CHAT_THREAD) {
            popUpTo(Routes.CHAT)
            launchSingleTop = true
        }
    }

    // "+" → New chat and every "New chat" (#166): a blank chat at once, no folder to pick.
    val newChat: (String?) -> Unit = { draft ->
        core.chat.newChat(draft = draft)
        openChat()
    }

    // The coach conversation (#114), below the Chat tab like every other conversation.
    val openCoach: () -> Unit = {
        if (Tab.forRoute(navController.currentDestination?.route) != Tab.CHAT) navController.openTab(Tab.CHAT)
        navController.navigate(Routes.CHAT_COACH) {
            popUpTo(Routes.CHAT)
            launchSingleTop = true
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
    val pairing by core.pairing.collectAsStateWithLifecycle()
    val looks by core.looks.collectAsStateWithLifecycle()
    // The courses' colours come from the saved course list: read it once unlocked.
    val connected = core.connection.collectAsStateWithLifecycle().value == dev.lifeos.app.ConnectionState.CONNECTED
    LaunchedEffect(pairing != null, locked, connected) { if (pairing != null && !locked) core.loadLooks() }
    // ---- a tapped push notification (#38): its screen, once the app is unlocked
    val context = androidx.compose.ui.platform.LocalContext.current
    val reminderLost = stringResource(R.string.reminder_lost)
    val sessionLost = stringResource(R.string.push_session_lost)
    val reminderNote = stringResource(R.string.reminder_note_shown)
    LaunchedEffect(openPush, locked) {
        val push = openPush ?: return@LaunchedEffect
        if (locked) return@LaunchedEffect
        // A session's notification asks the computer where it leads. Right after a start the
        // pairing may not be read yet, and the first request may fail: wait a little, try twice.
        suspend fun sessionOf(id: String): SessionLookup.Found {
            withTimeoutOrNull(8_000) { core.pairing.first { it != null } }
            val found = SessionLookup.find(core.api, id)
            if (found != SessionLookup.Found.Unreachable) return found
            delay(2_500)
            return SessionLookup.find(core.api, id)
        }
        when (val destination = push.destination) {
            // An answer, a question or an approval of a session (#148): that conversation, read
            // afresh, so that what waits for the owner is on screen at once.
            is Destination.Chat -> when (val found = sessionOf(destination.sessionId)) {
                is SessionLookup.Found.Session -> {
                    core.chat.open(found.session, found.memory, fresh = true)
                    openChat()
                }
                SessionLookup.Found.Coach -> openCoach()
                else -> {
                    navController.openTab(Tab.CHAT)
                    navController.navigate(Routes.CHAT_SESSIONS) { launchSingleTop = true }
                    showMessage(sessionLost)
                }
            }
            Destination.Sessions -> navController.openTab(Tab.CHAT)
            is Destination.Course -> {
                navController.openTab(Tab.MORE)
                navController.navigate(Routes.COURSES) { launchSingleTop = true }
                navController.navigate(Routes.course(destination.courseId)) { launchSingleTop = true }
            }
            Destination.Courses -> {
                navController.openTab(Tab.MORE)
                navController.navigate(Routes.COURSES) { launchSingleTop = true }
            }
            // The sign-in ran out (#100): Courses, and the site's sign-in opens at once (one tap).
            Destination.CourseSignIn -> {
                navController.openTab(Tab.MORE)
                navController.navigate(Routes.COURSES) { launchSingleTop = true }
                renewMoodleSignIn(core, context)
            }
            // The daily note or a nudge (#76): Today, its coach card showing the message the push
            // named (a question shows its answer card there). The coach conversation is a placeholder.
            is Destination.Coach -> {
                core.showCoachMessage(destination.messageId)
                navController.openTab(Tab.TODAY)
            }
            Destination.Sleep -> navController.navigate(Routes.ACTIVITY) { launchSingleTop = true }
            // A new release (#138): its prompt sits at the top of Today; ask again in case the app was closed.
            // A consolidation proposal waits (#151): its screen, below Today.
            is Destination.Consolidation -> {
                navController.openTab(Tab.TODAY)
                navController.navigate(Routes.CONSOLIDATION) { launchSingleTop = true }
            }
            Destination.AppUpdate -> {
                core.checkUpdate()
                navController.openTab(Tab.TODAY)
            }
            // A session reminder (#75): its session, and its note in one line.
            is Destination.Reminder -> {
                withTimeoutOrNull(8_000) { core.pairing.first { it != null } }
                val reminder = try {
                    SessionLookup.reminder(core.api, destination.reminderId)
                } catch (_: ApiException) {
                    null
                }
                val found = reminder?.let { sessionOf(it.sessionId) }
                when (found) {
                    is SessionLookup.Found.Session -> {
                        core.chat.open(found.session, found.memory)
                        openChat()
                    }
                    SessionLookup.Found.Coach -> openCoach()
                    else -> {
                        // The reminder or its session could not be read: the list of sessions is the nearest place.
                        navController.openTab(Tab.CHAT)
                        navController.navigate(Routes.CHAT_SESSIONS) { launchSingleTop = true }
                        showMessage(reminderLost)
                    }
                }
                reminder?.note?.takeIf { it.isNotBlank() && found is SessionLookup.Found.Session }?.let { showMessage(reminderNote.format(it.trim().take(200))) }
            }
            Destination.Today, Destination.Notice -> navController.openTab(Tab.TODAY)
        }
        // A notice's own title is shown only here, in the unlocked app.
        push.noticeTitle?.let { showMessage(it) }
        onPushOpened()
    }
    // ---- a tapped task (#167): a course assignment's task opens the assignment's page (below the
    // tab it was tapped on), any other task its card. The card shows the task as it was tapped.
    var taskCard by rememberSaveable(stateSaver = TaskSaver) { mutableStateOf<Task?>(null) }
    val openTask: (Task) -> Unit = { task ->
        when (val target = TaskTarget.of(task, looks::match)) {
            is TaskTarget.AssignmentPage -> {
                val root = Tab.forRoute(navController.currentDestination?.route).route
                navController.navigate(Routes.assignment(target.courseId, target.assignmentId, root)) { launchSingleTop = true }
            }
            TaskTarget.Card -> taskCard = task
        }
    }
    // Nothing of a task stays on screen behind the lock.
    LaunchedEffect(locked) { if (locked) taskCard = null }
    CompositionLocalProvider(LocalSnackbar provides showMessage, LocalUndo provides showUndo, LocalLooks provides looks, LocalOpenTask provides openTask) {
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
                    onOpenConsolidation = { navController.navigate(Routes.CONSOLIDATION) { launchSingleTop = true } },
                )
            }
            // The coach conversation (#114): the same screen from the coach card on Today and from the Chat tab.
            listOf(Routes.COACH, Routes.CHAT_COACH).forEach { route ->
                composable(route) {
                    CoachScreen(
                        core = core,
                        onBack = { navController.popBackStack() },
                        onConnectionClick = openSettings,
                        onPair = openPair,
                        onSessions = { navController.navigate(Routes.CHAT_SESSIONS) { launchSingleTop = true } },
                    )
                }
            }
            composable(Routes.CHAT) {
                ChatListScreen(
                    core = core,
                    onConnectionClick = openSettings,
                    onPair = openPair,
                    onOpenChat = openChat,
                    onSessions = { navController.navigate(Routes.CHAT_SESSIONS) { launchSingleTop = true } },
                    onOpenCoach = openCoach,
                    onNewChat = { newChat(null) },
                )
            }
            composable(Routes.CHAT_THREAD) {
                ChatScreen(
                    core = core,
                    onBack = { navController.popBackStack() },
                    onConnectionClick = openSettings,
                    onPair = openPair,
                    onSessions = { navController.navigate(Routes.CHAT_SESSIONS) { launchSingleTop = true } },
                    onNewChat = { newChat(null) },
                )
            }
            // The same list from the Chat tab and from More.
            listOf(Routes.CHAT_SESSIONS, Routes.SESSIONS).forEach { route ->
                composable(route) {
                    SessionsScreen(
                        core = core,
                        onBack = { navController.popBackStack() },
                        onConnectionClick = openSettings,
                        onPair = openPair,
                        onOpenChat = openChat,
                        onNewChat = { newChat(null) },
                        onOpenCoach = openCoach,
                    )
                }
            }
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
            // ---- memory consolidation (#151) and projects (#152)
            composable(Routes.CONSOLIDATION) {
                ConsolidationScreen(core, onBack = { navController.popBackStack() }, onConnectionClick = openSettings, onPair = openPair)
            }
            composable(Routes.PROJECTS) {
                ProjectsScreen(
                    core = core,
                    onBack = { navController.popBackStack() },
                    onConnectionClick = openSettings,
                    onPair = openPair,
                    onOpenProject = { navController.navigate(Routes.project(it)) { launchSingleTop = true } },
                )
            }
            composable(
                route = Routes.PROJECT,
                arguments = listOf(navArgument(Routes.PROJECT_ARG) { type = NavType.StringType }),
            ) { entry ->
                ProjectScreen(
                    core = core,
                    projectId = entry.arguments?.getString(Routes.PROJECT_ARG).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onConnectionClick = openSettings,
                    onPair = openPair,
                    // "Ask about this project": a new chat in its folder, through the chat's own entry point.
                    onAsk = { project ->
                        project.folder?.let { folder ->
                            core.chat.newChat(Folder(folder, project.label))
                            openChat()
                        }
                    },
                    onOpenSession = { project, session ->
                        core.chat.open(SessionSummary(session.id, session.title, folder = project.folder.orEmpty(), lastActivity = session.lastActivity))
                        openChat()
                    },
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
                    onOpenDeadline = { deadline ->
                        // The assignment's own id when the bridge sends it (#144); else the lookup by name and time.
                        val courseId = deadline.courseId
                        val direct = AssignmentMatch.direct(deadline)
                        if (courseId != null) {
                            val route = if (direct != null) Routes.assignment(courseId, direct) else Routes.deadline(courseId, deadline.name, deadline.due)
                            navController.navigate(route) { launchSingleTop = true }
                        }
                    },
                )
            }
            composable(Routes.GRADES) {
                AllGradesScreen(core, onBack = { navController.popBackStack() }, onConnectionClick = openSettings, onPair = openPair)
            }
            composable(
                route = Routes.COURSE,
                arguments = listOf(navArgument(Routes.COURSE_ARG) { type = NavType.LongType }),
            ) { entry ->
                val courseId = entry.arguments?.getLong(Routes.COURSE_ARG) ?: 0
                CourseScreen(
                    core = core,
                    courseId = courseId,
                    onBack = { navController.popBackStack() },
                    onConnectionClick = openSettings,
                    onPair = openPair,
                    onOpenAssignment = { navController.navigate(Routes.assignment(courseId, it)) { launchSingleTop = true } },
                )
            }
            // ---- assignment detail (#129); below Today and Tasks too, for a task of an assignment (#167)
            Routes.ASSIGNMENT_ROUTES.forEach { route ->
            composable(
                route = route,
                arguments = listOf(
                    navArgument(Routes.COURSE_ARG) { type = NavType.LongType },
                    navArgument(Routes.ASSIGNMENT_ARG) { type = NavType.LongType },
                    navArgument(Routes.ASSIGNMENT_NAME_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Routes.ASSIGNMENT_DUE_ARG) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) { entry ->
                val args = entry.arguments
                AssignmentScreen(
                    core = core,
                    courseId = args?.getLong(Routes.COURSE_ARG) ?: 0,
                    assignmentId = args?.getLong(Routes.ASSIGNMENT_ARG) ?: 0,
                    deadlineName = args?.getString(Routes.ASSIGNMENT_NAME_ARG),
                    deadlineDue = args?.getLong(Routes.ASSIGNMENT_DUE_ARG)?.takeIf { it >= 0 },
                    onBack = { navController.popBackStack() },
                    onConnectionClick = openSettings,
                    onPair = openPair,
                    onOpenCourse = { navController.navigate(Routes.course(it)) { launchSingleTop = true } },
                )
            }
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
                    onModels = { navController.navigate(Routes.MODELS) { launchSingleTop = true } },
                )
            }
            // ---- Settings → Models (#124)
            composable(Routes.MODELS) {
                ModelsScreen(core, onBack = { navController.popBackStack() }, onConnectionClick = openSettings, onPair = openPair)
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

    // Sheets are windows of their own: while the app is locked none may be open above the lock screen.
    taskCard?.takeIf { !locked }?.let { task ->
        key(task.id) { TaskCardSheet(core, task, scope, onClose = { taskCard = null }) }
    }
    }

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
            onNewChat = { draft ->
                captureOpen = false
                newChat(draft)
            },
        )
    }
}

/** Opens a bottom-bar tab at its root screen (#166, see [TabTaps]): no sub-page is kept or brought back. */
private fun NavHostController.openTab(tab: Tab) {
    val options = TabTaps.options(tab)
    // Already on the tab's root: nothing to do (the screen keeps its place, nothing is read again).
    if (currentDestination?.route == options.route) return
    navigate(options.route) {
        if (options.popUpToStart) popUpTo(graph.findStartDestination().id) { saveState = options.saveState }
        launchSingleTop = options.singleTop
        restoreState = options.restoreState
    }
}
