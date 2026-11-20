package dev.lifeos.app.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
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
import dev.lifeos.app.R
import dev.lifeos.app.settings.ThemeSetting
import dev.lifeos.app.ui.components.BottomBar
import dev.lifeos.app.ui.components.CaptureSheet
import dev.lifeos.app.ui.screens.ChatScreen
import dev.lifeos.app.ui.screens.MoreScreen
import dev.lifeos.app.ui.screens.PlaceholderScreen
import dev.lifeos.app.ui.screens.SettingsScreen
import dev.lifeos.app.ui.screens.TasksScreen
import dev.lifeos.app.ui.screens.TodayScreen
import dev.lifeos.app.ui.screens.titleRes
import dev.lifeos.app.ui.theme.LifeTheme
import kotlinx.coroutines.launch

/** The whole app: navigation host, bottom bar, capture sheet and snackbar. */
@Composable
fun LifeOsApp(theme: ThemeSetting, onThemeChange: (ThemeSetting) -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedTab = Tab.forRoute(backStackEntry?.destination?.route)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var captureOpen by rememberSaveable { mutableStateOf(false) }
    val savedMessage = stringResource(R.string.capture_saved)
    // Settings lives under More: switch to that tab first, so no other tab keeps it on its stack.
    val openSettings: () -> Unit = {
        navController.openTab(Tab.MORE)
        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
    }

    Scaffold(
        containerColor = LifeTheme.colors.bg,
        contentColor = LifeTheme.colors.text,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomBar(
                selected = selectedTab,
                onSelect = { navController.openTab(it) },
                onCapture = { captureOpen = true },
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
            composable(Routes.TODAY) { TodayScreen(onConnectionClick = openSettings) }
            composable(Routes.CHAT) { ChatScreen(onConnectionClick = openSettings) }
            composable(Routes.TASKS) {
                TasksScreen(
                    onConnectionClick = openSettings,
                    onShowAll = { navController.navigate(Routes.ALL_TASKS) },
                )
            }
            composable(Routes.ALL_TASKS) {
                PlaceholderScreen(
                    title = stringResource(R.string.tasks_all_title),
                    onBack = { navController.popBackStack() },
                    onConnectionClick = openSettings,
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
                    theme = theme,
                    onThemeChange = onThemeChange,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    if (captureOpen) {
        CaptureSheet(
            onDismiss = { captureOpen = false },
            onSaved = { _, _ ->
                // Nothing is stored yet; the capture queue arrives with a later step.
                captureOpen = false
                scope.launch { snackbarHostState.showSnackbar(savedMessage) }
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
