package dev.lifeos.app.ui.nav

/** Navigation routes. */
object Routes {
    const val TODAY = "today"
    const val COACH = "today/coach"
    const val CHAT = "chat"
    const val TASKS = "tasks"
    const val MORE = "more"
    const val ALL_TASKS = "tasks/all"
    const val SETTINGS = "more/settings"
    const val PAIR = "more/settings/pair"
    const val MODELS = "more/settings/models"
    const val NOTES = "more/notes"
    const val NOTE = "more/note/{note}"
    const val NOTE_ARG = "note"
    const val CALENDAR = "more/calendar"
    const val COURSES = "more/courses"
    const val ACTIVITY = "more/activity"
    const val GRADES = "more/courses/grades"
    const val COURSE = "more/course/{course}"
    const val COURSE_ARG = "course"
    const val SECTION = "more/section/{section}"
    const val SECTION_ARG = "section"

    // Chat and sessions: the Chat tab is the list, the conversation and the sessions sit below it.
    const val CHAT_THREAD = "chat/thread"
    const val CHAT_SESSIONS = "chat/sessions"
    const val SESSIONS = "more/sessions"

    /** The coach conversation below the Chat tab (#114); [COACH] is the same screen below Today. */
    const val CHAT_COACH = "chat/coach"

    fun course(id: Long): String = "more/course/$id"

    // ---- memory consolidation (#151) and projects (#152)
    const val CONSOLIDATION = "today/consolidation"
    const val PROJECTS = "more/projects"
    const val PROJECT = "more/project/{project}"
    const val PROJECT_ARG = "project"

    fun project(id: String): String = "more/project/${android.net.Uri.encode(id)}"

    // ---- assignment detail (#129): by its id, or (id 0) by a deadline's name and due time
    const val ASSIGNMENT = "more/course/{course}/assignment/{assignment}?name={name}&due={due}"
    const val ASSIGNMENT_ARG = "assignment"
    const val ASSIGNMENT_NAME_ARG = "name"
    const val ASSIGNMENT_DUE_ARG = "due"

    fun assignment(courseId: Long, assignmentId: Long): String = "more/course/$courseId/assignment/$assignmentId"

    fun deadline(courseId: Long, name: String, due: Long?): String =
        "more/course/$courseId/assignment/0?name=${android.net.Uri.encode(name)}&due=${due ?: -1}"

    /** Note ids are letters, digits and dashes (the bridge checks this); encoded all the same. */
    fun note(id: String): String = "more/note/${java.net.URLEncoder.encode(id, "UTF-8")}"

    /** Sections that are built have their own screen; the others share the placeholder. */
    fun section(section: MoreSection): String = when (section) {
        MoreSection.SESSIONS -> SESSIONS
        MoreSection.NOTES -> NOTES
        MoreSection.CALENDAR -> CALENDAR
        MoreSection.COURSES -> COURSES
        MoreSection.ACTIVITY -> ACTIVITY
        MoreSection.PROJECTS -> PROJECTS
        else -> "more/section/${section.id}"
    }
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

/**
 * What a tap on a bottom-bar tab does (#166): it always opens the tab's root screen, never a
 * sub-page kept from an earlier visit, also when the tab is already selected. Everything above
 * the start screen goes and is not saved; the tab's root is put on top unless it is the start
 * screen itself (no second copy of it).
 */
object TabTaps {
    /** How the navigation is told: pop to the start screen, save nothing, restore nothing. */
    data class Options(val route: String, val popUpToStart: Boolean, val saveState: Boolean, val restoreState: Boolean, val singleTop: Boolean)

    fun options(tab: Tab): Options = Options(tab.route, popUpToStart = true, saveState = false, restoreState = false, singleTop = true)

    /** The back stack after a tap on [tab], from any [stack] that begins with [start]. */
    fun stackAfter(stack: List<String>, tab: Tab, start: String = Routes.TODAY): List<String> {
        val options = options(tab)
        val kept = if (options.popUpToStart) stack.take(stack.indexOf(start).coerceAtLeast(0) + 1) else stack
        return if (options.singleTop && kept.lastOrNull() == options.route) kept else kept + options.route
    }
}

/** The screens listed under More. Settings has its own entry. */
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
