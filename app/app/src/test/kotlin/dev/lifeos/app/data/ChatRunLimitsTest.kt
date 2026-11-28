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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs that are not cut short (#199) and the change permission that stays with a chat (#200):
 * what a run is doing is shown while it works, a run that paused at a limit goes on with
 * "Continue" instead of starting again, and "may change files" is stored with the chat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRunLimitsTest {
    private val transport = FakeTransport()
    private val api = Api(transport)
    private val store = object : ChatStore {
        var pointer: ChatPointer? = null
        override fun load(): ChatPointer? = pointer
        override fun save(pointer: ChatPointer?) {
            this.pointer = pointer
        }
    }
    private val newId = "00000000-0000-4000-8000-000000000001"
    private val oldId = "11111111-1111-4111-8111-111111111111"

    private fun TestScope.chat() = Chat(api, store, this, { 1_900_000_000_000L }, { true }, { 50_000L }, newId = { newId })

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun event(name: String, data: String) = AnswerEvent.Stream(name, json(data))
    private fun done() = AnswerEvent.Done(RelayResponse(200, null))

    private fun answers(vararg events: AnswerEvent) {
        transport.events = { flow { events.forEach { emit(it) } } }
    }

    private fun sent() = transport.calls.last { it.method == "POST" && (it.path == "/ask" || it.path.endsWith("/messages")) }
    private fun patches() = transport.calls.filter { it.method == "PATCH" && it.path == "/sessions/$oldId" }

    private fun stored(id: String, capable: Boolean = false) {
        transport.answer("GET /sessions/$id/queue", 200, """{"running":false,"paused":null,"queue":[]}""")
        transport.answer("GET /sessions/$id/history", 200, """{"exchanges":[{"user":"Earlier","assistant":"Yes.","complete":true}],"older":null}""")
        transport.answer("GET /sessions/$id", 200, """{"id":"$id","folder":"/home/owner/work","folder_key":"/home/owner/work","folder_label":"work","kind":"project","colour":1,"capable":$capable}""")
        transport.answer("PATCH /sessions/$id", 200, """{"id":"$id","capable":true}""")
    }

    @Test
    fun `what the run is doing is shown while it works and gone when it ends`() = runTest {
        val chat = chat()
        val seen = mutableListOf<RunProgress?>()
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"$newId"}"""))
                emit(event("progress", """{"tool":"Edit","detail":"app.py","steps":3}"""))
                seen += chat.state.value.progress
                emit(event("progress", """{"tool":"Bash","detail":"Run the tests","steps":4,"later":"ignored"}"""))
                seen += chat.state.value.progress
                emit(event("done", """{"answer":"Done.","session_id":"$newId"}"""))
                emit(done())
            }
        }
        chat.send("Fix it")
        advanceUntilIdle()
        assertEquals(listOf(RunProgress("Edit", "app.py", 3), RunProgress("Bash", "Run the tests", 4)), seen)
        assertNull(chat.state.value.progress)
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
    }

    @Test
    fun `a run that paused at the step limit goes on with Continue, not with the same message again`() = runTest {
        val chat = chat()
        answers(event("session", """{"session_id":"$newId"}"""), event("error", """{"error":"error_max_turns","message":"turn limit"}"""), done())
        chat.send("Do the long work")
        advanceUntilIdle()
        val failure = checkNotNull(chat.state.value.failure)
        assertEquals(ChatProblem.STEP_LIMIT, failure.problem)
        assertEquals(ChatProblem.STEP_LIMIT, Chat.problemOf("error_max_turns"))

        answers(event("done", """{"answer":"Finished.","session_id":"$newId"}"""), done())
        chat.continueRun("Continue where you stopped.")
        advanceUntilIdle()
        assertNull(chat.state.value.failure)
        assertEquals(JsonPrimitive("Continue where you stopped."), (sent().body as JsonObject)["prompt"])
        assertTrue(sent().path.endsWith("/sessions/$newId/messages"))
        // Only a paused run is continued: any other failure keeps its own buttons.
        val before = transport.calls.size
        chat.continueRun("Continue where you stopped.")
        advanceUntilIdle()
        assertEquals(before, transport.calls.size)
    }

    @Test
    fun `may change files is stored with the chat when the owner switches it`() = runTest {
        val chat = chat()
        stored(oldId)
        chat.open(SessionSummary(oldId, "Parser"))
        advanceUntilIdle()
        assertFalse(chat.state.value.capable)
        chat.setCapable(true)
        advanceUntilIdle()
        assertTrue(chat.state.value.capable)
        assertEquals(JsonPrimitive(true), (patches().single().body as JsonObject)["capable"])
        // The same value again asks nothing. After a lock the choice comes back from the
        // computer with the conversation: nothing of it was kept in memory.
        chat.setCapable(true)
        stored(oldId, capable = true)
        chat.onLocked()
        assertFalse(chat.state.value.capable)
        chat.load()
        advanceUntilIdle()
        assertEquals(1, patches().size)
        assertTrue(chat.state.value.capable)
        // When the computer cannot store it, the switch goes back and says so.
        transport.answer("PATCH /sessions/$oldId", 500, """{"error":"failed"}""")
        chat.setCapable(false)
        advanceUntilIdle()
        assertTrue(chat.state.value.capable)
        assertEquals(ChatProblem.SETTING_FAILED, chat.state.value.failure?.problem)
    }

    @Test
    fun `a chat opened again comes back with the permission the owner gave it`() = runTest {
        val chat = chat()
        stored(oldId, capable = true)
        // From the list: the session says it.
        chat.open(SessionSummary(oldId, "Parser", capable = true))
        advanceUntilIdle()
        assertTrue(chat.state.value.capable)
        answers(event("done", """{"answer":"On it.","session_id":"$oldId"}"""), done())
        chat.send("Go on")
        advanceUntilIdle()
        assertEquals(JsonPrimitive("capable"), (sent().body as JsonObject)["mode"])

        // After a restart of the app: the pointer knows only the id, the computer the rest.
        val restarted = chat()
        store.pointer = ChatPointer(oldId, "/home/owner/work", "work")
        restarted.restore()
        assertFalse(restarted.state.value.capable)
        restarted.load()
        advanceUntilIdle()
        assertTrue(restarted.state.value.capable)
        assertTrue(patches().isEmpty())
    }

    @Test
    fun `a new conversation keeps the switch local until its first message`() = runTest {
        val chat = chat()
        chat.newChat(Folder("/home/owner/work", "work"))
        chat.setCapable(true)
        advanceUntilIdle()
        assertTrue(transport.calls.none { it.method == "PATCH" })
        answers(event("session", """{"session_id":"$newId"}"""), event("done", """{"answer":"Done.","session_id":"$newId"}"""), done())
        chat.send("Rename the module")
        advanceUntilIdle()
        assertEquals(JsonPrimitive("capable"), (sent().body as JsonObject)["mode"])
    }
}
