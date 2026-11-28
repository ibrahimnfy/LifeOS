package dev.lifeos.app.data

import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTest {
    private val transport = FakeTransport()
    private val api = Api(transport)
    private val store = object : ChatStore {
        var pointer: ChatPointer? = null
        override fun load(): ChatPointer? = pointer
        override fun save(pointer: ChatPointer?) {
            this.pointer = pointer
        }
    }
    private var reachable = true
    private val memory = Folder("/home/owner/brain", "brain", "memory", default = true)
    private val project = Folder("/home/owner/project-alpha", "project-alpha")

    private companion object {
        /** The id the phone chooses for a new conversation in these tests. */
        const val NEW_ID = "00000000-0000-4000-8000-000000000001"
    }

    private fun TestScope.chat() = Chat(api, store, this, { 1_000L + testScheduler.currentTime }, { reachable }, newId = { NEW_ID })

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun event(name: String, data: String) = AnswerEvent.Stream(name, json(data))
    private fun done(status: Int = 200, body: String? = null) = AnswerEvent.Done(RelayResponse(status, body?.let(::json)))

    private fun answers(vararg events: AnswerEvent) {
        transport.events = { flow { events.forEach { emit(it) } } }
    }

    /** The usual good answer: the session is named, text arrives in two pieces, the run ends. */
    private fun answerWith(session: String, text: String) = answers(
        event("session", """{"session_id":"$session"}"""),
        event("delta", """{"text":"${text.take(3)}"}"""),
        event("delta", """{"text":"${text.drop(3)}"}"""),
        event("done", """{"answer":"$text","session_id":"$session","duration_ms":5,"num_turns":1,"denied_tools":[],"files":[]}"""),
        done(),
    )

    private fun history(vararg exchanges: Pair<String, String>, complete: Boolean = true, older: String? = null) {
        val items = exchanges.joinToString(",") { (user, assistant) ->
            """{"id":"x","at":"2027-01-15T08:00:00.000Z","user":"$user","assistant":"$assistant","tools":[],"complete":$complete,"truncated":false}"""
        }
        transport.answer("GET /sessions/s-1/history", 200, """{"exchanges":[$items],"older":${older?.let { "\"$it\"" }},"total_exchanges_known":null}""")
    }

    private fun running(running: Boolean, queue: String = "[]", paused: String? = null) =
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":$running,"paused":${paused?.let { "\"$it\"" }},"queue":$queue,"last":null}""")

    private fun texts(chat: Chat) = chat.state.value.messages.map { (if (it.fromOwner) "me:" else "it:") + it.text }

    // ------------------------------------------------------------------ sending

    @Test
    fun `a first message starts a conversation in the chosen folder and the answer is shown`() = runTest {
        val chat = chat()
        chat.newChat(project)
        answerWith("s-1", "Hello there")
        assertTrue(chat.send("  Hi  "))
        assertEquals(ChatPhase.SENDING, chat.state.value.phase)
        assertEquals(listOf("me:Hi"), texts(chat))
        advanceUntilIdle()

        val call = transport.calls.single()
        assertEquals("POST /ask", call.method + " " + call.path)
        assertEquals(json("""{"prompt":"Hi","stream":true,"interactive":true,"folder":"/home/owner/project-alpha","new_session_id":"00000000-0000-4000-8000-000000000001"}"""), call.body)
        val state = chat.state.value
        assertEquals(listOf("me:Hi", "it:Hello there"), texts(chat))
        assertEquals(ChatPhase.IDLE, state.phase)
        assertEquals("s-1", state.sessionId)
        assertNull(state.failure)
        // The conversation is found again after the app was closed: its id and folder, no text.
        assertEquals(ChatPointer("s-1", "/home/owner/project-alpha", "project-alpha", memory = false, kind = FolderKinds.PROJECT), store.pointer)
    }

    @Test
    fun `a conversation in the memory folder names it, as no folder means a blank chat since #163`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        answerWith("s-1", "Hello there")
        chat.send("Hi")
        advanceUntilIdle()
        assertEquals(json("""{"prompt":"Hi","stream":true,"interactive":true,"folder":"/home/owner/brain","new_session_id":"00000000-0000-4000-8000-000000000001"}"""), transport.calls.single().body)
    }

    @Test
    fun `the next message continues the same session until a new chat is started`() = runTest {
        val chat = chat()
        chat.newChat(project)
        answerWith("s-1", "Hello there")
        chat.send("Hi")
        advanceUntilIdle()
        answerWith("s-1", "Second answer")
        chat.send("And then?")
        advanceUntilIdle()

        val second = transport.calls.last()
        assertEquals("POST /sessions/s-1/messages", second.method + " " + second.path)
        assertEquals(json("""{"prompt":"And then?","stream":true,"interactive":true}"""), second.body)
        assertEquals(listOf("me:Hi", "it:Hello there", "me:And then?", "it:Second answer"), texts(chat))

        chat.newChat(memory)
        assertNull(chat.state.value.sessionId)
        assertTrue(chat.state.value.messages.isEmpty())
        assertNull(store.pointer)
        answerWith("s-2", "Fresh start")
        chat.send("New topic")
        advanceUntilIdle()
        assertEquals("/ask", transport.calls.last().path)
    }

    @Test
    fun `text appears while it is written and the finished answer replaces it`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        val gate = CompletableDeferred<Unit>()
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"s-1"}"""))
                emit(event("delta", """{"text":"Here is the file.\n[[file: notes/"}"""))
                emit(event("delta", """{"text":"plan.md]]\nAnything else?"}"""))
                gate.await()
                emit(event("done", """{"answer":"Here is the file.\nAnything else?","session_id":"s-1","files":[{"offer":"o1","name":"plan.md"}]}"""))
                emit(done())
            }
        }
        chat.send("Send me the plan")
        runCurrent()
        // While it is written: what arrived so far, without the line that becomes a file card.
        assertEquals(ChatPhase.ANSWERING, chat.state.value.phase)
        assertEquals("it:Here is the file.\nAnything else?", texts(chat).last())
        assertFalse(chat.state.value.canSend)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("me:Send me the plan", "it:Here is the file.\nAnything else?"), texts(chat))
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
    }

    // ------------------------------------------------------------------ refusals and failures

    @Test
    fun `a session that is open on the computer is refused and the message can be sent again`() = runTest {
        val chat = chat()
        store.pointer = ChatPointer("s-1", "/home/owner/project-alpha", "project-alpha")
        chat.restore()
        history("Earlier" to "Answer")
        running(false)
        chat.load()
        advanceUntilIdle()

        // The refusal is the whole answer: one result with a status, no events.
        answers(done(409, """{"error":"session_in_use","message":"this session is open on the computer right now"}"""))
        chat.send("Next step?")
        advanceUntilIdle()
        val failure = checkNotNull(chat.state.value.failure)
        assertEquals(ChatProblem.IN_USE, failure.problem)
        assertEquals("Next step?", failure.prompt)
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Next step?"), texts(chat))

        // Retry sends the same message in place of the refused one, not next to it.
        answerWith("s-1", "Now it works")
        chat.retry()
        advanceUntilIdle()
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Next step?", "it:Now it works"), texts(chat))
        assertNull(chat.state.value.failure)
    }

    @Test
    fun `a run that breaks off keeps what was written and says why`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        answers(
            event("session", """{"session_id":"s-1"}"""),
            event("delta", """{"text":"Let me look"}"""),
            event("error", """{"error":"timeout","message":"the run took too long and was stopped"}"""),
            done(),
        )
        chat.send("Do a lot")
        advanceUntilIdle()
        val state = chat.state.value
        assertEquals(ChatProblem.TOO_LONG, state.failure?.problem)
        assertEquals(ChatPhase.IDLE, state.phase)
        assertEquals(listOf("me:Do a lot", "it:Let me look"), texts(chat))
        assertTrue(state.messages.last().incomplete)
        // The run had started, so the message is in the conversation: a retry is a new message.
        assertNull(state.failure?.replaces)
        assertEquals("s-1", state.sessionId)
    }

    @Test
    fun `the error word decides what is said, not the status`() {
        assertEquals(ChatProblem.IN_USE, Chat.problemOf("session_in_use", 409))
        assertEquals(ChatProblem.FOLDER_GONE, Chat.problemOf("folder_missing", 409))
        assertEquals(ChatProblem.STOPPED, Chat.problemOf("cancelled", 409))
        assertEquals(ChatProblem.BUSY, Chat.problemOf("busy", 429))
        assertEquals(ChatProblem.SESSION_GONE, Chat.problemOf("session_not_found", 404))
        assertEquals(ChatProblem.FOLDER_GONE, Chat.problemOf("folder_not_allowed", 403))
        assertEquals(ChatProblem.FOLDER_IN_REPOSITORY, Chat.problemOf("folder_inside_repository", 403))
        assertEquals(ChatProblem.CAPABLE_OFF, Chat.problemOf("capable_mode_unavailable", 503))
        assertEquals(ChatProblem.NOT_SET_UP, Chat.problemOf("claude_not_found", 503))
        assertEquals(ChatProblem.NOT_SET_UP, Chat.problemOf(null, 503))
        assertEquals(ChatProblem.TOO_LARGE, Chat.problemOf("prompt_too_large", 413))
        assertEquals(ChatProblem.FAILED, Chat.problemOf("claude_failed", 502))
        assertEquals(ChatProblem.FAILED, Chat.problemOf(null))
    }

    // ------------------------------------------------------------------ offline

    @Test
    fun `while the computer cannot be reached nothing is sent and the text goes back to the composer`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        reachable = false
        chat.send("Remember this")
        advanceUntilIdle()
        assertTrue(transport.calls.isEmpty())
        val state = chat.state.value
        assertEquals(ChatProblem.NOT_REACHED, state.failure?.problem)
        assertNull(state.failure?.prompt)
        assertEquals("Remember this", state.draft)
        assertTrue(state.messages.isEmpty())
        assertTrue(state.canSend)
        chat.takeDraft()
        assertNull(chat.state.value.draft)
    }

    @Test
    fun `a message that did not leave the phone is handed back too`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        transport.events = { flow { throw ApiException(ApiException.Kind.NOT_SENT) } }
        chat.send("Remember this")
        advanceUntilIdle()
        assertEquals(ChatProblem.NOT_REACHED, chat.state.value.failure?.problem)
        assertEquals("Remember this", chat.state.value.draft)
        assertTrue(chat.state.value.messages.isEmpty())
    }

    // ------------------------------------------------------------------ an answer that stops arriving

    @Test
    fun `a first message without any answer is not sent again by itself`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        transport.events = { flow { throw ApiException(ApiException.Kind.UNCERTAIN) } }
        // The computer has neither a run nor a conversation under the id the phone chose.
        transport.answer("GET /sessions/$NEW_ID/queue", 200, """{"running":false,"paused":null,"queue":[]}""")
        transport.answer("GET /sessions/$NEW_ID/history", 404, """{"error":"session_not_found"}""")
        chat.send("Hello?")
        advanceUntilIdle()
        val state = chat.state.value
        // It did not arrive: the owner may send it again, and nothing was sent a second time by itself.
        assertEquals(ChatProblem.NOT_DELIVERED, state.failure?.problem)
        assertEquals("Hello?", state.failure?.prompt)
        assertEquals(listOf("me:Hello?"), texts(chat))
        assertEquals(1, transport.calls.count { it.method == "POST" })
    }

    @Test
    fun `when the answer stops arriving the run goes on and its result is read afterwards`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"s-1"}"""))
                emit(event("delta", """{"text":"Working on"}"""))
                throw ApiException(ApiException.Kind.UNCERTAIN)
            }
        }
        running(true)
        chat.send("Long job")
        advanceUntilIdle()
        // Not a failure: the computer is still working, the app just does not hear it.
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)
        assertNull(chat.state.value.failure)
        assertFalse(chat.state.value.canSend)
        assertEquals(1, transport.calls.count { it.method == "POST" })

        chat.check()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)

        running(false)
        history("Long job" to "All done.")
        chat.check()
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertEquals(listOf("me:Long job", "it:All done."), texts(chat))
        assertNull(chat.state.value.failure)
        assertEquals(1, transport.calls.count { it.method == "POST" })
    }

    @Test
    fun `a stream that ends without saying how the run ended is followed up the same way`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        answers(event("session", """{"session_id":"s-1"}"""), done())
        running(false)
        history("Hi" to "Hello")
        chat.send("Hi")
        advanceUntilIdle()
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertEquals(listOf("me:Hi", "it:Hello"), texts(chat))
    }

    @Test
    fun `a message that never arrived is offered again instead of vanishing`() = runTest {
        val chat = chat()
        store.pointer = ChatPointer("s-1", "/home/owner/brain", "brain", memory = true)
        chat.restore()
        history("Earlier" to "Answer")
        running(false)
        chat.load()
        advanceUntilIdle()

        transport.events = { flow { throw ApiException(ApiException.Kind.UNCERTAIN) } }
        chat.send("Did this arrive?")
        advanceUntilIdle()
        // The computer is not working and its conversation does not end with this message.
        val state = chat.state.value
        assertEquals(ChatPhase.IDLE, state.phase)
        assertEquals(ChatProblem.NOT_DELIVERED, state.failure?.problem)
        assertEquals("Did this arrive?", state.failure?.prompt)
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Did this arrive?"), texts(chat))

        answerWith("s-1", "Yes")
        history("Earlier" to "Answer", "Did this arrive?" to "Yes")
        chat.retry()
        advanceUntilIdle()
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Did this arrive?", "it:Yes"), texts(chat))
    }

    @Test
    fun `a run that left no trace of the message is not passed over in silence`() = runTest {
        val chat = chat()
        store.pointer = ChatPointer("s-1", "/home/owner/brain", "brain", memory = true)
        chat.restore()
        history("Earlier" to "Answer")
        running(false)
        chat.load()
        advanceUntilIdle()
        // The computer took the message, then nothing more came and nothing was written there.
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"s-1"}"""))
                throw ApiException(ApiException.Kind.UNCERTAIN)
            }
        }
        chat.send("Lost on the way?")
        advanceUntilIdle()
        assertEquals(ChatProblem.NOT_DELIVERED, chat.state.value.failure?.problem)
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Lost on the way?"), texts(chat))
    }

    @Test
    fun `while the computer cannot be asked the app keeps waiting instead of guessing`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"s-1"}"""))
                throw ApiException(ApiException.Kind.UNCERTAIN)
            }
        }
        transport.fail("GET /sessions/s-1/queue", ApiException(ApiException.Kind.UNCERTAIN))
        chat.send("Long job")
        advanceUntilIdle()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)
        assertTrue(chat.state.value.unreachable)
        assertNull(chat.state.value.failure)

        running(false)
        history("Long job" to "Done")
        chat.check()
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertFalse(chat.state.value.unreachable)
        assertEquals(listOf("me:Long job", "it:Done"), texts(chat))
    }

    @Test
    fun `a message for a session that is answering waits in line on the computer`() = runTest {
        val chat = chat()
        store.pointer = ChatPointer("s-1", "/home/owner/brain", "brain", memory = true)
        chat.restore()
        history("Earlier" to "Answer")
        running(false)
        chat.load()
        advanceUntilIdle()

        running(true, queue = """[{"id":"q1","prompt":"One more thing"}]""")
        answers(done(202, """{"queued":true,"queue_id":"q1","position":1,"interrupted_current_run":false}"""))
        chat.send("One more thing")
        advanceUntilIdle()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)
        assertEquals(listOf("q1" to "One more thing"), chat.state.value.queue.map { it.id to it.prompt })
        assertEquals(listOf("me:Earlier", "it:Answer"), texts(chat))
        assertNull(chat.state.value.failure)

        // Between two runs nothing is "running", but the message still waits: not over yet.
        running(false, queue = """[{"id":"q1","prompt":"One more thing"}]""")
        chat.check()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)

        running(false)
        history("Earlier" to "Answer", "One more thing" to "Noted")
        chat.check()
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertTrue(chat.state.value.queue.isEmpty())
        assertEquals("it:Noted", texts(chat).last())
    }

    // ------------------------------------------------------------------ lock, restart, other sessions

    @Test
    fun `locking the app drops the conversation from memory and the answer is read after unlocking`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        val gate = CompletableDeferred<Unit>()
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"s-1"}"""))
                emit(event("delta", """{"text":"Secret"}"""))
                gate.await()
                emit(event("done", """{"answer":"Secret answer","session_id":"s-1"}"""))
            }
        }
        chat.send("Question")
        runCurrent()
        assertEquals(ChatPhase.ANSWERING, chat.state.value.phase)

        chat.onLocked()
        var state = chat.state.value
        assertTrue(state.messages.isEmpty())
        assertEquals("s-1", state.sessionId)
        assertEquals(ChatPhase.IDLE, state.phase)
        assertFalse(state.loaded)
        // What still arrives for the old stream changes nothing.
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(chat.state.value.messages.isEmpty())

        // Unlocked: the run is still going on the computer, then it has ended.
        running(true)
        history("Question" to "", complete = false)
        chat.load()
        advanceUntilIdle()
        state = chat.state.value
        assertEquals(ChatPhase.ELSEWHERE, state.phase)
        assertEquals(listOf("me:Question"), texts(chat))
        running(false)
        history("Question" to "Secret answer")
        chat.check()
        assertEquals(listOf("me:Question", "it:Secret answer"), texts(chat))
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
    }

    @Test
    fun `a first message cut off by the lock before its session was named is reported`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        transport.events = { flow { CompletableDeferred<Unit>().await() } }
        chat.send("Question")
        runCurrent()
        chat.onLocked()
        // After the unlock the id the phone chose tells whether the message arrived.
        assertNull(chat.state.value.failure)
        assertNull(chat.state.value.sessionId)
        assertEquals(NEW_ID, chat.state.value.startingId)
        assertFalse(chat.state.value.loaded)
        transport.answer("GET /sessions/$NEW_ID/queue", 200, """{"running":false,"paused":null,"queue":[]}""")
        transport.answer("GET /sessions/$NEW_ID/history", 404, """{"error":"session_not_found"}""")
        chat.load()
        advanceUntilIdle()
        assertEquals(ChatProblem.FIRST_LOST, chat.state.value.failure?.problem)
        assertNull(chat.state.value.startingId)
        assertTrue(chat.state.value.canSend)
    }

    @Test
    fun `after a restart the same conversation is continued`() = runTest {
        store.pointer = ChatPointer("s-1", "/home/owner/project-alpha", "project-alpha")
        val chat = chat()
        chat.restore()
        var state = chat.state.value
        assertEquals("s-1", state.sessionId)
        assertEquals("project-alpha", state.folder?.label)
        assertFalse(state.loaded)
        assertFalse(state.canSend)

        history("Earlier" to "Answer", older = "p120")
        running(false)
        chat.load()
        assertTrue(chat.state.value.loading)
        advanceUntilIdle()
        state = chat.state.value
        assertEquals(listOf("me:Earlier", "it:Answer"), texts(chat))
        assertTrue(state.olderExist)
        assertTrue(state.canSend)

        answerWith("s-1", "Next answer")
        chat.send("Next")
        advanceUntilIdle()
        assertEquals("/sessions/s-1/messages", transport.calls.last().path)
    }

    @Test
    fun `a conversation that cannot be read offers a retry and sends nothing meanwhile`() = runTest {
        store.pointer = ChatPointer("s-1", "/home/owner/brain", "brain", memory = true)
        val chat = chat()
        chat.restore()
        transport.fail("GET /sessions/s-1/queue", ApiException(ApiException.Kind.NOT_SENT))
        transport.fail("GET /sessions/s-1/history", ApiException(ApiException.Kind.NOT_SENT))
        chat.load()
        advanceUntilIdle()
        assertEquals(Problem.UNREACHABLE, chat.state.value.loadProblem)
        assertFalse(chat.state.value.canSend)
        assertFalse(chat.send("Hi"))

        running(false)
        history("Earlier" to "Answer")
        chat.load()
        advanceUntilIdle()
        assertNull(chat.state.value.loadProblem)
        assertEquals(listOf("me:Earlier", "it:Answer"), texts(chat))
    }

    @Test
    fun `a session the computer no longer has is forgotten and the folder kept`() = runTest {
        store.pointer = ChatPointer("s-1", "/home/owner/project-alpha", "project-alpha")
        val chat = chat()
        chat.restore()
        running(false)
        transport.answer("GET /sessions/s-1/history", 404, """{"error":"session_not_found","message":"unknown session"}""")
        chat.load()
        advanceUntilIdle()
        val state = chat.state.value
        assertEquals(ChatProblem.SESSION_GONE, state.failure?.problem)
        assertNull(state.sessionId)
        assertNull(store.pointer)
        // The next message starts a new conversation in the same folder.
        answerWith("s-2", "Fresh")
        chat.send("Again")
        advanceUntilIdle()
        assertEquals("/ask", transport.calls.last().path)
        assertEquals(json("""{"prompt":"Again","stream":true,"interactive":true,"folder":"/home/owner/project-alpha","new_session_id":"00000000-0000-4000-8000-000000000001"}"""), transport.calls.last().body)
    }

    @Test
    fun `opening a session from the list shows its last messages and continues it`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        val session = SessionSummary("s-1", "Milestone summary", "/home/owner/project-alpha", "2027-01-15T08:00:00Z", folderLabel = "project-alpha")
        history("Earlier" to "Answer")
        running(false)
        chat.open(session)
        assertTrue(chat.state.value.loading)
        advanceUntilIdle()
        assertEquals(listOf("me:Earlier", "it:Answer"), texts(chat))
        assertEquals("project-alpha", chat.state.value.folder?.label)
        // A session of an older bridge names no kind: its colour is not known.
        assertEquals(ChatPointer("s-1", "/home/owner/project-alpha", "project-alpha"), store.pointer)
        // Opening the same one again asks nothing.
        val calls = transport.calls.size
        chat.open(session)
        advanceUntilIdle()
        assertEquals(calls, transport.calls.size)
        // An id that could not be part of a path is not used.
        chat.open(session.copy(id = "../whoami"))
        assertEquals("s-1", chat.state.value.sessionId)
    }

    @Test
    fun `a session the computer is working on is shown as such when it is opened`() = runTest {
        val chat = chat()
        history("Earlier" to "Answer", "Running now" to "", complete = false)
        running(true)
        chat.open(SessionSummary("s-1", "Busy", "/home/owner/brain", folderLabel = "brain"))
        advanceUntilIdle()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)
        assertFalse(chat.state.value.canSend)
        assertEquals("me:Running now", texts(chat).last())
    }

    @Test
    fun `a conversation that went on on the computer is read again when it is shown again`() = runTest {
        val chat = chat()
        val session = SessionSummary("s-1", "Shared", "/home/owner/project-alpha", folderLabel = "project-alpha")
        history("Earlier" to "Answer")
        running(false)
        chat.open(session)
        advanceUntilIdle()
        val shown = chat.state.value.messages
        // Nothing new on the computer: one quiet look, and the thread on screen stays as it is.
        var calls = transport.calls.size
        chat.refresh()
        advanceUntilIdle()
        assertEquals(calls + 1, transport.calls.size)
        assertEquals("/sessions/s-1/history", transport.calls.last().path)
        assertEquals(shown, chat.state.value.messages)
        // Coming back again at once asks nothing.
        chat.refresh()
        assertEquals(calls + 1, transport.calls.size)
        // The computer shortens a long answer when it reads it back: not a reason to read again.
        history("Earlier" to "Ans")
        testScheduler.advanceTimeBy(Chat.REFRESH_MILLIS)
        chat.refresh()
        advanceUntilIdle()
        assertEquals(shown, chat.state.value.messages)
        // The owner went on in a terminal: the new messages show.
        history("Earlier" to "Answer", "From the terminal" to "Done there")
        testScheduler.advanceTimeBy(Chat.REFRESH_MILLIS)
        chat.refresh()
        advanceUntilIdle()
        assertEquals(listOf("me:Earlier", "it:Answer", "me:From the terminal", "it:Done there"), texts(chat))
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
    }

    @Test
    fun `an answer that was finished on the computer shows when the conversation comes back`() = runTest {
        val chat = chat()
        history("Earlier" to "Answer", "Asked there" to "")
        running(false)
        chat.open(SessionSummary("s-1", "Shared", "/home/owner/project-alpha", folderLabel = "project-alpha"))
        advanceUntilIdle()
        assertEquals("me:Asked there", texts(chat).last())
        history("Earlier" to "Answer", "Asked there" to "Answered there")
        chat.refresh()
        advanceUntilIdle()
        assertEquals("it:Answered there", texts(chat).last())
        // While a message of the phone is on its way, nothing is asked on the side.
        testScheduler.advanceTimeBy(Chat.REFRESH_MILLIS)
        answerWith("s-1", "Hello there")
        assertTrue(chat.send("And now from the phone"))
        val calls = transport.calls.size
        chat.refresh()
        assertEquals(calls, transport.calls.size)
    }

    @Test
    fun `unpairing forgets the conversation and its file entry`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        answerWith("s-1", "Hello there")
        chat.send("Hi")
        advanceUntilIdle()
        chat.forget()
        assertEquals(ChatState(generation = chat.state.value.generation), chat.state.value)
        assertNull(store.pointer)
    }

    @Test
    fun `the pointer file holds an id and a folder and survives a restart`() {
        val folder = java.nio.file.Files.createTempDirectory("chat").toFile()
        try {
            val file = java.io.File(folder, "chat/current.json")
            assertNull(ChatFile(file).load())
            ChatFile(file).save(ChatPointer("s-1", "/home/owner/brain", "brain", memory = true))
            assertEquals(ChatPointer("s-1", "/home/owner/brain", "brain", memory = true), ChatFile(file).load())
            file.writeText("""{"sessionId":"../x"}""")
            assertNull(ChatFile(file).load())
            ChatFile(file).save(null)
            assertFalse(file.exists())
        } finally {
            folder.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ lists and small rules

    @Test
    fun `sessions are sorted newest first, pinned ones picked, and searched by title and folder`() {
        val sessions = listOf(
            SessionSummary("a", "Old alpha", "/w/alpha", "2027-01-10T08:00:00Z", folderLabel = "alpha"),
            SessionSummary("b", "Pinned", "/w/beta", "2027-01-11T08:00:00Z", folderLabel = "beta", pinned = true),
            SessionSummary("c", "Beta", "/w/beta", "2027-01-12T08:00:00Z", folderLabel = "beta"),
            SessionSummary("d", "New alpha", "/w/alpha", "2027-01-14T08:00:00Z", folderLabel = "alpha"),
            SessionSummary("e", "Other alpha", "/x/alpha", null, folderLabel = "alpha"),
        )
        assertEquals(listOf("b"), SessionLists.pinned(sessions).map { it.id })
        assertEquals(listOf("d", "c", "b"), SessionLists.newestFirst(sessions).take(3).map { it.id })
        assertEquals(listOf("a", "d"), SessionLists.matching(sessions, " ALPHA ").filter { it.folder == "/w/alpha" }.map { it.id })
        assertEquals(listOf("d"), SessionLists.matching(sessions, "new").map { it.id })
        assertEquals(sessions, SessionLists.matching(sessions, "  "))
        assertEquals("gamma", SessionSummary("f", folder = "/w/gamma/").folderName)
    }

    @Test
    fun `sessions, folders and history are read`() = runTest {
        transport.answer("GET /sessions", 200, """{"sessions":[{"id":"s-1","title":"Plan","folder":"/home/owner/brain","last_activity":"2027-01-15T08:00:00Z","in_use":true,"detected_title":"x","folder_key":"/home/owner/brain","folder_label":"brain","kind":"memory","colour":1,"pinned":true,"notify_replies":true,"model":null,"effort":null}],"total":1,"folders":[]}""")
        transport.answer("GET /folders", 200, """{"folders":[{"path":"/home/owner/LifeOS-Chats","label":"Chats","kind":"chat","default":true,"colour":0,"usable":true,"reason":null},{"path":"/home/owner/brain","label":"brain","kind":"memory","default":false,"colour":1},{"path":"/home/owner/project-alpha","label":"project-alpha","kind":"project","default":false,"colour":2}]}""")
        val session = api.get(ChatEndpoints.sessions).first.sessions.single()
        assertTrue(session.inUse && session.pinned)
        assertEquals("brain", session.folderName)
        assertEquals(FolderKinds.MEMORY, session.kind)
        assertEquals(1, session.colour)
        assertEquals(mapOf("limit" to "200"), transport.calls.last().query)
        val folders = api.get(ChatEndpoints.folders).first.folders
        assertEquals(listOf(false, true, false), folders.map { it.isMemory })
        assertEquals(listOf(true, false, false), folders.map { it.isChat })
        assertEquals(listOf(0, 1, 2), folders.map { it.colour })
        history("Q" to "A", older = "p12")
        val page = api.history("s-1")
        assertEquals("p12", page.older)
        assertEquals(Exchange("2027-01-15T08:00:00.000Z", "Q", "A"), page.exchanges.single())
        assertTrue(RunState(running = true).busy)
        assertTrue(RunState(queue = listOf(QueuedData("q1", "m"))).busy)
        // A queue that is held (after a cancel or an error) does not move by itself.
        assertFalse(RunState(queue = listOf(QueuedData("q1", "m")), paused = "cancel").busy)
        assertFalse(RunState().busy)
    }

    @Test
    fun `a message counts as arrived when the computer's conversation has it, also cut or trimmed`() {
        assertTrue(Chat.same("  What is\nopen?  ", "What is open?"))
        // The computer keeps at most a part of a very long message.
        val long = "word ".repeat(30_000)
        assertTrue(Chat.same(long.take(60_000), long))
        // It leaves out a part it takes for one of its own markers.
        assertTrue(Chat.same("Explain this line of the page to me", "<the-page class=x>\n\nExplain this line of the page to me"))
        assertTrue(Chat.same("Earlier text. And now: do it", "do it"))
        // An earlier short message is not the new longer one.
        assertFalse(Chat.same("ok", "ok, and now delete the drafts"))
        assertFalse(Chat.same("Earlier", "Did this arrive?"))
        assertFalse(Chat.same("", "Hi"))
        assertEquals("", SessionSummary("s", title = "(untitled)").shownTitle)
        assertEquals("Plan", SessionSummary("s", title = " Plan ").shownTitle)
    }

    @Test
    fun `marker lines for file cards are not shown while the answer is written`() {
        assertEquals("One\nTwo", Chat.visible("One\n[[file: notes/a.md]]\nTwo\n"))
        assertEquals("One", Chat.visible("One\n  [[file: b.pdf]]  "))
        assertEquals("Inline [[file: x]] stays", Chat.visible("Inline [[file: x]] stays"))
        assertTrue(Chat.validId("3f2c9a0e-1b2c-4d5e-8f90-a1b2c3d4e5f6"))
        assertFalse(Chat.validId("a/b"))
        assertFalse(Chat.validId(""))
    }
}
