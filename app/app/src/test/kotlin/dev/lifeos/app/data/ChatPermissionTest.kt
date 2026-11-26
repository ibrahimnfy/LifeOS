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

/**
 * The change permission of chats opened by asking or from the command bar (#190): a new chat
 * takes over "may change files" only when the computer says so, a message the app sends by
 * itself is marked as relayed, and the bar's message waits in the composer when it cannot go.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPermissionTest {
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

    private fun TestScope.chat(reachable: Boolean = true) = Chat(api, store, this, { 1_900_000_000_000L }, { reachable }, { 50_000L }, newId = { newId })

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun event(name: String, data: String) = AnswerEvent.Stream(name, json(data))
    private fun done() = AnswerEvent.Done(RelayResponse(200, null))

    private fun answers(vararg events: AnswerEvent) {
        transport.events = { flow { events.forEach { emit(it) } } }
    }

    private fun sent() = transport.calls.last { it.method == "POST" && (it.path == "/ask" || it.path.endsWith("/messages")) }
    private fun body(call: FakeTransport.Call) = call.body as JsonObject

    private fun folder(kind: String, mode: String?) =
        """{"path":"/home/owner/work","label":"work","kind":"$kind","colour":1${mode?.let { ""","mode":"$it"""" } ?: ""}}"""

    private fun newSwitch(kind: String = "project", mode: String? = "capable") =
        """{"action":"new","sessions":[],"folder":${folder(kind, mode)},"forward":"Rename the module"}"""

    @Test
    fun `a new chat takes changes over only when the computer says so, and never for a course`() {
        val step = ChatSwitch.of(json(newSwitch()))?.step() as SwitchStep.New
        assertTrue(step.capable)
        assertFalse((ChatSwitch.of(json(newSwitch(mode = null)))?.step() as SwitchStep.New).capable)
        assertFalse((ChatSwitch.of(json(newSwitch(mode = "full")))?.step() as SwitchStep.New).capable)
        assertFalse((ChatSwitch.of(json(newSwitch(kind = FolderKinds.COURSE)))?.step() as SwitchStep.New).capable)
        // A card keeps it for the owner's pick; a followed or relayed answer hands nothing on.
        val card = checkNotNull(ChatSwitch.of(json("""{"action":"choose_folder","folders":[${folder("project", "capable")}],"forward":"x"}""")))
        val pick = card.picked(card.folders.single()).step() as SwitchStep.Pick
        assertTrue(pick.capable)
        assertTrue(pick.capableIn(SwitchFolder("/home/owner/work", "work", FolderKinds.PROJECT)))
        assertFalse(pick.capableIn(SwitchFolder("/home/owner/courses/ex", "EX101", FolderKinds.COURSE)))
        val bare = card.withoutCarry()
        assertNull(bare.forward)
        assertFalse((bare.picked(bare.folders.single()).step() as SwitchStep.Pick).capable)
        assertFalse((checkNotNull(ChatSwitch.of(json(newSwitch()))).withoutCarry().step() as SwitchStep.New).capable)
    }

    @Test
    fun `the carried request goes out as a relayed capable message, the owner's next one as their own`() = runTest {
        val chat = chat()
        answers(event("session", """{"session_id":"$newId"}"""), event("done", """{"answer":"Done.","session_id":"$newId"}"""), done())
        val origin = SwitchSession(oldId, folderLabel = "work", kind = FolderKinds.PROJECT)
        chat.startFrom(Folder("/home/owner/work", "work"), "Rename the module", origin, capable = true)
        assertTrue(chat.state.value.capable)
        advanceUntilIdle()
        val first = body(sent())
        assertEquals(JsonPrimitive("capable"), first["mode"])
        assertEquals(JsonPrimitive(true), first["relayed"])
        assertEquals(JsonPrimitive(oldId), first["continued_from"])
        chat.send("Now run the tests")
        advanceUntilIdle()
        val second = body(sent())
        assertEquals(JsonPrimitive("capable"), second["mode"])
        assertNull(second["relayed"])
        // Without the computer's word the new chat is read-only, as before.
        chat.startFrom(Folder("/home/owner/work", "work"), "Summarise it", origin)
        assertFalse(chat.state.value.capable)
        advanceUntilIdle()
        assertNull(body(sent())["mode"])
    }

    @Test
    fun `an answer to a relayed message hands no permission on`() = runTest {
        val chat = chat()
        answers(
            event("session", """{"session_id":"$newId"}"""),
            event("done", """{"answer":"Moving on.","session_id":"$newId","switch":${newSwitch()}}"""),
            done(),
        )
        chat.startFrom(Folder("/home/owner/work", "work"), "Rename the module", null, capable = true)
        advanceUntilIdle()
        assertNull(chat.state.value.switchTo)
        val card = checkNotNull(chat.state.value.choices)
        assertNull(card.forward)
        assertNull(card.folder?.mode)
    }

    private fun stored(id: String) {
        transport.answer("GET /sessions/$id/queue", 200, """{"running":false,"paused":null,"queue":[]}""")
        transport.answer("GET /sessions/$id/history", 200, """{"exchanges":[{"user":"Earlier","assistant":"Yes.","complete":true}],"older":null}""")
        transport.answer("GET /sessions/$id", 200, """{"id":"$id","folder":"/home/owner/work","folder_key":"/home/owner/work","folder_label":"work","kind":"project","colour":1}""")
    }

    @Test
    fun `the bar's message is sent into the chat once it is read, relayed and with the picked permission`() = runTest {
        val chat = chat()
        stored(oldId)
        answers(event("done", """{"answer":"On it.","session_id":"$oldId"}"""), done())
        chat.openWith(SessionSummary(oldId, "Parser"), memory = false, capable = true, text = "Fix the failing test", send = true)
        // Nothing leaves before the chat is read.
        assertTrue(transport.calls.none { it.path.endsWith("/messages") })
        advanceUntilIdle()
        val message = sent()
        assertEquals("/sessions/$oldId/messages", message.path)
        assertEquals(JsonPrimitive("Fix the failing test"), body(message)["prompt"])
        assertEquals(JsonPrimitive("capable"), body(message)["mode"])
        assertEquals(JsonPrimitive(true), body(message)["relayed"])
        assertEquals(1, transport.calls.count { it.path.endsWith("/messages") })
        assertNull(chat.state.value.draft)
    }

    @Test
    fun `a message the bar could not send waits in the composer, and the permission is still set`() = runTest {
        val chat = chat()
        stored(oldId)
        // The computer said the chat is busy: the text is only offered.
        chat.openWith(SessionSummary(oldId, "Parser"), memory = false, capable = false, text = "Fix the failing test", send = false)
        advanceUntilIdle()
        assertEquals("Fix the failing test", chat.state.value.draft)
        assertFalse(chat.state.value.capable)
        assertTrue(transport.calls.none { it.path.endsWith("/messages") })
        // Opening without a message only sets the permission.
        chat.openWith(SessionSummary(oldId, "Parser"), memory = false, capable = true, text = null, send = true)
        advanceUntilIdle()
        assertTrue(chat.state.value.capable)
        assertTrue(transport.calls.none { it.path.endsWith("/messages") })
    }

    @Test
    fun `a lock or another chat drops the bar's waiting message`() = runTest {
        val chat = chat()
        stored(oldId)
        chat.openWith(SessionSummary(oldId, "Parser"), memory = false, capable = true, text = "Fix the failing test", send = true)
        chat.onLocked()
        advanceUntilIdle()
        chat.load()
        advanceUntilIdle()
        assertTrue(transport.calls.none { it.path.endsWith("/messages") })
        assertFalse(chat.state.value.capable)
    }

    private fun posts() = transport.calls.count { it.method == "POST" }

    @Test
    fun `a relayed message is never put in line behind a running answer`() = runTest {
        val chat = chat()
        stored(oldId)
        chat.open(SessionSummary(oldId, "Parser"))
        advanceUntilIdle()
        // The owner's message is being answered: anything written now would wait in line.
        transport.events = { flow { emit(event("delta", """{"text":"Working"}""")); CompletableDeferred<Unit>().await() } }
        assertTrue(chat.send("Start"))
        runCurrent()
        assertTrue(chat.state.value.canQueue)
        val before = posts()
        assertFalse(chat.send("Fix the failing test", forwarded = true))
        runCurrent()
        assertEquals(before, posts())
        assertTrue(chat.state.value.queue.isEmpty())
        // The bar's message for this chat waits in the composer instead.
        chat.openWith(SessionSummary(oldId, "Parser"), memory = false, capable = true, text = "Fix the failing test", send = true)
        runCurrent()
        assertEquals(before, posts())
        assertEquals("Fix the failing test", chat.state.value.draft)
        // A message the owner writes does wait in line, as before.
        transport.events = { flow { emit(AnswerEvent.Done(RelayResponse(202, json("""{"queued":true,"queue_id":"q1","position":1,"interrupted_current_run":false}""")))) } }
        assertTrue(chat.send("One more thing"))
        runCurrent()
        assertEquals(before + 1, posts())
        assertNull(body(transport.calls.last { it.method == "POST" })["relayed"])
        chat.onLocked()
    }

    @Test
    fun `the bar's message for a chat the computer works on waits in the composer`() = runTest {
        val chat = chat()
        stored(oldId)
        transport.answer("GET /sessions/$oldId/queue", 200, """{"running":true,"paused":null,"queue":[]}""")
        chat.openWith(SessionSummary(oldId, "Parser"), memory = false, capable = true, text = "Fix the failing test", send = true)
        runCurrent()
        assertEquals(ChatPhase.ELSEWHERE, chat.state.value.phase)
        assertEquals("Fix the failing test", chat.state.value.draft)
        assertEquals(0, posts())
        chat.onLocked()
    }

    @Test
    fun `retry of a relayed message that did not arrive is still relayed`() = runTest {
        val chat = chat()
        transport.events = { flow { emit(AnswerEvent.Done(RelayResponse(503, json("""{"error":"busy","message":"x"}""")))) } }
        chat.startFrom(Folder("/home/owner/work", "work"), "Rename the module", null, capable = true)
        advanceUntilIdle()
        checkNotNull(chat.state.value.failure)
        answers(event("session", """{"session_id":"$newId"}"""), event("done", """{"answer":"Done.","session_id":"$newId"}"""), done())
        chat.retry()
        advanceUntilIdle()
        assertEquals(JsonPrimitive(true), body(sent())["relayed"])
        assertEquals(2, transport.calls.count { it.path == "/ask" })
    }

    @Test
    fun `the bar's outcome names the permission and why less was done`() {
        val outcome = BridgeJson.decodeFromString(
            CommandOutcome.serializer(),
            """{"action":"continue_chat","ok":true,"title":"Parser","mode":"capable","note":"chat_busy","perform":{"action":"open_chat","session_id":"$oldId","mode":"capable","text":"Fix it"}}""",
        )
        assertTrue(outcome.capable)
        assertEquals("chat_busy", outcome.note)
        assertFalse(outcome.flag("send"))
        assertEquals("Fix it", outcome.field("text"))
        val sending = BridgeJson.decodeFromString(CommandOutcome.serializer(), """{"action":"continue_chat","mode":"read_only","perform":{"action":"open_chat","send":true}}""")
        assertTrue(sending.flag("send"))
        assertFalse(sending.capable)
        assertFalse(BridgeJson.decodeFromString(CommandOutcome.serializer(), """{"action":"add_task"}""").capable)
    }
}
