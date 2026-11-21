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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Following a run live, send now for a line on hold, and file cards after a re-read (#154). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatLiveTest {
    private val transport = FakeTransport()
    private val api = Api(transport)
    private val store = object : ChatStore {
        var pointer: ChatPointer? = ChatPointer("s-1", "/home/owner/brain", "brain", memory = true)
        override fun load(): ChatPointer? = pointer
        override fun save(pointer: ChatPointer?) {
            this.pointer = pointer
        }
    }
    private var uptime = 50_000L
    private val wall = 1_900_000_000_000L

    private fun TestScope.chat() = Chat(api, store, this, { wall }, { true }, { uptime })

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun event(name: String, data: String) = AnswerEvent.Stream(name, json(data))
    private fun done(status: Int = 200, body: String? = null) = AnswerEvent.Done(RelayResponse(status, body?.let(::json)))
    private fun prompt(call: FakeTransport.Call) = ((call.body as JsonObject)["prompt"] as JsonPrimitive).content

    private fun history(vararg exchanges: Triple<String, String, Boolean>) {
        val items = exchanges.joinToString(",") { (user, assistant, complete) -> """{"user":"$user","assistant":${JsonPrimitive(assistant)},"complete":$complete}""" }
        transport.answer("GET /sessions/s-1/history", 200, """{"exchanges":[$items],"older":null}""")
    }

    private fun queue(running: Boolean, paused: String? = null, vararg waiting: Pair<String, String>) {
        val items = waiting.joinToString(",") { (id, text) -> """{"id":"$id","prompt":"$text"}""" }
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":$running,"paused":${paused?.let { "\"$it\"" }},"queue":[$items],"last":null}""")
    }

    private fun texts(chat: Chat) = chat.state.value.messages.map { (if (it.fromOwner) "me:" else "it:") + it.text }
    private fun gets(suffix: String) = transport.calls.count { it.method == "GET" && it.path.endsWith(suffix) }

    /** A followed run: the first event says that it can be followed, then [events]. */
    private fun live(queueId: String? = null, events: suspend kotlinx.coroutines.flow.FlowCollector<AnswerEvent>.() -> Unit) {
        transport.live = {
            flow {
                emit(event("live", """{"session_id":"s-1","running":true,"followable":true,"queue_id":${queueId?.let { "\"$it\"" }}}"""))
                events()
                emit(done())
            }
        }
    }

    private suspend fun TestScope.opened(): Chat {
        val chat = chat()
        chat.restore()
        chat.load()
        advanceUntilIdle()
        return chat
    }

    /** The app's own run, answered until [gate] opens; further messages are put in line by the computer. */
    private fun ownRun(gate: CompletableDeferred<Unit>) {
        var first = true
        transport.events = { call ->
            if (first) {
                first = false
                flow {
                    emit(event("session", """{"session_id":"s-1"}"""))
                    emit(event("delta", """{"text":"Working"}"""))
                    gate.await()
                    emit(event("done", """{"answer":"Finished","session_id":"s-1"}"""))
                    emit(done())
                }
            } else {
                flow { emit(done(202, """{"queued":true,"queue_id":"q-${prompt(call)}","position":0}""")) }
            }
        }
    }

    @Test
    fun `the answer to a waiting message is followed as it is written`() = runTest {
        history(Triple("Earlier", "Answer", true))
        queue(false)
        val chat = opened()
        val gate = CompletableDeferred<Unit>()
        ownRun(gate)
        chat.send("Start")
        runCurrent()
        chat.send("Later")
        runCurrent()
        assertEquals(listOf("q-Later"), chat.state.value.queue.map { it.id })

        // The run ends; the computer takes the waiting message out of its line and sends it by itself.
        queue(true)
        val written = CompletableDeferred<Unit>()
        live("q-Later") {
            emit(event("delta", """{"text":"Hello"}"""))
            emit(event("keepalive", "{}"))
            written.await()
            emit(event("delta", """{"text":" there"}"""))
            emit(event("done", """{"answer":"Hello there.","session_id":"s-1"}"""))
        }
        val read = gets("/history")
        gate.complete(Unit)
        runCurrent()
        // It is part of the conversation while its answer arrives, no longer in the line.
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Start", "it:Finished", "me:Later", "it:Hello"), texts(chat))
        assertEquals(ChatPhase.ANSWERING, chat.state.value.phase)
        assertTrue(chat.state.value.queue.isEmpty())
        assertTrue(chat.state.value.canStop)

        written.complete(Unit)
        advanceUntilIdle()
        assertEquals("it:Hello there.", texts(chat).last())
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        // Nothing was asked for again: the thread was not read anew, and following sent nothing.
        assertEquals(read, gets("/history"))
        assertEquals("GET", transport.calls.last { it.path.endsWith("/live") }.method)
        assertNull(transport.calls.last { it.path.endsWith("/live") }.body)
    }

    @Test
    fun `an answer that was stopped for a waiting message keeps what was written of it`() = runTest {
        history(Triple("Earlier", "Answer", true))
        queue(false)
        val chat = opened()
        val stopped = CompletableDeferred<Unit>()
        var first = true
        transport.events = { call ->
            if (first) {
                first = false
                flow {
                    emit(event("session", """{"session_id":"s-1"}"""))
                    emit(event("delta", """{"text":"One two"}"""))
                    stopped.await()
                    emit(event("error", """{"error":"cancelled"}"""))
                    emit(done())
                }
            } else {
                flow { emit(done(202, """{"queued":true,"queue_id":"q-${prompt(call)}","position":0}""")) }
            }
        }
        chat.send("Count")
        runCurrent()
        chat.send("Urgent")
        runCurrent()
        transport.answers["POST /sessions/s-1/queue/q-Urgent/send-now"] = {
            stopped.complete(Unit)
            RelayResponse(200, json("""{"promoted":true,"queue_id":"q-Urgent","interrupted_current_run":true,"queue":[{"id":"q-Urgent","prompt":"Urgent"}]}"""))
        }
        queue(true)
        live("q-Urgent") { emit(event("done", """{"answer":"Right away.","session_id":"s-1"}""")) }
        chat.sendNow(chat.state.value.queue.single().key)
        advanceUntilIdle()
        // The interrupted answer is still there, marked as unfinished; the urgent message follows it.
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Count", "it:One two", "me:Urgent", "it:Right away."), texts(chat))
        assertTrue(chat.state.value.messages[3].incomplete)
        assertFalse(chat.state.value.stopping)
        assertNull(chat.state.value.failure)
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
    }

    @Test
    fun `when an answer was lost and the next message is already being answered, the thread is read at the end`() = runTest {
        history(Triple("Earlier", "Answer", true))
        queue(false)
        val chat = opened()
        val lost = CompletableDeferred<Unit>()
        var first = true
        transport.events = { call ->
            if (first) {
                first = false
                flow {
                    emit(event("session", """{"session_id":"s-1"}"""))
                    emit(event("delta", """{"text":"One"}"""))
                    lost.await()
                    throw ApiException(ApiException.Kind.UNCERTAIN)
                }
            } else {
                flow { emit(done(202, """{"queued":true,"queue_id":"q-${prompt(call)}","position":0}""")) }
            }
        }
        chat.send("Count")
        runCurrent()
        chat.send("Later")
        runCurrent()
        // The first answer stops arriving; by then the computer has finished it and sends the waiting message.
        queue(true)
        val going = CompletableDeferred<Unit>()
        live("q-Later") {
            emit(event("delta", """{"text":"Second"}"""))
            going.await()
            emit(event("done", """{"answer":"Second answer.","session_id":"s-1"}"""))
        }
        lost.complete(Unit)
        runCurrent()
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Count", "it:One", "me:Later", "it:Second"), texts(chat))

        // Only the computer's conversation has the whole first answer: it is read when the second is done.
        history(Triple("Earlier", "Answer", true), Triple("Count", "One two three", true), Triple("Later", "Second answer.", true))
        queue(false)
        going.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Count", "it:One two three", "me:Later", "it:Second answer."), texts(chat))
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertNull(chat.state.value.failure)
    }

    @Test
    fun `a run that is in progress when the conversation is opened is attached to, with its cards`() = runTest {
        history(Triple("Earlier", "Answer", true), Triple("Count", "1 2", false))
        queue(true)
        val expires = Instant.ofEpochMilli(wall + 240_000).toString()
        val gate = CompletableDeferred<Unit>()
        live {
            // First what the run said so far, in one piece; then a card that was made a while ago.
            emit(event("replay_truncated", "{}"))
            emit(event("delta", """{"text":"1 2 3"}"""))
            emit(event("interaction", """{"id":"a1","session_id":"s-1","kind":"approval","command":"rm x","folder":"/home/owner/brain","category":"deletion","created_at":"2027-01-15T08:00:00Z","expires_at":"$expires"}"""))
            gate.await()
            emit(event("interaction_closed", """{"id":"a1","reason":"answered"}"""))
            emit(event("done", """{"answer":"1 2 3 4","session_id":"s-1"}"""))
        }
        val chat = opened()
        // The unfinished answer read from the conversation gave way to the stream: it is there once.
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Count", "it:1 2 3"), texts(chat))
        assertEquals(ChatPhase.ANSWERING, chat.state.value.phase)
        val card = chat.state.value.interactions.single()
        // Its time is what is left by its end time, not a whole new wait.
        assertEquals(uptime + 240_000, card.deadline)

        val read = gets("/history")
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Count", "it:1 2 3 4"), texts(chat))
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertEquals(Interaction.Status.GONE, chat.state.value.interactions.single().status)
        assertEquals(read, gets("/history"))
    }

    @Test
    fun `an answer the app stopped hearing is picked up again without doubling what was written`() = runTest {
        history(Triple("Earlier", "Answer", true))
        queue(false)
        val chat = opened()
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"s-1"}"""))
                emit(event("delta", """{"text":"Partial"}"""))
                throw ApiException(ApiException.Kind.UNCERTAIN)
            }
        }
        queue(true)
        live {
            emit(event("delta", """{"text":"Partial answer"}"""))
            emit(event("done", """{"answer":"Partial answer, finished.","session_id":"s-1"}"""))
        }
        chat.send("Count")
        advanceUntilIdle()
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Count", "it:Partial answer, finished."), texts(chat))
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertNull(chat.state.value.failure)
        assertFalse(chat.state.value.messages.last().incomplete)
    }

    @Test
    fun `a run that cannot be followed is asked about as before`() = runTest {
        history(Triple("Count", "", false))
        queue(true)
        transport.live = { flow { emit(event("live", """{"session_id":"s-1","running":true,"followable":false,"queue_id":null}""")); emit(done()) } }
        val chat = opened()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)
        assertEquals(1, gets("/live"))

        // Not reached at all: the same.
        transport.live = { flow { throw ApiException(ApiException.Kind.NOT_SENT) } }
        chat.check()
        advanceUntilIdle()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)

        history(Triple("Count", "1 2 3", true))
        queue(false)
        chat.check()
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertEquals(listOf("me:Count", "it:1 2 3"), texts(chat))
    }

    @Test
    fun `a followed answer that stops arriving is asked about, and read when the run is over`() = runTest {
        history(Triple("Count", "", false))
        queue(true)
        live {
            emit(event("delta", """{"text":"One"}"""))
            throw ApiException(ApiException.Kind.UNCERTAIN)
        }
        val chat = chat()
        chat.restore()
        chat.load()
        runCurrent()
        // Unfinished, and the app is asking again.
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)
        assertEquals(listOf("me:Count", "it:One"), texts(chat))

        history(Triple("Count", "One two", true))
        queue(false)
        advanceUntilIdle()
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertEquals(listOf("me:Count", "it:One two"), texts(chat))
    }

    @Test
    fun `send now lets a line that is on hold go on with that message`() = runTest {
        history(Triple("Earlier", "Answer", true))
        queue(false, "cancel", "q-A" to "First", "q-B" to "Second")
        val chat = opened()
        assertEquals(Chat.HOLD_CANCEL, chat.state.value.queueHold)
        val second = chat.state.value.queue.last()

        transport.answer("POST /sessions/s-1/queue/q-B/send-now", 200, """{"promoted":true,"queue_id":"q-B","interrupted_current_run":false,"queue":[{"id":"q-B","prompt":"Second"},{"id":"q-A","prompt":"First"}]}""")
        queue(true, null, "q-A" to "First")
        live("q-B") { emit(event("done", """{"answer":"Second answered.","session_id":"s-1"}""")) }
        // Afterwards the rest of the line is the computer's: its next run has not begun yet.
        val once = transport.live
        var asked = 0
        transport.live = { call -> if (asked++ == 0) once(call) else flow { } }
        chat.sendNow(second.key)
        runCurrent()
        // Nothing ran, so nothing is "stopping"; the line moves again, that message first.
        assertFalse(chat.state.value.stopping)
        assertNull(chat.state.value.queueHold)
        assertEquals(listOf("q-B", "q-A"), chat.state.value.queue.map { it.id })
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)

        advanceUntilIdle()
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Second", "it:Second answered."), texts(chat))
        assertEquals(listOf("q-A"), chat.state.value.queue.map { it.id })
    }

    @Test
    fun `send now for a message the computer has just sent is no failure`() = runTest {
        history(Triple("Earlier", "Answer", true))
        queue(false)
        val chat = opened()
        val gate = CompletableDeferred<Unit>()
        ownRun(gate)
        chat.send("Start")
        runCurrent()
        chat.send("Later")
        runCurrent()
        transport.answer("POST /sessions/s-1/queue/q-Later/send-now", 404, """{"error":"not_queued"}""")
        queue(true)
        chat.sendNow(chat.state.value.queue.single().key)
        assertTrue(chat.state.value.stopping)
        runCurrent()
        assertNull(chat.state.value.failure)
        assertFalse(chat.state.value.stopping)
        assertTrue(chat.state.value.queue.isEmpty())

        gate.complete(Unit)
    }

    @Test
    fun `send now that does not get through leaves the message in the line`() = runTest {
        history(Triple("Earlier", "Answer", true))
        queue(false)
        val chat = opened()
        val gate = CompletableDeferred<Unit>()
        ownRun(gate)
        chat.send("Start")
        runCurrent()
        chat.send("Later")
        runCurrent()
        transport.fail("POST /sessions/s-1/queue/q-Later/send-now", ApiException(ApiException.Kind.NOT_SENT))
        chat.sendNow(chat.state.value.queue.single().key)
        runCurrent()
        assertEquals(ChatProblem.QUEUE_FAILED, chat.state.value.failure?.problem)
        assertFalse(chat.state.value.stopping)
        assertEquals(listOf("q-Later" to false), chat.state.value.queue.map { it.id to it.busy })
        assertEquals(ChatPhase.ANSWERING, chat.state.value.phase)
        gate.complete(Unit)
    }

    @Test
    fun `a file card read again keeps its download for the time that is left`() = runTest {
        val marker = "\n[[file: notes/outline.md]]"
        history(
            Triple("Old one", "Here.$marker", true),
            Triple("Again", "Here it is.$marker\n[[file: data/table.csv]]", true),
        )
        queue(false)
        // Two offers of the same name: the later one belongs to the later answer. One file has none any more.
        val soon = Instant.ofEpochMilli(wall + 3 * 60_000).toString()
        val later = Instant.ofEpochMilli(wall + 10 * 60_000).toString()
        transport.answer(
            "GET /sessions/s-1/files",
            200,
            """{"files":[{"offer":"o-old","name":"outline.md","type":"text/markdown","size":20,"expires_at":"$soon"},{"offer":"o-new","name":"outline.md","type":"text/markdown","size":24,"expires_at":"$later"},{"offer":"o-other","name":"unrelated.txt","type":"text/plain","size":1,"expires_at":"$later"}]}""",
        )
        val chat = opened()
        val (first, second) = chat.state.value.messages.filter { !it.fromOwner }
        assertEquals("o-old", first.files.single().offer?.offer)
        assertEquals(listOf("o-new", null), second.files.map { it.offer?.offer })
        val restored = second.files.first()
        assertEquals("s-1", restored.offer?.sessionId)
        assertEquals(24L, restored.offer?.size)
        // Ten minutes are left on the computer: the card offers the download for nine, not for a new quarter of an hour.
        assertTrue(restored.open(uptime + 8 * 60_000))
        assertFalse(restored.open(uptime + 9 * 60_000 + 1))
        assertTrue(first.files.single().open(uptime + 60_000))
        assertFalse(first.files.single().open(uptime + 2 * 60_000 + 1))

        // A thread without such cards asks for nothing.
        transport.calls.clear()
        history(Triple("Plain", "Text", true))
        chat.load()
        advanceUntilIdle()
        assertEquals(0, gets("/files"))
    }

    @Test
    fun `the requests of the queue and of one session`() = runTest {
        transport.answer("POST /sessions/s-1/queue/q-1/send-now", 200, """{"promoted":true,"queue_id":"q-1","interrupted_current_run":true,"queue":[{"id":"q-1","prompt":"A"}]}""")
        val sent = api.sendQueuedNow("s-1", "q-1")
        assertTrue(sent.promoted && sent.interrupted)
        assertEquals(listOf("q-1"), sent.queue.map { it.id })
        transport.answer("POST /sessions/s-1/queue/resume", 200, """{"resumed":true,"queue":[]}""")
        api.resumeQueue("s-1")
        transport.answer("GET /sessions/s-1", 200, """{"id":"s-1","title":"T","folder":"/home/owner/brain","model":"opus","effort":null,"skills":true,"notify_replies":false,"running_here":true,"open_elsewhere":false,"followable":true,"queue":[{"id":"q-1","prompt":"A"}],"queue_paused":"cancel"}""")
        val session = api.session("s-1")
        assertTrue(session.skills && session.followable)
        assertFalse(session.notifyReplies)
        assertEquals("cancel", session.queuePaused)
        assertEquals(listOf("A"), session.queue.map { it.prompt })
        val events: Flow<AnswerEvent> = api.live("s-1")
        events.collect { }
        assertEquals("GET /sessions/s-1/live", transport.calls.last().let { it.method + " " + it.path })
    }
}
