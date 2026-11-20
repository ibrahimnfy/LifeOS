package dev.lifeos.app.ui.nav

/** Navigation routes. */
object Routes {
    const val TODAY = "today"
    const val CHAT = "chat"
    const val TASKS = "tasks"
    const val MORE = "more"
    const val ALL_TASKS = "tasks/all"
    const val SETTINGS = "more/settings"
    const val SECTION = "more/section/{section}"
    const val SECTION_ARG = "section"

    fun section(section: MoreSection): String = "more/section/${section.id}"
}

/** The four destinations of the bottom bar, in display order. */
enum class Tab(val route: String) {
    TODAY(Routes.TODAY),
    CHAT(Routes.CHAT),
    TASKS(Routes.TASKS),
    MORE(Routes.MORE);

    companion object {
        /**
         * The bottom-bar item to mark as active for a route. Screens below a tab
         * (e.g. "more/settings") keep their tab active; unknown routes fall back to Today.
         */
        fun forRoute(route: String?): Tab {
            val root = route.orEmpty().substringBefore('/')
            return entries.firstOrNull { it.route == root } ?: TODAY
        }
    }
}

/** Screens listed under More that are still placeholders. Settings has its own screen. */
enum class MoreSection(val id: String) {
    SESSIONS("sessions"),
    NOTES("notes"),
    PROJECTS("projects"),
    CALENDAR("calendar"),
    COURSES("courses"),
    ACTIVITY("activity");

    companion object {
        fun fromId(id: String?): MoreSection? = entries.firstOrNull { it.id == id }
    }
}
