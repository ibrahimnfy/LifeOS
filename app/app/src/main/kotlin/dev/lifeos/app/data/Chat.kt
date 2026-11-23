package dev.lifeos.app.data

import dev.lifeos.app.relay.AnswerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/**
 * Which conversation the Chat tab continues, so that it survives closing the app. Only the
 * session's id and its folder: never a message. The computer keeps the conversation itself.
 */
@Serializable
data class ChatPointer(
    val sessionId: String,
    val folderPath: String = "",
    val folderLabel: String = "",
    val memory: Boolean = false,
    /** The folder's kind and colour (#168); a pointer saved before them has neither. */
    val kind: String = "",
    val colour: Int = ChatFolder.NO_COLOUR,
)

interface ChatStore {
    fun load(): ChatPointer?

    /** Null forgets the conversation. */
    fun save(pointer: ChatPointer?)
}

/** The pointer as one small file in the app's private files (never in a backup; deleted on unpair). */
class ChatFile(private val file: File) : ChatStore {
    override fun load(): ChatPointer? = try {
        BridgeJson.decodeFromString(ChatPointer.serializer(), file.readText()).takeIf { Chat.validId(it.sessionId) }
    } catch (_: Exception) {
        null
    }

    override fun save(pointer: ChatPointer?) {
        try {
            if (pointer == null) {
                file.delete()
                return
            }
            val folder = file.parentFile ?: return
            if (!folder.isDirectory && !folder.mkdirs()) return
            val temporary = File(folder, file.name + ".new")
            FileOutputStream(temporary).use { it.write(BridgeJson.encodeToString(ChatPointer.serializer(), pointer).toByteArray()) }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // Without the file the next start opens the list instead of this conversation.
        }
    }
}

/**
 * One bubble. [incomplete]: the answer was cut off (a failed or stopped run), or is still being
 * written elsewhere. [files]: what the assistant offered with this answer.
 */
data class ChatMessage(
    val key: Long,
    val fromOwner: Boolean,
    val text: String,
    val incomplete: Boolean = false,
    val files: List<OfferedFile> = emptyList(),
    /** The MCP servers this answer used (#153): they ran outside the sandbox. */
    val servers: List<String> = emptyList(),
)

/**
 * A file the assistant offered. Nothing of it is on the phone: [offer] only names it, and the
 * file is fetched when the owner taps "Download" (while the offer is valid, [open]).
 * [refused]: why the computer does not send it. Neither: it was offered in an earlier answer
 * that is shown from the computer's conversation, where offers are not kept; the owner asks again.
 */
data class OfferedFile(val name: String, val offer: FileOffer? = null, val refused: Refusal? = null, val offeredAt: Long = 0) {
    enum class Refusal { TOO_LARGE, OUTSIDE, HIDDEN, TOO_MANY, OTHER }

    /** Whether the offer can still be used at [now] (the phone's running clock). */
    fun open(now: Long): Boolean = offer != null && now - offeredAt < Chat.OFFER_MILLIS

    companion object {
        /** The bridge's reason (files.py) as what the card says. */
        fun refusal(reason: String): Refusal = when {
            reason.startsWith("larger than") -> Refusal.TOO_LARGE
            reason.startsWith("outside") -> Refusal.OUTSIDE
            reason.startsWith("hidden") -> Refusal.HIDDEN
            reason.startsWith("too many") -> Refusal.TOO_MANY
            else -> Refusal.OTHER
        }
    }
}

enum class ChatPhase {
    /** Nothing in progress: the owner can write. */
    IDLE,

    /** The message is on its way or the assistant has started; no text of the answer yet. */
    SENDING,

    /** The answer is arriving piece by piece. */
    ANSWERING,

    /**
     * The computer is working on this conversation, but the app is not receiving the answer as
     * it is written (the connection was interrupted, the app was locked, or the run was started
     * somewhere else). The app asks now and then and shows the answer when the run has ended.
     */
    ELSEWHERE,
}

enum class ChatProblem {
    /** Nothing was sent: the computer cannot be reached. The text is back in the composer. */
    NOT_REACHED,

    /** A first message was sent but nothing came back: the conversation may exist under Sessions. */
    NO_ANSWER,

    /**
     * The answer was lost on the way, the run is over, and the conversation on the computer
     * does not end with this message: most likely it never arrived. The owner decides whether
     * to send it again.
     */
    NOT_DELIVERED,

    /** The session is open on the computer right now: the computer refuses a second writer. */
    IN_USE,

    /** Too many runs at once on the computer. */
    BUSY,

    /** An approved update is being applied; the computer restarts in a few minutes (#180). */
    UPDATING,

    /** The session's folder no longer exists, or is no longer an allowed folder. */
    FOLDER_GONE,

    /** The computer no longer has this session. */
    SESSION_GONE,

    /** Capable mode: the folder sits inside another git repository. */
    FOLDER_IN_REPOSITORY,

    /** Capable mode is switched off on the computer (its guardrail check has not passed). */
    CAPABLE_OFF,

    /** The assistant is not set up on the computer. */
    NOT_SET_UP,

    /** The run took longer than the computer allows and was stopped. */
    TOO_LONG,

    /** The run was stopped (cancelled) on the computer. */
    STOPPED,

    /** The coach conversation is being tidied up: that run is never stopped; the message is answered after it. */
    COMPACTING,

    /** The owner stopped the run from this phone: what it had changed stays. */
    STOPPED_HERE,

    /** The request to stop the run did not get through. */
    STOP_FAILED,

    /** A change to a waiting message did not get through; the list shows what the computer has. */
    QUEUE_FAILED,

    /** The message starts with "/" but names none of the folder's skills or commands. */
    COMMAND_UNKNOWN,

    /** The folder's skills cannot be used right now (the computer's check for them has not passed). */
    SKILLS_OFF,

    /** The folder has approved MCP servers, and the computer's check for them has not passed: nothing ran. */
    MCP_OFF,

    /** The computer could not write its audit log, so it ran nothing. */
    UNRECORDED,

    /** A setting of the session (its model) could not be changed on the computer. */
    SETTING_FAILED,

    /** The message is longer than the computer accepts. */
    TOO_LARGE,

    /**
     * A first message was on its way when the app was locked, and the computer has no
     * conversation for it: it did not arrive. Its text is gone with the lock.
     */
    FIRST_LOST,

    /** The coach conversation could not be tidied up now (or the computer did not say that it was). */
    TIDY_FAILED,

    /** This phone is not paired any more. */
    NOT_PAIRED,

    /** Blank chats are switched off on the computer: its chat folder cannot be used (#163). */
    CHAT_ROOT_UNUSABLE,

    /** Anything else the computer answered with. */
    FAILED,
}

/**
 * Why the last message has no answer. [prompt] is what "Retry" sends again (null: retrying makes
 * no sense); [replaces] is the bubble a retry replaces (a message the computer never took).
 * [detail]: the computer's own reason, where the screen shows it ([ChatProblem.CHAT_ROOT_UNUSABLE]).
 */
data class ChatFailure(val problem: ChatProblem, val prompt: String? = null, val replaces: Long? = null, val status: Int = 0, val detail: String? = null)

/**
 * A message in the session's queue on the computer. [key] is the phone's own handle for the
 * row; [id] is the computer's (empty while the message is on its way there: [busy]).
 */
data class QueuedMessage(val key: Long, val id: String, val prompt: String, val busy: Boolean = false)

/**
 * Where the conversation runs, as the header shows it. A blank chat (#166) has no folder of its
 * own on the phone ([BLANK]): its first message names none, and the computer gives it a folder
 * of its own; a blank chat continued from the list has that folder as its [path], and its
 * [kind] says that it is a blank chat (#163). [colour]: the folder's colour number, or
 * [NO_COLOUR] while it is not known.
 */
data class ChatFolder(val path: String, val label: String, val memory: Boolean, val kind: String = "", val colour: Int = NO_COLOUR) {
    val blank: Boolean get() = kind == FolderKinds.CHAT || (kind.isEmpty() && path.isEmpty() && !memory)

    /** What the first message of a new conversation names as its folder: none for a blank chat. */
    val requested: String? get() = path.takeIf { !blank && it.isNotEmpty() }

    fun pointer(sessionId: String) = ChatPointer(sessionId, path, label, memory, kind, colour)

    /** What the fresh read of the session says about its folder; an older bridge says nothing. */
    fun refreshed(session: SessionSummary): ChatFolder = if (session.kind.isEmpty()) {
        this
    } else {
        ChatFolder(session.folder.ifEmpty { path }, session.folderName.ifEmpty { label }, memory || session.kind == FolderKinds.MEMORY, session.kind, session.colour)
    }

    companion object {
        const val NO_COLOUR = -1
        val BLANK = ChatFolder("", "", false, FolderKinds.CHAT)

        fun of(pointer: ChatPointer) = ChatFolder(pointer.folderPath, pointer.folderLabel, pointer.memory, pointer.kind, pointer.colour)

        fun of(session: SessionSummary, memory: Boolean) =
            ChatFolder(session.folder, session.folderName, memory || session.kind == FolderKinds.MEMORY, session.kind, if (session.kind.isEmpty()) NO_COLOUR else session.colour)

        /** A new conversation in [folder]; a blank chat without one. */
        fun of(folder: Folder?) = if (folder == null || folder.isChat) BLANK else ChatFolder(folder.path, folder.label, folder.isMemory, folder.kind, folder.colour)
    }
}

