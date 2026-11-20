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
    /** Open tasks without those the owner already ticked here (the tick is waiting or unclear). */
    fun open(tasks: List<Task>, outbox: List<OutboxItem>, justTicked: Set<String> = emptySet()): List<Task> {
        val ticked = outbox.filter { it.kind == OutboxItem.Kind.COMPLETE && it.state != OutboxItem.State.FAILED }.mapNotNull { it.taskId }.toSet()
        return tasks.filter { !it.done && it.id !in ticked && it.id !in justTicked }
    }

    /** New tasks that have not reached the computer yet, oldest first. They have no id: read-only. */
    fun waiting(outbox: List<OutboxItem>): List<OutboxItem> =
        outbox.filter { it.kind == OutboxItem.Kind.TASK && it.state == OutboxItem.State.WAITING }
}
