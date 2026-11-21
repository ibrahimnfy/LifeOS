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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Approvals and questions in the middle of a run (the bridge's interactions, #105). */
@OptIn(ExperimentalCoroutinesApi::class)
class InteractionsTest {
    private val transport = FakeTransport()
    private val api = Api(transport)
    private val store = object : ChatStore {
        var pointer: ChatPointer? = null
        override fun load(): ChatPointer? = pointer
        override fun save(pointer: ChatPointer?) {
            this.pointer = pointer
        }
    }

    /** The phone's running clock, moved by hand; the wall clock is far off to show it is not used. */
    private var uptime = 50_000L
    private val wall = 1_900_000_000_000L
    private val memory = Folder("/home/owner/brain", "brain", "memory", default = true)

    private fun TestScope.chat() = Chat(api, store, this, { wall }, { true }, { uptime })

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun event(name: String, data: String) = AnswerEvent.Stream(name, json(data))
    private fun done() = AnswerEvent.Done(RelayResponse(200, null))

    private val deletion = """{"id":"a1b2c3d4e5f6","session_id":"s-1","kind":"approval","tool":"Bash","command":"rm -r notes/drafts","folder":"/home/owner/brain","category":"deletion","reason":"permanently deleting files","created_at":"2027-01-15T08:00:00Z","expires_at":"2027-01-15T08:10:00Z"}"""
    private val question = """{"id":"q1","session_id":"s-1","kind":"question","questions":[{"question":"Red or blue?","header":"Colour","multi_select":false,"options":[{"label":"Red","description":"Warm"},{"label":"Blue","description":""}]},{"question":"Which days?","header":"","multi_select":true,"options":[{"label":"Mon","description":""},{"label":"Tue","description":""}]}],"created_at":"2027-01-15T08:00:00Z","expires_at":"2027-01-15T08:10:00Z"}"""

    /** A run that asks [asked] and goes on when [gate] opens. */
    private fun runThatAsks(asked: String, gate: CompletableDeferred<Unit>, answer: String = "Done") {
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"s-1"}"""))
                emit(event("interaction", asked))
                gate.await()
                emit(event("done", """{"answer":"$answer","session_id":"s-1"}"""))
                emit(done())
            }
        }
    }

    private fun answered(id: String) = transport.answer("POST /sessions/s-1/interactions/$id", 200, """{"answered":true,"id":"$id"}""")
    private fun posts() = transport.calls.filter { it.method == "POST" && "/interactions/" in it.path }

    @Test
    fun `every message says that the app can show questions and approvals, and asks for changes only when allowed`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        transport.events = { flow { emit(event("session", """{"session_id":"s-1"}""")); emit(event("done", """{"answer":"ok"}""")) } }
        chat.send("Hi")
        advanceUntilIdle()
        assertEquals(json("""{"prompt":"Hi","stream":true,"interactive":true}"""), transport.calls.last().body)

        chat.setCapable(true)
        chat.send("Tidy up")
        advanceUntilIdle()
        assertEquals(json("""{"prompt":"Tidy up","stream":true,"interactive":true,"mode":"capable"}"""), transport.calls.last().body)

        // A new conversation is read-only again.
        chat.newChat(memory)
        assertFalse(chat.state.value.capable)
    }

    @Test
    fun `an approval shows exactly what will run and where, and runs only after a yes`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        chat.setCapable(true)
        val gate = CompletableDeferred<Unit>()
        runThatAsks(deletion, gate)
        chat.send("Clear out the old drafts")
        runCurrent()

        val asked = chat.state.value.interactions.single()
        assertEquals(Interaction.Kind.APPROVAL, asked.kind)
        assertEquals("rm -r notes/drafts", asked.command)
        assertEquals("/home/owner/brain", asked.folder)
        assertTrue(asked.isDeletion)
        assertEquals(Interaction.Status.WAITING, asked.status)
        // Ten minutes from when it arrived, on the phone's own running clock.
        assertEquals(uptime + 600_000, asked.deadline)
        assertTrue(posts().isEmpty())

        answered("a1b2c3d4e5f6")
        chat.answerApproval("a1b2c3d4e5f6", approve = true)
        assertEquals(Interaction.Status.SENDING, chat.state.value.interactions.single().status)
        runCurrent()
        assertEquals(json("""{"approve":true}"""), posts().single().body)
        assertEquals("/sessions/s-1/interactions/a1b2c3d4e5f6", posts().single().path)
        assertEquals(Interaction.Status.ALLOWED, chat.state.value.interactions.single().status)

        // A second tap changes nothing.
        chat.answerApproval("a1b2c3d4e5f6", approve = false)
        runCurrent()
        assertEquals(1, posts().size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertEquals("Done", chat.state.value.messages.last().text)
        // The card stays as a record until the next message.
        assertEquals(Interaction.Status.ALLOWED, chat.state.value.interactions.single().status)
        transport.events = { flow { emit(event("done", """{"answer":"ok"}""")) } }
        chat.send("Next")
        assertTrue(chat.state.value.interactions.isEmpty())
        advanceUntilIdle()
    }

    @Test
    fun `a no is sent as a no`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        val gate = CompletableDeferred<Unit>()
        runThatAsks(deletion, gate)
        chat.send("Clear out the old drafts")
        runCurrent()
        answered("a1b2c3d4e5f6")
        chat.answerApproval("a1b2c3d4e5f6", approve = false)
        runCurrent()
        assertEquals(json("""{"approve":false}"""), posts().single().body)
        assertEquals(Interaction.Status.REFUSED, chat.state.value.interactions.single().status)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `no answer in time counts as no and nothing is sent`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        val gate = CompletableDeferred<Unit>()
        runThatAsks(deletion, gate)
        chat.send("Clear out the old drafts")
        runCurrent()
        val asked = chat.state.value.interactions.single()
        assertTrue(asked.open(uptime))
        assertEquals(600_000, asked.remainingMillis(uptime))

        // Not yet: the card stays open.
        uptime += 599_000
        chat.expire(asked.id)
        assertEquals(Interaction.Status.WAITING, chat.state.value.interactions.single().status)
        assertEquals(1_000, asked.remainingMillis(uptime))

        uptime += 1_000
        assertFalse(asked.open(uptime))
        assertEquals(0, asked.remainingMillis(uptime + 5_000))
        // A tap that comes too late approves nothing.
        chat.answerApproval(asked.id, approve = true)
        runCurrent()
        assertEquals(Interaction.Status.EXPIRED, chat.state.value.interactions.single().status)
        assertTrue(posts().isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(Interaction.Status.EXPIRED, chat.state.value.interactions.single().status)
    }

    @Test
    fun `an answer the computer no longer waits for is said to be so`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        val gate = CompletableDeferred<Unit>()
        runThatAsks(deletion, gate)
        chat.send("Clear out the old drafts")
        runCurrent()
        transport.answer("POST /sessions/s-1/interactions/a1b2c3d4e5f6", 404, """{"error":"not_pending","message":"this question is no longer waiting"}""")
        chat.answerApproval("a1b2c3d4e5f6", approve = true)
        runCurrent()
        assertEquals(Interaction.Status.GONE, chat.state.value.interactions.single().status)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `an answer that could not be sent can be given again`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        val gate = CompletableDeferred<Unit>()
        runThatAsks(deletion, gate)
        chat.send("Clear out the old drafts")
        runCurrent()
        transport.fail("POST /sessions/s-1/interactions/a1b2c3d4e5f6", ApiException(ApiException.Kind.NOT_SENT))
        chat.answerApproval("a1b2c3d4e5f6", approve = true)
        runCurrent()
        var asked = chat.state.value.interactions.single()
        assertEquals(Interaction.Status.WAITING, asked.status)
        assertTrue(asked.sendFailed)

        answered("a1b2c3d4e5f6")
        chat.answerApproval("a1b2c3d4e5f6", approve = true)
        runCurrent()
        asked = chat.state.value.interactions.single()
        assertEquals(Interaction.Status.ALLOWED, asked.status)
        assertFalse(asked.sendFailed)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `when the run ends, what still waited is closed`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        val gate = CompletableDeferred<Unit>()
        runThatAsks(deletion, gate)
        chat.send("Clear out the old drafts")
        runCurrent()
        // The run was cancelled or timed out on the computer.
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(Interaction.Status.GONE, chat.state.value.interactions.single().status)
        chat.answerApproval("a1b2c3d4e5f6", approve = true)
        runCurrent()
        assertTrue(posts().isEmpty())
    }

    @Test
    fun `a run that goes on after the waiting time says that the silence counted as no`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        val gate = CompletableDeferred<Unit>()
        runThatAsks(deletion, gate, answer = "I left them.")
        chat.send("Clear out the old drafts")
        runCurrent()
        // Ten minutes pass with the screen off; the computer refuses by itself and finishes.
        uptime += 600_000
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(Interaction.Status.EXPIRED, chat.state.value.interactions.single().status)
        assertEquals("I left them.", chat.state.value.messages.last().text)
        assertTrue(posts().isEmpty())
    }

    @Test
    fun `questions are answered with a label, several labels, own words, or declined`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        val gate = CompletableDeferred<Unit>()
        runThatAsks(question, gate)
        chat.send("Plan my week")
        runCurrent()
        val asked = chat.state.value.interactions.single()
        assertEquals(Interaction.Kind.QUESTION, asked.kind)
        assertEquals(listOf("Red or blue?", "Which days?"), asked.questions.map { it.question })
        assertEquals(listOf(false, true), asked.questions.map { it.multiSelect })
        assertEquals(listOf("Red", "Blue"), asked.questions[0].options.map { it.label })

        // Not every question answered: nothing is sent.
        chat.answerQuestion("q1", mapOf("Red or blue?" to listOf("Red")))
        runCurrent()
        assertTrue(posts().isEmpty())

        answered("q1")
        chat.answerQuestion("q1", mapOf("Red or blue?" to listOf(" Purple, actually "), "Which days?" to listOf("Mon", "Tue")))
        runCurrent()
        assertEquals(json("""{"answers":{"Red or blue?":"Purple, actually","Which days?":["Mon","Tue"]}}"""), posts().single().body)
        assertEquals(Interaction.Status.ANSWERED, chat.state.value.interactions.single().status)
        // The card keeps what was answered, as it was sent.
        assertEquals(mapOf("Red or blue?" to listOf("Purple, actually"), "Which days?" to listOf("Mon", "Tue")), chat.state.value.interactions.single().answers)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(json("""{"decline":true}"""), Interaction.declineBody)
        assertFalse(Interaction.complete(asked.questions, mapOf("Red or blue?" to listOf("Red"), "Which days?" to listOf(" "))))
        assertTrue(Interaction.complete(asked.questions, mapOf("Red or blue?" to listOf("Red"), "Which days?" to listOf("Mon"))))
    }

    @Test
    fun `a question can be skipped`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        val gate = CompletableDeferred<Unit>()
        runThatAsks(question, gate)
        chat.send("Plan my week")
        runCurrent()
        answered("q1")
        chat.skipQuestion("q1")
        runCurrent()
        assertEquals(json("""{"decline":true}"""), posts().single().body)
        assertEquals(Interaction.Status.SKIPPED, chat.state.value.interactions.single().status)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a run the app no longer hears still gets its answer`() = runTest {
        // The app was locked while the run waited: after unlocking the computer tells what waits.
        store.pointer = ChatPointer("s-1", "/home/owner/brain", "brain", memory = true)
        val chat = chat()
        chat.restore()
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":true,"paused":null,"queue":[]}""")
        transport.answer("GET /sessions/s-1/history", 200, """{"exchanges":[{"user":"Clear out the old drafts","assistant":"","complete":false}],"older":null}""")
        // Its end time against the phone's wall clock: four minutes are left.
        val expires = java.time.Instant.ofEpochMilli(wall + 240_000).toString()
        val waitingNow = deletion.replace("2027-01-15T08:10:00Z", expires)
        transport.answer("GET /sessions/s-1/interactions", 200, """{"pending":[$waitingNow]}""")
        chat.load()
        advanceUntilIdle()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)
        val asked = chat.state.value.interactions.single()
        assertEquals("rm -r notes/drafts", asked.command)
        assertEquals(uptime + 240_000, asked.deadline)

        // Looking again adds nothing twice.
        chat.check()
        assertEquals(1, chat.state.value.interactions.size)

        answered("a1b2c3d4e5f6")
        chat.answerApproval("a1b2c3d4e5f6", approve = false)
        runCurrent()
        assertEquals(Interaction.Status.REFUSED, chat.state.value.interactions.single().status)

        // The run goes on and ends; the answer is read.
        transport.answer("GET /sessions/s-1/interactions", 200, """{"pending":[]}""")
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":false,"paused":null,"queue":[]}""")
        transport.answer("GET /sessions/s-1/history", 200, """{"exchanges":[{"user":"Clear out the old drafts","assistant":"I left them.","complete":true}],"older":null}""")
        chat.check()
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertEquals("I left them.", chat.state.value.messages.last().text)
    }

    @Test
    fun `what the computer no longer lists is closed, what it still lists stays answerable`() = runTest {
        store.pointer = ChatPointer("s-1", "/home/owner/brain", "brain", memory = true)
        val chat = chat()
        chat.restore()
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":true,"paused":null,"queue":[]}""")
        transport.answer("GET /sessions/s-1/history", 200, """{"exchanges":[],"older":null}""")
        // The computer's clock is behind the phone's: by the wall clocks the time would be over.
        transport.answer("GET /sessions/s-1/interactions", 200, """{"pending":[$deletion]}""")
        chat.load()
        advanceUntilIdle()
        var asked = chat.state.value.interactions.single()
        assertTrue(asked.open(uptime))

        uptime += 10_000
        chat.check()
        asked = chat.state.value.interactions.single()
        assertTrue(asked.open(uptime))

        // Answered on another device, or timed out there.
        transport.answer("GET /sessions/s-1/interactions", 200, """{"pending":[]}""")
        chat.check()
        assertEquals(Interaction.Status.GONE, chat.state.value.interactions.single().status)
    }

    @Test
    fun `what cannot be shown is not shown`() {
        fun of(text: String) = Interaction.of(BridgeJson.decodeFromString(InteractionData.serializer(), text), uptime, wall, fresh = true)
        assertNull(of("""{"id":"x1","kind":"approval","command":" "}"""))
        assertNull(of("""{"id":"x1","kind":"question","questions":[]}"""))
        assertNull(of("""{"id":"x1","kind":"something_new","command":"ls"}"""))
        assertNull(of("""{"id":"../x","kind":"approval","command":"ls"}"""))
        // Without times the computer's default applies; an unknown kind of action is still an approval.
        val plain = checkNotNull(of("""{"id":"x1","kind":"approval","command":"make install","category":"later_kind"}"""))
        assertEquals(uptime + 600_000, plain.deadline)
        assertFalse(plain.isDeletion)
        assertEquals("", plain.folder)
    }

    @Test
    fun `locking the app closes the cards on the phone only`() = runTest {
        val chat = chat()
        chat.newChat(memory)
        chat.setCapable(true)
        runThatAsks(deletion, CompletableDeferred())
        chat.send("Clear out the old drafts")
        runCurrent()
        assertEquals(1, chat.state.value.interactions.size)
        chat.onLocked()
        assertTrue(chat.state.value.interactions.isEmpty())
        // Nothing was answered on the owner's behalf, and changes must be allowed again.
        assertTrue(posts().isEmpty())
        assertFalse(chat.state.value.capable)
    }
}