data class ChatState(
    /** Changes whenever another conversation is shown; work for an earlier one is dropped. */
    val generation: Int = 0,
    val sessionId: String? = null,
    /**
     * A new conversation's id, chosen by the phone and sent with its first message (#148),
     * until the computer has confirmed the session (then it is [sessionId]). With it a first
     * message whose answer was lost can be looked for, and sent again without running twice.
     */
    val startingId: String? = null,
    val folder: ChatFolder? = null,
    val messages: List<ChatMessage> = emptyList(),
    val phase: ChatPhase = ChatPhase.IDLE,
    /** When the current wait began (for "working for 0:42"). */
    val sinceMillis: Long = 0,
    val failure: ChatFailure? = null,
    /** Text handed back to the composer because it was not sent; the screen takes it ([Chat.takeDraft]). */
    val draft: String? = null,
    /** Whether the messages of [sessionId] were read from the computer (a new conversation has none to read). */
    val loaded: Boolean = true,
    val loading: Boolean = false,
    val loadProblem: Problem? = null,
    /** Where the messages before the ones shown begin on the computer; null when there are none. */
    val olderCursor: String? = null,
    val loadingOlder: Boolean = false,
    /** Counts the pages of earlier messages put on top (the screen keeps its place when it changes). */
    val olderLoads: Int = 0,
    /** Messages that wait on the computer until the run before them has ended, in order. */
    val queue: List<QueuedMessage> = emptyList(),
    /** Why the queue does not move by itself ("cancel", "error", "restart"); null while it does. */
    val queueHold: String? = null,
    /** The owner asked to stop the run; its end is then no failure. */
    val stopping: Boolean = false,
    /** In [ChatPhase.ELSEWHERE]: the last question to the computer got no answer. */
    val unreachable: Boolean = false,
    /** Questions and approvals of the current run, in the order they came; answered ones stay until the next message. */
    val interactions: List<Interaction> = emptyList(),
    /**
     * The owner allowed changes for this conversation: its messages ask for the capable mode
     * (files may be changed and commands run, inside the computer's guardrails). Off for
     * every new conversation.
     */
    val capable: Boolean = false,
    /** The conversation's own model and effort (null: the default for chat sessions). */
    val choice: ModelChoice = ModelChoice(),
    /** Whether [choice] is what the computer has for this session (a restored conversation learns it from the session list). */
    val choiceKnown: Boolean = true,
    // ---- the coach conversation only (#114)
    /** What the computer says about the coach conversation: its session, size, summary and plan usage. */
    val coach: CoachChatView? = null,
    /** The message that was sent waits while the computer tidies the conversation up first (one more run). */
    val tidying: Boolean = false,
    /** The owner's message that began a renewed session: what stands above it was tidied up. */
    val renewedAt: Long? = null,
    /** The owner asked to tidy the conversation up now; the computer is at it. */
    val compacting: Boolean = false,
    /** What the tidying up before [renewedAt] did, as the answer's own stream said it (#156): files that got facts, a summary. */
    val tidied: CoachCompacted? = null,
    // ---- moving between conversations by asking (#164, #168); never from the history
    /**
     * The answer to the owner's message asked to open a conversation or to start one in a
     * folder: the screen does it once ([Chat.takeSwitch]). Gone with a lock or another conversation.
     */
    val switchTo: ChatSwitch? = null,
    /** When [switchTo] came (the phone's running clock): one that waited too long becomes a card ([Chat.takeSwitch]). */
    val switchAt: Long = 0,
    /** Conversations (or a folder) the last answer offers on a card; the owner picks. Gone with the next message. */
    val choices: ChatSwitch? = null,
) {
    val canSend: Boolean get() = phase == ChatPhase.IDLE && !loading && loadProblem == null && loaded && !compacting

    /** A message written now waits in line on the computer: an answer is awaited and the session is known. */
    val canQueue: Boolean get() = phase != ChatPhase.IDLE && sessionId != null && queue.size < Chat.MAX_QUEUED

    /** The computer has earlier messages than the ones shown. */
    val olderExist: Boolean get() = olderCursor != null

    /**
     * A run can be stopped once its session is known. While the computer tidies the coach
     * conversation up first it refuses (`compaction_running`), and the screen says so (#156).
     */
    val canStop: Boolean get() = phase != ChatPhase.IDLE && sessionId != null && !stopping
    val isEmpty: Boolean get() = messages.isEmpty() && phase == ChatPhase.IDLE && failure == null
}

/**
 * The conversation of the Chat tab: one at a time, continued until the owner starts a new one
 * or opens another session. It lives outside the screens, so an answer keeps arriving while
 * the owner looks at something else.
 *
 * The computer keeps the conversation; the phone holds only what is on screen. Whenever the
 * app stops receiving an answer that the computer is still writing (connection lost, app
 * locked, a long silence), the run goes on there: the state becomes [ChatPhase.ELSEWHERE] and
 * [check] reads the result once the run has ended. A message is repeated only by the owner.
 *
 * The public functions are called from the main thread, except [onLocked] and [forget], which
 * the core also calls from its own scope (the lock timer): the state is one atomic value, and
 * work for a conversation that is no longer shown is dropped by its generation.
 */
