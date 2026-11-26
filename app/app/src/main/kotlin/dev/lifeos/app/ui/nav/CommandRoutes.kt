package dev.lifeos.app.ui.nav

/**
 * What the command bar's answers ask the app to do (#185). The bridge's action registry
 * (bridge/src/lifeos_bridge/actions.py) names the same screens and actions; the bridge's test
 * `test_actions.py` reads this file and fails when the two differ.
 */
object CommandRoutes {
    /** The screens of the action `open_screen` → the app's route (actions.SCREENS). */
    val SCREENS: Map<String, String> = mapOf(
        "today" to Routes.TODAY,
        "coach" to Routes.COACH,
        "chats" to Routes.CHAT,
        "sessions" to Routes.SESSIONS,
        "tasks" to Routes.TASKS,
        "all_tasks" to Routes.ALL_TASKS,
        "more" to Routes.MORE,
        "notes" to Routes.NOTES,
        "projects" to Routes.PROJECTS,
        "calendar" to Routes.CALENDAR,
        "courses" to Routes.COURSES,
        "grades" to Routes.GRADES,
        "activity" to Routes.ACTIVITY,
        "settings" to Routes.SETTINGS,
        "models" to Routes.MODELS,
        "consolidation" to Routes.CONSOLIDATION,
    )

    /** Everything the app performs: the registry's app actions and what its bridge actions hand over (actions.PERFORMS). */
    val PERFORMED: Set<String> = setOf(
        "open_screen", "search_notes", "new_chat", "ask_coach", "set_home_style",
        "note_saved", "note_deleted", "open_note", "open_chat", "new_chat_in", "open_course", "open_assignment", "open_project",
    )
}
