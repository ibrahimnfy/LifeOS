package dev.lifeos.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The form of a `client_id` the bridge accepts. */
private val CLIENT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{15,63}$")

@Serializable
data class OutboxItem(
    val id: String,
    val kind: Kind,
    val createdAtMillis: Long,
    /**
     * The note text or the task title; for a tick, the task's title (for display only). For an
     * edit, the new text or title (and [due] the new date, null for none).
     */
    val text: String = "",
    val due: String? = null,
    val taskId: String? = null,
    val noteId: String? = null,
    val eventId: String? = null,
    val event: EventDraft? = null,
    val state: State = State.WAITING,
    /** Set (and stored) before the item is sent, cleared when the outcome is known. */
    val sending: Boolean = false,
    val attempts: Int = 0,
    val attemptAtMillis: Long? = null,
    val errorCode: String? = null,
) {
    @Serializable
    enum class Kind { NOTE, TASK, COMPLETE, EVENT_CREATE, EVENT_UPDATE, TASK_EDIT, NOTE_EDIT }

    @Serializable
    enum class State {
        /** Will be sent when the computer is reachable. */
        WAITING,

        /** Sent, but no answer came: it may have arrived. The owner decides (send again or discard). */
        CHECK,

        /** The computer refused it. */
        FAILED,
    }

    /**
     * The phone's own id of a new note or task, sent as `client_id`: the item's id, when it has
     * the form the bridge accepts (16 to 64 letters, digits, `-`, `_`; every item made by this
     * app has 16 hex digits).
     */
    val clientId: String? get() = id.takeIf { (kind == Kind.NOTE || kind == Kind.TASK) && CLIENT_ID.matches(it) }

    /**
     * Sending it twice does no harm, so an uncertain attempt is simply repeated: ticks, task
     * edits, event changes, and new notes and tasks with a [clientId] (the computer answers a
     * repeat with the item it already stored). A note edit is not: the note's id changes with
     * its text, so a repeat cannot find it.
     */
    val repeatable: Boolean
        get() = kind == Kind.COMPLETE || kind == Kind.EVENT_UPDATE || kind == Kind.TASK_EDIT || clientId != null
}

/** The outbox on storage: one JSON file, replaced atomically on every change. */
class OutboxStore(private val file: File) {
    fun load(): List<OutboxItem> = try {
        BridgeJson.decodeFromString(ListSerializer(OutboxItem.serializer()), file.readText())
    } catch (_: Exception) {
        emptyList()
    }