class Chat(
    private val api: Api,
    private val store: ChatStore,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    /** Does the computer answer right now? Asked before a message is sent. */
    private val reachable: suspend () -> Boolean = { true },
    /** A clock that only runs forward (time since start), for how long a question still waits. */
    private val uptimeMillis: () -> Long = nowMillis,
    /**
     * The one conversation with the coach (#114) instead of a chat session: which session it
     * is the computer says (`GET /coach/chat`) and may change when it tidies the conversation
     * up; messages go to `/coach/chat/messages`; always read-only and on the coach's model.
     */
    private val coach: Boolean = false,
    /** A new conversation's id: a lowercase UUID, as the computer demands. */
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /** Nothing shown yet. The coach conversation always exists, so it is always to be read. */
    private fun blank(generation: Int) = ChatState(generation = generation, loaded = !coach)

    private val mutable = MutableStateFlow(blank(0))
    private val keys = AtomicLong()
    @Volatile
    private var run: Job? = null

    @Volatile
    private var reading: Job? = null
    private val side = mutableSetOf<Job>()

    /**
     * The message whose answer has not been seen to its end (text and bubble key); never kept
     * across a lock. If the answer is lost, the conversation on the computer tells whether the
     * message is part of it.
     */
    @Volatile
    private var awaiting: Pair<String, Long>? = null

    /** The id the computer called a repeat of an earlier first message (see [read]). */
    @Volatile
    private var repeatedId: String? = null

    /** The first message [ChatState.startingId] was sent with: the same text again is the same start. */
    @Volatile
    private var startingPrompt: String? = null

    /** Coach: the thread was already found without the awaited message once (see [read]). */
    @Volatile
    private var doubted = false

    /** The stream of the message that was sent has named its session: what runs now is the message itself. */
    @Volatile
    private var named = false

    /** Counts the owner's own choices of a model, so an older answer about the session never overwrites one. */
    @Volatile
    private var choiceEdits = 0

    /** The job that listens to a run the computer makes without a request of this app behind it (see [follow]). */
    @Volatile
    private var following: Job? = null

    /** Waiting messages the computer took out of its line (it is sending them): their text, for the thread. */
    private val taken = LinkedHashMap<String, QueuedMessage>()

    val state: StateFlow<ChatState> = mutable

    // ------------------------------------------------------------------ which conversation

    /**
     * After a start or an unlock: continue the conversation that was open last. Its messages
     * are read when the screen asks ([load]); the Chat tab shows it as the current chat.
     */
    fun restore() {
        if (coach) return
        val current = mutable.value
        if (current.sessionId != null || current.messages.isNotEmpty() || current.folder != null) return
        val pointer = store.load() ?: return
        mutable.value = ChatState(
            generation = current.generation + 1,
            sessionId = pointer.sessionId,
            folder = ChatFolder.of(pointer),
            loaded = false,
            choiceKnown = false,
        )
    }

    /**
     * "New chat": an empty conversation. Without [folder] (or with the blank chats' entry) it
     * is a blank chat (#166): its first message is sent without a folder, so the computer gives
     * it a folder of its own. With one ("Ask about this project", or a switch to a folder,
     * #164) it runs there, the memory folder included. [draft] goes into the composer. The
     * previous conversation stays on the computer (Sessions).
     */
    fun newChat(folder: Folder? = null, draft: String? = null) {
        if (coach) return
        stopWork()
        startingPrompt = null
        val where = ChatFolder.of(folder)
        mutable.update { ChatState(generation = it.generation + 1, folder = where, draft = draft?.takeIf { text -> text.isNotBlank() }) }
        store.save(null)
    }

    /**
     * The switch the last answer asked for, taken once by the screen that acts on it (it
     * opens the conversation, or starts the new one). Null when there is none. One that
     * waited longer than [SWITCH_FRESH_MILLIS] (the owner had left the conversation before
     * the answer ended) is not done by itself any more: it becomes the card to pick from.
     */
    fun takeSwitch(): ChatSwitch? {
        val now = uptimeMillis()
        var taken: ChatSwitch? = null
        mutable.update {
            val switch = it.switchTo
            when {
                switch == null -> it.also { taken = null }
                now - it.switchAt <= SWITCH_FRESH_MILLIS -> it.copy(switchTo = null).also { taken = switch }
                else -> it.copy(switchTo = null, choices = switch).also { taken = null }
            }
        }
        return taken
    }

    /**
     * Continue [session]: its last messages are read from the computer. [memory]: it runs in
     * the memory folder. [fresh]: a notification led here, so a conversation that is already
     * shown is read again (what waits for the owner may be newer than what is on screen).
     */
    fun open(session: SessionSummary, memory: Boolean = false, fresh: Boolean = false) {
        if (coach || !validId(session.id)) return
        val current = mutable.value
        if (current.sessionId == session.id && (current.loaded || current.loading)) {
            if (fresh) load()
            return
        }
        stopWork()
        val folder = ChatFolder.of(session, memory)
        mutable.update {
            ChatState(generation = it.generation + 1, sessionId = session.id, folder = folder, loaded = false, choice = ModelChoice(session.model, session.effort))
        }
        store.save(folder.pointer(session.id))
        load()
    }

    /** The app was locked: nothing of the conversation stays in memory; a run goes on on the computer. */
    fun onLocked() {
        val lostFirst = mutable.value.let { it.phase != ChatPhase.IDLE && it.sessionId == null }
        stopWork()
        if (coach) return mutable.update { blank(it.generation + 1) }
        mutable.update {
            ChatState(
                generation = it.generation + 1,
                sessionId = it.sessionId,
                // A first message was on its way: after the unlock its chosen id tells whether it arrived.
                startingId = it.startingId.takeIf { lostFirst },
                folder = it.folder,
                choice = it.choice,
                choiceKnown = it.choiceKnown,
                loaded = it.sessionId == null && !(lostFirst && it.startingId != null),
                // Without a chosen id (it never got that far) a first message cannot be followed up.
                failure = if (lostFirst && it.startingId == null) ChatFailure(ChatProblem.NO_ANSWER) else null,
            )
        }
    }

    /** Unpaired: forget everything, also the file. */
    fun forget() {
        stopWork()
        mutable.update { blank(it.generation + 1) }
        store.save(null)
    }

    private fun stopWork() {
        run?.cancel()
        reading?.cancel()
        run = null
        reading = null
        following = null
        awaiting = null
        synchronized(taken) { taken.clear() }
        synchronized(side) {
            side.forEach { it.cancel() }
            side.clear()
        }
    }

    /** Work next to the answer that is being received (a message for the queue, a stop request). */
    private fun launchSide(block: suspend () -> Unit) {
        val job = scope.launch { block() }
        synchronized(side) { side += job }
        job.invokeOnCompletion { synchronized(side) { side -= job } }
    }

    // ------------------------------------------------------------------ reading

    /** Read the conversation's last messages, and whether the computer is working on it. */
    fun load() {
        val current = mutable.value
        // The coach conversation's session is asked for first ([read]); a first message has its chosen id.
        val id = current.sessionId ?: current.startingId ?: if (coach) "" else return
        if (current.loading || current.phase == ChatPhase.SENDING || current.phase == ChatPhase.ANSWERING) return
        val generation = current.generation
        mutable.update { if (it.generation == generation) it.copy(loading = true, loadProblem = null) else it }
        reading = scope.launch { read(generation, id, wholeThread = true) }
        // What the computer keeps about the session is read fresh (#154), not taken from a list.
        val edits = choiceEdits
        if (!coach && current.sessionId != null) launchSide { settings(generation, id, edits) }
    }

    /**
     * The session's own model and effort as the computer has them now. A choice the owner made
     * since the question was asked ([edits] then differs) is newer than the answer and stays.
     * Its folder too (#168): a conversation opened by a switch, or restored from a pointer of
     * an older app, learns its path, kind and colour here.
     */
    private suspend fun settings(generation: Int, id: String, edits: Int) {
        val session = attempt { api.session(id) }.getOrNull() ?: return
        var moved: ChatFolder? = null
        change(generation) { state ->
            if (state.sessionId != id) return@change state
            val folder = state.folder?.refreshed(session) ?: state.folder
            if (folder != state.folder) moved = folder
            val chosen = if (choiceEdits == edits) state.copy(choice = ModelChoice(session.model, session.effort), choiceKnown = true) else state
            chosen.copy(folder = folder)
        }
        moved?.let { store.save(it.pointer(id)) }
    }

    /**
     * One look at a run the app is not receiving ([ChatPhase.ELSEWHERE]); the screen calls it
     * every few seconds while it is visible. When the run has ended, the conversation is read.
     */
    suspend fun check() {
        val current = mutable.value
        val id = current.sessionId ?: current.startingId ?: return
        if (current.phase != ChatPhase.ELSEWHERE || current.loading) return
        read(current.generation, id, wholeThread = false)
    }

    private suspend fun read(generation: Int, known: String, wholeThread: Boolean) {
        var id = known
        if (coach) {
            // Which session the coach conversation is now: it changes when the computer tidies it up.
            val view = attempt { api.coachChat() }
            view.getOrNull()?.let { found -> change(generation) { withCoach(it, found) } }
            val current = mutable.value
            if (current.generation != generation) return
            id = current.sessionId ?: return change(generation) {
                if (it.phase == ChatPhase.ELSEWHERE) {
                    it.copy(loading = false, unreachable = true)
                } else {
                    it.copy(loading = false, loaded = false, loadProblem = (view.exceptionOrNull() as? ApiException)?.let(UiStates::problemOf) ?: Problem.SERVER)
                }
            }
        }
        val run = attempt { api.runState(id) }
        val busy = run.getOrNull()?.busy
        // A run under the id the phone chose: the first message arrived, the session exists.
        if (busy == true) change(generation) { confirmed(it, id) }
        run.getOrNull()?.let { listed -> change(generation) { queueFrom(it, listed) } }
        // A run the app does not hear may be waiting for the owner's yes or no; and a card that
        // waits across runs (a skill install, #97) is there whenever the conversation is read.
        if (busy == true || (!coach && mutable.value.sessionId == id)) pending(generation, id)
        val running = run.getOrNull()?.running == true
        if (!wholeThread) {
            if (busy == null) return change(generation) { it.copy(unreachable = true) }
            if (busy) {
                change(generation) { it.copy(unreachable = false) }
                // The run can be heard after all: its answer is followed as it is written (#154).
                if (running) follow(generation, id)
                return
            }
        }
        val page = attempt { api.history(id) }
        val error = page.exceptionOrNull() as? ApiException
        val gone = error != null && error.kind == ApiException.Kind.HTTP && error.code == "session_not_found"
        // The coach conversation always exists; before its first message the computer has no thread to read.
        val exchanges = page.getOrNull() ?: HistoryPage().takeIf { coach && gone }
        // A sent message whose answer was never seen: the thread tells whether it arrived.
        val sent = awaiting
        val unconfirmed = mutable.value.sessionId == null
        if (gone && busy != true && sent != null && repeatedId == id && !doubted) {
            // "A repeat", says the computer, yet nothing runs and nothing is there: the first
            // try may be starting at this very moment. Look once more before giving the id up.
            doubted = true
            return change(generation) { it.copy(loading = false, phase = ChatPhase.ELSEWHERE, unreachable = false) }
        }
        if (coach && sent != null && busy != true && exchanges != null && !doubted && !arrived(exchanges, sent)) {
            // Between tidying up and the answer's start there is a moment in which the new
            // session has neither a run nor the message: look once more before saying "not delivered".
            doubted = true
            return change(generation) { it.copy(loading = false, loaded = true, phase = ChatPhase.ELSEWHERE, sinceMillis = since(it), unreachable = false) }
        }
        change(generation) { state ->
            when {
                exchanges != null -> shown(confirmed(state, id), exchanges, busy == true, sent)
                // The message is not there and nothing runs: it did not arrive (or was refused, and the
                // answer was lost). Asked under the id the phone chose, that id is kept: "Retry" is
                // then recognised as the same start, should the message arrive after all.
                gone && sent != null && busy != true -> state.copy(
                    sessionId = null,
                    startingId = if (unconfirmed) state.startingId else null,
                    loading = false,
                    loaded = true,
                    phase = ChatPhase.IDLE,
                    failure = ChatFailure(ChatProblem.NOT_DELIVERED, sent.first, replaces = sent.second),
                )
                // The same after a lock, when the text of the first message is gone too.
                gone && state.sessionId == null && busy != true ->
                    state.copy(startingId = null, loading = false, loaded = true, phase = ChatPhase.IDLE, failure = ChatFailure(ChatProblem.FIRST_LOST))
                busy == true ->
                    // The computer works on it although its messages cannot be read (yet).
                    state.copy(loading = false, loaded = true, phase = ChatPhase.ELSEWHERE, sinceMillis = since(state))
                // The next message starts a new conversation in the same folder.
                gone -> state.copy(
                    sessionId = null,
                    messages = emptyList(),
                    loading = false,
                    loaded = true,
                    phase = ChatPhase.IDLE,
                    failure = ChatFailure(ChatProblem.SESSION_GONE),
                )
                // No answer: while a run is awaited the app keeps asking; otherwise the screen offers "Retry".
                state.phase == ChatPhase.ELSEWHERE -> state.copy(loading = false, unreachable = true)
                else -> state.copy(loading = false, loaded = false, loadProblem = error?.let(UiStates::problemOf) ?: Problem.SERVER)
            }
        }
        if (mutable.value.generation != generation) return
        // The id leads nowhere and is given up (unless "Retry" is to repeat a first message under it).
        if (gone && busy != true && !(sent != null && unconfirmed)) {
            startingPrompt = null
            store.save(null)
        }
        if ((exchanges != null || gone) && busy != true && awaiting === sent) awaiting = null
        if (exchanges != null) restoreOffers(generation, id)
        if (exchanges != null && running) follow(generation, id)
    }

    /** The thread as the computer has it; a sent message that is not in it did not arrive. */
    private fun shown(state: ChatState, page: HistoryPage, busy: Boolean, sent: Pair<String, Long>?): ChatState {
        val messages = bubbles(page)
        val base = state.copy(
            messages = messages,
            loading = false,
            loaded = true,
            loadProblem = null,
            olderCursor = page.older,
            loadingOlder = false,
            unreachable = false,
        )
        if (busy) return base.copy(phase = ChatPhase.ELSEWHERE, sinceMillis = since(state))
        val arrived = arrived(page, sent)
        // The run is over: whatever still waited was counted as "no" there.
        val ended = base.copy(phase = ChatPhase.IDLE, stopping = false, interactions = closed(base.interactions))
        if (arrived || sent == null) return ended
        val bubble = ChatMessage(sent.second, true, sent.first)
        return ended.copy(messages = messages + bubble, failure = ChatFailure(ChatProblem.NOT_DELIVERED, sent.first, replaces = sent.second))
    }

    /** The computer has the session [id]: an id the phone chose for a new conversation is now the session's. */
    private fun confirmed(state: ChatState, id: String): ChatState =
        if (state.sessionId == null && state.startingId == id) state.copy(sessionId = id, startingId = null) else state

    private fun arrived(page: HistoryPage, sent: Pair<String, Long>?): Boolean =
        sent == null || page.exchanges.lastOrNull()?.user?.let { same(it, sent.first) } == true

    /** [view] is what the computer says about the coach conversation now. A changed session: it was tidied up. */
    private fun withCoach(state: ChatState, view: CoachChatView): ChatState {
        val id = view.sessionId.takeIf(::validId) ?: return state.copy(coach = view)
        val renewed = state.sessionId != null && state.sessionId != id
        return state.copy(
            coach = view,
            sessionId = id,
            // Earlier messages of the session before are not part of the new one.
            olderCursor = if (renewed) null else state.olderCursor,
            renewedAt = if (renewed) awaiting?.second ?: state.renewedAt else state.renewedAt,
        )
    }

    /** Coach: read the conversation's size and plan usage again (after an answer). */
    private suspend fun refreshCoach(generation: Int) {
        attempt { api.coachChat() }.getOrNull()?.let { view -> change(generation) { withCoach(it, view) } }
    }

    /**
     * Coach: tidy the conversation up now instead of when it has grown long. One run on the
     * computer; afterwards a new session continues and the screen starts empty.
     */
    fun tidyCoach() {
        val state = mutable.value
        if (!coach || state.compacting || !state.canSend || state.coach?.started != true) return
        val generation = state.generation
        change(generation) { it.copy(compacting = true, failure = null) }
        launchSide {
            val done = attempt { api.compactCoach() }
            if (done.isFailure) {
                change(generation) { it.copy(compacting = false, failure = ChatFailure(ChatProblem.TIDY_FAILED)) }
                // It may have been done all the same: the computer says which session it is now.
                return@launchSide refreshCoach(generation)
            }
            change(generation) {
                it.copy(compacting = false, messages = emptyList(), olderCursor = null, interactions = emptyList(), renewedAt = null, loading = true)
            }
            read(generation, "", wholeThread = true)
        }
    }

    /**
     * One more look at what waits for the owner, for the screen: a skill that is still being
     * downloaded becomes ready (or fails) without any event, so the card asks while it is shown.
     */
    suspend fun refreshCards() {
        val state = mutable.value
        val id = state.sessionId ?: return
        if (!state.loading && state.loaded) pending(state.generation, id)
    }

    /** What the computer says is waiting: new items are shown, items it no longer lists are closed. */
    private suspend fun pending(generation: Int, id: String) {
        val list = attempt { api.interactions(id) }.getOrNull() ?: return
        val now = uptimeMillis()
        val wall = nowMillis()
        change(generation) { state ->
            val listed = list.associateBy { it.id }
            val known = state.interactions.map { it.id }.toSet()
            val kept = state.interactions.map {
                val data = listed[it.id]
                when {
                    it.status != Interaction.Status.WAITING -> it
                    data == null -> it.copy(status = Interaction.Status.GONE)
                    // The computer still waits, whatever the two clocks say: answerable until the next look.
                    // What a card that waits across runs shows may have changed (downloaded, or failed).
                    else -> it.copy(deadline = maxOf(it.deadline, now + STILL_WAITING_MILLIS), install = if (it.install != null) SkillInstall.of(data) else null, server = data.server ?: it.server)
                }
            }
            val added = list.filter { it.id !in known }.mapNotNull { data ->
                Interaction.of(data, now, wall, fresh = false)?.let { it.copy(deadline = maxOf(it.deadline, now + STILL_WAITING_MILLIS)) }
            }
            state.copy(interactions = kept + added)
        }
    }

    /**
     * The messages before the ones shown, one page at a time (when the owner scrolls up). They
     * are held only as long as the conversation is on screen.
     */
    fun loadOlder() {
        val current = mutable.value
        val id = current.sessionId ?: return
        val cursor = current.olderCursor ?: return
        if (current.loadingOlder || current.loading) return
        val generation = current.generation
        change(generation) { it.copy(loadingOlder = true) }
        launchSide {
            val page = attempt { api.history(id, cursor) }
            val error = page.exceptionOrNull() as? ApiException
            change(generation) { state ->
                val found = page.getOrNull()
                when {
                    // The thread was read anew meanwhile: this page no longer belongs on top of it.
                    state.olderCursor != cursor -> state.copy(loadingOlder = false)
                    found != null -> state.copy(
                        messages = bubbles(found) + state.messages,
                        olderCursor = found.older,
                        loadingOlder = false,
                        olderLoads = state.olderLoads + 1,
                    )
                    // A cursor the computer does not know any more: nothing further back can be asked for.
                    error?.kind == ApiException.Kind.HTTP && error.status == 400 -> state.copy(olderCursor = null, loadingOlder = false)
                    else -> state.copy(loadingOlder = false)
                }
            }
            restoreOffers(generation, id)
        }
    }

    private fun bubbles(page: HistoryPage): List<ChatMessage> = buildList {
        for (exchange in page.exchanges) {
            if (exchange.user.isNotBlank()) add(ChatMessage(keys.incrementAndGet(), true, exchange.user))
            // The conversation on the computer still has the lines that offered files; the offers themselves are gone.
            val text = visible(exchange.assistant)
            val files = offeredNames(exchange.assistant).map { OfferedFile(it) }
            val servers = exchange.mcpServers.map { it.name }.filter { it.isNotBlank() }
            if (text.isNotBlank() || files.isNotEmpty()) add(ChatMessage(keys.incrementAndGet(), false, text, incomplete = !exchange.complete, files = files, servers = servers))
        }
    }

    /** The file cards of a finished answer: offers with the session they belong to, and refusals. */
    private fun files(data: JsonObject?, sessionId: String?): List<OfferedFile> {
        val now = uptimeMillis()
        val offered = (data?.get("files") as? JsonArray).orEmpty().mapNotNull { item ->
            val offer = try {
                BridgeJson.decodeFromJsonElement(FileOffer.serializer(), item)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            if (sessionId == null || !validId(offer.offer)) null else OfferedFile(offer.name, offer.copy(sessionId = sessionId), offeredAt = now)
        }
        val refused = (data?.get("files_refused") as? JsonArray).orEmpty().mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            OfferedFile(entry.text("name") ?: return@mapNotNull null, refused = OfferedFile.refusal(entry.text("reason").orEmpty()))
        }
        return offered + refused
    }

    /** The queue as the computer lists it; rows that are on their way there stay as they are. */
    private fun queueFrom(state: ChatState, listed: RunState): ChatState {
        val known = state.queue.associateBy { it.id }
        val rows = listed.queue.filter { validId(it.id) }.map { known[it.id]?.copy(prompt = it.prompt) ?: QueuedMessage(keys.incrementAndGet(), it.id, it.prompt) }
        val onTheWay = state.queue.filter { it.id.isEmpty() }
        // A message that left the line is being sent: its text is kept for the thread (see [attach]).
        val still = rows.map { it.id }.toSet()
        synchronized(taken) {
            state.queue.filter { it.id.isNotEmpty() && it.id !in still }.forEach { taken[it.id] = it }
            while (taken.size > MAX_QUEUED * 2) taken.remove(taken.keys.first())
        }
        return state.copy(queue = rows + onTheWay, queueHold = listed.paused.takeIf { rows.isNotEmpty() })
    }

    private fun since(state: ChatState): Long = if (state.phase == ChatPhase.IDLE || state.sinceMillis == 0L) nowMillis() else state.sinceMillis

    /**
     * File cards of answers read from the conversation have only a name: the computer keeps
     * the offers themselves, for 15 minutes. Those that have not run out are put back on
     * their cards (#154), so a card keeps its download after a lock or a re-read. The newest
     * answer first and each offer once: of two files of one name, the later offer belongs to
     * the later answer.
     */
    private suspend fun restoreOffers(generation: Int, id: String) {
        fun bare(file: OfferedFile) = file.offer == null && file.refused == null
        val shown = mutable.value
        if (shown.generation != generation || shown.messages.none { message -> message.files.any(::bare) }) return
        val offers = attempt { api.sessionFiles(id) }.getOrNull()?.filter { validId(it.offer) } ?: return
        val now = uptimeMillis()
        val wall = nowMillis()
        change(generation) { state ->
            val used = state.messages.flatMap { message -> message.files.mapNotNull { it.offer?.offer } }.toSet()
            val left = offers.filter { it.offer !in used }.sortedByDescending { it.expiresAt.orEmpty() }.toMutableList()
            val messages = state.messages.asReversed().map { message ->
                if (message.files.none(::bare)) return@map message
                val files = message.files.asReversed().map { file ->
                    val index = if (bare(file)) left.indexOfFirst { it.name == file.name } else -1
                    if (index < 0) return@map file
                    val offer = left.removeAt(index)
                    // The time that is left of its 15 minutes, by the computer's end time: not a new window.
                    val remaining = try {
                        java.time.Instant.parse(offer.expiresAt).toEpochMilli() - wall
                    } catch (_: Exception) {
                        0L
                    }
                    file.copy(offer = FileOffer(offer.offer, offer.name, offer.type, offer.size, sessionId = id), offeredAt = now - (OFFER_FULL_MILLIS - remaining.coerceIn(0, OFFER_FULL_MILLIS)))
                }
                message.copy(files = files.asReversed())
            }
            state.copy(messages = messages.asReversed())
        }
    }

    // ------------------------------------------------------------------ following a run (#154)

    /**
     * Listen to the run the computer is making for this conversation without a request of
     * this app behind it: a waiting message it sent by itself, or an answer the app stopped
     * hearing (a lock, a lost connection). It only reads on the computer, so it is never a
     * second writer. With nothing to follow, nothing changes here and the screen keeps asking.
     */
    private fun follow(generation: Int, id: String) {
        val state = mutable.value
        if (state.generation != generation || state.sessionId != id || state.phase != ChatPhase.ELSEWHERE) return
        if (following?.isActive == true) return
        val job = scope.launch { followRun(generation, id) }
        following = job
        // It is the answer that is being received now: a lock or another conversation ends it.
        run = job
    }

    private suspend fun followRun(generation: Int, id: String) {
        val answer = keys.incrementAndGet()
        val streamed = StringBuilder()
        var attached = false
        var ended = false
        var known = false
        var prompt: String? = null
        // Until the computer says that the replay is over (#157), what arrives was said earlier.
        var caughtUp = false
        try {
            api.live(id).firstOrNull { event ->
                when (event) {
                    // Also when the relay let the stream go (`stream_abandoned`): it is asked about below.
                    is AnswerEvent.Done -> true
                    is AnswerEvent.Stream -> when {
                        event.event == "live" -> {
                            val data = event.data as? JsonObject
                            // Without a run that can be followed the stream ends by itself at once: it is read to its end.
                            if ((data?.get("followable") as? JsonPrimitive)?.content == "true") {
                                attached = true
                                val asked = attach(generation, data.text("queue_id"))
                                prompt = asked.first
                                known = asked.second
                            }
                            false
                        }
                        event.event == "replay_end" -> {
                            caughtUp = true
                            false
                        }
                        // The final answer carries the whole text, whatever of the replay was left out.
                        !attached || event.event == "replay_truncated" -> false
                        // A replayed card has only its end time; a new one has its whole wait ahead.
                        // A followed answer never switches by itself (#168): what it asks for is offered on a card.
                        else -> onEvent(generation, event, prompt, answer, streamed, fresh = caughtUp, own = false).also { ended = it }
                    }
                    else -> false
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: ApiException) {
            // Not reached, or the stream broke off: the computer is asked instead, as before.
        }
        following = null
        if (!attached) return
        if (!ended) {
            // The stream stopped without saying how the run ended.
            change(generation) { if (it.phase == ChatPhase.IDLE) it else unheard(it) }
            delay(FOLLOW_AGAIN_MILLIS)
            return read(generation, id, wholeThread = false)
        }
        // The message this answer belongs to was not on screen: the thread is read as the computer has it.
        if (!known) return read(generation, id, wholeThread = true)
        afterRun(generation)
        if (coach) refreshCoach(generation)
    }

    /**
     * The followed run begins on screen. [queueId]: the waiting message it answers, which
     * moves from the line into the conversation. Returns that message's text (for "Retry"
     * after a failure) and whether the question of this answer is on screen.
     */
    private fun attach(generation: Int, queueId: String?): Pair<String?, Boolean> {
        var prompt: String? = awaiting?.first
        var known = prompt != null
        // What is followed is a run of its own, never the tidying up before one.
        named = true
        change(generation) { state ->
            val row = queueId?.let { id -> state.queue.firstOrNull { it.id == id } ?: synchronized(taken) { taken[id] } }
            val last = state.messages.lastOrNull()
            val unfinished = last != null && !last.fromOwner && last.incomplete
            val before = if (unfinished) state.messages.dropLast(1) else state.messages
            val asked = before.lastOrNull()?.takeIf { it.fromOwner }
            // What the run said so far comes again with the stream, so this run's unfinished answer on
            // screen gives way to it. An unfinished answer of the run before (stopped for this
            // message, or broken off) is not this run's: it stays as it is.
            val own = row == null || (asked != null && same(asked.text, row.prompt))
            var messages = if (own) before else state.messages
            if (row != null) {
                prompt = row.prompt
                if (!own) messages = messages + ChatMessage(row.key, true, row.prompt)
            }
            // A message of this app whose answer was never seen to its end, and now another one is
            // being answered: only the computer's conversation says what became of the first.
            known = (row != null || awaiting != null || asked != null) && !(queueId != null && awaiting != null)
            state.copy(
                messages = messages,
                queue = if (queueId != null) state.queue.filter { it.id != queueId } else state.queue,
                phase = ChatPhase.SENDING,
                sinceMillis = if (row != null) nowMillis() else since(state),
                unreachable = false,
                // A waiting message's own run: the stop that made room for it is done with.
                stopping = if (row != null) false else state.stopping,
                failure = state.failure?.takeIf { row == null || it.problem != ChatProblem.STOPPED_HERE },
                // Another run begins: a card of the answer before belonged to that answer.
                choices = null,
            )
        }
        return prompt to known
    }

    /** The answer is no longer heard: what was written of it is unfinished until the computer says more. */
    private fun unheard(state: ChatState): ChatState {
        val last = state.messages.lastOrNull()
        val cut = state.phase == ChatPhase.ANSWERING && last != null && !last.fromOwner
        return state.copy(messages = if (cut) state.messages.dropLast(1) + checkNotNull(last).copy(incomplete = true) else state.messages, phase = ChatPhase.ELSEWHERE)
    }

    // ------------------------------------------------------------------ sending

    /**
     * Send [prompt] in this conversation; while an answer is awaited it is put in line on the
     * computer instead and sent when that run has ended. False if nothing was started.
     */
    fun send(prompt: String): Boolean {
        val text = prompt.trim()
        val current = mutable.value
        if (text.isEmpty()) return false
        if (!current.canSend) return current.canQueue && enqueue(current.generation, text)
        val generation = current.generation
        val key = keys.incrementAndGet()
        // A new conversation gets its id from the phone (#148). The same first message again
        // keeps the id, so the computer can tell a repeat from a second start.
        val starting = when {
            coach || current.sessionId != null -> null
            current.startingId != null && startingPrompt == text -> current.startingId
            else -> newId()
        }
        if (starting != null) {
            startingPrompt = text
            // From here on the conversation can be found again, whatever happens to this answer.
            current.folder?.let { store.save(it.pointer(starting)) }
        }
        mutable.update {
            if (it.generation != generation) return false
            it.copy(
                startingId = starting,
                messages = it.messages + ChatMessage(key, true, text),
                phase = ChatPhase.SENDING,
                sinceMillis = nowMillis(),
                failure = null,
                draft = null,
                unreachable = false,
                stopping = false,
                interactions = carried(it.interactions),
                // The card of the last answer belonged to it (#168).
                switchTo = null,
                choices = null,
                // What the computer said last: the next message is tidied up first. Its stream confirms it.
                tidying = coach && it.coach?.dueForCompaction == true,
                // As on the computer: the hold a stop put on the queue ends with the owner's next message.
                queueHold = it.queueHold.takeIf { hold -> hold != HOLD_CANCEL },
            )
        }
        doubted = false
        named = false
        run = scope.launch { exchange(generation, text, key, queued = false) }
        return true
    }

    // ------------------------------------------------------------------ while a run is in progress

    /** Put [text] in line: one request, answered at once with the message's place in the queue. */
    private fun enqueue(generation: Int, text: String): Boolean {
        val key = keys.incrementAndGet()
        mutable.update { state ->
            if (state.generation != generation) return false
            state.copy(queue = state.queue + QueuedMessage(key, "", text, busy = true), draft = null, failure = null)
        }
        launchSide { exchange(generation, text, key, queued = true) }
        return true
    }

    /** Change the text of a waiting message (possible until the computer sends it). */
    fun editQueued(key: Long, prompt: String) {
        val text = prompt.trim()
        if (text.isNotEmpty()) queueChange(key, text) { id, queueId -> api.editQueued(id, queueId, text) }
    }

    /** Take a waiting message out of the line. */
    fun removeQueued(key: Long) = queueChange(key, null) { id, queueId -> api.removeQueued(id, queueId) }

    private fun queueChange(key: Long, newText: String?, request: suspend (String, String) -> Unit) {
        val state = mutable.value
        val id = state.sessionId ?: return
        val row = state.queue.firstOrNull { it.key == key && !it.busy && it.id.isNotEmpty() } ?: return
        val generation = state.generation
        change(generation) { st -> st.copy(queue = st.queue.map { if (it.key == key) it.copy(busy = true) else it }, failure = null) }
        launchSide {
            val done = attempt { request(id, row.id) }
            change(generation) { st ->
                val queue = when {
                    done.isFailure -> st.queue.map { if (it.key == key) it.copy(busy = false) else it }
                    newText == null -> st.queue.filter { it.key != key }
                    else -> st.queue.map { if (it.key == key) it.copy(prompt = newText, busy = false) else it }
                }
                st.copy(queue = queue, queueHold = st.queueHold.takeIf { queue.isNotEmpty() }, failure = if (done.isFailure) ChatFailure(ChatProblem.QUEUE_FAILED) else st.failure)
            }
            // It may have been sent meanwhile: the computer's list is the truth.
            if (done.isFailure) attempt { api.runState(id) }.getOrNull()?.let { listed -> change(generation) { queueFrom(it, listed) } }
        }
    }

    /**
     * "Send now": this waiting message becomes the next one, in one request (#154). A run in
     * progress is interrupted for it (what it has already changed stays; the screen says so
     * first); a line that is on hold goes on with it. Nothing is sent twice or deleted.
     */
    fun sendNow(key: Long) {
        val state = mutable.value
        val id = state.sessionId ?: return
        val row = state.queue.firstOrNull { it.key == key && !it.busy && it.id.isNotEmpty() } ?: return
        val generation = state.generation
        // An answer that is being received ends as "stopped": asked for, so no failure.
        val heard = state.phase == ChatPhase.SENDING || state.phase == ChatPhase.ANSWERING
        change(generation) { st -> st.copy(queue = st.queue.map { if (it.key == key) it.copy(busy = true) else it }, failure = null, stopping = st.stopping || heard) }
        launchSide {
            val sent = attempt { api.sendQueuedNow(id, row.id) }
            val result = sent.getOrNull()
            val error = sent.exceptionOrNull() as? ApiException
            fun free(st: ChatState) = st.copy(queue = st.queue.map { if (it.key == key) it.copy(busy = false) else it })

            // No "stopped" will come for this request: what was expected of the answer is taken back.
            fun calm(st: ChatState) = if (heard) st.copy(stopping = false) else st
            when {
                result != null -> {
                    change(generation) { st ->
                        // Nothing was interrupted (the run had just ended): no "stopped" will come. If
                        // something was, the answer's own end says so (it may have done so already).
                        queueFrom(free(st), RunState(queue = result.queue)).let { if (result.interrupted) it else calm(it) }
                    }
                    // Nothing heard here says how it goes on: ask, and follow the answer.
                    if (!heard) {
                        change(generation) { if (it.phase == ChatPhase.IDLE) it.copy(phase = ChatPhase.ELSEWHERE, sinceMillis = nowMillis(), interactions = carried(it.interactions), choices = null) else it }
                        delay(RESUME_LOOK_MILLIS)
                        read(generation, id, wholeThread = false)
                    }
                }
                // "Not queued": the computer sent it meanwhile. Its list says what waits now.
                error?.kind == ApiException.Kind.HTTP && error.status == 404 -> {
                    val listed = attempt { api.runState(id) }.getOrNull()
                    change(generation) { st -> calm(if (listed != null) queueFrom(free(st), listed) else free(st).let { it.copy(queue = it.queue.filter { waiting -> waiting.key != key }) }) }
                }
                else -> {
                    // It may have arrived all the same: the computer's list is the truth.
                    val listed = if (error?.kind == ApiException.Kind.UNCERTAIN) attempt { api.runState(id) }.getOrNull() else null
                    val problem = if (error?.code == COMPACTION_RUNNING) ChatProblem.COMPACTING else ChatProblem.QUEUE_FAILED
                    change(generation) { st -> calm(if (listed != null) queueFrom(free(st), listed) else free(st)).copy(failure = ChatFailure(problem)) }
                }
            }
        }
    }

    /**
     * Stop the current run (the computer interrupts it cleanly). What it has already changed
     * stays; messages that wait are kept and held until the owner sends or lets them go on.
     */
    fun cancel() {
        val state = mutable.value
        val id = state.sessionId ?: return
        if (!state.canStop) return
        val generation = state.generation
        change(generation) { it.copy(stopping = true, failure = null) }
        launchSide {
            val asked = attempt { api.cancelRun(id) }
            val result = asked.getOrNull()
            if (result == null) {
                // Coach: the tidying up before the message is never stopped; the computer says so.
                val tidyingUp = (asked.exceptionOrNull() as? ApiException)?.code == COMPACTION_RUNNING
                return@launchSide change(generation) { it.copy(stopping = false, failure = ChatFailure(if (tidyingUp) ChatProblem.COMPACTING else ChatProblem.STOP_FAILED)) }
            }
            change(generation) { st ->
                st.copy(
                    // Nothing was running any more: the answer is complete, or on its way.
                    stopping = st.stopping && result.cancelled,
                    queueHold = if (result.queuePaused && st.queue.isNotEmpty()) HOLD_CANCEL else st.queueHold,
                )
            }
            followStop(generation, id)
        }
    }

    /** After a stop request: the answer's own end normally says so; if it does not, ask. */
    private suspend fun followStop(generation: Int, id: String) {
        if (mutable.value.phase != ChatPhase.ELSEWHERE) delay(STOP_WAIT_MILLIS)
        val state = mutable.value
        if (state.generation != generation || !state.stopping || state.phase == ChatPhase.IDLE) return
        if (state.phase != ChatPhase.ELSEWHERE) {
            run?.cancel()
            change(generation) { unheard(it) }
        }
        read(generation, id, wholeThread = false)
    }

    /**
     * Let a queue that is on hold go on: the computer answers at once (#154) and sends the
     * waiting messages one after the other in the background; their answers are followed.
     */
    fun resumeQueue() {
        val state = mutable.value
        val id = state.sessionId ?: return
        val hold = state.queueHold
        if (hold == null || state.queue.isEmpty() || state.phase != ChatPhase.IDLE) return
        val generation = state.generation
        change(generation) { it.copy(phase = ChatPhase.ELSEWHERE, sinceMillis = nowMillis(), queueHold = null, failure = null, interactions = carried(it.interactions), choices = null) }
        launchSide {
            if (attempt { api.resumeQueue(id) }.isFailure) {
                // It may have arrived all the same: the computer's own word decides.
                val listed = attempt { api.runState(id) }.getOrNull()
                if (listed == null || (!listed.busy && listed.queue.isNotEmpty())) {
                    // Not let go: the line is on hold as before, and the screen says that it did not get through.
                    return@launchSide change(generation) { st ->
                        val held = if (listed != null) queueFrom(st, listed) else st
                        held.copy(phase = ChatPhase.IDLE, queueHold = held.queueHold ?: hold, failure = ChatFailure(ChatProblem.QUEUE_FAILED))
                    }
                }
            }
            delay(RESUME_LOOK_MILLIS)
            read(generation, id, wholeThread = false)
        }
    }

    /** "Retry" under a failed message: send it again, in place of the bubble the computer never took. */
    fun retry() {
        val failure = mutable.value.failure ?: return
        val prompt = failure.prompt ?: return
        mutable.update { state -> state.copy(failure = null, messages = state.messages.filter { it.key != failure.replaces }) }
        send(prompt)
    }

    fun dismissFailure() = mutable.update { it.copy(failure = null) }

    /** The composer took the text that was handed back. */
    fun takeDraft() = mutable.update { it.copy(draft = null) }

    /**
     * One message to the computer and what comes back: the answer as events, or one result
     * that says the message was refused or put in line. [queued]: it is meant for the line
     * behind a run; it can still get an answer at once (the run before it ended in the same
     * moment) and then becomes the conversation's current message ([startedAfterAll]).
     */
    private suspend fun exchange(generation: Int, prompt: String, key: Long, queued: Boolean) {
        if (!queued && !reachable()) return notSent(generation, prompt, key, ChatProblem.NOT_REACHED)
        val before = mutable.value
        if (before.generation != generation) return
        val answer = keys.incrementAndGet()
        val streamed = StringBuilder()
        var started = !queued
        // The relay let this stream go because its messages stayed unread (#157); the run goes on.
        var abandoned = false
        if (!queued) awaiting = prompt to key
        try {
            // Only a new conversation names its folder: none for a blank chat (#166), the path for
            // any other, the memory folder included (no folder means a blank chat since #163).
            val folder = before.folder?.requested
            val ended = api.chat(prompt, before.sessionId, folder, options(before), coach = coach).firstOrNull { event ->
                when (event) {
                    // Coach (#156): after its wait for the tidying up the message was put in line after all.
                    is AnswerEvent.Stream -> if (event.event == "queued") {
                        val placed = AnswerEvent.Done(dev.lifeos.app.relay.RelayResponse(202, event.data))
                        if (started) onRefusal(generation, placed, prompt, key) else onQueueAnswer(generation, placed, prompt, key)
                        true
                    } else {
                        if (!started) {
                            started = true
                            startedAfterAll(generation, prompt, key)
                        }
                        onEvent(generation, event, prompt, answer, streamed)
                    }
                    is AnswerEvent.Done -> {
                        when {
                            event.response.error == STREAM_ABANDONED -> abandoned = true
                            started -> onRefusal(generation, event, prompt, key)
                            else -> onQueueAnswer(generation, event, prompt, key)
                        }
                        true
                    }
                    else -> false
                }
            }
            // The stream ended without saying how the run ended: the computer is asked, and the run followed again.
            if ((ended == null || abandoned) && started) lost(generation)
            if (abandoned && !started) notQueued(generation, prompt, key, uncertain = true)
            if (started) afterRun(generation)
            if (started && coach) refreshCoach(generation)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException) {
            when {
                !started -> notQueued(generation, prompt, key, uncertain = error.kind == ApiException.Kind.UNCERTAIN)
                error.kind == ApiException.Kind.NOT_SENT -> notSent(generation, prompt, key, ChatProblem.NOT_REACHED)
                error.kind == ApiException.Kind.NOT_PAIRED || error.kind == ApiException.Kind.REFUSED -> notSent(generation, prompt, key, ChatProblem.NOT_PAIRED)
                else -> lost(generation)
            }
        }
    }

    /** A message meant for the line is answered right away: it is the current message now. */
    private fun startedAfterAll(generation: Int, prompt: String, key: Long) {
        awaiting = prompt to key
        named = false
        change(generation) { state ->
            state.copy(
                queue = state.queue.filter { it.key != key },
                messages = state.messages + ChatMessage(key, true, prompt),
                phase = ChatPhase.SENDING,
                sinceMillis = nowMillis(),
                stopping = false,
                unreachable = false,
                interactions = carried(state.interactions),
                switchTo = null,
                choices = null,
            )
        }
    }

    /** The one result for a message meant for the line: its place there, or a refusal. */
    private suspend fun onQueueAnswer(generation: Int, done: AnswerEvent.Done, prompt: String, key: Long) {
        val body = done.response.body as? JsonObject
        val queueId = body.text("queue_id")?.takeIf(::validId)
        if (done.response.status != 202 || queueId == null) {
            return if (done.response.ok) notQueued(generation, prompt, key, uncertain = true) else refusedForQueue(generation, done.response, prompt, key)
        }
        val position = (body?.get("position") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        renewedBy(generation, body)
        change(generation) { state ->
            // A look at the computer's list may have shown the new entry already.
            val others = state.queue.filter { it.key != key && it.id != queueId }
            val row = QueuedMessage(key, queueId, prompt)
            // Position 1 is the front; without a usable position it stands where the row stood.
            val at = if (position in 1..others.size + 1) position - 1 else state.queue.indexOfFirst { it.key == key }.coerceIn(0, others.size)
            state.copy(queue = others.take(at) + row + others.drop(at), queueHold = null)
        }
    }

    private fun refusedForQueue(generation: Int, response: dev.lifeos.app.relay.RelayResponse, prompt: String, key: Long) {
        val code = (response.body as? JsonObject).text("error") ?: response.error
        change(generation) { state ->
            state.copy(queue = state.queue.filter { it.key != key }, draft = prompt, failure = ChatFailure(problemOf(code, response.status)))
        }
    }

    /**
     * A message for the line got no (usable) answer. [uncertain]: it may have arrived, so the
     * computer's list decides; only when it is not there does the text return to the composer.
     */
    private suspend fun notQueued(generation: Int, prompt: String, key: Long, uncertain: Boolean) {
        val id = mutable.value.sessionId
        val listed = if (uncertain && id != null) attempt { api.runState(id) }.getOrNull() else null
        change(generation) { state ->
            val before = state.queue.map { it.id }.toSet()
            val without = state.copy(queue = state.queue.filter { it.key != key })
            val synced = if (listed != null) queueFrom(without, listed) else without
            val there = synced.queue.any { it.id.isNotEmpty() && it.id !in before && same(it.prompt, prompt) }
            if (there) synced else synced.copy(draft = prompt, failure = ChatFailure(ChatProblem.NOT_REACHED))
        }
    }

    /** A run of this conversation ended on screen: if messages wait and nothing holds them, the computer goes on. */
    private suspend fun afterRun(generation: Int) {
        val state = mutable.value
        val id = state.sessionId ?: return
        if (state.generation != generation || state.phase != ChatPhase.IDLE) return
        if (state.queue.none { it.id.isNotEmpty() } || state.queueHold != null) return
        change(generation) { it.copy(phase = ChatPhase.ELSEWHERE, sinceMillis = nowMillis()) }
        read(generation, id, wholeThread = false)
    }

    /**
     * What the request carries besides the prompt. `interactive`: this app shows questions and
     * approvals, so the run may wait for the owner instead of being refused at once. `mode`:
     * only when the owner allowed changes for this conversation.
     */
    private fun options(state: ChatState): Map<String, JsonElement> = buildMap {
        put("interactive", JsonPrimitive(true))
        // The coach conversation is read-only and has its own model: the computer refuses anything else.
        if (coach) return@buildMap
        if (state.capable) put("mode", JsonPrimitive("capable"))
        // A new conversation names its model with its first message; afterwards the computer remembers it.
        if (state.sessionId == null) {
            state.choice.model?.let { put("model", JsonPrimitive(it)) }
            state.choice.effort?.let { put("effort", JsonPrimitive(it)) }
            state.startingId?.let { put("new_session_id", JsonPrimitive(it)) }
        }
    }

    // ------------------------------------------------------------------ model and effort

    /**
     * The model and effort of this conversation (null: the default). For a session the
     * computer knows it is stored there and used from its next run; for a new conversation
     * it goes out with the first message.
     */
    fun setModel(choice: ModelChoice) {
        if (coach) return
        val state = mutable.value
        val before = state.choice
        if (choice == before && state.choiceKnown) return
        val generation = state.generation
        choiceEdits++
        change(generation) { it.copy(choice = choice, choiceKnown = true, failure = null) }
        val id = state.sessionId ?: return
        launchSide {
            val body = buildJsonObject {
                put("model", choice.model?.let(::JsonPrimitive) ?: JsonNull)
                put("effort", choice.effort?.let(::JsonPrimitive) ?: JsonNull)
            }
            if (attempt { api.patchSession(id, body) }.isFailure) {
                change(generation) { it.copy(choice = before, failure = ChatFailure(ChatProblem.SETTING_FAILED)) }
            }
        }
    }

    // ------------------------------------------------------------------ questions and approvals

    /** "May make changes" for this conversation, from the next message on. */
    fun setCapable(on: Boolean) = mutable.update { if (coach) it else it.copy(capable = on) }

    /** Yes or no to an action that needs the owner's approval. Only an explicit tap leads here. */
    fun answerApproval(id: String, approve: Boolean) =
        answer(id, Interaction.approvalBody(approve), if (approve) Interaction.Status.ALLOWED else Interaction.Status.REFUSED)

    /** The owner's choices (or own words) per question; see [Interaction.answersBody]. */
    fun answerQuestion(id: String, answers: Map<String, List<String>>) {
        val interaction = mutable.value.interactions.firstOrNull { it.id == id } ?: return
        if (!Interaction.complete(interaction.questions, answers)) return
        val given = answers.mapValues { (_, values) -> values.map { it.trim() }.filter { it.isNotEmpty() } }
        mutable.update { state -> state.copy(interactions = state.interactions.map { if (it.id == id) it.copy(answers = given) else it }) }
        answer(id, Interaction.answersBody(interaction.questions, answers), Interaction.Status.ANSWERED)
    }

    /** The owner does not want to answer the question; the assistant continues without. */
    fun skipQuestion(id: String) = answer(id, Interaction.declineBody, Interaction.Status.SKIPPED)

    /**
     * The time ran out on the phone: the card closes. Nothing is sent: the computer counts
     * the missing answer as "no" by itself.
     */
    fun expire(id: String) = mutable.update { state ->
        val now = uptimeMillis()
        state.copy(
            interactions = state.interactions.map {
                if (it.id == id && it.status == Interaction.Status.WAITING && now >= it.deadline) it.copy(status = Interaction.Status.EXPIRED) else it
            },
        )
    }

    private fun answer(id: String, body: JsonElement, result: Interaction.Status) {
        val state = mutable.value
        val session = state.sessionId ?: return
        val interaction = state.interactions.firstOrNull { it.id == id } ?: return
        if (!interaction.open(uptimeMillis())) return expire(id)
        // A skill whose contents are not listed (yet) can be declined, never installed.
        val approve = result == Interaction.Status.ALLOWED
        if (approve && !interaction.canApprove) return
        val generation = state.generation
        mark(generation, id) { it.copy(status = Interaction.Status.SENDING, sendFailed = false, refusal = null) }
        scope.launch {
            var refusal: Interaction.Refusal? = null
            var answered: JsonElement? = null
            val outcome = try {
                answered = api.answerInteraction(session, id, body)
                // A card that waits across runs says what the computer did with the yes.
                if (interaction.crossRun) Interaction.settled(approve, answered) else result
            } catch (error: ApiException) {
                // "No longer waiting" closes the card. A yes the computer refused leaves it waiting
                // there (#157), and one that was not delivered too: both can be answered again.
                refusal = Interaction.refusal(error, interaction.kind).takeIf { interaction.crossRun }
                Interaction.Status.GONE.takeIf { Interaction.gone(error) }
            }
            mark(generation, id) {
                if (outcome != null) {
                    // A move says which files went and which did not (#163).
                    it.copy(status = outcome, sendFailed = false, move = it.move?.answered(answered), update = it.update?.answered(answered))
                } else {
                    it.copy(status = Interaction.Status.WAITING, sendFailed = refusal == null, refusal = refusal)
                }
            }
            // What the card shows may be behind (downloaded meanwhile, or failed): look again.
            if (refusal != null) pending(generation, session)
        }
    }

    private fun mark(generation: Int, id: String, transform: (Interaction) -> Interaction) =
        change(generation) { state -> state.copy(interactions = state.interactions.map { if (it.id == id) transform(it) else it }) }

    /**
     * What stays when the next message begins: the cards that wait across runs and are not
     * settled. Everything else belonged to the run before.
     */
    private fun carried(interactions: List<Interaction>): List<Interaction> = interactions.filter { it.crossRun && it.pending }

    /**
     * The skill installs (#97) and the moves of a blank chat's files (#163) a finished answer
     * proposed: cards that wait from now on, and what was refused at once.
     */
    private fun proposals(data: JsonObject?): List<Interaction> {
        val now = uptimeMillis()
        return listOf("skill_installs", "file_moves", "update_requests").flatMap { field -> (data?.get(field) as? JsonArray).orEmpty() }.mapNotNull { item ->
            val card = try {
                BridgeJson.decodeFromJsonElement(InteractionData.serializer(), item)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            when {
                card.state != "refused" && card.id.isNotEmpty() -> Interaction.of(card, now, nowMillis(), fresh = true)
                card.kind == Interaction.MOVE_FILES -> Interaction.refusedMove(card, keys.incrementAndGet(), now)
                card.kind == Interaction.APPLY_UPDATE -> Interaction.refusedUpdate(card, keys.incrementAndGet(), now)
                else -> Interaction.refusedInstall(card, keys.incrementAndGet(), now)
            }
        }
    }

    /** The run ended: what was still waiting for it is closed (an answer on its way keeps its own result). */
    private fun closed(interactions: List<Interaction>): List<Interaction> {
        val now = uptimeMillis()
        return interactions.map {
            when {
                it.status != Interaction.Status.WAITING || it.crossRun -> it
                // Its time was up, give or take a moment: the computer counted the silence as "no".
                now >= it.deadline - EXPIRY_SLACK_MILLIS -> it.copy(status = Interaction.Status.EXPIRED)
                else -> it.copy(status = Interaction.Status.GONE)
            }
        }
    }

    /** Coach: the conversation was tidied up before the message that is awaited; a new session continues. */
    private fun renewed(state: ChatState, result: CoachCompacted): ChatState = state.copy(
        sessionId = result.sessionId.takeIf(::validId) ?: state.sessionId,
        tidying = false,
        olderCursor = null,
        renewedAt = awaiting?.second ?: state.renewedAt,
        tidied = result,
    )

    /** Coach (#157): a message that was put in line says in its answer when the conversation was tidied up first. */
    private fun renewedBy(generation: Int, body: JsonObject?) {
        if (!coach) return
        val result = try {
            BridgeJson.decodeFromJsonElement(CoachCompacted.serializer(), body?.get("compacted") as? JsonObject ?: return)
        } catch (_: Exception) {
            return
        }
        change(generation) { renewed(it, result) }
    }

    /**
     * True when the event ends the answer. [fresh]: it happens now (not a replay of a followed
     * run). [own]: the answer to a message this app sent and is receiving itself; only such an
     * answer switches to another conversation by itself (#168).
     */
    private fun onEvent(generation: Int, event: AnswerEvent.Stream, prompt: String?, answer: Long, streamed: StringBuilder, fresh: Boolean = true, own: Boolean = true): Boolean {
        val data = event.data as? JsonObject
        when (event.event) {
            "session" -> {
                val id = data.text("session_id")?.takeIf(::validId) ?: return false
                // From here on the conversation can be found again, whatever happens to this answer.
                change(generation) { state ->
                    state.folder?.let { store.save(it.pointer(id)) }
                    // Coach: that it was tidied up first is said by `compacted`, not guessed from the id.
                    // Earlier messages of another session are not part of this one in any case.
                    val other = coach && state.sessionId != null && state.sessionId != id
                    state.copy(sessionId = id, startingId = null, tidying = false, olderCursor = if (other) null else state.olderCursor)
                }
                named = true
            }
            // Coach (#156): the conversation was tidied up before this message; a new session continues.
            "compacted" -> {
                if (!coach) return false
                val result = try {
                    BridgeJson.decodeFromJsonElement(CoachCompacted.serializer(), event.data)
                } catch (_: Exception) {
                    CoachCompacted()
                }
                change(generation) { renewed(it, result) }
            }
            "delta" -> {
                streamed.append(data.text("text").orEmpty())
                val text = streaming(streamed)
                if (text.isNotBlank()) {
                    change(generation) { it.copy(messages = put(it.messages, ChatMessage(answer, false, text)), phase = ChatPhase.ANSWERING) }
                }
            }
            "interaction" -> {
                val asked = try {
                    BridgeJson.decodeFromJsonElement(InteractionData.serializer(), event.data)
                } catch (_: Exception) {
                    return false
                }
                // Just created: the whole waiting time lies ahead (unless it may be a replay of a followed run).
                val interaction = Interaction.of(asked, uptimeMillis(), nowMillis(), fresh = fresh) ?: return false
                change(generation) { state ->
                    if (state.interactions.any { it.id == interaction.id }) state else state.copy(interactions = state.interactions + interaction)
                }
            }
            // A sign of life from a run that says nothing for a while: the stream is alive (#148).
            // Coach (#156): before the message's own run has begun, it is the tidying up that takes this long.
            "keepalive" -> if (coach && !named) change(generation) { if (it.phase == ChatPhase.SENDING) it.copy(tidying = true) else it }
            // The computer no longer waits for this card: answered (from any device), out of time, or the run ended.
            "interaction_closed" -> {
                val id = data.text("id") ?: return false
                val late = data.text("reason") == "timed_out"
                mark(generation, id) {
                    // An answer that is on its way from here, or already settled, keeps its own result.
                    if (it.status != Interaction.Status.WAITING) it else it.copy(status = if (late) Interaction.Status.EXPIRED else Interaction.Status.GONE)
                }
            }
            "done" -> {
                awaiting = null
                // The finished answer replaces what was streamed: the computer has tidied it.
                val text = data.text("answer")?.takeIf { it.isNotBlank() } ?: visible(streamed)
                val id = data.text("session_id")?.takeIf(::validId)
                val files = files(data, id ?: mutable.value.sessionId)
                val proposed = proposals(data)
                val servers = (data?.get("mcp_servers") as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                // Another conversation to go to (#164): only the answer of a run that happens now
                // carries one. Received here, "open" and "new" are done at once; "choose", or any
                // switch of an answer that was followed, is a card for the owner.
                val switch = if (fresh) ChatSwitch.of(data?.get("switch")) else null
                val direct = switch?.takeIf { own && it.action != ChatSwitch.CHOOSE }
                change(generation) { state ->
                    val messages = if (text.isBlank() && files.isEmpty()) state.messages else put(state.messages, ChatMessage(answer, false, text, files = files, servers = servers))
                    if (id != null && id != state.sessionId) state.folder?.let { store.save(it.pointer(id)) }
                    state.copy(
                        messages = messages,
                        phase = ChatPhase.IDLE,
                        sessionId = id ?: state.sessionId,
                        startingId = if (id != null) null else state.startingId,
                        loaded = true,
                        stopping = false,
                        tidying = false,
                        interactions = closed(state.interactions).let { cards -> cards + proposed.filter { new -> cards.none { it.id == new.id } } },
                        switchTo = direct,
                        switchAt = if (direct != null) uptimeMillis() else state.switchAt,
                        choices = switch.takeIf { direct == null },
                    )
                }
                return true
            }
            "error" -> {
                awaiting = null
                val problem = problemOf(data.text("error"))
                change(generation) { state ->
                    val messages = state.messages.map { if (it.key == answer) it.copy(incomplete = true) else it }
                    // The owner stopped it from here: that is what was asked for, not a failure to retry.
                    val failure = if (problem == ChatProblem.STOPPED && state.stopping) ChatFailure(ChatProblem.STOPPED_HERE) else ChatFailure(problem, prompt, detail = detailOf(problem, data.text("message")))
                    // The run had started: the message is part of the conversation, a retry is a new message.
                    state.copy(messages = messages, phase = ChatPhase.IDLE, stopping = false, tidying = false, failure = failure, interactions = closed(state.interactions))
                }
                return true
            }
        }
        return false
    }

    /** The whole answer in one piece: the computer did not start a run (refused, or put the message in line). */
    private suspend fun onRefusal(generation: Int, done: AnswerEvent.Done, prompt: String, key: Long) {
        val response = done.response
        val body = response.body as? JsonObject
        val queueId = body.text("queue_id")?.takeIf(::validId)
        if (response.status == 202 && queueId != null) {
            // A run is going on there (started elsewhere): this message waits in line behind it.
            renewedBy(generation, body)
            awaiting = null
            change(generation) { state ->
                state.copy(
                    messages = state.messages.filter { it.key != key },
                    queue = state.queue + QueuedMessage(key, queueId, prompt),
                    phase = ChatPhase.ELSEWHERE,
                    tidying = false,
                )
            }
            val id = mutable.value.sessionId ?: return
            return read(generation, id, wholeThread = false)
        }
        val repeated = body.text("session_id")?.takeIf { response.ok && (body?.get("repeat") as? JsonPrimitive)?.content == "true" && validId(it) }
        if (repeated != null) {
            // The same first message again (#148): nothing was run a second time. The computer
            // has the conversation under the id the phone chose; its run, or its end, is read from there.
            repeatedId = repeated
            change(generation) { state ->
                state.folder?.let { store.save(it.pointer(repeated)) }
                state.copy(sessionId = repeated, startingId = null, phase = ChatPhase.ELSEWHERE)
            }
            return read(generation, repeated, wholeThread = false)
        }
        if (response.ok) return lost(generation)
        awaiting = null
        val code = body.text("error") ?: response.error
        val problem = problemOf(code, response.status)
        // A refused first message leaves its id used up on the computer: the next try gets a new one.
        if (mutable.value.sessionId == null && !coach) {
            startingPrompt = null
            store.save(null)
        }
        // Nothing ran: "Retry" sends the same message in place of this one.
        change(generation) {
            it.copy(phase = ChatPhase.IDLE, startingId = null, tidying = false, failure = ChatFailure(problem, prompt, replaces = key, status = response.status, detail = detailOf(problem, body.text("message"))))
        }
    }

    /** Nothing left the phone: the text goes back to the composer, to be sent by the owner later. */
    private fun notSent(generation: Int, prompt: String, key: Long, problem: ChatProblem) {
        awaiting = null
        // A first message: there is no conversation to find again (its id is kept for the next try).
        if (mutable.value.let { it.generation == generation && it.sessionId == null }) store.save(null)
        change(generation) { state ->
            state.copy(messages = state.messages.filter { it.key != key }, phase = ChatPhase.IDLE, tidying = false, draft = prompt, failure = ChatFailure(problem))
        }
    }

    /** The answer stopped arriving. The run, if there is one, goes on: ask the computer. */
    private suspend fun lost(generation: Int) {
        val state = mutable.value
        if (state.generation != generation) return
        // A first message whose session the computer never named is looked for under the id the phone chose.
        val id = state.sessionId ?: state.startingId
        if (id == null) {
            awaiting = null
            return change(generation) { it.copy(phase = ChatPhase.IDLE, failure = ChatFailure(ChatProblem.NO_ANSWER)) }
        }
        change(generation) { unheard(it).copy(tidying = false) }
        read(generation, id, wholeThread = false)
    }

    private inline fun change(generation: Int, transform: (ChatState) -> ChatState) =
        mutable.update { if (it.generation == generation) transform(it) else it }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: ApiException) {
        Result.failure(error)
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,80}")

        /** The computer keeps at most this many waiting messages per session. */
        const val MAX_QUEUED = 10

        /** The computer's word for "the coach conversation is being tidied up: that run is not stopped". */
        const val COMPACTION_RUNNING = "compaction_running"

        /** The relay's last word for a stream it let go because nobody read it. */
        const val STREAM_ABANDONED = "stream_abandoned"

        /** The hold a stop puts on the queue; it ends with the owner's next message. */
        const val HOLD_CANCEL = "cancel"

        /** How long the answer may take to say that it was stopped before the app asks instead. */
        private const val STOP_WAIT_MILLIS = 12_000L

        /** How long after "Send them" or "Send now" the app first looks whether the line moves. */
        private const val RESUME_LOOK_MILLIS = 1_500L

        /** How long the app waits before it asks again after a followed answer stopped arriving. */
        private const val FOLLOW_AGAIN_MILLIS = 2_000L

        /** How long a switch of an answer is done by itself; later the owner picks it on a card (#168). */
        const val SWITCH_FRESH_MILLIS = 60_000L
        /**
         * The marker lines the computer takes out of a finished answer: file offers, skill
         * installs (#97), switches to another conversation (#164) and moves of a blank chat's
         * files (#163). The history keeps the move lines, so they are hidden there too. A request
         * to apply an update (#180) is a line without a value: [UPDATE_LINE].
         */
        private val MARKERS = listOf("file", "skill-install", "switch-new", "switch", "move-files")
        private const val UPDATE_LINE = "[[apply-update]]"
        private val FILE_LINE = Regex("(?m)^[ \\t]*\\[\\[(?:(?:${MARKERS.joinToString("|")}):[^\\n]*|apply-update)]][ \\t]*\\n?")
        private val FILE_PATH = Regex("(?m)^[ \\t]*\\[\\[file:([^\\n]*?)]][ \\t]*$")

        /** Ids are put into request paths: only the characters a session id can have. */
        fun validId(id: String): Boolean = ID.matches(id)

        /** The text without the marker lines the computer turns into cards and switches at the end. */
        fun visible(streamed: CharSequence): String = FILE_LINE.replace(streamed, "").trimEnd()

        /**
         * What is shown of an answer while it is written: [visible], and a last line that is
         * becoming a marker line (`[[swi…`) is held back until it is complete, so it never flashes.
         */
        fun streaming(streamed: CharSequence): String {
            val shown = FILE_LINE.replace(streamed, "")
            val start = shown.lastIndexOf('\n') + 1
            val tail = shown.substring(start).trimStart(' ', '\t')
            val unfinished = tail.isNotEmpty() &&
                (MARKERS.any { name -> "[[$name:".let { head -> tail.startsWith(head) || head.startsWith(tail) } } || UPDATE_LINE.startsWith(tail))
            return (if (unfinished) shown.substring(0, start) else shown).trimEnd()
        }

        /** The computer's own reason, for the problems whose notice shows it. */
        internal fun detailOf(problem: ChatProblem, message: String?): String? =
            message?.takeIf { problem == ChatProblem.CHAT_ROOT_UNUSABLE }?.substringAfter(": ")?.trim()?.takeIf { it.isNotEmpty() }

        /** The names of the files a text offers with its marker lines (the last part of each path). */
        fun offeredNames(text: String): List<String> = FILE_PATH.findAll(text).mapNotNull { match ->
            match.groupValues[1].trim().trim('`', '\'', '"').trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() }
        }.toList()

        /** An offer is valid for 15 minutes on the computer; the card stops a little earlier. */
        const val OFFER_MILLIS = 14 * 60_000L

        /** The whole time the computer keeps an offer. */
        private const val OFFER_FULL_MILLIS = 15 * 60_000L

        private fun put(messages: List<ChatMessage>, message: ChatMessage): List<ChatMessage> =
            if (messages.any { it.key == message.key }) messages.map { if (it.key == message.key) message else it } else messages + message

        /**
         * Is [stored] (a message as the computer's conversation has it) the message [sent]? The
         * computer stores what was sent, but may cut a long text and leaves out parts it takes
         * for its own markers, so a text that contains the other counts as the same. In doubt
         * the answer is yes: a wrong "not there" would invite the owner to send a message twice.
         */
        internal fun same(stored: String, sent: String): Boolean {
            val a = stored.trim().replace(Regex("\\s+"), " ")
            val b = sent.trim().replace(Regex("\\s+"), " ")
            return a == b || (b.isNotEmpty() && a.contains(b)) || (a.length >= PART && b.contains(a))
        }

        /** A stored text this long that is part of the sent one is the sent one, cut or trimmed. */
        private const val PART = 20

        /** A waiting item the computer still lists stays answerable at least until the app looks again. */
        private const val STILL_WAITING_MILLIS = 15_000L

        /** How close to its end a waiting item must be for the end of the run to mean "timed out". */
        private const val EXPIRY_SLACK_MILLIS = 3_000L

        private fun JsonObject?.text(name: String): String? = (this?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** The bridge's error word (docs/bridge.md) as what the screen says. The word decides, not the status. */
        fun problemOf(code: String?, status: Int = 0): ChatProblem = when (code) {
            "session_in_use" -> ChatProblem.IN_USE
            "busy", "queue_full" -> ChatProblem.BUSY
            "update_in_progress" -> ChatProblem.UPDATING
            "folder_missing", "folder_not_allowed" -> ChatProblem.FOLDER_GONE
            "session_not_found" -> ChatProblem.SESSION_GONE
            UiStates.FOLDER_IN_REPOSITORY -> ChatProblem.FOLDER_IN_REPOSITORY
            "capable_mode_unavailable" -> ChatProblem.CAPABLE_OFF
            "claude_not_found", "working_directory_missing", "not_configured" -> ChatProblem.NOT_SET_UP
            "timeout" -> ChatProblem.TOO_LONG
            "cancelled" -> ChatProblem.STOPPED
            COMPACTION_RUNNING -> ChatProblem.COMPACTING
            "prompt_too_large", "too_large" -> ChatProblem.TOO_LARGE
            "command_not_allowed" -> ChatProblem.COMMAND_UNKNOWN
            "skills_unavailable" -> ChatProblem.SKILLS_OFF
            "mcp_unavailable" -> ChatProblem.MCP_OFF
            "audit_unavailable" -> ChatProblem.UNRECORDED
            "chat_root_unusable" -> ChatProblem.CHAT_ROOT_UNUSABLE
            else -> if (status == 503) ChatProblem.NOT_SET_UP else ChatProblem.FAILED
        }
    }
}
