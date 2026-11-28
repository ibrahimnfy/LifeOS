package dev.lifeos.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A note the owner deleted a moment ago (#184) whose deletion can still be undone. [noteId] is
 * the name it was deleted under (the computer's id, or "local-" and the phone id of a note made
 * here); [knownAs] the computer's id such a note got meanwhile.
 */
@Serializable
data class PendingDelete(val noteId: String, val label: String = "", val atMillis: Long = 0, val knownAs: String? = null)

/** The deletions in their undo time on storage: one small JSON file, replaced atomically. */
class PendingDeleteStore(private val file: File) {
    fun load(): List<PendingDelete> = try {
        BridgeJson.decodeFromString(ListSerializer(PendingDelete.serializer()), file.readText())
    } catch (_: Exception) {
        emptyList()
    }

    fun save(items: List<PendingDelete>): Boolean = try {
        if (items.isEmpty()) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".new")
            FileOutputStream(temporary).use { out ->
                out.write(BridgeJson.encodeToString(ListSerializer(PendingDelete.serializer()), items).toByteArray())
                out.flush()
                out.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (_: Exception) {
        false
    }

    fun wipe() {
        file.delete()
    }
}

/**
 * Deleting a task with a moment to undo (#197), kept on storage like a note's deletion: when
 * the app ends inside the undo time, the next start completes it ([finishLeftOver]). An entry's
 * [PendingDelete.noteId] is the task's id here, its label the task's title.
 */
class TaskDeletions(
    private val store: PendingDeleteStore,
    private val outbox: Outbox,
    private val nowMillis: () -> Long,
    private val newItemId: () -> String,
) {
    private var pending: List<PendingDelete> = store.load()

    /** The tasks an earlier run left in their undo time: hidden until [finishLeftOver] ran. */
    val leftOver: Set<String> get() = pending.map { it.noteId }.toSet()

    private fun set(next: List<PendingDelete>) {
        pending = next
        store.save(next)
    }

    @Synchronized
    fun begin(taskId: String, title: String) {
        if (pending.none { it.noteId == taskId }) set(pending + PendingDelete(taskId, title.take(120), nowMillis()))
    }

    /** The owner took it back. False when it was handed to the outbox already. */
    @Synchronized
    fun undo(taskId: String): Boolean {
        if (pending.none { it.noteId == taskId }) return false
        set(pending.filterNot { it.noteId == taskId })
        return true
    }

    /**
     * The undo time is over: hand the deletion to the outbox under the id the task has now
     * ([idNow]; see [TaskLists.deletion]). True when something was queued to send.
     */
    @Synchronized
    fun finish(taskId: String, idNow: String = taskId): Boolean {
        val entry = pending.firstOrNull { it.noteId == taskId } ?: return false
        val queued = when (val plan = TaskLists.deletion(idNow, outbox.items.value)) {
            is TaskLists.Deletion.Discard -> {
                outbox.discard(plan.itemId)
                false
            }
            is TaskLists.Deletion.Delete -> {
                val there = outbox.items.value.any { it.kind == OutboxItem.Kind.TASK_DELETE && it.taskId == plan.taskId && it.state != OutboxItem.State.FAILED }
                there || outbox.add(OutboxItem(newItemId(), OutboxItem.Kind.TASK_DELETE, nowMillis(), text = entry.label, taskId = plan.taskId))
            }
        }
        set(pending.filterNot { it.noteId == taskId })
        return queued
    }

    @Synchronized
    fun finishLeftOver(): Boolean = pending.map { finish(it.noteId) }.any { it }

    @Synchronized
    fun wipe() {
        pending = emptyList()
        store.wipe()
    }
}

/**
 * Deleting a note with a moment to undo (#184), so that the moment survives the end of the app
 * (#191). A deletion is written to storage when it [begin]s and leaves it when it is taken
 * back ([undo]) or handed to the outbox ([finish]). The timer between the two belongs to the
 * caller; when the app dies before it runs out, the next start completes what is left
 * ([finishLeftOver]): the note was gone from the screen and the offer to undo with it.
 *
 * If storage cannot be written the deletion still works for as long as the app lives.
 */
class NoteDeletions(
    private val store: PendingDeleteStore,
    private val outbox: Outbox,
    private val nowMillis: () -> Long,
    private val newItemId: () -> String,
    /** The computer's id of a note made here (its "local-" name), where the app knows it. */
    private val resolve: (String) -> String?,
) {
    private var pending: List<PendingDelete> = store.load()
    private val hiddenState = MutableStateFlow(names(pending))

    /** The notes the lists hide: being deleted, under every name they are shown by. */
    val hidden: StateFlow<Set<String>> = hiddenState

    private fun names(items: List<PendingDelete>): Set<String> = items.flatMap { listOfNotNull(it.noteId, it.knownAs) }.toSet()

    private fun set(next: List<PendingDelete>) {
        pending = next
        store.save(next)
        hiddenState.value = names(next)
    }

    /** The note disappears now. False when it cannot be deleted now (an edit of it is on its way). */
    @Synchronized
    fun begin(noteId: String, label: String): Boolean {
        if (pending.any { it.noteId == noteId }) return true
        if (!NoteLists.canDelete(noteId, outbox.items.value)) return false
        set(pending + PendingDelete(noteId, label.take(120), nowMillis()))
        return true
    }

    /** The owner took it back. False when the deletion is on its way already. */
    @Synchronized
    fun undo(noteId: String): Boolean {
        if (pending.none { it.noteId == noteId }) return false
        set(pending.filterNot { it.noteId == noteId })
        return true
    }

    /** A note made here reached the computer while its deletion waits: it stays hidden under its new id too. */
    @Synchronized
    fun retarget(localName: String, id: String) {
        if (pending.any { it.noteId == localName && it.knownAs != id }) set(pending.map { if (it.noteId == localName) it.copy(knownAs = id) else it })
    }

    /**
     * The undo time is over: hand the deletion to the outbox (see [NoteLists.deletion] for what
     * happens to what waits). True when something was queued that should be sent now.
     */
    @Synchronized
    fun finish(noteId: String): Boolean {
        val entry = pending.firstOrNull { it.noteId == noteId } ?: return false
        val queued = when (val plan = NoteLists.deletion(noteId, outbox.items.value) { entry.knownAs ?: resolve(it) }) {
            is NoteLists.Deletion.Discard -> {
                outbox.discard(plan.itemId)
                false
            }
            is NoteLists.Deletion.Delete -> {
                plan.dropEdit?.let { outbox.discard(it) }
                // Written before the entry is cleared; a replay after a crash in between finds it.
                val there = outbox.items.value.any { it.kind == OutboxItem.Kind.NOTE_DELETE && it.noteId == plan.noteId && it.state != OutboxItem.State.FAILED }
                // [label]: the note's start, for the notice when the computer refuses the deletion.
                there || outbox.add(OutboxItem(newItemId(), OutboxItem.Kind.NOTE_DELETE, nowMillis(), text = entry.label, noteId = plan.noteId))
            }
            // An edit left meanwhile: the note stays; nothing is sent.
            NoteLists.Deletion.NotNow -> false
        }
        set(pending.filterNot { it.noteId == noteId })
        return queued
    }

    /** At the start of the app: complete the deletions an earlier run left in their undo time. */
    @Synchronized
    fun finishLeftOver(): Boolean = pending.map { finish(it.noteId) }.any { it }

    @Synchronized
    fun wipe() {
        pending = emptyList()
        store.wipe()
        hiddenState.value = emptySet()
    }
}
