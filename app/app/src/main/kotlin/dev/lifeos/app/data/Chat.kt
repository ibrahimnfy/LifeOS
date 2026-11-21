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
data class ChatPointer(val sessionId: String, val folderPath: String = "", val folderLabel: String = "", val memory: Boolean = false)

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

    /** Anything else the computer answered with. */
    FAILED,
}

/**
 * Why the last message has no answer. [prompt] is what "Retry" sends again (null: retrying makes
 * no sense); [replaces] is the bubble a retry replaces (a message the computer never took).
 */
data class ChatFailure(val problem: ChatProblem, val prompt: String? = null, val replaces: Long? = null, val status: Int = 0)

/**
 * A message in the session's queue on the computer. [key] is the phone's own handle for the
 * row; [id] is the computer's (empty while the message is on its way there: [busy]).
 */
data class QueuedMessage(val key: Long, val id: String, val prompt: String, val busy: Boolean = false)

/** Where the conversation runs, as the header shows it. */
data class ChatFolder(val path: String, val label: String, val memory: Boolean)

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
) {
    val canSend: Boolean get() = phase == ChatPhase.IDLE && !loading && loadProblem == null && loaded && !compacting

    /** A message written now waits in line on the computer: an answer is awaited and the session is known. */
    val canQueue: Boolean get() = phase != ChatPhase.IDLE && sessionId != null && queue.size < Chat.MAX_QUEUED

    /** The computer has earlier messages than the ones shown. */
    val olderExist: Boolean get() = olderCursor != null

    /** A run can be stopped once the computer has named its session. */
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

    @Volatile
    private var resume = false

    /** The id the computer called a repeat of an earlier first message (see [read]). */
    @Volatile
    private var repeatedId: String? = null

    /** The first message [ChatState.startingId] was sent with: the same text again is the same start. */
    @Volatile
    private var startingPrompt: String? = null

    /** Coach: the thread was already found without the awaited message once (see [read]). */
    @Volatile
    private var doubted = false

    val state: StateFlow<ChatState> = mutable

    // ------------------------------------------------------------------ which conversation

    /**
     * After a start or an unlock: continue the conversation that was open last. Its messages
     * are read when the screen asks ([load]).
     */
    fun restore() {
        if (coach) return
        val current = mutable.value
        if (current.sessionId != null || current.messages.isNotEmpty() || current.folder != null) return
        val pointer = store.load() ?: return
        mutable.value = ChatState(
            generation = current.generation + 1,
            sessionId = pointer.sessionId,
            folder = ChatFolder(pointer.folderPath, pointer.folderLabel, pointer.memory),
            loaded = false,
            choiceKnown = false,
        )
        resume = true
    }

    /** True once per app start when there is a conversation to continue: the Chat tab opens it directly. */
    fun takeResume(): Boolean {
        val wanted = resume && mutable.value.sessionId != null
        resume = false
        return wanted
    }

    /** "New chat": an empty conversation in [folder]. The previous one stays on the computer (Sessions). */
    fun newChat(folder: Folder) {
        if (coach) return
        stopWork()
        resume = false
        startingPrompt = null
        mutable.update { ChatState(generation = it.generation + 1, folder = ChatFolder(folder.path, folder.label, folder.isMemory)) }
        store.save(null)
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
        resume = false
        val folder = ChatFolder(session.folder, session.folderLabel, memory)
        mutable.update {
            ChatState(generation = it.generation + 1, sessionId = session.id, folder = folder, loaded = false, choice = ModelChoice(session.model, session.effort))
        }
        store.save(ChatPointer(session.id, folder.path, folder.label, folder.memory))
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
        resume = false
        mutable.update { blank(it.generation + 1) }
        store.save(null)
    }

    private fun stopWork() {
        run?.cancel()
        reading?.cancel()
        run = null
        reading = null
        awaiting = null
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
        // A run the app does not hear may be waiting for the owner's yes or no.
        if (busy == true) pending(generation, id)
        if (!wholeThread) {
            if (busy == null) return change(generation) { it.copy(unreachable = true) }
            if (busy) return change(generation) { it.copy(unreachable = false) }
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

    /** What the computer says is waiting: new items are shown, items it no longer lists are closed. */
    private suspend fun pending(generation: Int, id: String) {
        val list = attempt { api.interactions(id) }.getOrNull() ?: return
        val now = uptimeMillis()
        val wall = nowMillis()
        change(generation) { state ->
            val listed = list.map { it.id }.toSet()
            val known = state.interactions.map { it.id }.toSet()
            val kept = state.interactions.map {
                when {
                    it.status != Interaction.Status.WAITING -> it
                    it.id !in listed -> it.copy(status = Interaction.Status.GONE)
                    // The computer still waits, whatever the two clocks say: answerable until the next look.
                    else -> it.copy(deadline = maxOf(it.deadline, now + STILL_WAITING_MILLIS))
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
        }
    }

    private fun bubbles(page: HistoryPage): List<ChatMessage> = buildList {
        for (exchange in page.exchanges) {
            if (exchange.user.isNotBlank()) add(ChatMessage(keys.incrementAndGet(), true, exchange.user))
            // The conversation on the computer still has the lines that offered files; the offers themselves are gone.
            val text = visible(exchange.assistant)
            val files = offeredNames(exchange.assistant).map { OfferedFile(it) }
            if (text.isNotBlank() || files.isNotEmpty()) add(ChatMessage(keys.incrementAndGet(), false, text, incomplete = !exchange.complete, files = files))
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
        return state.copy(queue = rows + onTheWay, queueHold = listed.paused.takeIf { rows.isNotEmpty() })
    }

    private fun since(state: ChatState): Long = if (state.phase == ChatPhase.IDLE || state.sinceMillis == 0L) nowMillis() else state.sinceMillis

    // ------------------------------------------------------------------ sending

    /**
     * Send [prompt] in this conversation; while an answer is awaited it is put in line on the
     * computer instead and sent when that run has ended. False if nothing was started.
     */
    fun send(prompt: String): Boolean {
        val text = prompt.trim()
        val current = mutable.value
        if (text.isEmpty()) return false
        if (!current.canSend) return current.canQueue && enqueue(current.generation, text, now = false, replaces = null)
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
            current.folder?.let { store.save(ChatPointer(starting, it.path, it.label, it.memory)) }
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
                interactions = emptyList(),
                tidying = coach && it.coach?.dueForCompaction == true,
                // As on the computer: the hold a stop put on the queue ends with the owner's next message.
                queueHold = it.queueHold.takeIf { hold -> hold != HOLD_CANCEL },
            )
        }
        doubted = false
        run = scope.launch { exchange(generation, text, key, Sending.DIRECT, replaces = null) }
        return true
    }

    // ------------------------------------------------------------------ while a run is in progress

    /**
     * Put [text] in line: one request, answered at once with the message's place in the queue.
     * [now] ("send now"): it goes to the front and the current run is interrupted for it.
     * [replaces]: the queued row this is the same message as (its old place is given up).
     */
    private fun enqueue(generation: Int, text: String, now: Boolean, replaces: QueuedMessage?): Boolean {
        val key = replaces?.key ?: keys.incrementAndGet()
        mutable.update { state ->
            if (state.generation != generation) return false
            val row = QueuedMessage(key, replaces?.id.orEmpty(), text, busy = true)
            val queue = if (replaces == null) state.queue + row else state.queue.map { if (it.key == key) row else it }
            state.copy(queue = queue, draft = null, failure = null, stopping = state.stopping || now)
        }
        launchSide { exchange(generation, text, key, if (now) Sending.NOW else Sending.QUEUED, replaces) }
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
     * "Send now": interrupt the current run and send this waiting message at once. What the run
     * has already changed stays (the screen says so before this is called).
     */
    fun sendNow(key: Long) {
        val state = mutable.value
        val row = state.queue.firstOrNull { it.key == key && !it.busy && it.id.isNotEmpty() } ?: return
        if (state.sessionId == null) return
        enqueue(state.generation, row.prompt, now = true, replaces = row)
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
            val result = attempt { api.cancelRun(id) }.getOrNull()
            if (result == null) return@launchSide change(generation) { it.copy(stopping = false, failure = ChatFailure(ChatProblem.STOP_FAILED)) }
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
            change(generation) { it.copy(phase = ChatPhase.ELSEWHERE) }
        }
        read(generation, id, wholeThread = false)
    }

    /** Let a queue that is on hold go on: the computer sends the waiting messages one after the other. */
    fun resumeQueue() {
        val state = mutable.value
        val id = state.sessionId ?: return
        if (state.queueHold == null || state.queue.isEmpty() || state.phase != ChatPhase.IDLE) return
        val generation = state.generation
        change(generation) { it.copy(phase = ChatPhase.ELSEWHERE, sinceMillis = nowMillis(), queueHold = null, failure = null, interactions = emptyList()) }
        // The answer to this comes only when the first waiting message is done: not waited for.
        launchSide { attempt { api.resumeQueue(id) } }
        launchSide {
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

    /** How a message goes out: straight away, into the line behind a run, or to its front ("send now"). */
    private enum class Sending { DIRECT, QUEUED, NOW }

    /**
     * One message to the computer and what comes back: the answer as events, or one result
     * that says the message was refused or put in line. A message meant for the line can still
     * get an answer at once (the run before it ended in the same moment): it then becomes the
     * conversation's current message ([started]).
     */
    private suspend fun exchange(generation: Int, prompt: String, key: Long, sending: Sending, replaces: QueuedMessage?) {
        val direct = sending == Sending.DIRECT
        if (direct && !reachable()) return notSent(generation, prompt, key, ChatProblem.NOT_REACHED)
        val before = mutable.value
        if (before.generation != generation) return
        val answer = keys.incrementAndGet()
        val streamed = StringBuilder()
        var started = direct
        if (direct) awaiting = prompt to key
        try {
            val folder = before.folder?.takeIf { !it.memory }?.path?.takeIf { it.isNotEmpty() }
            val options = if (sending == Sending.NOW) options(before) + ("when" to JsonPrimitive("now")) else options(before)
            val ended = api.chat(prompt, before.sessionId, folder, options, coach = coach).firstOrNull { event ->
                when (event) {
                    is AnswerEvent.Stream -> {
                        if (!started) {
                            started = true
                            startedAfterAll(generation, prompt, key, replaces)
                        }
                        onEvent(generation, event, prompt, answer, streamed)
                    }
                    is AnswerEvent.Done -> {
                        if (started) onRefusal(generation, event, prompt, key) else onQueueAnswer(generation, event, prompt, key, replaces)
                        true
                    }
                    else -> false
                }
            }
            // The stream ended without saying how the run ended.
            if (ended == null && started) lost(generation)
            if (started) afterRun(generation)
            if (started && coach) refreshCoach(generation)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException) {
            when {
                !started -> notQueued(generation, prompt, key, replaces, uncertain = error.kind == ApiException.Kind.UNCERTAIN)
                error.kind == ApiException.Kind.NOT_SENT -> notSent(generation, prompt, key, ChatProblem.NOT_REACHED)
                error.kind == ApiException.Kind.NOT_PAIRED || error.kind == ApiException.Kind.REFUSED -> notSent(generation, prompt, key, ChatProblem.NOT_PAIRED)
                else -> lost(generation)
            }
        }
    }

    /** A message meant for the line is answered right away: it is the current message now. */
    private fun startedAfterAll(generation: Int, prompt: String, key: Long, replaces: QueuedMessage?) {
        awaiting = prompt to key
        change(generation) { state ->
            state.copy(
                queue = state.queue.filter { it.key != key },
                messages = state.messages + ChatMessage(key, true, prompt),
                phase = ChatPhase.SENDING,
                sinceMillis = nowMillis(),
                stopping = false,
                unreachable = false,
                interactions = emptyList(),
            )
        }
        // Its old place in the line is given up (it may be gone already).
        if (replaces != null) giveUp(generation, replaces)
    }

    /** The one result for a message meant for the line: its place there, or a refusal. */
    private suspend fun onQueueAnswer(generation: Int, done: AnswerEvent.Done, prompt: String, key: Long, replaces: QueuedMessage?) {
        val body = done.response.body as? JsonObject
        val queueId = body.text("queue_id")?.takeIf(::validId)
        if (done.response.status != 202 || queueId == null) {
            return if (done.response.ok) notQueued(generation, prompt, key, replaces, uncertain = true) else refusedForQueue(generation, done.response, prompt, key, replaces)
        }
        val position = (body?.get("position") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        change(generation) { state ->
            // A look at the computer's list may have shown the new entry already.
            val others = state.queue.filter { it.key != key && it.id != queueId }
            val row = QueuedMessage(key, queueId, prompt)
            // Position 1 is the front; without a usable position it stands where the row stood.
            val at = if (position in 1..others.size + 1) position - 1 else state.queue.indexOfFirst { it.key == key }.coerceIn(0, others.size)
            state.copy(
                queue = others.take(at) + row + others.drop(at),
                queueHold = null,
                // "Send now" while the answer is not received here: no end of an answer will say "stopped".
                stopping = state.stopping && state.phase != ChatPhase.ELSEWHERE,
            )
        }
        if (replaces != null) giveUp(generation, replaces)
    }

    /** Take a message's old entry out of the computer's queue; if that fails it shows up in the list again. */
    private fun giveUp(generation: Int, old: QueuedMessage) {
        val id = mutable.value.sessionId ?: return
        if (old.id.isEmpty()) return
        launchSide {
            attempt { api.removeQueued(id, old.id) }
            attempt { api.runState(id) }.getOrNull()?.let { listed -> change(generation) { queueFrom(it, listed) } }
        }
    }

    private fun refusedForQueue(generation: Int, response: dev.lifeos.app.relay.RelayResponse, prompt: String, key: Long, replaces: QueuedMessage?) {
        val code = (response.body as? JsonObject).text("error") ?: response.error
        change(generation) { state ->
            if (replaces != null) {
                // "Send now" was refused: the message keeps its place in the line.
                state.copy(queue = state.queue.map { if (it.key == key) replaces else it }, stopping = false, failure = ChatFailure(problemOf(code, response.status)))
            } else {
                state.copy(queue = state.queue.filter { it.key != key }, draft = prompt, failure = ChatFailure(problemOf(code, response.status)))
            }
        }
    }

    /**
     * A message for the line got no (usable) answer. [uncertain]: it may have arrived, so the
     * computer's list decides; only when it is not there does the text return to the composer.
     */
    private suspend fun notQueued(generation: Int, prompt: String, key: Long, replaces: QueuedMessage?, uncertain: Boolean) {
        val id = mutable.value.sessionId
        val listed = if (uncertain && id != null) attempt { api.runState(id) }.getOrNull() else null
        change(generation) { state ->
            if (replaces != null) {
                // "Send now" did not get through: the message keeps its place in the line.
                val kept = state.copy(queue = state.queue.map { if (it.key == key) replaces else it }, stopping = false, failure = ChatFailure(ChatProblem.QUEUE_FAILED))
                return@change if (listed != null) queueFrom(kept, listed) else kept
            }
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

    /** What the session list says about this session's model, for a conversation restored after a start. */
    fun noteChoice(session: SessionSummary) = mutable.update {
        if (it.sessionId == session.id && !it.choiceKnown) it.copy(choice = ModelChoice(session.model, session.effort), choiceKnown = true) else it
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
        val generation = state.generation
        mark(generation, id) { it.copy(status = Interaction.Status.SENDING, sendFailed = false) }
        scope.launch {
            val outcome = try {
                api.answerInteraction(session, id, body)
                result
            } catch (error: ApiException) {
                // "Not pending": answered, expired or cancelled meanwhile. Anything else: not delivered.
                if (error.kind == ApiException.Kind.HTTP && error.status == 404) Interaction.Status.GONE else null
            }
            mark(generation, id) {
                if (outcome != null) it.copy(status = outcome, sendFailed = false) else it.copy(status = Interaction.Status.WAITING, sendFailed = true)
            }
        }
    }

    private fun mark(generation: Int, id: String, transform: (Interaction) -> Interaction) =
        change(generation) { state -> state.copy(interactions = state.interactions.map { if (it.id == id) transform(it) else it }) }

    /** The run ended: what was still waiting is closed (an answer on its way keeps its own result). */
    private fun closed(interactions: List<Interaction>): List<Interaction> {
        val now = uptimeMillis()
        return interactions.map {
            when {
                it.status != Interaction.Status.WAITING -> it
                // Its time was up, give or take a moment: the computer counted the silence as "no".
                now >= it.deadline - EXPIRY_SLACK_MILLIS -> it.copy(status = Interaction.Status.EXPIRED)
                else -> it.copy(status = Interaction.Status.GONE)
            }
        }
    }

    /** True when the event ends the answer. */
    private fun onEvent(generation: Int, event: AnswerEvent.Stream, prompt: String, answer: Long, streamed: StringBuilder): Boolean {
        val data = event.data as? JsonObject
        when (event.event) {
            "session" -> {
                val id = data.text("session_id")?.takeIf(::validId) ?: return false
                // From here on the conversation can be found again, whatever happens to this answer.
                change(generation) { state ->
                    state.folder?.let { store.save(ChatPointer(id, it.path, it.label, it.memory)) }
                    // Coach: another session than before means that the conversation was tidied up first.
                    val renewed = coach && state.sessionId != null && state.sessionId != id
                    state.copy(
                        sessionId = id,
                        startingId = null,
                        tidying = false,
                        olderCursor = if (renewed) null else state.olderCursor,
                        renewedAt = if (renewed) awaiting?.second ?: state.renewedAt else state.renewedAt,
                    )
                }
            }
            "delta" -> {
                streamed.append(data.text("text").orEmpty())
                val text = visible(streamed)
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
                // Just created: the whole waiting time lies ahead.
                val interaction = Interaction.of(asked, uptimeMillis(), nowMillis(), fresh = true) ?: return false
                change(generation) { state ->
                    if (state.interactions.any { it.id == interaction.id }) state else state.copy(interactions = state.interactions + interaction)
                }
            }
            // A sign of life from a run that says nothing for a while: the stream is alive (#148).
            "keepalive" -> Unit
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
                change(generation) { state ->
                    val messages = if (text.isBlank() && files.isEmpty()) state.messages else put(state.messages, ChatMessage(answer, false, text, files = files))
                    if (id != null && id != state.sessionId) state.folder?.let { store.save(ChatPointer(id, it.path, it.label, it.memory)) }
                    state.copy(
                        messages = messages,
                        phase = ChatPhase.IDLE,
                        sessionId = id ?: state.sessionId,
                        startingId = if (id != null) null else state.startingId,
                        loaded = true,
                        stopping = false,
                        tidying = false,
                        interactions = closed(state.interactions),
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
                    val failure = if (problem == ChatProblem.STOPPED && state.stopping) ChatFailure(ChatProblem.STOPPED_HERE) else ChatFailure(problem, prompt)
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
            awaiting = null
            change(generation) { state ->
                state.copy(
                    messages = state.messages.filter { it.key != key },
                    queue = state.queue + QueuedMessage(key, queueId, prompt),
                    phase = ChatPhase.ELSEWHERE,
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
                state.folder?.let { store.save(ChatPointer(repeated, it.path, it.label, it.memory)) }
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
            it.copy(phase = ChatPhase.IDLE, startingId = null, failure = ChatFailure(problem, prompt, replaces = key, status = response.status))
        }
    }

    /** Nothing left the phone: the text goes back to the composer, to be sent by the owner later. */
    private fun notSent(generation: Int, prompt: String, key: Long, problem: ChatProblem) {
        awaiting = null
        change(generation) { state ->
            state.copy(messages = state.messages.filter { it.key != key }, phase = ChatPhase.IDLE, draft = prompt, failure = ChatFailure(problem))
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
        change(generation) { it.copy(phase = ChatPhase.ELSEWHERE, tidying = false) }
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

        /** The hold a stop puts on the queue; it ends with the owner's next message. */
        const val HOLD_CANCEL = "cancel"

        /** How long the answer may take to say that it was stopped before the app asks instead. */
        private const val STOP_WAIT_MILLIS = 12_000L

        /** How long after "Send them" the app first looks whether the line moves. */
        private const val RESUME_LOOK_MILLIS = 3_000L
        private val FILE_LINE = Regex("(?m)^[ \\t]*\\[\\[file:[^\\n]*]][ \\t]*\\n?")
        private val FILE_PATH = Regex("(?m)^[ \\t]*\\[\\[file:([^\\n]*?)]][ \\t]*$")

        /** Ids are put into request paths: only the characters a session id can have. */
        fun validId(id: String): Boolean = ID.matches(id)

        /** The streamed text without the marker lines the computer turns into file cards at the end. */
        fun visible(streamed: CharSequence): String = FILE_LINE.replace(streamed, "").trimEnd()

        /** The names of the files a text offers with its marker lines (the last part of each path). */
        fun offeredNames(text: String): List<String> = FILE_PATH.findAll(text).mapNotNull { match ->
            match.groupValues[1].trim().trim('`', '\'', '"').trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() }
        }.toList()

        /** An offer is valid for 15 minutes on the computer; the card stops a little earlier. */
        const val OFFER_MILLIS = 14 * 60_000L

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
            "folder_missing", "folder_not_allowed" -> ChatProblem.FOLDER_GONE
            "session_not_found" -> ChatProblem.SESSION_GONE
            UiStates.FOLDER_IN_REPOSITORY -> ChatProblem.FOLDER_IN_REPOSITORY
            "capable_mode_unavailable" -> ChatProblem.CAPABLE_OFF
            "claude_not_found", "working_directory_missing", "not_configured" -> ChatProblem.NOT_SET_UP
            "timeout" -> ChatProblem.TOO_LONG
            "cancelled" -> ChatProblem.STOPPED
            "prompt_too_large", "too_large" -> ChatProblem.TOO_LARGE
            "command_not_allowed" -> ChatProblem.COMMAND_UNKNOWN
            "skills_unavailable" -> ChatProblem.SKILLS_OFF
            else -> if (status == 503) ChatProblem.NOT_SET_UP else ChatProblem.FAILED
        }
    }
}
