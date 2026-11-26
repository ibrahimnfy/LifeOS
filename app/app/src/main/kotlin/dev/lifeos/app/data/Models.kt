package dev.lifeos.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/** The JSON the bridge answers with (docs/bridge.md). Unknown fields are ignored. */
val BridgeJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/** Grades and similar values are text on the site but may arrive as numbers: read both as text. */
object LenientText : JsonTransformingSerializer<String>(String.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive && element !is JsonNull && !element.isString) JsonPrimitive(element.content) else element
}

// ---------------------------------------------------------------- memory

@Serializable
data class Task(
    val id: String,
    val title: String,
    val done: Boolean = false,
    val due: String? = null,
    val project: String? = null,
    val course: String? = null,
    val added: String? = null,
    /** The coach's first step for it (`next`); null when it has none. Not the owner's [note]. */
    val next: String? = null,
    /** The course assignment it was made from (`moodle-<assignment id>`, #167); null for other tasks. */
    val assignment: String? = null,
    /** The owner's own note on it (#170): one paragraph, at most [TaskNote.MAX] characters; null when none. */
    val note: String? = null,
)

/** The owner's note on a task (#170), kept the way the computer keeps it. */
object TaskNote {
    /** The longest note the computer accepts (the bridge's MAX_TASK_NOTE). */
    const val MAX = 500

