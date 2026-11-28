package dev.lifeos.app.data

import java.time.LocalDate
import java.time.format.DateTimeParseException

enum class TaskSection { OVERDUE, TODAY, UPCOMING, NO_DATE }

object Dates {
    fun parse(day: String?): LocalDate? = try {
        day?.let(LocalDate::parse)
    } catch (_: DateTimeParseException) {
        null
    }

    /** Calendar days from [today] to the local day of a moment ([epochSeconds]); 0 is today, negative is past. */
    fun daysUntil(epochSeconds: Long, today: LocalDate, zone: java.time.ZoneId): Long =
        java.time.temporal.ChronoUnit.DAYS.between(today, java.time.Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate())

    /** Where a task belongs, seen from [today] in the phone's own time zone. */
    fun sectionOf(due: String?, today: LocalDate): TaskSection {
        val day = parse(due) ?: return TaskSection.NO_DATE
        return when {
            day < today -> TaskSection.OVERDUE
            day == today -> TaskSection.TODAY
            else -> TaskSection.UPCOMING
        }
    }

    /** Tasks by section, in the fixed order of the sections; within a section by date, then as given. */
    fun sections(tasks: List<Task>, today: LocalDate): List<Pair<TaskSection, List<Task>>> =
        TaskSection.entries.mapNotNull { section ->
            val found = tasks.filter { sectionOf(it.due, today) == section }
            val sorted = if (section == TaskSection.NO_DATE) found else found.sortedBy { parse(it.due) }
            if (sorted.isEmpty()) null else section to sorted
        }

    /**
     * The agenda: every day from [first] to [last] that has an event, with the events that
     * touch it. An event of several days appears on each of them. All-day events come first,
     * then by start time.
     */
    fun agenda(events: List<Event>, first: LocalDate, last: LocalDate): List<Pair<LocalDate, List<Event>>> {
        val days = sortedMapOf<LocalDate, MutableList<Event>>()
        for (event in events) {
            val start = parse(event.date) ?: continue
            val end = (parse(event.endDate) ?: start).coerceAtLeast(start)
            var day = maxOf(start, first)
            while (day <= minOf(end, last)) {
                days.getOrPut(day) { mutableListOf() }.add(event)
                day = day.plusDays(1)
            }
        }
        return days.map { (day, list) -> day to list.sortedWith(compareBy({ !isAllDay(it) }, { it.start ?: "" }, { it.title })) }
    }

    fun isAllDay(event: Event): Boolean = event.allDay || event.start == null
}

/**
 * Which coach message the Today card shows. A different one each time the app is opened:
 * chosen at random among the messages not shown most recently, never the same twice in a
 * row. Nothing is dropped here; the computer already decides which messages exist.
 */
object CoachRotation {
    /** [recent] holds the ids shown before, newest last. [random] returns a number below its argument. */
    fun pick(ids: List<String>, recent: List<String>, random: (Int) -> Int): String? {
        if (ids.isEmpty()) return null
        val fresh = ids.filterNot { it in recent }
        val candidates = fresh.ifEmpty { ids.filterNot { it == recent.lastOrNull() } }.ifEmpty { ids }
        return candidates[random(candidates.size)]
    }

    /**
     * The memory after showing [picked]: the newest half of the current messages. So a message
     * returns only after at least that many others, and the choice stays random.
     */
    fun remember(recent: List<String>, picked: String, ids: List<String>): List<String> =
        (recent.filter { it in ids && it != picked } + picked).takeLast((ids.size / 2).coerceAtLeast(1))
}

/** What the task lists show while writes are still on their way. */
object TaskLists {
    /**
     * Open tasks without those the owner already ticked here (the tick is waiting or unclear),
     * each shown with the title and date of its latest edit that is still on its way, and the
     * note of its latest such edit that changes the note (#170: an edit that leaves the note
     * alone carries none).
     */
    fun open(tasks: List<Task>, outbox: List<OutboxItem>, justTicked: Set<String> = emptySet()): List<Task> {
        // Ticked or deleted here (#197), and waiting or unclear: gone from the lists already.
        val ticked = outbox.filter { (it.kind == OutboxItem.Kind.COMPLETE || it.kind == OutboxItem.Kind.TASK_DELETE) && it.state != OutboxItem.State.FAILED }
            .mapNotNull { it.taskId }.toSet()
        // The task of an assignment the owner just marked done or removed (#196) goes with it.
        val marked = AssignmentMarks.closedTaskMarkers(outbox)
        return tasks.filter { !it.done && it.id !in ticked && it.id !in justTicked && it.assignment?.trim() !in marked }.map { task ->
            val edited = pendingEdit(task.id, outbox)?.let { task.copy(title = it.text, due = it.due) } ?: task
            pendingNote(task.id, outbox)?.let { edited.copy(note = TaskNote.of(it.note)) } ?: edited
        }
    }

    /** The latest waiting edit of a task that changes its note (a refused one does not count). */
    private fun pendingNote(taskId: String, outbox: List<OutboxItem>): OutboxItem? =
        outbox.lastOrNull { it.kind == OutboxItem.Kind.TASK_EDIT && it.taskId == taskId && it.state != OutboxItem.State.FAILED && it.note != null }

    /** The latest edit of a task that has not been confirmed yet (a refused one does not count). */
    fun pendingEdit(taskId: String, outbox: List<OutboxItem>): OutboxItem? =
        outbox.lastOrNull { it.kind == OutboxItem.Kind.TASK_EDIT && it.taskId == taskId && it.state != OutboxItem.State.FAILED }

    /** What deleting a task does to what waits on this phone (#197), see [deletion]. */
    sealed interface Deletion {
        /** A task made here that never left the phone: it is simply dropped from the outbox. */
        data class Discard(val itemId: String) : Deletion

        /**
         * Delete [taskId] on the computer. For a task made here that may have reached the
         * computer already, [taskId] is its "local-" name: the deletion waits behind it and
         * follows it once the computer confirms it ([Outbox.retarget]).
         */
        data class Delete(val taskId: String) : Deletion
    }

    /** [taskId]: the id the task has now (see [LocalItems.currentId]). */
    fun deletion(taskId: String, outbox: List<OutboxItem>): Deletion {
        if (!LocalItems.isWaiting(taskId)) return Deletion.Delete(taskId)
        val own = outbox.firstOrNull { it.kind == OutboxItem.Kind.TASK && LocalItems.PREFIX + it.id == taskId } ?: return Deletion.Delete(taskId)
        val untouched = own.state == OutboxItem.State.WAITING && !own.sending && own.attempts == 0
        return if (untouched || own.state == OutboxItem.State.FAILED) Deletion.Discard(own.id) else Deletion.Delete(taskId)
    }

    /** New tasks that have not reached the computer yet, oldest first. They have no id: read-only. */
    fun waiting(outbox: List<OutboxItem>): List<OutboxItem> =
        outbox.filter { it.kind == OutboxItem.Kind.TASK && it.state == OutboxItem.State.WAITING }
}
