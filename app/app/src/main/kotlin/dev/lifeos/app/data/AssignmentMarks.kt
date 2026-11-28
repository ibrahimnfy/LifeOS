package dev.lifeos.app.data

/**
 * A course assignment marked by hand (#196): "done" (handed in, for example on paper, which
 * the site never shows as submitted) or "removed" (not to be listed), and the mark taken off
 * again. The computer keeps the marks and makes the assignment's task follow; the phone sends
 * a mark through the outbox, so it also works while the computer cannot be reached, and shows
 * it at once.
 */
object AssignmentMarks {
    const val DONE = "done"
    const val REMOVED = "removed"

    /** The marker the course sync writes into an assignment's task: `(assignment: moodle-<id>)`. */
    fun taskMarker(assignmentId: Long): String = "moodle-$assignmentId"

    private fun waiting(outbox: List<OutboxItem>): List<OutboxItem> =
        outbox.filter { it.kind == OutboxItem.Kind.ASSIGNMENT_MARK && it.state != OutboxItem.State.FAILED }

    /** The latest mark of an assignment that the computer has not confirmed (a refused one does not count). */
    fun pending(assignmentId: Long, outbox: List<OutboxItem>): OutboxItem? =
        waiting(outbox).lastOrNull { it.assignmentId == assignmentId }

    /** The mark the screens show: the one on its way, else the computer's [server] mark. */
    fun shown(assignmentId: Long, server: String?, outbox: List<OutboxItem>): String? {
        val pending = pending(assignmentId, outbox) ?: return server
        return pending.mark
    }

    /** A course's assignments as the screen lists them, and the removed ones apart. */
    data class Lists(val listed: List<Assignment>, val removed: List<Assignment>)

    /** The computer's lists with the marks that are still on their way applied. */
    fun lists(data: Assignments, outbox: List<OutboxItem>): Lists {
        val all = (data.assignments + data.removed).distinctBy { it.id }.map { it.copy(mark = shown(it.id, it.mark, outbox)) }
        return Lists(all.filter { it.mark != REMOVED }, all.filter { it.mark == REMOVED })
    }

    /**
     * The task markers of the assignments a waiting mark closes ("done" or "removed"): their
     * tasks leave the task lists at once, as the computer will make them ([TaskLists.open]).
     */
    fun closedTaskMarkers(outbox: List<OutboxItem>): Set<String> {
        val latest = waiting(outbox).filter { it.assignmentId != null }.associateBy { it.assignmentId!! }
        return latest.values.filter { it.mark != null }.map { taskMarker(it.assignmentId!!) }.toSet()
    }

    /** What marking an assignment does with what waits on this phone, see [plan]. */
    data class Plan(
        /** Marks of it that never left the phone: withdrawn, the new one replaces them. */
        val withdraw: List<String>,
        /** Whether the new mark has to be sent (false: the computer has it already, or will). */
        val send: Boolean,
    )

    /**
     * The owner wants [wanted] (null: no mark) for an assignment the computer shows with
     * [server]. Marks of it that have not left the phone are taken back; the new one is sent
     * unless that already leaves the assignment as wanted (an Undo of a mark that never left
     * sends nothing at all).
     */
    fun plan(assignmentId: Long, wanted: String?, server: String?, outbox: List<OutboxItem>): Plan {
        val mine = waiting(outbox).filter { it.assignmentId == assignmentId }
        val unsent = mine.filter { it.state == OutboxItem.State.WAITING && !it.sending && it.attempts == 0 }
        val left = mine - unsent.toSet()
        // What the assignment has once everything that already left has arrived.
        val then = if (left.isEmpty()) server else left.last().mark
        return Plan(unsent.map { it.id }, send = then != wanted)
    }
}