    /**
     * What the form keeps while the owner types: one paragraph (a line break becomes a space,
     * as on the computer), no other control characters (the computer refuses them), at most [MAX].
     */
    fun typed(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').filterNot { it.isISOControl() }.take(MAX)

    /** The note to send: trimmed; blank means none. */
    fun of(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * What an edit does to the note: null when [typed] is null or says what [shown] says (the
     * edit leaves the note alone, so a title or date change never touches it), "" to remove it,
     * otherwise the new note.
     */
    fun change(shown: String?, typed: String?): String? {
        if (typed == null) return null
        val next = of(typed)
        return if (next == of(shown)) null else next ?: ""
    }
}

@Serializable
data class TaskList(val tasks: List<Task> = emptyList())

@Serializable
data class Event(
    val id: String,
    val title: String,
    val date: String,
    @SerialName("end_date") val endDate: String? = null,
    val start: String? = null,
    val end: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val note: String? = null,
)

@Serializable
data class EventList(val events: List<Event> = emptyList())

/**
 * A note in the list: a capture of the inbox ([kind] "inbox", no title; its id changes with
 * its text) or a topic note ("topic", read-only here). [at] is "YYYY-MM-DD HH:MM" for a
 * capture and a date for a topic note.
 */
@Serializable
data class NoteSummary(
    val id: String,
    val kind: String = "inbox",
    val title: String? = null,
    val at: String? = null,
    val preview: String = "",
) {
    val editable: Boolean get() = kind == "inbox"
}

@Serializable
data class NoteList(val notes: List<NoteSummary> = emptyList())

@Serializable
data class Note(
    val id: String,
    val kind: String = "inbox",
    val title: String? = null,
    val at: String? = null,
    val text: String = "",
    /** Its reminder (#185): the one to come, else the one that fired last; null when it has none. */
    val reminder: Reminder? = null,
    /**
     * A capture the computer's tidy-up filed into a topic file ([kind] "filed", #191): the file
     * that has its words now. It is in no list; a reminder of it still opens it, read-only.
     */
    @SerialName("filed_in") val filedIn: String? = null,
) {
    val editable: Boolean get() = kind == "inbox"
    val filed: Boolean get() = kind == "filed"
}

/** The answer to a new note (`POST /notes`): the stored note's id and time (`at`, ISO). */
@Serializable
data class NoteSaved(
    val id: String? = null,
    val at: String? = null,
    val repeat: Boolean = false,
    @SerialName("client_id") val clientId: String? = null,
)

/** The answer to a new task (`POST /tasks`): the stored task, or `gone` when it was removed on the computer meanwhile. */
@Serializable
data class TaskSaved(
    val id: String? = null,
    val title: String = "",
    val done: Boolean = false,
    val due: String? = null,
    val project: String? = null,
    val course: String? = null,
    val added: String? = null,
    val note: String? = null,
    val gone: Boolean = false,
    val repeat: Boolean = false,
) {
    fun task(): Task? = if (gone || id == null) null else Task(id, title, done, due, project, course, added, note = note)
}

@Serializable
data class Agenda(
    val date: String? = null,
    @SerialName("events_today") val eventsToday: List<Event> = emptyList(),
    @SerialName("tasks_today") val tasksToday: List<Task> = emptyList(),
    @SerialName("tasks_overdue") val tasksOverdue: List<Task> = emptyList(),
)

// ---------------------------------------------------------------- coach

@Serializable
data class CoachMessage(
    val id: String,
    val kind: String = "",
    val text: String = "",
    val why: String? = null,
    val task: String? = null,
    val question: String? = null,
)

@Serializable
data class TopItem(val task: Task, val reason: String? = null)

@Serializable
data class CoachPlan(
    @SerialName("prepared_at") val preparedAt: String? = null,
    @SerialName("age_seconds") val ageSeconds: Long? = null,
    val stale: Boolean = false,
    val messages: List<CoachMessage> = emptyList(),
    @SerialName("top_three") val topThree: List<TopItem> = emptyList(),
    val order: List<Task> = emptyList(),
) {
    /** Whether the coach has prepared anything; without it the order is plain date order. */
    val exists: Boolean get() = preparedAt != null
}

// ---------------------------------------------------------------- courses

@Serializable
data class Deadline(
    @SerialName("course_id") val courseId: Long? = null,
    val course: String? = null,
    val name: String = "",
    val due: Long? = null,
    /** The assignment this deadline belongs to (#144); absent for other deadlines. */
    @SerialName("assignment_id") val assignmentId: Long? = null,
)

@Serializable
data class CourseSummary(
    val configured: Boolean = false,
    val deadlines: List<Deadline> = emptyList(),
    @SerialName("new_total") val newTotal: Int = 0,
    @SerialName("last_sync") val lastSync: String? = null,
    val stale: Boolean = false,
) {
    /**
     * The deadlines still ahead at [nowSeconds], nearest first. The computer sends upcoming
     * ones only, but a saved copy can be older than some of them.
     */
    fun upcoming(nowSeconds: Long): List<Deadline> = deadlines.filter { (it.due ?: Long.MAX_VALUE) >= nowSeconds }.sortedBy { it.due ?: Long.MAX_VALUE }
}

@Serializable
data class NewCounts(
    val files: Int = 0,
    val announcements: Int = 0,
    val deadlines: Int = 0,
    val grades: Int = 0,
    val pages: Int = 0,
    val assignments: Int = 0,
) {
    val total: Int get() = files + announcements + deadlines + grades + pages + assignments
}

@Serializable
data class Course(
    val id: Long,
    val name: String = "",
    val shortname: String = "",
    @SerialName("next_deadline") val nextDeadline: Deadline? = null,
    val new: NewCounts = NewCounts(),
    @SerialName("has_new") val hasNew: Boolean = false,
)

@Serializable
data class CourseList(
    val courses: List<Course> = emptyList(),
    @SerialName("last_sync") val lastSync: String? = null,
    val stale: Boolean = false,
)

@Serializable
data class CourseFile(
    val key: String,
    val name: String = "",
    val type: String? = null,
    val size: Long = 0,
    val modified: Long? = null,
    val new: Boolean = false,
    val downloaded: Boolean = false,
    val skipped: String? = null,
)

@Serializable
data class MaterialSection(val name: String = "", val files: List<CourseFile> = emptyList())

@Serializable
data class CourseLink(val section: String = "", val name: String = "", val url: String = "")

@Serializable
data class Materials(
    val name: String = "",
    val sections: List<MaterialSection> = emptyList(),
    val links: List<CourseLink> = emptyList(),
    @SerialName("last_sync") val lastSync: String? = null,
)

@Serializable
data class Announcement(val id: Long = 0, val subject: String = "", val text: String = "", val created: Long? = null)

@Serializable
data class Announcements(val announcements: List<Announcement> = emptyList())

@Serializable
data class AssignmentStatus(val submission: String? = null, val grading: String? = null, val graded: Boolean = false)

@Serializable
data class Assignment(
    val id: Long = 0,
    val name: String = "",
    val due: Long? = null,
    val status: AssignmentStatus? = null,
    val opens: Long? = null,
    val cutoff: Long? = null,
    val submitted: Boolean = false,
    /** How many files are attached (the detail lists them). */
    val files: Int = 0,
    /** The id of its task in the memory, or null. */
    val task: String? = null,
)

/** One assignment in full (`GET /courses/{id}/assignments/{aid}`); [description] is Markdown. */
@Serializable
data class AssignmentDetail(
    val id: Long = 0,
    val name: String = "",
    val course: String = "",
    @SerialName("course_id") val courseId: Long = 0,
    val opens: Long? = null,
    val due: Long? = null,
    val cutoff: Long? = null,
    val status: AssignmentStatus? = null,
    val submitted: Boolean = false,
    @SerialName("grading_due") val gradingDue: Long? = null,
    @SerialName("modified_iso") val modifiedIso: String? = null,
    val description: String = "",
    val files: List<CourseFile> = emptyList(),
    val task: String? = null,
)

/**
 * Which assignment a deadline is: the id the bridge sends with it (#144), or, for an older
 * bridge or a deadline without it, the one found by name and due time ([find]).
 */
object AssignmentMatch {
    /** The assignment id to open directly, or null when it must be looked up. */
    fun direct(deadline: Deadline): Long? = deadline.assignmentId?.takeIf { it > 0 && deadline.courseId != null }

    fun find(assignments: List<Assignment>, name: String, due: Long?): Assignment? =
        assignments.firstOrNull { it.name == name && it.due == due }
            ?: assignments.filter { due != null && it.due == due }.singleOrNull()
            ?: assignments.filter { it.name.equals(name, ignoreCase = true) }.singleOrNull()
}

@Serializable
data class Assignments(val assignments: List<Assignment> = emptyList())

/** What tapping a task opens (#167). */
sealed interface TaskTarget {
    /** The page of the course assignment the task was made from. */
    data class AssignmentPage(val courseId: Long, val assignmentId: Long) : TaskTarget

    /** The task's own card (a bottom sheet). */
    data object Card : TaskTarget

    companion object {
        /** The marker the course sync writes into an assignment's task: `(assignment: moodle-<id>)`. */
        private val MARKER = Regex("^moodle-(\\d+)$")

        /** A course folder as the bridge names it (`<short name>-<course id>`): the id is its last part. */
        private val FOLDER = Regex("^[a-z0-9-]*-(\\d+)$")

        /**
         * A task made from a course assignment opens the assignment, when both ids can be told:
         * the assignment's from the marker, the course's from the course list ([courseOf]) or else
         * from the course folder's name. Anything else (and a task still waiting on the phone)
         * opens the card, so a tap never leads nowhere.
         */
        fun of(task: Task, courseOf: (String) -> Course? = { null }): TaskTarget {
            if (LocalItems.isWaiting(task)) return Card
            val assignmentId = task.assignment?.trim()?.let { MARKER.matchEntire(it) }?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return Card
            val course = task.course?.trim()?.takeIf { it.isNotEmpty() } ?: return Card
            val courseId = courseOf(course)?.id ?: FOLDER.matchEntire(course)?.groupValues?.get(1)?.toLongOrNull()
            return if (courseId != null && courseId > 0) AssignmentPage(courseId, assignmentId) else Card
        }
    }
}

@Serializable
data class GradeItem(
    val name: String = "",
    val type: String? = null,
    @Serializable(with = LenientText::class) val grade: String? = null,
    @Serializable(with = LenientText::class) val range: String? = null,
    @Serializable(with = LenientText::class) val percentage: String? = null,
    val feedback: String? = null,
)

@Serializable
data class Grades(
    @Serializable(with = LenientText::class) val overall: String? = null,
    val items: List<GradeItem> = emptyList(),
)

@Serializable
data class CourseGrades(
    val id: Long = 0,
    val name: String = "",
    @Serializable(with = LenientText::class) val overall: String? = null,
    val items: List<GradeItem> = emptyList(),
)

@Serializable
data class AllGrades(val courses: List<CourseGrades> = emptyList())

@Serializable
data class SyncState(
    val configured: Boolean = false,
    val running: Boolean = false,
    @SerialName("last_sync") val lastSync: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    val stale: Boolean = false,
)

/** The direct address of a file. [url] is as sensitive as a password: see [DownloadRules]. */
@Serializable
class FileAddress(val url: String, val name: String = "", val size: Long = 0, val type: String? = null) {
    override fun toString(): String = "FileAddress(hidden)"
}

@Serializable
data class FileOffer(
    val offer: String,
    val name: String = "",
    val type: String? = null,
    val size: Long = 0,
    @SerialName("session_id") val sessionId: String = "",
)

// ---------------------------------------------------------------- model routing (#124)

/** Plan usage of a job's runs, as the runs report it; [costUsd] is Claude Code's own estimate. */
@Serializable
data class JobUsage(
    val runs: Int = 0,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationTokens: Long = 0,
    @SerialName("cache_read_input_tokens") val cacheReadTokens: Long = 0,
    @SerialName("cost_usd") val costUsd: Double = 0.0,
    val models: List<String> = emptyList(),
) {
    /** All tokens the runs used, cache included. */
    val tokens: Long get() = inputTokens + outputTokens + cacheCreationTokens + cacheReadTokens
}

@Serializable
data class JobUsagePeriods(val today: JobUsage = JobUsage(), val week: JobUsage = JobUsage())

/** One model job of the bridge: its model, effort and advisor, where that comes from, and its usage. */
@Serializable
data class ModelJob(
    val job: String,
    val label: String = "",
    val model: String? = null,
    val effort: String? = null,
    val advisor: String? = null,
    /** "default", "routing" (the owner's routing setting) or "background_model". */
    val source: String = "default",
    @SerialName("per_session") val perSession: Boolean = false,
    val usage: JobUsagePeriods = JobUsagePeriods(),
    /** What the job uses without the owner's choice in the app (for "back to the default"). */
    @SerialName("default") val defaults: JobDefault? = null,
)

@Serializable
data class JobDefault(val model: String? = null, val effort: String? = null, val advisor: String? = null, val source: String = "default")

/** `GET /models`: the models and effort levels the app may offer. */
@Serializable
data class ModelChoices(
    val models: List<String> = emptyList(),
    val efforts: List<String> = emptyList(),
    @SerialName("default_model") val defaultModel: String? = null,
    @SerialName("default_effort") val defaultEffort: String? = null,
)

/**
 * One field of a job's routing as the owner sets it in the app: the default underneath
 * ([Default]), a value, or for the advisor [Off].
 */
sealed interface RoutingChoice {
    data object Default : RoutingChoice
    data object Off : RoutingChoice
    data class Value(val name: String) : RoutingChoice
}

/** Changing a job's routing (`PUT /models/routing`). */
object RoutingEdit {
    /**
     * Where the editor starts: the job's current values where the app chose them (its source is
     * "app" and the value differs from the default underneath), else "Default".
     */
    fun initial(job: ModelJob): Triple<RoutingChoice, RoutingChoice, RoutingChoice> {
        if (job.source != "app") return Triple(RoutingChoice.Default, RoutingChoice.Default, RoutingChoice.Default)
        val base = job.defaults
        fun pick(value: String?, underneath: String?): RoutingChoice =
            if (value == null || value == underneath) RoutingChoice.Default else RoutingChoice.Value(value)
        val advisor = when {
            job.advisor == null && base?.advisor != null -> RoutingChoice.Off
            else -> pick(job.advisor, base?.advisor)
        }
        return Triple(pick(job.model, base?.model), pick(job.effort, base?.effort), advisor)
    }

    /** The request body: every field given (`null` = back to the default, `"none"` = no advisor). */
    fun body(job: String, model: RoutingChoice, effort: RoutingChoice, advisor: RoutingChoice): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("job", kotlinx.serialization.json.JsonPrimitive(job))
            put("model", model.json())
            put("effort", effort.json())
            put("advisor", advisor.json())
        }

    fun reset(job: String): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("job", kotlinx.serialization.json.JsonPrimitive(job))
        put("reset", kotlinx.serialization.json.JsonPrimitive(true))
    }

