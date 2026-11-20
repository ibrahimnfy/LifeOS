package dev.lifeos.app.data

import dev.lifeos.app.relay.AnswerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** One bubble. [incomplete]: the answer was cut off (a failed or stopped run), or is still being written elsewhere. */
data class ChatMessage(val key: Long, val fromOwner: Boolean, val text: String, val incomplete: Boolean = false)

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

    /** The message did not arrive on the computer (it is not in the conversation there). */
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

    /** The message is longer than the computer accepts. */
    TOO_LARGE,

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

/** Where the conversation runs, as the header shows it. */
data class ChatFolder(val path: String, val label: String, val memory: Boolean)

data class ChatState(
    /** Changes whenever another conversation is shown; work for an earlier one is dropped. */
    val generation: Int = 0,
    val sessionId: String? = null,
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
    /** The computer has earlier messages than the ones shown. */
    val olderExist: Boolean = false,
    /** The last message waits on the computer behind a run that is still going. */
    val queued: Boolean = false,
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
) {
    val canSend: Boolean get() = phase == ChatPhase.IDLE && !loading && loadProblem == null && loaded
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
 * The public functions are called from the main thread.
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
) {
    private val mutable = MutableStateFlow(ChatState())
    private val keys = AtomicLong()
    private var run: Job? = null
    private var reading: Job? = null

    /**
     * The message whose answer has not been seen to its end (text and bubble key); never kept
     * across a lock. If the answer is lost, the conversation on the computer tells whether the
     * message is part of it.
     */
    @Volatile
    private var awaiting: Pair<String, Long>? = null

    @Volatile
    private var resume = false

    val state: StateFlow<ChatState> = mutable

    // ------------------------------------------------------------------ which conversation

    /**
     * After a start or an unlock: continue the conversation that was open last. Its messages
     * are read when the screen asks ([load]).
     */
    fun restore() {
        val current = mutable.value
        if (current.sessionId != null || current.messages.isNotEmpty() || current.folder != null) return
        val pointer = store.load() ?: return
        mutable.value = ChatState(
            generation = current.generation + 1,
            sessionId = pointer.sessionId,
            folder = ChatFolder(pointer.folderPath, pointer.folderLabel, pointer.memory),
            loaded = false,
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
        stopWork()
        resume = false
        mutable.update { ChatState(generation = it.generation + 1, folder = ChatFolder(folder.path, folder.label, folder.isMemory)) }
        store.save(null)
    }

    /** Continue [session]: its last messages are read from the computer. [memory]: it runs in the memory folder. */
    fun open(session: SessionSummary, memory: Boolean = false) {
        if (!validId(session.id)) return
        val current = mutable.value
        if (current.sessionId == session.id && (current.loaded || current.loading)) return
        stopWork()
        resume = false
        val folder = ChatFolder(session.folder, session.folderLabel, memory)
        mutable.update { ChatState(generation = it.generation + 1, sessionId = session.id, folder = folder, loaded = false) }
        store.save(ChatPointer(session.id, folder.path, folder.label, folder.memory))
        load()
    }

    /** The app was locked: nothing of the conversation stays in memory; a run goes on on the computer. */
    fun onLocked() {
        val lostFirst = mutable.value.let { it.phase != ChatPhase.IDLE && it.sessionId == null }
        stopWork()
        mutable.update {
            ChatState(
                generation = it.generation + 1,
                sessionId = it.sessionId,
                folder = it.folder,
                loaded = it.sessionId == null,
                // A first message whose session is not known yet cannot be followed up.
                failure = if (lostFirst) ChatFailure(ChatProblem.NO_ANSWER) else null,
            )
        }
    }

    /** Unpaired: forget everything, also the file. */
    fun forget() {
        stopWork()
        resume = false
        mutable.update { ChatState(generation = it.generation + 1) }
        store.save(null)
    }

    private fun stopWork() {
        run?.cancel()
        reading?.cancel()
        run = null
        reading = null
        awaiting = null
    }

    // ------------------------------------------------------------------ reading

    /** Read the conversation's last messages, and whether the computer is working on it. */
    fun load() {
        val current = mutable.value
        val id = current.sessionId ?: return
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
        val id = current.sessionId ?: return
        if (current.phase != ChatPhase.ELSEWHERE || current.loading) return
        read(current.generation, id, wholeThread = false)
    }

    private suspend fun read(generation: Int, id: String, wholeThread: Boolean) {
        val run = attempt { api.runState(id) }
        val busy = run.getOrNull()?.busy
        // A run the app does not hear may be waiting for the owner's yes or no.
        if (busy == true) pending(generation, id)
        if (!wholeThread) {
            if (busy == null) return change(generation) { it.copy(unreachable = true) }
            if (busy) return change(generation) { it.copy(unreachable = false) }
        }
        val page = attempt { api.history(id) }
        val exchanges = page.getOrNull()
        val error = page.exceptionOrNull() as? ApiException
        val gone = error != null && error.kind == ApiException.Kind.HTTP && error.code == "session_not_found"
        // A sent message whose answer was never seen: the thread tells whether it arrived.
        val sent = awaiting
        change(generation) { state ->
            when {
                exchanges != null -> shown(state, exchanges, busy == true, sent)
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
        if (gone && busy != true) store.save(null)
        if ((exchanges != null || gone) && busy != true && awaiting === sent) awaiting = null
    }

    /** The thread as the computer has it; a sent message that is not in it did not arrive. */
    private fun shown(state: ChatState, page: HistoryPage, busy: Boolean, sent: Pair<String, Long>?): ChatState {
        val messages = buildList {
            for (exchange in page.exchanges) {
                if (exchange.user.isNotBlank()) add(ChatMessage(keys.incrementAndGet(), true, exchange.user))
                if (exchange.assistant.isNotBlank()) add(ChatMessage(keys.incrementAndGet(), false, exchange.assistant, incomplete = !exchange.complete))
            }
        }
        val base = state.copy(messages = messages, loading = false, loaded = true, loadProblem = null, olderExist = page.older != null, unreachable = false)
        if (busy) return base.copy(phase = ChatPhase.ELSEWHERE, sinceMillis = since(state))
        val arrived = sent == null || page.exchanges.lastOrNull()?.user?.let { same(it, sent.first) } == true
        // The run is over: whatever still waited was counted as "no" there.
        val ended = base.copy(phase = ChatPhase.IDLE, queued = false, interactions = closed(base.interactions))
        if (arrived) return ended
        val bubble = ChatMessage(sent.second, true, sent.first)
        return ended.copy(messages = messages + bubble, failure = ChatFailure(ChatProblem.NOT_DELIVERED, sent.first, replaces = sent.second))
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

    private fun since(state: ChatState): Long = if (state.phase == ChatPhase.IDLE || state.sinceMillis == 0L) nowMillis() else state.sinceMillis

    // ------------------------------------------------------------------ sending

    /** Send [prompt] in this conversation. False if nothing was started (empty text, or an answer is still awaited). */
    fun send(prompt: String): Boolean {
        val text = prompt.trim()
        val current = mutable.value
        if (text.isEmpty() || !current.canSend) return false
        val generation = current.generation
        val key = keys.incrementAndGet()
        mutable.update {
            if (it.generation != generation) return false
            it.copy(
                messages = it.messages + ChatMessage(key, true, text),
                phase = ChatPhase.SENDING,
                sinceMillis = nowMillis(),
                failure = null,
                draft = null,
                queued = false,
                unreachable = false,
                interactions = emptyList(),
            )
        }
        run = scope.launch { exchange(generation, text, key) }
        return true
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

    private suspend fun exchange(generation: Int, prompt: String, key: Long) {
        if (!reachable()) return notSent(generation, prompt, key, ChatProblem.NOT_REACHED)
        val before = mutable.value
        if (before.generation != generation) return
        val answer = keys.incrementAndGet()
        val streamed = StringBuilder()
        awaiting = prompt to key
        try {
            val folder = before.folder?.takeIf { !it.memory }?.path?.takeIf { it.isNotEmpty() }
            val ended = api.chat(prompt, before.sessionId, folder, options(before)).firstOrNull { event ->
                when (event) {
                    is AnswerEvent.Stream -> onEvent(generation, event, prompt, answer, streamed)
                    is AnswerEvent.Done -> {
                        onRefusal(generation, event, prompt, key)
                        true
                    }
                    else -> false
                }
            }
            // The stream ended without saying how the run ended.
            if (ended == null) lost(generation)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException) {
            when (error.kind) {
                ApiException.Kind.NOT_SENT -> notSent(generation, prompt, key, ChatProblem.NOT_REACHED)
                ApiException.Kind.NOT_PAIRED, ApiException.Kind.REFUSED -> notSent(generation, prompt, key, ChatProblem.NOT_PAIRED)
                else -> lost(generation)
            }
        }
    }

    /**
     * What the request carries besides the prompt. `interactive`: this app shows questions and
     * approvals, so the run may wait for the owner instead of being refused at once. `mode`:
     * only when the owner allowed changes for this conversation.
     */
    private fun options(state: ChatState): Map<String, JsonElement> = buildMap {
        put("interactive", JsonPrimitive(true))
        if (state.capable) put("mode", JsonPrimitive("capable"))
    }

    // ------------------------------------------------------------------ questions and approvals

    /** "May make changes" for this conversation, from the next message on. */
    fun setCapable(on: Boolean) = mutable.update { it.copy(capable = on) }

    /** Yes or no to an action that needs the owner's approval. Only an explicit tap leads here. */
    fun answerApproval(id: String, approve: Boolean) =
        answer(id, Interaction.approvalBody(approve), if (approve) Interaction.Status.ALLOWED else Interaction.Status.REFUSED)

    /** The owner's choices (or own words) per question; see [Interaction.answersBody]. */
    fun answerQuestion(id: String, answers: Map<String, List<String>>) {
        val interaction = mutable.value.interactions.firstOrNull { it.id == id } ?: return
        if (!Interaction.complete(interaction.questions, answers)) return
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
                    state.copy(sessionId = id)
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
            "done" -> {
                awaiting = null
                // The finished answer replaces what was streamed: the computer has tidied it.
                val text = data.text("answer")?.takeIf { it.isNotBlank() } ?: visible(streamed)
                val id = data.text("session_id")?.takeIf(::validId)
                change(generation) { state ->
                    val messages = if (text.isBlank()) state.messages else put(state.messages, ChatMessage(answer, false, text))
                    if (id != null && id != state.sessionId) state.folder?.let { store.save(ChatPointer(id, it.path, it.label, it.memory)) }
                    state.copy(
                        messages = messages,
                        phase = ChatPhase.IDLE,
                        sessionId = id ?: state.sessionId,
                        loaded = true,
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
                    // The run had started: the message is part of the conversation, a retry is a new message.
                    state.copy(messages = messages, phase = ChatPhase.IDLE, failure = ChatFailure(problem, prompt), interactions = closed(state.interactions))
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
        if (response.status == 202 && (body?.get("queued") as? JsonPrimitive)?.content == "true") {
            // A run is going on there; this message is sent when it ends.
            return change(generation) { it.copy(phase = ChatPhase.ELSEWHERE, queued = true) }
        }
        if (response.ok) return lost(generation)
        awaiting = null
        val code = body.text("error") ?: response.error
        val problem = problemOf(code, response.status)
        // Nothing ran: "Retry" sends the same message in place of this one.
        change(generation) { it.copy(phase = ChatPhase.IDLE, failure = ChatFailure(problem, prompt, replaces = key, status = response.status)) }
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
        val id = state.sessionId
        if (id == null) {
            // A first message, and the computer never named its session.
            awaiting = null
            return change(generation) { it.copy(phase = ChatPhase.IDLE, failure = ChatFailure(ChatProblem.NO_ANSWER)) }
        }
        change(generation) { it.copy(phase = ChatPhase.ELSEWHERE) }
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
        private val FILE_LINE = Regex("(?m)^[ \\t]*\\[\\[file:[^\\n]*]][ \\t]*\\n?")

        /** Ids are put into request paths: only the characters a session id can have. */
        fun validId(id: String): Boolean = ID.matches(id)

        /** The streamed text without the marker lines the computer turns into file cards at the end. */
        fun visible(streamed: CharSequence): String = FILE_LINE.replace(streamed, "").trimEnd()

        private fun put(messages: List<ChatMessage>, message: ChatMessage): List<ChatMessage> =
            if (messages.any { it.key == message.key }) messages.map { if (it.key == message.key) message else it } else messages + message

        /** Is [stored] (a message as the computer has it, possibly cut) the message [sent]? */
        private fun same(stored: String, sent: String): Boolean {
            val a = stored.trim().replace(Regex("\\s+"), " ")
            val b = sent.trim().replace(Regex("\\s+"), " ")
            return a == b || (a.length >= LONG && b.startsWith(a.take(LONG)))
        }

        private const val LONG = 2000

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
            else -> if (status == 503) ChatProblem.NOT_SET_UP else ChatProblem.FAILED
        }
    }
}
