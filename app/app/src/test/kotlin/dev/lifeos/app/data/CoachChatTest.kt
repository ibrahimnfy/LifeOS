package dev.lifeos.app.data

import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one conversation with the coach (#114): its session is the computer's to name and to renew. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachChatTest {
    private val transport = FakeTransport()
    private val api = Api(transport)
    private val store = object : ChatStore {
        var saved = 0
        override fun load(): ChatPointer? = null
        override fun save(pointer: ChatPointer?) {
            if (pointer != null) saved++
        }
    }

    private fun TestScope.coach() = Chat(api, store, this, { 1_000L + testScheduler.currentTime }, coach = true)

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun event(name: String, data: String) = AnswerEvent.Stream(name, json(data))
    private fun done() = AnswerEvent.Done(RelayResponse(200, null))

    private fun view(session: String, started: Boolean = true, bytes: Long = 1_000, compactions: Int = 0, runs: Int = 2) = transport.answer(
        "GET /coach/chat",
        200,
        """{"session_id":"$session","started":$started,"transcript_bytes":$bytes,"compact_at_bytes":400000,"compactions":$compactions,"summary":null,"last_compaction":null,"earlier_sessions":[],"model":"opus","effort":"high","usage":{"today":{"runs":$runs,"input_tokens":10,"output_tokens":5,"cost_usd":0.2},"week":{"runs":7}},"morning_usage":{"today":{"runs":1},"week":{"runs":6}}}""",
    )

    private fun history(session: String, vararg exchanges: Pair<String, String>) {
        val items = exchanges.joinToString(",") { (user, assistant) -> """{"user":"$user","assistant":"$assistant","complete":true}""" }
        transport.answer("GET /sessions/$session/history", 200, """{"exchanges":[$items],"older":null}""")
    }

    private fun running(session: String, running: Boolean) =
        transport.answer("GET /sessions/$session/queue", 200, """{"running":$running,"paused":null,"queue":[],"last":null}""")

    private fun answerIn(session: String, text: String) {
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"$session"}"""))
                emit(event("done", """{"answer":"$text","session_id":"$session"}"""))
                emit(done())
            }
        }
    }

    private fun texts(chat: Chat) = chat.state.value.messages.map { (if (it.fromOwner) "me:" else "it:") + it.text }

    @Test
    fun `the conversation is the session the computer names, and its usage is read with it`() = runTest {
        val coach = coach()
        view("c-1")
        running("c-1", false)
        history("c-1", "What first?" to "The outline.")
        assertFalse(coach.state.value.loaded)
        coach.load()
        advanceUntilIdle()
        val state = coach.state.value
        assertEquals("c-1", state.sessionId)
        assertEquals(listOf("me:What first?", "it:The outline."), texts(coach))
        assertEquals(2, state.coach?.usage?.today?.runs)
        assertEquals(1, state.coach?.morningUsage?.today?.runs)
        assertEquals(6, state.coach?.morningUsage?.week?.runs)
        assertTrue(state.canSend)
    }

    @Test
    fun `before its first message there is nothing to read, and that is no error`() = runTest {
        val coach = coach()
        view("c-1", started = false)
        running("c-1", false)
        transport.answer("GET /sessions/c-1/history", 404, """{"error":"session_not_found"}""")
        coach.load()
        advanceUntilIdle()
        val state = coach.state.value
        assertTrue(state.loaded)
        assertTrue(state.isEmpty)
        assertNull(state.failure)
        assertEquals("c-1", state.sessionId)
    }

    @Test
    fun `messages go to the coach's own address, read-only and without a model`() = runTest {
        val coach = coach()
        view("c-1")
        running("c-1", false)
        history("c-1")
        coach.load()
        advanceUntilIdle()
        // Neither of these is the coach's: the computer would refuse the message.
        coach.setCapable(true)
        coach.setModel(ModelChoice("sonnet", "low"))
        answerIn("c-1", "Start with the outline.")
        assertTrue(coach.send("What first?"))
        advanceUntilIdle()
        val sent = transport.calls.single { it.method == "POST" }
        assertEquals("/coach/chat/messages", sent.path)
        assertEquals("""{"prompt":"What first?","stream":true,"interactive":true}""", sent.body.toString())
        assertEquals(listOf("me:What first?", "it:Start with the outline."), texts(coach))
        assertEquals(0, store.saved)
    }

    @Test
    fun `a long conversation is tidied up first, and the answer comes in a new session`() = runTest {
        val coach = coach()
        view("c-1", bytes = 400_000)
        running("c-1", false)
        history("c-1", "Earlier" to "Answer")
        coach.load()
        advanceUntilIdle()
        assertTrue(coach.state.value.coach?.dueForCompaction == true)
        var release = false
        transport.events = {
            flow {
                // The stream of #155: signs of life while the conversation is tidied up, then what
                // was done, then the message's own events in the new session.
                while (!release) {
                    kotlinx.coroutines.delay(20_000)
                    emit(event("keepalive", "{}"))
                }
                emit(event("compacted", """{"facts":["profile/work.md","projects/alpha.md"],"summary":true,"session_id":"c-2"}"""))
                emit(event("session", """{"session_id":"c-2"}"""))
                emit(event("keepalive", "{}"))
                emit(event("done", """{"answer":"Fresh start.","session_id":"c-2"}"""))
                emit(done())
            }
        }
        coach.send("And now?")
        testScheduler.advanceTimeBy(50_000)
        // The screen says why it takes long. Stop is there; the computer refuses it for the tidying up.
        assertTrue(coach.state.value.tidying)
        assertTrue(coach.state.value.canStop)
        assertEquals(ChatPhase.SENDING, coach.state.value.phase)
        transport.answer("POST /sessions/c-1/cancel", 409, """{"error":"compaction_running"}""")
        coach.cancel()
        testScheduler.runCurrent()
        assertEquals(ChatProblem.COMPACTING, coach.state.value.failure?.problem)
        assertFalse(coach.state.value.stopping)
        assertEquals(ChatPhase.SENDING, coach.state.value.phase)

        view("c-2", bytes = 2_000, compactions = 1, runs = 4)
        release = true
        advanceUntilIdle()
        val state = coach.state.value
        assertFalse(state.tidying)
        assertEquals("c-2", state.sessionId)
        assertEquals(listOf("me:Earlier", "it:Answer", "me:And now?", "it:Fresh start."), texts(coach))
        // What stands above the new message was tidied up; nothing further back belongs to this session.
        assertEquals(state.messages[2].key, state.renewedAt)
        assertEquals(listOf("profile/work.md", "projects/alpha.md"), state.tidied?.facts)
        assertTrue(state.tidied?.summary == true)
        assertNull(state.olderCursor)
        assertEquals(1, state.coach?.compactions)
        assertEquals(4, state.coach?.usage?.today?.runs)
    }

    @Test
    fun `the tidying up is shown from the stream, also when the app did not expect it, and not for a quiet answer`() = runTest {
        val coach = coach()
        view("c-1")
        running("c-1", false)
        history("c-1", "Earlier" to "Answer")
        coach.load()
        advanceUntilIdle()
        assertFalse(coach.state.value.coach?.dueForCompaction == true)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val named = kotlinx.coroutines.CompletableDeferred<Unit>()
        transport.events = {
            flow {
                emit(event("keepalive", "{}"))
                gate.await()
                emit(event("compacted", """{"facts":[],"summary":false,"session_id":"c-2"}"""))
                emit(event("session", """{"session_id":"c-2"}"""))
                // The message's own run says nothing for a while: that is not tidying up.
                emit(event("keepalive", "{}"))
                named.await()
                emit(event("done", """{"answer":"Here.","session_id":"c-2"}"""))
                emit(done())
            }
        }
        coach.send("And now?")
        testScheduler.runCurrent()
        assertTrue(coach.state.value.tidying)
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertFalse(coach.state.value.tidying)
        assertEquals("c-2", coach.state.value.sessionId)
        assertFalse(coach.state.value.tidied?.summary == true)
        named.complete(Unit)
        advanceUntilIdle()
        assertEquals("it:Here.", texts(coach).last())
    }

    @Test
    fun `a message that had to wait after all is put in line`() = runTest {
        val coach = coach()
        view("c-1", bytes = 400_000)
        running("c-1", false)
        history("c-1", "Earlier" to "Answer")
        coach.load()
        advanceUntilIdle()
        transport.events = { flow { emit(event("keepalive", "{}")); emit(event("queued", """{"queued":true,"queue_id":"q-1","position":1}""")); emit(done()) } }
        transport.answer("GET /sessions/c-1/queue", 200, """{"running":true,"paused":null,"queue":[{"id":"q-1","prompt":"And now?"}]}""")
        coach.send("And now?")
        advanceUntilIdle()
        assertEquals("c-1", coach.state.value.sessionId)
        assertEquals(listOf("q-1" to "And now?"), coach.state.value.queue.map { it.id to it.prompt })
        assertEquals(listOf("me:Earlier", "it:Answer"), texts(coach))
        assertEquals(ChatPhase.ELSEWHERE, coach.state.value.phase)
        assertFalse(coach.state.value.tidying)
    }

    @Test
    fun `a message put in line says in its answer when the conversation was tidied up first`() = runTest {
        val coach = coach()
        view("c-1", bytes = 400_000)
        running("c-1", false)
        history("c-1", "Earlier" to "Answer")
        coach.load()
        advanceUntilIdle()
        // One answer, not a stream (#157): put in line, in the session that continues after the tidying up.
        transport.events = { flow { emit(AnswerEvent.Done(RelayResponse(202, json("""{"queued":true,"queue_id":"q-1","position":1,"compacted":{"facts":["profile/work.md"],"summary":true,"session_id":"c-2"}}""")))) } }
        view("c-2", bytes = 2_000, compactions = 1)
        transport.answer("GET /sessions/c-2/queue", 200, """{"running":true,"paused":null,"queue":[{"id":"q-1","prompt":"And now?"}]}""")
        coach.send("And now?")
        advanceUntilIdle()
        assertEquals("c-2", coach.state.value.sessionId)
        assertEquals(listOf("profile/work.md"), coach.state.value.tidied?.facts)
        assertEquals(listOf("q-1"), coach.state.value.queue.map { it.id })
        assertFalse(coach.state.value.tidying)
    }

    @Test
    fun `an error before the coach's message runs ends the wait with its reason`() = runTest {
        val coach = coach()
        view("c-1", bytes = 400_000)
        running("c-1", false)
        history("c-1", "Earlier" to "Answer")
        coach.load()
        advanceUntilIdle()
        transport.events = { flow { emit(event("keepalive", "{}")); emit(event("error", """{"error":"busy","message":"x"}""")); emit(done()) } }
        coach.send("And now?")
        advanceUntilIdle()
        assertEquals(ChatPhase.IDLE, coach.state.value.phase)
        assertFalse(coach.state.value.tidying)
        assertEquals(ChatProblem.BUSY, coach.state.value.failure?.problem)
        assertEquals("And now?", coach.state.value.failure?.prompt)
        assertEquals(ChatProblem.COMPACTING, Chat.problemOf("compaction_running", 409))
    }

    @Test
    fun `an answer lost while the computer tidied up is found in the new session, not sent again`() = runTest {
        val coach = coach()
        view("c-1", bytes = 400_000)
        running("c-1", false)
        history("c-1", "Earlier" to "Answer")
        coach.load()
        advanceUntilIdle()
        transport.events = { flow { throw ApiException(ApiException.Kind.UNCERTAIN) } }
        // The computer has renewed the session meanwhile, and the new one has neither a run nor the message yet.
        view("c-2", started = false, compactions = 1)
        running("c-2", false)
        transport.answer("GET /sessions/c-2/history", 404, """{"error":"session_not_found"}""")
        coach.send("And now?")
        advanceUntilIdle()
        assertEquals(ChatPhase.ELSEWHERE, coach.state.value.phase)
        assertNull(coach.state.value.failure)
        assertEquals("c-2", coach.state.value.sessionId)

        history("c-2", "And now?" to "Fresh start.")
        coach.check()
        assertEquals(ChatPhase.IDLE, coach.state.value.phase)
        assertNull(coach.state.value.failure)
        assertEquals(listOf("me:And now?", "it:Fresh start."), texts(coach))
        assertEquals(1, transport.calls.count { it.method == "POST" })
    }

    @Test
    fun `a message that is still missing at the second look is offered again`() = runTest {
        val coach = coach()
        view("c-1")
        running("c-1", false)
        history("c-1", "Earlier" to "Answer")
        coach.load()
        advanceUntilIdle()
        transport.events = { flow { throw ApiException(ApiException.Kind.UNCERTAIN) } }
        coach.send("Lost one")
        advanceUntilIdle()
        assertEquals(ChatPhase.ELSEWHERE, coach.state.value.phase)
        coach.check()
        val state = coach.state.value
        assertEquals(ChatPhase.IDLE, state.phase)
        assertEquals(ChatProblem.NOT_DELIVERED, state.failure?.problem)
        assertEquals("Lost one", state.failure?.prompt)
    }

    @Test
    fun `tidying up now renews the session and the screen starts empty`() = runTest {
        val coach = coach()
        view("c-1")
        running("c-1", false)
        history("c-1", "Earlier" to "Answer")
        coach.load()
        advanceUntilIdle()
        transport.answer("POST /coach/chat/compact", 200, """{"facts":["profile/work.md"],"summary":true,"session_id":"c-2"}""")
        view("c-2", started = false, compactions = 1)
        running("c-2", false)
        transport.answer("GET /sessions/c-2/history", 404, """{"error":"session_not_found"}""")
        coach.tidyCoach()
        assertTrue(coach.state.value.compacting)
        assertFalse(coach.state.value.canSend)
        advanceUntilIdle()
        val state = coach.state.value
        assertFalse(state.compacting)
        assertEquals("c-2", state.sessionId)
        assertTrue(state.isEmpty)
        assertEquals(1, state.coach?.compactions)
    }

    @Test
    fun `a refused tidying up changes nothing and says so`() = runTest {
        val coach = coach()
        view("c-1")
        running("c-1", false)
        history("c-1", "Earlier" to "Answer")
        coach.load()
        advanceUntilIdle()
        transport.answer("POST /coach/chat/compact", 409, """{"error":"session_in_use"}""")
        coach.tidyCoach()
        advanceUntilIdle()
        assertEquals(ChatProblem.TIDY_FAILED, coach.state.value.failure?.problem)
        assertEquals(listOf("me:Earlier", "it:Answer"), texts(coach))
        assertEquals("c-1", coach.state.value.sessionId)
    }

    @Test
    fun `a lock forgets everything, also the summary, and it is read again afterwards`() = runTest {
        val coach = coach()
        view("c-1")
        running("c-1", false)
        history("c-1", "Earlier" to "Answer")
        coach.load()
        advanceUntilIdle()
        coach.onLocked()
        val state = coach.state.value
        assertNull(state.coach)
        assertNull(state.sessionId)
        assertTrue(state.messages.isEmpty())
        assertFalse(state.loaded)
        coach.load()
        advanceUntilIdle()
        assertEquals(listOf("me:Earlier", "it:Answer"), texts(coach))
    }

    @Test
    fun `while the computer cannot be asked which session it is, the screen offers to try again`() = runTest {
        val coach = coach()
        transport.fail("GET /coach/chat", ApiException(ApiException.Kind.NOT_SENT))
        coach.load()
        advanceUntilIdle()
        assertEquals(Problem.UNREACHABLE, coach.state.value.loadProblem)
        assertFalse(coach.state.value.canSend)
    }

    @Test
    fun `the coach's view tells when the next message is tidied up first`() {
        assertFalse(CoachChatView(transcriptBytes = 399_999, compactAtBytes = 400_000).dueForCompaction)
        assertTrue(CoachChatView(transcriptBytes = 400_000, compactAtBytes = 400_000).dueForCompaction)
        assertFalse(CoachChatView(transcriptBytes = 400_000, compactAtBytes = 0).dueForCompaction)
        assertEquals(50, CoachChatView(transcriptBytes = 200_000, compactAtBytes = 400_000).fullPercent)
        assertEquals(100, CoachChatView(transcriptBytes = 900_000, compactAtBytes = 400_000).fullPercent)
    }
}