    @Throws(IOException::class)
    fun save(items: List<OutboxItem>) {
        file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("the outbox folder cannot be created") }
        val temporary = File(file.parentFile, file.name + ".new")
        FileOutputStream(temporary).use { out ->
            out.write(BridgeJson.encodeToString(ListSerializer(OutboxItem.serializer()), items).toByteArray())
            out.flush()
            out.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun wipe() {
        file.delete()
    }
}

/**
 * Writes that wait for the computer: new notes and tasks, ticks, new and changed events.
 * They are stored durably, sent in the order they were made, one at a time, and never sent
 * twice blindly:
 *
 * - "attempt started" is stored before an item is sent, so a crash in between is noticed;
 * - when nothing left the phone, the item simply keeps waiting;
 * - when it left but no answer came, a tick, a task edit, an event change and a new note or
 *   task with its `client_id` are repeated later (repeating them is harmless: a tick answered
 *   with "task not found" is done, a repeated create is answered with the stored item), while
 *   a new event and a note edit become "check" and are sent again only if the owner says so;
 * - a refusal by the computer makes the item "failed" and visible.
 */
class Outbox(
    private val store: OutboxStore,
    private val nowMillis: () -> Long,
    private val send: suspend (OutboxItem) -> Unit,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow(recover(store.load()))

    val items: StateFlow<List<OutboxItem>> = state

    init {
        persist(state.value)
    }

    /** An item that was being sent when the app died has an unknown outcome. */
    private fun recover(loaded: List<OutboxItem>): List<OutboxItem> = loaded.map { item ->
        if (!item.sending) item else item.copy(sending = false, state = if (item.repeatable) OutboxItem.State.WAITING else OutboxItem.State.CHECK)
    }

    @Synchronized
    private fun update(change: (List<OutboxItem>) -> List<OutboxItem>): Boolean {
        val next = change(state.value)
        if (!persist(next)) return false
        state.value = next
        return true
    }

    private fun persist(items: List<OutboxItem>): Boolean = try {
        store.save(items)
        true
    } catch (_: IOException) {
        false
    }

    /** Store a new item. False if it could not be written to storage (it is then not kept). */
    fun add(item: OutboxItem): Boolean = update { it + item }

    fun discard(id: String) {
        update { items -> items.filterNot { it.id == id && !it.sending } }
    }

    /**
     * Put a "check" or "failed" item back in line. For "check" this is allowed only once the
     * earlier attempt can no longer be accepted by the computer ([SETTLE_MILLIS] after it).
     */
    fun sendAgain(id: String): Boolean {
        val item = state.value.firstOrNull { it.id == id } ?: return false
        if (item.state == OutboxItem.State.CHECK && !settled(item)) return false
        return update { items -> items.map { if (it.id == id) it.copy(state = OutboxItem.State.WAITING, errorCode = null) else it } }
    }

    /**
     * Change the text of an item that has not left the phone yet (a second edit of the same
     * note must replace the first: the first one changes the id the second would aim at).
     * False if the item is on its way, needs a look, or is gone.
     */
    fun revise(id: String, text: String, due: String? = null): Boolean {
        val item = state.value.firstOrNull { it.id == id } ?: return false
        if (item.sending || item.state != OutboxItem.State.WAITING) return false
        return update { items ->
            items.map { if (it.id == id && !it.sending && it.state == OutboxItem.State.WAITING) it.copy(text = text, due = due) else it }
        }
    }

    /**
     * A task made on this phone reached the computer (#167): ticks and edits that wait for it
     * under its phone id ([from], see [LocalItems.PREFIX]) now name the computer's id [to].
     */
    fun retarget(from: String, to: String): Boolean = update { items ->
        items.map { if (it.taskId == from && !it.sending) it.copy(taskId = to) else it }
    }

    fun settled(item: OutboxItem): Boolean = nowMillis() - (item.attemptAtMillis ?: 0) > SETTLE_MILLIS

    /** Send what is waiting, in order. Returns how many items were completed. */
    suspend fun drain(): Int = mutex.withLock {
        var done = 0
        while (true) {
            val item = state.value.firstOrNull { it.state == OutboxItem.State.WAITING } ?: break
            // Stored before sending: if this cannot be written, nothing is sent.
            if (!update { items -> items.map { if (it.id == item.id) it.copy(sending = true, attempts = it.attempts + 1, attemptAtMillis = nowMillis()) else it } }) break
            val outcome = try {
                send(item)
                null
            } catch (error: ApiException) {
                error
            } catch (error: CancellationException) {
                // Stopped while the item was on its way: nobody knows whether it arrived.
                val (next, _) = afterFailure(item, ApiException(ApiException.Kind.UNCERTAIN))
                update { items -> items.map { if (it.id == item.id) next(it) else it } }
                throw error
            }
            if (outcome == null || alreadyDone(item, outcome)) {
                update { items -> items.filterNot { it.id == item.id } }
                done++
                continue
            }
            val (next, goOn) = afterFailure(item, outcome)
            update { items -> items.map { if (it.id == item.id) next(it) else it } }
            if (!goOn) break
        }
        done
    }

    /**
     * "Not found" can mean an earlier attempt arrived: a ticked task is no longer open, and an
     * edited note has a new id. For a note edit this holds only after an attempt that left the
     * phone without an answer ([OutboxItem.attempts] counts only those).
     */
    private fun alreadyDone(item: OutboxItem, error: ApiException): Boolean = error.status == 404 && when (item.kind) {
        OutboxItem.Kind.COMPLETE -> true
        OutboxItem.Kind.NOTE_EDIT -> item.attempts > 0
        else -> false
    }

    private fun afterFailure(item: OutboxItem, error: ApiException): Pair<(OutboxItem) -> OutboxItem, Boolean> = when {
        // Nothing left the phone: this was not an attempt.
        error.kind == ApiException.Kind.NOT_SENT || error.kind == ApiException.Kind.NOT_PAIRED || error.kind == ApiException.Kind.REFUSED ->
            Pair({ it.copy(sending = false, attempts = it.attempts - 1) }, false)
        error.kind == ApiException.Kind.UNCERTAIN ->
            if (item.repeatable) {
                Pair({ it.copy(sending = false) }, false)
            } else {
                Pair({ it.copy(sending = false, state = OutboxItem.State.CHECK) }, false)
            }
        // The computer is busy or its memory is not available right now: try again later.
        error.status >= 500 || error.status == 429 -> Pair({ it.copy(sending = false) }, false)
        else -> Pair({ it.copy(sending = false, state = OutboxItem.State.FAILED, errorCode = error.code) }, true)
    }

    fun wipe() {
        synchronized(this) { state.value = emptyList() }
        store.wipe()
    }

    companion object {
        /** The computer accepts a request for five minutes; after that an old attempt is dead. */
        const val SETTLE_MILLIS = 6 * 60_000L
    }
}
