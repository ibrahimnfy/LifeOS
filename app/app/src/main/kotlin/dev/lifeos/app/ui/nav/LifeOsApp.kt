package dev.lifeos.app.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.lifeos.app.home.HomeTile
import dev.lifeos.app.settings.HomeStyle
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
import dev.lifeos.app.ui.components.LocalUndoCommand
import dev.lifeos.app.ui.components.UndoBar
import dev.lifeos.app.ui.screens.ChatScreen
import dev.lifeos.app.ui.screens.CoachScreen
import dev.lifeos.app.ui.screens.HomeScreen
import dev.lifeos.app.ui.screens.ChatListScreen
import dev.lifeos.app.ui.screens.ConsolidationScreen
import dev.lifeos.app.ui.screens.ProjectScreen
import dev.lifeos.app.ui.screens.ProjectsScreen
import dev.lifeos.app.data.Folder
import dev.lifeos.app.data.SessionSummary
import dev.lifeos.app.data.ChatSwitch
import dev.lifeos.app.data.FolderKinds
import dev.lifeos.app.data.SwitchStep
import dev.lifeos.app.data.asFolder
import dev.lifeos.app.data.BridgeJson
import dev.lifeos.app.data.CommandAnswer
import dev.lifeos.app.data.CommandBar
import dev.lifeos.app.data.CommandOutcome
import dev.lifeos.app.data.SwitchFolder
import dev.lifeos.app.ui.components.CommandHooks
import dev.lifeos.app.ui.components.outcomeLine
import dev.lifeos.app.data.step
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
    // A search the command bar asked for (#185): handed to the Notes screen once.
    var notesQuery by rememberSaveable { mutableStateOf<String?>(null) }
    // The home (#192): the physics tiles or the classic list, and whether the list is open above the tiles.
    val homeStyle by core.settings.homeStyle.collectAsStateWithLifecycle(initialValue = null)
    var todayList by rememberSaveable { mutableStateOf(false) }
    // Where a page opened from a home tile grows from (#195): the tile's bounds, for pages and for the list in place.
    val reveal = remember { RevealState() }
    var todayOrigin by remember { mutableStateOf<RevealOrigin?>(null) }
    val homeOnTop = { backStackEntry?.destination?.route == Routes.TODAY }
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

    // Moving between conversations by asking (#164, #168): a switch of an answer, or the owner's
    // pick on its card. A conversation continues in its own folder, the coach's on its own
    // screen; a new one starts in the folder named (the blank chats: a blank chat), with the
    // owner's request as its first message when the switch carried one (#187).
    val folderNotOpened = stringResource(R.string.switch_folder_failed)
    val switchTo: (ChatSwitch) -> Unit = { switch ->
        when (val step = switch.step()) {
            SwitchStep.Coach -> openCoach()
            is SwitchStep.Open -> {
                core.chat.open(step.session, memory = step.session.kind == FolderKinds.MEMORY)
                openChat()
            }
            is SwitchStep.New -> {
                core.chat.startFrom(step.folder, step.forward, step.origin, step.capable)
                openChat()
            }
            // A folder found by name (#179): the computer opens it (it may add it to the allowed
            // folders, by its own rules), then a new chat starts there.
            is SwitchStep.Pick -> scope.launch {
                try {
                    val opened = core.api.openFolder(step.path)
                    core.chat.startFrom(opened.folder.asFolder(), step.forward, step.origin, step.capableIn(opened.folder))
                    openChat()
                } catch (_: ApiException) {
                    showMessage(folderNotOpened)
                }
            }
            null -> Unit
        }
    }

    // The one Undo bar (#207). Visible a little shorter than a tick or a deletion can be taken
    // back in the core (its timers), so a tap on Undo always comes in time.
    val undoLabel = stringResource(R.string.action_undo)
    val undoFailed = stringResource(R.string.command_undo_failed)
    val undoBar = remember(undoLabel) { UndoBar(snackbarHostState, scope, undoLabel, UNDO_MILLIS - 500) }
    val showUndo: (String, () -> Unit) -> Unit = undoBar::offer
    val showUndoCommand: (String, String) -> Unit = { line, token -> undoBar.offerCommand(line, token, undoFailed, core::undoCommand) }
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
                todayList = true // with the home tiles (#192) the coach card is on the list behind "Today's tasks"
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
                // Right after a start the first request may fail: once more, as for a session (#191).
                suspend fun asked(): dev.lifeos.app.data.Reminder? = try {
                    SessionLookup.reminder(core.api, destination.reminderId)
                } catch (_: ApiException) {
                    delay(2_500)
                    try {
                        SessionLookup.reminder(core.api, destination.reminderId)
                    } catch (_: ApiException) {
                        null
                    }
                }
                val reminder = asked()
                val noteId = reminder?.noteId
                val found = reminder?.sessionId?.takeIf { noteId == null }?.let { sessionOf(it) }
                when {
                    // A note's reminder (#185): the note itself.
                    noteId != null -> {
                        navController.openTab(Tab.MORE)
                        navController.navigate(Routes.NOTES) { launchSingleTop = true }
                        navController.navigate(Routes.note(noteId)) { launchSingleTop = true }
                    }
                    else -> when (found) {
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

    // ---- the command bar (#185): what its answers hand over to the app
    val commandContext = androidx.compose.ui.platform.LocalContext.current
    val commandLocale = dev.lifeos.app.ui.components.appLocale()
    val openRoute: (String) -> Unit = { route ->
        val tab = Tab.forRoute(route)
        navController.openTab(tab)
        if (route != tab.route) navController.navigate(route) { launchSingleTop = true }
    }
    val performCommand: (CommandOutcome) -> Unit = { outcome ->
        when (outcome.performs) {
            "open_screen" -> CommandRoutes.SCREENS[outcome.field("screen")]?.let(openRoute)
            "search_notes" -> {
                notesQuery = outcome.field("query")
                openRoute(Routes.NOTES)
            }
            // The chats the bar opens start with the change permission it picked (#190).
            "new_chat" -> {
                core.chat.newChat(draft = outcome.field("text"), capable = outcome.capable)
                openChat()
            }
            "ask_coach" -> {
                core.coach.offerDraft(outcome.field("text"))
                openCoach()
            }
            "open_note" -> outcome.field("note_id")?.let { id ->
                openRoute(Routes.NOTES)
                navController.navigate(Routes.note(id)) { launchSingleTop = true }
            }
            "open_chat" -> outcome.field("session_id")?.let { id ->
                scope.launch {
                    when (val found = SessionLookup.find(core.api, id)) {
                        is SessionLookup.Found.Session -> {
                            core.chat.openWith(found.session, found.memory, outcome.capable, outcome.field("text"), outcome.flag("send"))
                            openChat()
                        }
                        SessionLookup.Found.Coach -> {
                            core.coach.offerDraft(outcome.field("text"))
                            openCoach()
                        }
                        else -> showMessage(sessionLost)
                    }
                }
            }
            "new_chat_in" -> {
                val folder = outcome.folder()?.let { runCatching { BridgeJson.decodeFromJsonElement(SwitchFolder.serializer(), it) }.getOrNull() }
                core.chat.newChat(folder?.asFolder(), draft = outcome.field("text"), capable = outcome.capable && folder?.kind != FolderKinds.COURSE)
                openChat()
            }
            "open_course" -> outcome.number("course_id")?.let { id ->
                openRoute(Routes.COURSES)
                navController.navigate(Routes.course(id)) { launchSingleTop = true }
            }
            "open_assignment" -> {
                val course = outcome.number("course_id")
                val assignment = outcome.number("assignment_id")
                if (course != null && assignment != null) {
                    navController.openTab(Tab.MORE)
                    navController.navigate(Routes.assignment(course, assignment)) { launchSingleTop = true }
                }
            }
            "open_project" -> outcome.field("project_id")?.let { id ->
                openRoute(Routes.PROJECTS)
                navController.navigate(Routes.project(id)) { launchSingleTop = true }
            }
            // A note was saved or deleted: every list reads again.
            "note_saved", "note_deleted" -> core.memoryChanged()
            // The home style (#192), as Settings changes it.
            "set_home_style" -> HomeStyle.entries.firstOrNull { it.storedValue == outcome.field("style") }?.let { style ->
                todayList = false
                scope.launch { core.settings.setHomeStyle(style) }
            }
        }
    }
    val commandHooks = run {
        CommandHooks(
            run = core::runCommand,
            newRequestId = { core.newItemId() + core.newItemId() },
            undo = core::undoCommand,
            systemScreen = core::openingSystemScreen,
            perform = { performCommand(it) },
            leave = { answer: CommandAnswer ->
                captureOpen = false
                answer.results.filter { it.ok && it.performs in CommandBar.NAVIGATING }.forEach { performCommand(it) }
                // One line of what was done, with Undo when the first change can be taken back.
                val first = answer.results.firstOrNull { it.undo != null && it.ok } ?: answer.results.firstOrNull()
                if (first != null) {
                    val line = outcomeLine(commandContext, first, commandLocale)
                    val token = first.undo
                    if (token != null) showUndoCommand(line, token) else showMessage(line)
                }
            },
        )
    }
    CompositionLocalProvider(
        LocalSnackbar provides showMessage,
        LocalUndo provides showUndo,
        LocalUndoCommand provides showUndoCommand,
        LocalLooks provides looks,
        LocalOpenTask provides openTask,
    ) {
    Scaffold(
        containerColor = LifeTheme.colors.bg,
        contentColor = LifeTheme.colors.text,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomBar(
                selected = selectedTab,
                onSelect = {
                    if (it == Tab.TODAY) todayList = false
                    navController.openTab(it)
                },
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
            composable(
                Routes.TODAY,
                // A tile's page grows out of the tile above the home and shrinks back into it (#195).
                exitTransition = { if (reveal.tapped) ExitTransition.KeepUntilTransitionsFinished else null },
                popEnterTransition = { if (reveal.fromTile && initialState.destination.route in TILE_ROUTES) EnterTransition.None else null },
            ) {
                // Nothing until the stored style is read, so the other home is never drawn first.
                val style = homeStyle
                val dark = LifeTheme.colors.isDark
                // The list above the tiles belongs to the tiles: with the classic home it is the home itself.
                LaunchedEffect(style, todayList) { if (style == HomeStyle.CLASSIC && todayList) todayList = false }
                val openCoachPage = { navController.navigate(Routes.COACH) { launchSingleTop = true } }
                val openConsolidation = { navController.navigate(Routes.CONSOLIDATION) { launchSingleTop = true } }
                val today: @Composable (onBack: (() -> Unit)?) -> Unit = { onBack ->
                    TodayScreen(
                        core = core,
                        onPair = openPair,
                        onConnectionClick = openSettings,
                        onOpenCoach = openCoachPage,
                        onOpenConsolidation = openConsolidation,
                        onBack = onBack,
                    )
                }
                if (style == HomeStyle.TILES) {
                    // "Today's tasks" opens the list in place, growing out of its tile like every other page.
                    val list = updateTransition(todayList, label = "today list")
                    val grow = list.animateFloat(
                        transitionSpec = { if (targetState) tween(RevealMotion.OPEN_MS, easing = RevealMotion.Open) else tween(RevealMotion.CLOSE_MS, easing = RevealMotion.Close) },
                        label = "today list window",
                    ) { if (it) 1f else 0f }
                    val fade = list.animateFloat(
                        transitionSpec = {
                            if (targetState) tween(RevealMotion.FADE_MS, RevealMotion.FADE_DELAY_MS, LinearEasing) else tween(RevealMotion.FADE_MS, easing = LinearEasing)
                        },
                        label = "today list content",
                    ) { if (it) 1f else 0f }
                    Box(Modifier.fillMaxSize()) {
                    if (!(list.currentState && list.targetState)) {
                        HomeScreen(
                            core = core,
                            onPair = openPair,
                            onConnectionClick = openSettings,
                            onOpenConsolidation = openConsolidation,
                            onCommand = {
                                capturePrefill = ""
                                captureOpen = true
                            },
                            onToggleTheme = { onThemeChange(if (dark) ThemeSetting.LIGHT else ThemeSetting.DARK) },
                            // Each tile opens a screen the app already has; Back returns to the tiles.
                            onOpen = { tile, origin ->
                                if (tile == HomeTile.TODAY) {
                                    todayOrigin = origin
                                    todayList = true
                                } else {
                                    reveal.tap(origin)
                                    when (tile) {
                                        HomeTile.COACH -> openCoachPage()
                                        HomeTile.CHATS -> navController.openTab(Tab.CHAT)
                                        HomeTile.AGENDA -> navController.navigate(Routes.CALENDAR) { launchSingleTop = true }
                                        HomeTile.NOTES -> navController.navigate(Routes.NOTES) { launchSingleTop = true }
                                        HomeTile.COURSES -> navController.navigate(Routes.COURSES) { launchSingleTop = true }
                                        HomeTile.ACTIVITY -> navController.navigate(Routes.ACTIVITY) { launchSingleTop = true }
                                        HomeTile.SESSIONS -> navController.navigate(Routes.SESSIONS) { launchSingleTop = true }
                                        HomeTile.PROJECTS -> navController.navigate(Routes.PROJECTS) { launchSingleTop = true }
                                        HomeTile.TODAY -> Unit
                                    }
                                }
                            },
                        )
                    }
                    if (list.currentState || list.targetState) {
                        val origin = todayOrigin
                        if (origin != null) {
                            RevealBox(origin, { grow.value }, { fade.value }) { today { todayList = false } }
                        } else {
                            today { todayList = false }
                        }
                    }
                    }
                } else if (style != null) {
                    today(null)
                }
            }
            // The coach conversation (#114): the same screen from the coach card on Today and from the Chat tab.
            listOf(Routes.COACH, Routes.CHAT_COACH).forEach { route ->
                tilePage(route, reveal, homeOnTop) {
                    CoachScreen(
                        core = core,
                        onBack = { navController.popBackStack() },
                        onConnectionClick = openSettings,
                        onPair = openPair,
                        onSessions = { navController.navigate(Routes.CHAT_SESSIONS) { launchSingleTop = true } },
                        onSwitch = switchTo,
                    )
                }
            }
            tilePage(Routes.CHAT, reveal, homeOnTop) {
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
                    onSwitch = switchTo,
                )
            }
            // The same list from the Chat tab and from More.
            listOf(Routes.CHAT_SESSIONS, Routes.SESSIONS).forEach { route ->
                tilePage(route, reveal, homeOnTop) {
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
            tilePage(Routes.NOTES, reveal, homeOnTop) {
                NotesScreen(
                    core = core,
                    onBack = { navController.popBackStack() },
                    onConnectionClick = openSettings,
                    onPair = openPair,
                    onOpenNote = { navController.navigate(Routes.note(it)) { launchSingleTop = true } },
                    startQuery = notesQuery,
                    onQueryTaken = { notesQuery = null },
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
            tilePage(Routes.PROJECTS, reveal, homeOnTop) {
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
            tilePage(Routes.ACTIVITY, reveal, homeOnTop) {
                ActivityScreen(core, onBack = { navController.popBackStack() }, onConnectionClick = openSettings)
            }
            tilePage(Routes.CALENDAR, reveal, homeOnTop) {
                CalendarScreen(core, onBack = { navController.popBackStack() }, onConnectionClick = openSettings, onPair = openPair)
            }
            tilePage(Routes.COURSES, reveal, homeOnTop) {
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
            onSaved = { kind, text, due, taskNote, remindAt ->
                captureOpen = false
                scope.launch {
                    val sent = core.capture(note = kind == CaptureKind.NOTE, text = text, due = due, taskNote = taskNote, remindAt = remindAt)
                    snackbarHostState.showSnackbar(if (sent) savedMessage else waitingMessage)
                }
            },
            onNewChat = { draft ->
                captureOpen = false
                newChat(draft)
            },
            hooks = commandHooks,
        )
    }
}

/** The screens the home tiles open (#195). */
private val TILE_ROUTES = setOf(Routes.COACH, Routes.CHAT, Routes.CALENDAR, Routes.NOTES, Routes.COURSES, Routes.ACTIVITY, Routes.SESSIONS, Routes.PROJECTS)

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
