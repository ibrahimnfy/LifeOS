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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Earlier messages on demand, the queue while a run is in progress, send now and stop (#94). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatQueueTest {
    private val transport = FakeTransport()
    private val api = Api(transport)
    private val store = object : ChatStore {
        var pointer: ChatPointer? = ChatPointer("s-1", "/home/owner/brain", "brain", memory = true)
        override fun load(): ChatPointer? = pointer
        override fun save(pointer: ChatPointer?) {
            this.pointer = pointer
        }
    }

    private fun TestScope.chat() = Chat(api, store, this, { 1_000L + testScheduler.currentTime })

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun event(name: String, data: String) = AnswerEvent.Stream(name, json(data))
    private fun done(status: Int = 200, body: String? = null) = AnswerEvent.Done(RelayResponse(status, body?.let(::json)))
    private fun prompt(call: FakeTransport.Call) = ((call.body as JsonObject)["prompt"] as JsonPrimitive).content

    private fun history(vararg exchanges: Pair<String, String>, older: String? = null, key: String = "GET /sessions/s-1/history") {
        val items = exchanges.joinToString(",") { (user, assistant) -> """{"user":"$user","assistant":"$assistant","complete":true}""" }
        transport.answer(key, 200, """{"exchanges":[$items],"older":${older?.let { "\"$it\"" }}}""")
    }

    private fun queue(running: Boolean, paused: String? = null, vararg waiting: Pair<String, String>) {
        val items = waiting.joinToString(",") { (id, text) -> """{"id":"$id","prompt":"$text","mode":"read_only","queued_at":"2027-01-15T08:00:00Z"}""" }
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":$running,"paused":${paused?.let { "\"$it\"" }},"queue":[$items],"last":null}""")
    }

    private fun texts(chat: Chat) = chat.state.value.messages.map { (if (it.fromOwner) "me:" else "it:") + it.text }
    private fun waiting(chat: Chat) = chat.state.value.queue.map { it.id to it.prompt }
    private fun calls(method: String) = transport.calls.filter { it.method == method }.map { it.path }

    /** An open conversation with one earlier exchange. */
    private suspend fun TestScope.opened(older: String? = null): Chat {
        val chat = chat()
        chat.restore()
        history("Earlier" to "Answer", older = older)
        queue(false)
        chat.load()
        advanceUntilIdle()
        return chat
    }

    /**
     * A run that is being answered until [gate] opens; every further message while it runs is
     * put in line by the computer ([queued] maps a prompt to the answer of its request).
     */
    private fun runningUntil(
        gate: CompletableDeferred<String>,
        queued: (FakeTransport.Call) -> AnswerEvent = { done(202, """{"queued":true,"queue_id":"q-${prompt(it).replace(' ', '-')}","position":0}""") },
    ) {
        var first = true
        transport.events = { call ->
            if (first) {
                first = false
                flow {
                    emit(event("session", """{"session_id":"s-1"}"""))
                    emit(event("delta", """{"text":"Working"}"""))
                    val end = gate.await()
                    if (end == "done") emit(event("done", """{"answer":"Finished","session_id":"s-1"}""")) else emit(event("error", """{"error":"$end"}"""))
                    emit(done())
                }
            } else {
                flow { emit(queued(call)) }
            }
        }
    }

    // ------------------------------------------------------------------ earlier messages

    @Test
    fun `earlier messages are read one page at a time and put on top`() = runTest {
        val chat = opened(older = "p200")
        assertTrue(chat.state.value.olderExist)
        // The fake answers every history request the same way; the page before is told apart by its query.
        transport.answers["GET /sessions/s-1/history"] = { call ->
            assertEquals(mapOf("before" to "p200"), call.query)
            RelayResponse(200, json("""{"exchanges":[{"user":"First","assistant":"One","complete":true},{"user":"Second","assistant":"Two","complete":true}],"older":null}"""))
        }
        chat.loadOlder()
        assertTrue(chat.state.value.loadingOlder)
        advanceUntilIdle()
        val state = chat.state.value
        assertEquals(listOf("me:First", "it:One", "me:Second", "it:Two", "me:Earlier", "it:Answer"), texts(chat))
        assertFalse(state.olderExist)
        assertFalse(state.loadingOlder)
        assertEquals(1, state.olderLoads)
        // Nothing further back: asking again asks nothing.
        val asked = transport.calls.size
        chat.loadOlder()
        advanceUntilIdle()
        assertEquals(asked, transport.calls.size)
    }

    @Test
    fun `a page that cannot be read leaves the thread as it is`() = runTest {
        val chat = opened(older = "p200")
        transport.fail("GET /sessions/s-1/history", ApiException(ApiException.Kind.UNCERTAIN))
        chat.loadOlder()
        advanceUntilIdle()
        assertEquals(listOf("me:Earlier", "it:Answer"), texts(chat))
        // It can be asked for again.
        assertTrue(chat.state.value.olderExist)
        assertFalse(chat.state.value.loadingOlder)

        // A cursor the computer no longer knows ends the way back.
        transport.answer("GET /sessions/s-1/history", 400, """{"error":"bad_request"}""")
        chat.loadOlder()
        advanceUntilIdle()
        assertFalse(chat.state.value.olderExist)
    }

    // ------------------------------------------------------------------ the queue

    @Test
    fun `a message written during a run waits in line and is answered after it`() = runTest {
        val chat = opened()
        val gate = CompletableDeferred<String>()
        runningUntil(gate)
        chat.send("Start")
        runCurrent()
        assertEquals(ChatPhase.ANSWERING, chat.state.value.phase)
        assertFalse(chat.state.value.canSend)
        assertTrue(chat.state.value.canQueue)

        assertTrue(chat.send("And then this"))
        // Shown at once, without an id while it is on its way.
        assertEquals(listOf("" to "And then this"), waiting(chat))
        assertTrue(chat.state.value.queue.single().busy)
        runCurrent()
        assertEquals(listOf("q-And-then-this" to "And then this"), waiting(chat))
        assertFalse(chat.state.value.queue.single().busy)
        // It is not part of the thread yet, and the request was the same kind as any message.
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Start", "it:Working"), texts(chat))
        assertEquals("/sessions/s-1/messages", transport.calls.last().path)

        // The run ends: the computer goes on with the waiting message, which is not received here.
        queue(true)
        gate.complete("done")
        advanceUntilIdle()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)
        assertTrue(chat.state.value.queue.isEmpty())
        assertEquals("it:Finished", texts(chat).last())

        queue(false)
        history("Start" to "Finished", "And then this" to "Done too")
        chat.check()
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertEquals(listOf("me:Start", "it:Finished", "me:And then this", "it:Done too"), texts(chat))
    }

    @Test
    fun `a message for the line that is answered at once becomes the current message`() = runTest {
        // The app believes a run is going on (it is not receiving it); in fact it has just ended.
        val chat = chat()
        chat.restore()
        history("Earlier" to "Answer")
        queue(true)
        chat.load()
        advanceUntilIdle()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)

        val gate = CompletableDeferred<Unit>()
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"s-1"}"""))
                gate.await()
                emit(event("done", """{"answer":"Right away","session_id":"s-1"}"""))
                emit(done())
            }
        }
        queue(false)
        chat.send("Next")
        runCurrent()
        assertTrue(chat.state.value.queue.isEmpty())
        assertEquals(ChatPhase.SENDING, chat.state.value.phase)
        assertEquals("me:Next", texts(chat).last())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Next", "it:Right away"), texts(chat))
    }

    @Test
    fun `a waiting message can be changed and removed until it is sent`() = runTest {
        val chat = opened()
        val gate = CompletableDeferred<String>()
        runningUntil(gate)
        chat.send("Start")
        runCurrent()
        chat.send("One")
        chat.send("Two")
        runCurrent()
        val (one, two) = chat.state.value.queue

        transport.answer("PATCH /sessions/s-1/queue/q-One", 200, """{"id":"q-One","prompt":"One, changed"}""")
        chat.editQueued(one.key, " One, changed ")
        runCurrent()
        assertEquals(json("""{"prompt":"One, changed"}"""), transport.calls.last().body)
        assertEquals(listOf("q-One" to "One, changed", "q-Two" to "Two"), waiting(chat))

        transport.answer("DELETE /sessions/s-1/queue/q-Two", 200, """{"removed":true}""")
        chat.removeQueued(two.key)
        runCurrent()
        assertEquals(listOf("q-One" to "One, changed"), waiting(chat))

        // Sent meanwhile: the computer says so, and its list is shown.
        transport.answer("DELETE /sessions/s-1/queue/q-One", 404, """{"error":"not_queued"}""")
        queue(true)
        chat.removeQueued(one.key)
        runCurrent()
        assertEquals(ChatProblem.QUEUE_FAILED, chat.state.value.failure?.problem)
        assertTrue(chat.state.value.queue.isEmpty())
        gate.complete("done")
        queue(false)
        history("Start" to "Finished")
        advanceUntilIdle()
    }

    @Test
    fun `a message that cannot be put in line returns to the composer`() = runTest {
        val chat = opened()
        val gate = CompletableDeferred<String>()
        runningUntil(gate) { done(429, """{"error":"queue_full","message":"at most 10 messages can wait"}""") }
        chat.send("Start")
        runCurrent()
        chat.send("One too many")
        runCurrent()
        assertTrue(chat.state.value.queue.isEmpty())
        assertEquals("One too many", chat.state.value.draft)
        assertEquals(ChatProblem.BUSY, chat.state.value.failure?.problem)
        // The answer that is being received is not touched by it.
        assertEquals(ChatPhase.ANSWERING, chat.state.value.phase)
        gate.complete("done")
        advanceUntilIdle()
        assertEquals("it:Finished", texts(chat).last())
    }

    @Test
    fun `at most ten messages wait and none before the session is known`() = runTest {
        val chat = chat()
        chat.newChat(Folder("/home/owner/brain", "brain", "memory", default = true))
        transport.events = { flow { CompletableDeferred<Unit>().await() } }
        chat.send("First of a new conversation")
        runCurrent()
        // The computer has not named the session yet: there is nothing to queue behind.
        assertFalse(chat.state.value.canQueue)
        assertFalse(chat.send("Too early"))
        assertFalse(chat.state.value.canStop)
        chat.onLocked()

        val full = ChatState(sessionId = "s-1", phase = ChatPhase.ANSWERING, queue = List(Chat.MAX_QUEUED) { QueuedMessage(it.toLong(), "q$it", "m") })
        assertFalse(full.canQueue)
        assertTrue(full.copy(queue = full.queue.drop(1)).canQueue)
    }

    // ------------------------------------------------------------------ send now

    @Test
    fun `send now interrupts the run and gives up the message's old place in the line`() = runTest {
        val chat = opened()
        val gate = CompletableDeferred<String>()
        runningUntil(gate) { call ->
            val now = "when" in (call.body as JsonObject)
            done(202, """{"queued":true,"queue_id":"${if (now) "q-front" else "q-" + prompt(call)}","position":${if (now) 1 else 0},"interrupted_current_run":$now}""")
        }
        chat.send("Start")
        runCurrent()
        chat.send("Later")
        chat.send("Urgent")
        runCurrent()
        val urgent = chat.state.value.queue.last()
        assertEquals("q-Urgent", urgent.id)

        transport.answer("DELETE /sessions/s-1/queue/q-Urgent", 200, """{"removed":true}""")
        queue(true, null, "q-front" to "Urgent", "q-Later" to "Later")
        chat.sendNow(urgent.key)
        assertTrue(chat.state.value.stopping)
        runCurrent()
        // The same message again, marked to go first; then its old entry is taken out.
        val sent = transport.calls.last { it.method == "POST" }
        assertEquals(json("""{"prompt":"Urgent","stream":true,"interactive":true,"when":"now"}"""), sent.body)
        assertEquals(listOf("/sessions/s-1/queue/q-Urgent"), calls("DELETE"))
        assertEquals(listOf("q-front" to "Urgent", "q-Later" to "Later"), waiting(chat))

        // The interrupted answer ends as "stopped": asked for, so no failure to retry.
        gate.complete("cancelled")
        queue(true, null, "q-Later" to "Later")
        advanceUntilIdle()
        val state = chat.state.value
        assertEquals(ChatProblem.STOPPED_HERE, state.failure?.problem)
        assertNull(state.failure?.prompt)
        assertFalse(state.stopping)
        assertTrue(state.messages.last().incomplete)
        // The computer goes on with the line.
        assertEquals(ChatPhase.ELSEWHERE, state.phase)
        assertEquals(listOf("q-Later" to "Later"), waiting(chat))
    }

    // ------------------------------------------------------------------ stop

    @Test
    fun `stopping a run keeps what was written and holds the waiting messages`() = runTest {
        val chat = opened()
        val gate = CompletableDeferred<String>()
        runningUntil(gate)
        chat.send("Start")
        runCurrent()
        chat.send("Later")
        runCurrent()
        assertTrue(chat.state.value.canStop)

        transport.answer("POST /sessions/s-1/cancel", 200, """{"cancelled":true,"queue_paused":true,"note":"x"}""")
        chat.cancel()
        assertTrue(chat.state.value.stopping)
        assertFalse(chat.state.value.canStop)
        runCurrent()
        assertEquals(listOf("/sessions/s-1/cancel"), calls("POST").filter { it.endsWith("/cancel") })
        gate.complete("cancelled")
        runCurrent()

        val state = chat.state.value
        assertEquals(ChatPhase.IDLE, state.phase)
        assertEquals(ChatProblem.STOPPED_HERE, state.failure?.problem)
        assertEquals(listOf("me:Earlier", "it:Answer", "me:Start", "it:Working"), texts(chat))
        // The line is kept and does not move by itself.
        assertEquals(listOf("q-Later" to "Later"), waiting(chat))
        assertEquals(Chat.HOLD_CANCEL, state.queueHold)
        advanceUntilIdle()
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)

        // "Send them": the computer answers only when the first one is done, so the app does not wait for it.
        val resumed = CompletableDeferred<Unit>()
        transport.answers["POST /sessions/s-1/queue/resume"] = {
            resumed.complete(Unit)
            throw ApiException(ApiException.Kind.UNCERTAIN)
        }
        queue(true)
        chat.resumeQueue()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)
        assertNull(chat.state.value.queueHold)
        runCurrent()
        assertTrue(resumed.isCompleted)
        advanceUntilIdle()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)
        assertTrue(chat.state.value.queue.isEmpty())
    }

    @Test
    fun `the owner's next message ends the hold a stop put on the line`() = runTest {
        val chat = chat()
        chat.restore()
        history("Earlier" to "Answer")
        queue(false, "cancel", "q-1" to "Later")
        chat.load()
        advanceUntilIdle()
        assertEquals(Chat.HOLD_CANCEL, chat.state.value.queueHold)
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)

        transport.events = { flow { emit(event("session", """{"session_id":"s-1"}""")); emit(event("done", """{"answer":"Ok"}""")) } }
        queue(true)
        chat.send("Go on")
        assertNull(chat.state.value.queueHold)
        advanceUntilIdle()
        // After its answer the waiting message follows on the computer.
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)

        // A hold after an error stays until the owner lets the line go on.
        val held = ChatState(queueHold = "error", queue = listOf(QueuedMessage(1, "q-1", "m")))
        assertEquals("error", held.queueHold)
    }

    @Test
    fun `a stop that does not get through says so and the run goes on`() = runTest {
        val chat = opened()
        val gate = CompletableDeferred<String>()
        runningUntil(gate)
        chat.send("Start")
        runCurrent()
        transport.fail("POST /sessions/s-1/cancel", ApiException(ApiException.Kind.NOT_SENT))
        chat.cancel()
        runCurrent()
        assertEquals(ChatProblem.STOP_FAILED, chat.state.value.failure?.problem)
        assertFalse(chat.state.value.stopping)
        assertEquals(ChatPhase.ANSWERING, chat.state.value.phase)
        gate.complete("done")
        advanceUntilIdle()
        assertEquals("it:Finished", texts(chat).last())
    }

    @Test
    fun `a stopped run whose end is not heard is asked about`() = runTest {
        val chat = opened()
        runningUntil(CompletableDeferred())
        chat.send("Start")
        runCurrent()
        transport.answer("POST /sessions/s-1/cancel", 200, """{"cancelled":true,"queue_paused":false}""")
        history("Start" to "Working")
        chat.cancel()
        advanceUntilIdle()
        // No "stopped" came through the answer: the computer's conversation is shown.
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        assertFalse(chat.state.value.stopping)
        assertEquals(listOf("me:Start", "it:Working"), texts(chat))
    }

    @Test
    fun `a run stopped from somewhere else is reported as such`() = runTest {
        val chat = opened()
        val gate = CompletableDeferred<String>()
        runningUntil(gate)
        chat.send("Start")
        runCurrent()
        gate.complete("cancelled")
        advanceUntilIdle()
        assertEquals(ChatProblem.STOPPED, chat.state.value.failure?.problem)
        assertEquals("Start", chat.state.value.failure?.prompt)
    }
}
