package dev.lifeos.app.data

import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the chat screen does with the bridge's additions of #147 (#148): a new conversation
 * under an id the phone chose, keep-alives, cards closed by the computer, and a conversation
 * that a notification opens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStartTest {
    private val transport = FakeTransport()
    private val api = Api(transport)
    private val store = object : ChatStore {
        var pointer: ChatPointer? = null
        override fun load(): ChatPointer? = pointer
        override fun save(pointer: ChatPointer?) {
            this.pointer = pointer
        }
    }
    private val memory = Folder("/home/owner/brain", "brain", "memory", default = true)
    private var made = 0
    private fun id(number: Int) = "00000000-0000-4000-8000-%012d".format(number)

    private fun TestScope.chat() = Chat(api, store, this, { 1_000L + testScheduler.currentTime }, newId = { id(++made) })

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun event(name: String, data: String) = AnswerEvent.Stream(name, json(data))
    private fun done(status: Int = 200, body: String? = null) = AnswerEvent.Done(RelayResponse(status, body?.let(::json)))
    private fun answers(vararg events: AnswerEvent) {
        transport.events = { flow { events.forEach { emit(it) } } }
    }

    private fun lost() {
        transport.events = { flow { throw ApiException(ApiException.Kind.UNCERTAIN) } }
    }

    private fun running(session: String, running: Boolean) =
        transport.answer("GET /sessions/$session/queue", 200, """{"running":$running,"paused":null,"queue":[],"last":null}""")

    private fun history(session: String, vararg exchanges: Pair<String, String>) {
        val items = exchanges.joinToString(",") { (user, assistant) -> """{"user":"$user","assistant":"$assistant","complete":true}""" }
        transport.answer("GET /sessions/$session/history", 200, """{"exchanges":[$items],"older":null}""")
    }

    private fun noHistory(session: String) = transport.answer("GET /sessions/$session/history", 404, """{"error":"session_not_found"}""")
    private fun posts() = transport.calls.filter { it.method == "POST" }
    private fun sentId(call: FakeTransport.Call) = (call.body as JsonObject)["new_session_id"].toString().trim('"')
    private fun texts(chat: Chat) = chat.state.value.messages.map { (if (it.fromOwner) "me:" else "it:") + it.text }

    @Test
    fun `a new conversation goes out under an id the phone chose, and that becomes its session`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        answers(event("session", """{"session_id":"${id(1)}"}"""), event("done", """{"answer":"Hello.","session_id":"${id(1)}"}"""), done())
        chat.send("Hi")
        assertEquals(id(1), chat.state.value.startingId)
        // Found again whatever happens to the answer.
        assertEquals(id(1), store.pointer?.sessionId)
        advanceUntilIdle()
        assertEquals(id(1), sentId(posts().single()))
        assertEquals(id(1), chat.state.value.sessionId)
        assertNull(chat.state.value.startingId)
        // The next message belongs to the session and names no id.
        chat.send("More")
        advanceUntilIdle()
        assertEquals("/sessions/${id(1)}/messages", posts().last().path)
        assertFalse("new_session_id" in (posts().last().body as JsonObject))
    }

    @Test
    fun `a blank chat sends its first message without a folder and survives a restart`() = runTest {
        val chat = chat()
        chat.newChat(draft = "Shared text")
        var state = chat.state.value
        assertEquals(ChatFolder.BLANK, state.folder)
        assertEquals("Shared text", state.draft)
        assertNull(store.pointer)
        answers(event("session", """{"session_id":"${id(1)}"}"""), event("done", """{"answer":"Hello.","session_id":"${id(1)}"}"""), done())
        chat.send("Hi")
        // Found again whatever happens to the answer, although it names no folder.
        assertEquals(ChatPointer(id(1)), store.pointer)
        advanceUntilIdle()
        val first = posts().single()
        assertEquals("/ask", first.path)
        assertFalse("folder" in (first.body as JsonObject))
        assertEquals(id(1), chat.state.value.sessionId)
        assertEquals(ChatPointer(id(1)), store.pointer)
        // After a restart the same conversation is continued, in the folder the computer gave it.
        val again = chat()
        again.restore()
        state = again.state.value
        assertEquals(id(1), state.sessionId)
        assertTrue(state.folder?.blank == true)
    }

    @Test
    fun `a chat about a project still runs in its folder`() = runTest {
        val chat = chat()
        chat.newChat(Folder("/home/owner/project-alpha", "project-alpha"))
        answers(event("done", """{"answer":"Hello.","session_id":"${id(1)}"}"""), done())
        chat.send("Hi")
        advanceUntilIdle()
        assertEquals("\"/home/owner/project-alpha\"", (posts().single().body as JsonObject)["folder"].toString())
    }

    @Test
    fun `a first message whose answer was lost is followed under its id when it did arrive`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        lost()
        running(id(1), true)
        chat.send("Long job")
        advanceUntilIdle()
        var state = chat.state.value
        assertEquals(ChatPhase.ELSEWHERE, state.phase)
        assertEquals(id(1), state.sessionId)
        assertNull(state.failure)
        assertTrue(state.canStop)

        running(id(1), false)
        history(id(1), "Long job" to "All done.")
        chat.check()
        state = chat.state.value
        assertEquals(ChatPhase.IDLE, state.phase)
        assertEquals(listOf("me:Long job", "it:All done."), texts(chat))
        assertEquals(1, posts().size)
    }

    @Test
    fun `sending a lost first message again repeats it under the same id and runs nothing twice`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        lost()
        running(id(1), false)
        noHistory(id(1))
        chat.send("Hi")
        advanceUntilIdle()
        assertEquals(ChatProblem.NOT_DELIVERED, chat.state.value.failure?.problem)
        assertEquals(id(1), chat.state.value.startingId)

        // It had arrived after all: the computer recognises the repeat and is still answering the first.
        answers(done(200, """{"repeat":true,"session_id":"${id(1)}","running":true}"""))
        running(id(1), true)
        chat.retry()
        advanceUntilIdle()
        assertEquals(listOf(id(1), id(1)), posts().map(::sentId))
        var state = chat.state.value
        assertEquals(ChatPhase.ELSEWHERE, state.phase)
        assertEquals(id(1), state.sessionId)
        assertNull(state.failure)
        assertEquals(listOf("me:Hi"), texts(chat))

        running(id(1), false)
        history(id(1), "Hi" to "Hello.")
        chat.check()
        state = chat.state.value
        assertEquals(ChatPhase.IDLE, state.phase)
        assertEquals(listOf("me:Hi", "it:Hello."), texts(chat))
    }

    @Test
    fun `a repeat that left nothing behind gives the id up after a second look`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        lost()
        running(id(1), false)
        noHistory(id(1))
        chat.send("Hi")
        advanceUntilIdle()
        // The first try was refused on the computer (and that answer was lost): the id is used up there.
        answers(done(200, """{"repeat":true,"session_id":"${id(1)}","running":false}"""))
        chat.retry()
        advanceUntilIdle()
        // It may be starting at this very moment: one more look before the id is given up.
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)
        chat.check()
        val state = chat.state.value
        assertEquals(ChatPhase.IDLE, state.phase)
        assertEquals(ChatProblem.NOT_DELIVERED, state.failure?.problem)
        assertNull(state.sessionId)
        assertNull(state.startingId)
        assertNull(store.pointer)

        answers(event("session", """{"session_id":"${id(2)}"}"""), event("done", """{"answer":"Hello.","session_id":"${id(2)}"}"""), done())
        chat.retry()
        advanceUntilIdle()
        assertEquals(id(2), sentId(posts().last()))
        assertEquals(listOf("me:Hi", "it:Hello."), texts(chat))
    }

    @Test
    fun `a refused first message is tried again under a new id`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        answers(done(429, """{"error":"busy"}"""))
        chat.send("Hi")
        advanceUntilIdle()
        assertEquals(ChatProblem.BUSY, chat.state.value.failure?.problem)
        assertNull(chat.state.value.startingId)
        assertNull(store.pointer)
        answers(event("session", """{"session_id":"${id(2)}"}"""), event("done", """{"answer":"Hello.","session_id":"${id(2)}"}"""), done())
        chat.retry()
        advanceUntilIdle()
        assertEquals(listOf(id(1), id(2)), posts().map(::sentId))
        assertEquals(id(2), chat.state.value.sessionId)
    }

    @Test
    fun `an id the computer has for another start is given up too`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        answers(done(409, """{"error":"session_id_conflict"}"""))
        chat.send("Hi")
        advanceUntilIdle()
        assertEquals("Hi", chat.state.value.failure?.prompt)
        answers(event("session", """{"session_id":"${id(2)}"}"""), event("done", """{"answer":"Hello.","session_id":"${id(2)}"}"""), done())
        chat.retry()
        advanceUntilIdle()
        assertEquals(id(2), sentId(posts().last()))
    }

    @Test
    fun `a first message that never left the phone leaves nothing to find again`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        transport.events = { flow { throw ApiException(ApiException.Kind.NOT_SENT) } }
        chat.send("Hi")
        advanceUntilIdle()
        assertEquals("Hi", chat.state.value.draft)
        // After a restart the Chat tab does not open a conversation that never began.
        assertNull(store.pointer)
        // The same text again is the same start.
        answers(event("session", """{"session_id":"${id(1)}"}"""), event("done", """{"answer":"Hello.","session_id":"${id(1)}"}"""), done())
        chat.send("Hi")
        advanceUntilIdle()
        assertEquals(listOf(id(1), id(1)), posts().map(::sentId))
        assertEquals(id(1), store.pointer?.sessionId)
    }

    @Test
    fun `another first message is another start`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        lost()
        running(id(1), false)
        noHistory(id(1))
        chat.send("Hi")
        advanceUntilIdle()
        chat.dismissFailure()
        chat.send("Something else")
        advanceUntilIdle()
        assertEquals(listOf(id(1), id(2)), posts().map(::sentId))
    }

    @Test
    fun `a first message that was on its way during a lock is found again afterwards`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        transport.events = { flow { CompletableDeferred<Unit>().await() } }
        chat.send("Question")
        runCurrent()
        chat.onLocked()
        running(id(1), true)
        chat.load()
        advanceUntilIdle()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)
        assertEquals(id(1), chat.state.value.sessionId)
        running(id(1), false)
        history(id(1), "Question" to "Answer.")
        chat.check()
        assertEquals(listOf("me:Question", "it:Answer."), texts(chat))
    }

    // ---- keep-alives and closed cards

    private fun TestScope.asking(vararg after: AnswerEvent): Chat {
        val chat = chat()
        chat.newChat(memory)
        val hold = CompletableDeferred<Unit>()
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"${id(1)}"}"""))
                emit(event("keepalive", "{}"))
                emit(event("interaction", """{"id":"i-1","kind":"approval","tool":"Bash","command":"git push","folder":"/home/owner/brain","category":"push"}"""))
                emit(event("keepalive", "{}"))
                after.forEach { emit(it) }
                hold.await()
            }
        }
        chat.send("Push it")
        runCurrent()
        return chat
    }

    @Test
    fun `keep-alives change nothing on screen`() = runTest {
        val chat = asking()
        val state = chat.state.value
        assertEquals(ChatPhase.SENDING, state.phase)
        assertEquals(listOf("me:Push it"), texts(chat))
        assertEquals(Interaction.Status.WAITING, state.interactions.single().status)
        chat.onLocked()
    }

    @Test
    fun `a card answered elsewhere or left by an ended run closes`() = runTest {
        val chat = asking(event("interaction_closed", """{"id":"i-1","reason":"answered"}"""))
        assertEquals(Interaction.Status.GONE, chat.state.value.interactions.single().status)
        chat.onLocked()
    }

    @Test
    fun `a card whose time ran out on the computer says so`() = runTest {
        val chat = asking(event("interaction_closed", """{"id":"i-1","reason":"timed_out"}"""))
        assertEquals(Interaction.Status.EXPIRED, chat.state.value.interactions.single().status)
        chat.onLocked()
    }

    @Test
    fun `the computer's "answered" does not overwrite what the owner answered here`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        val answered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"${id(1)}"}"""))
                emit(event("interaction", """{"id":"i-1","kind":"approval","tool":"Bash","command":"git push","folder":"/home/owner/brain","category":"push"}"""))
                answered.await()
                emit(event("interaction_closed", """{"id":"i-1","reason":"answered"}"""))
                hold.await()
            }
        }
        transport.answer("POST /sessions/${id(1)}/interactions/i-1", 200, """{"answered":true}""")
        chat.send("Push it")
        runCurrent()
        chat.answerApproval("i-1", approve = true)
        runCurrent()
        answered.complete(Unit)
        runCurrent()
        assertEquals(Interaction.Status.ALLOWED, chat.state.value.interactions.single().status)
        chat.onLocked()
    }

    // ---- a notification opens the conversation

    @Test
    fun `a conversation opened from a notification is read again, with what waits for the owner`() = runTest {
        val chat = chat()
        val session = SessionSummary("s-1", "Title", "/home/owner/brain")
        running("s-1", false)
        history("s-1", "Push it" to "")
        transport.answer("GET /sessions/s-1/interactions", 200, """{"pending":[]}""")
        chat.open(session, memory = true)
        advanceUntilIdle()
        assertTrue(chat.state.value.interactions.isEmpty())
        val reads = transport.calls.size

        // Opening the same conversation from the list changes nothing; from a notification it is read anew.
        chat.open(session, memory = true)
        advanceUntilIdle()
        assertEquals(reads, transport.calls.size)
        running("s-1", true)
        transport.answer(
            "GET /sessions/s-1/interactions",
            200,
            """{"pending":[{"id":"i-7","kind":"approval","tool":"Bash","command":"git push","folder":"/home/owner/brain","category":"push","created_at":"2027-01-15T08:00:00Z","expires_at":"2027-01-15T08:10:00Z"}]}""",
        )
        chat.open(session, memory = true, fresh = true)
        advanceUntilIdle()
        val state = chat.state.value
        assertEquals(ChatPhase.ELSEWHERE, state.phase)
        assertEquals("i-7", state.interactions.single().id)
        assertEquals(Interaction.Status.WAITING, state.interactions.single().status)
    }

    @Test
    fun `the list tells a session the computer answers from one that is open elsewhere`() = runTest {
        transport.answer(
            "GET /sessions",
            200,
            """{"sessions":[{"id":"s-1","in_use":true,"running_here":true,"open_elsewhere":false},{"id":"s-2","in_use":false,"running_here":false,"open_elsewhere":true},{"id":"s-3","in_use":false,"running_here":false,"open_elsewhere":null}],"total":3,"groups":[]}""",
        )
        val sessions = api.get(ChatEndpoints.sessions).first.sessions
        assertEquals(listOf(true, false, false), sessions.map { it.inUse })
        assertEquals(listOf(false, true, null), sessions.map { it.openElsewhere })
    }

    @Test
    fun `a stream counts as lost after three missed keep-alives`() {
        assertTrue(Api.CHAT_IDLE_MILLIS in 60_000L..120_000L)
    }
}