    private fun RoutingChoice.json(): JsonElement = when (this) {
        RoutingChoice.Default -> JsonNull
        RoutingChoice.Off -> JsonPrimitive("none")
        is RoutingChoice.Value -> JsonPrimitive(name)
    }
}

@Serializable
data class ModelRouting(
    val jobs: List<ModelJob> = emptyList(),
    @SerialName("no_model") val noModel: List<String> = emptyList(),
    val today: String? = null,
    @SerialName("week_from") val weekFrom: String? = null,
    val note: String? = null,
)

/** Numbers of the Models screen, without Android classes. */
object UsageNumbers {
    /** 950 → "950", 12 345 → "12.3k", 4 200 000 → "4.2M". */
    fun compact(value: Long): String = when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> trim(value / 1_000.0) + "k"
        else -> trim(value / 1_000_000.0) + "M"
    }

    /** "$0.42"; amounts under a cent as "<$0.01", none as "$0". */
    fun dollars(value: Double): String = when {
        value <= 0.0 -> "$0"
        value < 0.01 -> "<$0.01"
        else -> "$" + String.format(java.util.Locale.ROOT, "%.2f", value)
    }

    private fun trim(value: Double): String =
        String.format(java.util.Locale.ROOT, if (value >= 100) "%.0f" else "%.1f", value).removeSuffix(".0")
}

