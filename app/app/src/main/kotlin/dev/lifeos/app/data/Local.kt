package dev.lifeos.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A new note or task the computer has just confirmed ([clientId] is the phone's id it was sent
 * with). It is shown from here until a list from the computer has caught up with it.
 */
@Serializable
data class Confirmed(
    val clientId: String,
    val atMillis: Long,
    val task: Task? = null,
    val note: NoteSummary? = null,
)

/**
 * The phone's own notes and tasks, together with the outbox: what waits to be sent is in the
 * outbox, what the computer confirmed a moment ago is here, so a capture is on every screen at
 * once and never flickers away while the lists catch up. One small JSON file; deleted on unpair.
 */
class ConfirmedStore(private val file: File, private val nowMillis: () -> Long) {
    private val state = MutableStateFlow(load())

    val items: StateFlow<List<Confirmed>> = state

    private fun load(): List<Confirmed> = try {
        BridgeJson.decodeFromString(ListSerializer(Confirmed.serializer()), file.readText())
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun add(item: Confirmed) = change { list -> list.filterNot { it.clientId == item.clientId } + item }

    /**
     * A full list of the computer (all open tasks, or all notes) that was asked for at
     * [askedAtMillis] already holds everything confirmed before that moment (or it was done or
     * removed since): those entries are no longer needed. Entries older than a week go anyway.
     */
    @Synchronized
    fun caughtUp(tasks: Boolean, askedAtMillis: Long) = change { list ->
        list.filterNot { (if (tasks) it.task != null else it.note != null) && it.atMillis < askedAtMillis }
            .filter { nowMillis() - it.atMillis < WEEK_MILLIS }
    }

    @Synchronized
    fun wipe() {
        state.value = emptyList()
        file.delete()
    }

    private fun change(edit: (List<Confirmed>) -> List<Confirmed>) {
        val next = edit(state.value)
        if (next == state.value) return
        state.value = next
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".new")
            temporary.writeText(BridgeJson.encodeToString(ListSerializer(Confirmed.serializer()), next))
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // Kept in memory; the lists catch up with the computer anyway.
        }
    }

    private companion object {
        const val WEEK_MILLIS = 7 * 24 * 60 * 60_000L
    }
}

/** How the screens put the phone's own notes and tasks into the computer's lists. */
object LocalItems {
    /** The id of a task that waits on the phone: it has no id of the computer yet. */
    const val PREFIX = "local-"

    fun isWaiting(task: Task): Boolean = isWaiting(task.id)

    fun isWaiting(taskId: String): Boolean = taskId.startsWith(PREFIX)

    /**
     * The id a tick or an edit names (#167): for a task made on this phone, the computer's id
     * once it confirmed the task ([known] in this run of the app, or still in [confirmed]);
     * otherwise [taskId] itself.
     */
    fun currentId(taskId: String, known: Map<String, String>, confirmed: List<Confirmed>): String {
        if (!isWaiting(taskId)) return taskId
        return known[taskId] ?: confirmed.firstOrNull { PREFIX + it.clientId == taskId }?.task?.id ?: taskId
    }

    /** Where a waiting tick or edit of a task goes when it is its turn to be sent. */
    sealed interface SendTo {
        /** The computer's task. */
        data class Computer(val id: String) : SendTo

        /** The task itself has not reached the computer: wait. */
        data object Later : SendTo

        /** The task never reached the computer (it was refused or discarded): nothing to send. */
        data object Nowhere : SendTo
    }

    /**
     * Where a tick or an edit of [taskId] goes now. A task made on this phone that the computer
     * has not confirmed: while it still waits in [outbox] (it is always sent before what was done
     * to it, see [Outbox.retarget]) the tick or edit waits too; once it was refused, or is gone
     * without a known id, there is no task on the computer to change.
     */
    fun sendTo(taskId: String, known: Map<String, String>, confirmed: List<Confirmed>, outbox: List<OutboxItem>): SendTo {
        val id = currentId(taskId, known, confirmed)
        if (!isWaiting(id)) return SendTo.Computer(id)
        val own = outbox.firstOrNull { it.kind == OutboxItem.Kind.TASK && PREFIX + it.id == id }
        return if (own != null && own.state != OutboxItem.State.FAILED) SendTo.Later else SendTo.Nowhere
    }

    /**
     * New tasks a list from [listAtMillis] does not have yet: those confirmed after it, then
     * those still waiting to be sent (as tasks with a [PREFIX] id; a refused one is not shown,
     * it has its own notice).
     */
    fun tasks(outbox: List<OutboxItem>, confirmed: List<Confirmed>, listAtMillis: Long): List<Task> =
        confirmed.filter { it.atMillis >= listAtMillis }.mapNotNull { it.task } +
            outbox.filter { it.kind == OutboxItem.Kind.TASK && it.state != OutboxItem.State.FAILED }
                .map { Task(PREFIX + it.id, it.text, due = it.due) }

    /** The computer's tasks with the phone's new ones; each task once (the computer's copy wins). */
    fun withTasks(server: List<Task>, local: List<Task>): List<Task> = (server + local).distinctBy { it.id }

    /** Notes confirmed after a list from [listAtMillis], newest first, and not in [server] yet. */
    fun notes(server: List<NoteSummary>, confirmed: List<Confirmed>, listAtMillis: Long): List<NoteSummary> {
        val known = server.map { it.id }.toSet()
        return confirmed.filter { it.atMillis >= listAtMillis }.mapNotNull { it.note }.filter { it.id !in known }.reversed()
    }

    /** "2026-11-21T10:05+03:00" (the bridge's `at`) as the lists write it: "2026-11-21 10:05". */
    fun noteMoment(at: String?): String? = at?.takeIf { it.length >= 16 }?.substring(0, 16)?.replace('T', ' ')
}
