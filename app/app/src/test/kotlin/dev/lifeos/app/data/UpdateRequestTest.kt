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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * A chat in the LifeOS program folder asks for its changes to be applied (#180): the approval
 * card, one the computer refused at once, and what a yes did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRequestTest {
    private val transport = FakeTransport()
    private val api = Api(transport)
    private val store = object : ChatStore {
        var pointer: ChatPointer? = null
        override fun load(): ChatPointer? = pointer
        override fun save(pointer: ChatPointer?) {
            this.pointer = pointer
        }
    }
    private var uptime = 50_000L
    private val wall = 1_900_000_000_000L

    private fun TestScope.chat() = Chat(api, store, this, { wall }, { true }, { uptime }, newId = { "00000000-0000-4000-8000-000000000001" })

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun event(name: String, data: String) = AnswerEvent.Stream(name, json(data))
    private fun done() = AnswerEvent.Done(RelayResponse(200, null))

    private val commit = "4f2a9c1e7b3d5a6c8e0f1a2b3c4d5e6f7a8b9c0d"
    private val created = Instant.ofEpochMilli(wall - 3_600_000L).toString()
    private val expires = Instant.ofEpochMilli(wall + 23 * 3_600_000L).toString()
    private val card = """{"id":"a1b2c3d4e5f6","kind":"apply_update","session_id":"s-1","folder":"/home/owner/lifeos","state":"ready","commit":"$commit","running_commit":"9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d","created_at":"$created","expires_at":"$expires"}"""

    private suspend fun TestScope.asked(): Chat {
        val chat = chat()
        chat.newChat()
        transport.events = {
            flow {
                listOf(
                    event("session", """{"session_id":"s-1"}"""),
                    event("delta", """{"text":"Committed.\n[[apply-update]]"}"""),
                    event("done", """{"answer":"Committed.","session_id":"s-1","files":[],"update_requests":[$card,{"kind":"apply_update","state":"refused","error":"an update is being applied already","error_code":"update_in_progress"}]}"""),
                    done(),
                ).forEach { emit(it) }
            }
        }
        chat.send("Fix the typo and apply it")
        advanceUntilIdle()
        return chat
    }

    @Test
    fun `a request is an approval card that waits across runs, a refused one says why`() = runTest {
        val chat = asked()
        val (waiting, refused) = chat.state.value.interactions
        assertEquals(Interaction.Kind.APPLY_UPDATE, waiting.kind)
        assertTrue(waiting.crossRun && waiting.open(uptime))
        val update = checkNotNull(waiting.update)
        assertEquals("4f2a9c1e7b3d", update.shortCommit)
        assertEquals("9e8d7c6b5a4f", update.shortRunning)
        assertNull(update.problem)
        assertEquals(UpdateRequest.Problem.IN_PROGRESS, refused.update?.problem)
        assertTrue(!refused.open(uptime))
        // The marker line never shows in the answer.
        assertEquals("Committed.", chat.state.value.messages.last().text)
    }

    @Test
    fun `a yes starts it, and a changed commit is not done`() = runTest {
        val chat = asked()
        transport.answer("POST /sessions/s-1/interactions/a1b2c3d4e5f6", 200, """{"answered":true,"id":"a1b2c3d4e5f6","approved":false,"reason":"changed"}""")
        chat.answerApproval("a1b2c3d4e5f6", approve = true)
        advanceUntilIdle()
        val card = chat.state.value.interactions.first { it.id == "a1b2c3d4e5f6" }
        assertEquals(Interaction.Status.NOT_DONE, card.status)
        assertEquals(UpdateRequest.NotDone.CHANGED, card.update?.notDone)
        assertEquals(Interaction.Status.ALLOWED, Interaction.settled(true, json("""{"approved":true,"state":"waiting"}""")))
        assertEquals(Interaction.Status.REFUSED, Interaction.settled(false, json("""{"approved":false}""")))
    }

    @Test
    fun `the request line never shows, not even while it is written`() {
        assertEquals("Committed.", Chat.visible("Committed.\n[[apply-update]]\n"))
        assertEquals("Committed.", Chat.streaming("Committed.\n[[apply-upd"))
        assertEquals("Committed.", Chat.streaming("Committed.\n[[apply-update]]"))
        assertEquals("See [[apply-update]] inline", Chat.visible("See [[apply-update]] inline"))
        assertEquals(ChatProblem.UPDATING, Chat.problemOf("update_in_progress", 503))
    }

    @Test
    fun `a request without its commit is nothing to approve`() {
        val data = BridgeJson.decodeFromString(InteractionData.serializer(), """{"id":"a1b2c3d4e5f6","kind":"apply_update","state":"ready"}""")
        assertNull(Interaction.of(data, 0, wall, fresh = true))
        assertEquals(UpdateRequest.Problem.NOT_PROGRAM_FOLDER, UpdateRequest.problemOf("not_program_folder"))
        assertEquals(UpdateRequest.Problem.OTHER, UpdateRequest.problemOf("something_new"))
    }
}