/** One endpoint a screen shows: where to ask, how to read the answer, and its place in the saved copies. */
class Endpoint<T>(
    val key: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val serializer: KSerializer<T>,
)

object Endpoints {
    val tasks = Endpoint("tasks", "/tasks", serializer = TaskList.serializer())
    val agenda = Endpoint("agenda", "/agenda", serializer = Agenda.serializer())
    val plan = Endpoint("coach-plan", "/coach/plan", serializer = CoachPlan.serializer())
    val courseSummary = Endpoint("courses-summary", "/courses/summary", serializer = CourseSummary.serializer())
    val courses = Endpoint("courses", "/courses", serializer = CourseList.serializer())
    val allGrades = Endpoint("courses-grades", "/courses/grades", serializer = AllGrades.serializer())

    /** Each model job and its plan usage (#124); changed with `PUT /models/routing` ([Api.changeRouting]). */
    val modelRouting = Endpoint("models-routing", "/models/routing", serializer = ModelRouting.serializer())

    /** The models and effort levels the app may offer. */
    val modelChoices = Endpoint("models", "/models", serializer = ModelChoices.serializer())

    /** All notes; this one has a saved copy. */
    val notes = Endpoint("notes", "/notes", serializer = NoteList.serializer())

    /** A search: asked fresh each time, never saved (its key is not used for saved copies). */
    fun noteSearch(query: String) = Endpoint("notes-search", "/notes", mapOf("query" to query), NoteList.serializer())

    /** One note in full: asked fresh, never saved (a capture's id changes with every edit). */
    fun note(id: String) = Endpoint("note", "/notes/$id", serializer = Note.serializer())

    fun events(start: String, end: String) =
        Endpoint("events", "/events", mapOf("start" to start, "end" to end), EventList.serializer())

    fun materials(id: Long) = Endpoint("course-$id-materials", "/courses/$id/materials", serializer = Materials.serializer())
    fun announcements(id: Long) =
        Endpoint("course-$id-announcements", "/courses/$id/announcements", serializer = Announcements.serializer())
    fun assignments(id: Long) =
        Endpoint("course-$id-assignments", "/courses/$id/assignments", serializer = Assignments.serializer())
    fun assignment(id: Long, assignmentId: Long) =
        Endpoint("course-$id-assignment-$assignmentId", "/courses/$id/assignments/$assignmentId", serializer = AssignmentDetail.serializer())
    fun grades(id: Long) = Endpoint("course-$id-grades", "/courses/$id/grades", serializer = Grades.serializer())
}
