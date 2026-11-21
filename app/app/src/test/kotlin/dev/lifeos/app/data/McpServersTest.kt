package dev.lifeos.app.data

import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** A session folder's MCP servers: list, approve through the card, revoke (#153). */
@OptIn(ExperimentalCoroutinesApi::class)
class McpServersTest {
    private val transport = FakeTransport()
    private val api = Api(transport)
    private val store = object : ChatStore {
        var pointer: ChatPointer? = ChatPointer("s-1", "/home/owner/project-alpha", "project-alpha")
        override fun load(): ChatPointer? = pointer
        override fun save(pointer: ChatPointer?) {
            this.pointer = pointer
        }
    }
    private var uptime = 50_000L
    private val wall = 1_900_000_000_000L

    private fun TestScope.chat() = Chat(api, store, this, { wall }, { true }, { uptime })

    private val tools = """{"name":"tools","type":"stdio","command":"npx","args":["-y","some-server"],"url":null,"env_names":["API_TOKEN"],"header_names":[],"hash":"h1","outside_sandbox":true,"state":"not_approved","approved_at":null}"""
    private val remote = """{"name":"remote","type":"http","command":null,"args":[],"url":"https://mcp.example.org/x","env_names":[],"header_names":["Authorization"],"hash":"h2","outside_sandbox":true,"state":"approved","approved_at":"2027-01-10T08:00:00Z"}"""

    private fun card(server: String = tools, id: String = "a1b2c3d4e5f6") =
        """{"id":"$id","kind":"mcp_server","session_id":"s-1","folder":"/home/owner/project-alpha","server":$server,"warning":"An approved MCP server runs outside the sandbox.","created_at":"${Instant.ofEpochMilli(wall)}","expires_at":"${Instant.ofEpochMilli(wall + 24 * 3_600_000L)}"}"""

    private fun pending(vararg cards: String) = transport.answer("GET /sessions/s-1/interactions", 200, """{"pending":[${cards.joinToString(",")}]}""")

    private suspend fun TestScope.opened(vararg cards: String): Chat {
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":false,"paused":null,"queue":[]}""")
        transport.answer("GET /sessions/s-1/history", 200, """{"exchanges":[{"user":"Hello","assistant":"Hi.","complete":true}],"older":null}""")
        pending(*cards)
        val chat = chat()
        chat.restore()
        chat.load()
        advanceUntilIdle()
        return chat
    }

    @Test
    fun `the list shows what would start, names of variables only, and the state of each server`() = runTest {
        val changed = tools.replace("\"tools\"", "\"index\"").replace("not_approved", "changed")
        transport.answer("GET /sessions/s-1/mcp-servers", 200, """{"capable_only":true,"note":"…","servers":[$tools,$remote,$changed,{"name":"x","state":"something new"}]}""")
        val servers = api.mcpServers("s-1")
        val (first, second, third, unknown) = servers
        assertEquals("npx -y some-server", first.commandLine)
        assertEquals(listOf("API_TOKEN"), first.envNames)
        assertEquals(McpServer.State.NOT_APPROVED, first.approval)
        assertTrue(first.outsideSandbox)
        assertEquals("", second.commandLine)
        assertEquals("https://mcp.example.org/x", second.url)
        assertEquals(listOf("Authorization"), second.headerNames)
        assertEquals(McpServer.State.APPROVED, second.approval)
        assertEquals(McpServer.State.CHANGED, third.approval)
        // A state the app does not know is never taken for an approval.
        assertEquals(McpServer.State.NOT_APPROVED, unknown.approval)
        // A name that could not stand in a request path is not offered.
        assertFalse(McpServer(name = "a/b").usable)
    }

    @Test
    fun `asking for an approval makes a card, and a card that already waits is not asked for twice`() = runTest {
        val server = BridgeJson.decodeFromString(McpServer.serializer(), tools)
        pending()
        transport.answer("POST /sessions/s-1/mcp-servers/tools/approval", 200, card())
        val made = McpApprovals.card(api, "s-1", server)
        assertEquals("a1b2c3d4e5f6", made.id)
        assertEquals("npx -y some-server", made.server?.commandLine)
        assertEquals(1, transport.calls.count { it.method == "POST" })

        pending(card())
        assertEquals("a1b2c3d4e5f6", McpApprovals.card(api, "s-1", server).id)
        assertEquals(1, transport.calls.count { it.method == "POST" })

        // The folder's file changed since that card: it shows another definition, so a new one is asked for.
        McpApprovals.card(api, "s-1", server.copy(hash = "h9"))
        assertEquals(2, transport.calls.count { it.method == "POST" })
    }

    @Test
    fun `the answer says what the computer did with the yes`() = runTest {
        val path = "POST /sessions/s-1/interactions/a1b2c3d4e5f6"
        transport.answer(path, 200, """{"answered":true,"id":"a1b2c3d4e5f6","approved":true,"name":"tools"}""")
        assertEquals(Interaction.Status.ALLOWED, McpApprovals.answer(api, "s-1", "a1b2c3d4e5f6", approve = true))
        assertEquals("""{"approve":true}""", transport.calls.last().body.toString())
        // The definition changed between the card and the answer: nothing was approved.
        transport.answer(path, 200, """{"answered":true,"id":"a1b2c3d4e5f6","approved":false,"reason":"the definition changed"}""")
        assertEquals(Interaction.Status.NOT_DONE, McpApprovals.answer(api, "s-1", "a1b2c3d4e5f6", approve = true))
        transport.answer(path, 200, """{"answered":true,"id":"a1b2c3d4e5f6","approved":false,"reason":null}""")
        assertEquals(Interaction.Status.REFUSED, McpApprovals.answer(api, "s-1", "a1b2c3d4e5f6", approve = false))
        assertEquals("""{"approve":false}""", transport.calls.last().body.toString())
        transport.answer(path, 503, """{"error":"audit_unavailable"}""")
        assertEquals(Interaction.Status.UNRECORDED, McpApprovals.answer(api, "s-1", "a1b2c3d4e5f6", approve = true))
        transport.answer(path, 404, """{"error":"not_pending"}""")
        assertEquals(Interaction.Status.GONE, McpApprovals.answer(api, "s-1", "a1b2c3d4e5f6", approve = true))
        // Not delivered: the caller may try again.
        transport.fail(path, ApiException(ApiException.Kind.NOT_SENT))
        try {
            McpApprovals.answer(api, "s-1", "a1b2c3d4e5f6", approve = true)
            fail("an answer that was not sent must not look settled")
        } catch (_: ApiException) {
        }
    }

    @Test
    fun `revoking is one request and says whether there was an approval`() = runTest {
        transport.answer("DELETE /sessions/s-1/mcp-servers/remote", 200, """{"revoked":true}""")
        assertTrue(api.revokeMcp("s-1", "remote"))
        transport.answer("DELETE /sessions/s-1/mcp-servers/tools", 200, """{"revoked":false}""")
        assertFalse(api.revokeMcp("s-1", "tools"))
    }

    @Test
    fun `the card waits in the conversation for a day and is answered like any approval`() = runTest {
        val chat = opened(card())
        val shown = chat.state.value.interactions.single()
        assertEquals(Interaction.Kind.MCP_SERVER, shown.kind)
        assertTrue(shown.crossRun)
        assertEquals(uptime + 24 * 3_600_000L, shown.deadline)
        assertEquals("tools", shown.server?.name)
        assertEquals("/home/owner/project-alpha", shown.folder)

        transport.answer("POST /sessions/s-1/interactions/a1b2c3d4e5f6", 200, """{"answered":true,"id":"a1b2c3d4e5f6","approved":false,"reason":"the definition changed"}""")
        chat.answerApproval("a1b2c3d4e5f6", approve = true)
        runCurrent()
        assertEquals(Interaction.Status.NOT_DONE, chat.state.value.interactions.single().status)
    }

    @Test
    fun `a card without a usable server is not shown`() {
        fun of(text: String) = Interaction.of(BridgeJson.decodeFromString(InteractionData.serializer(), text), uptime, wall, fresh = false)
        assertNull(of("""{"id":"a1","kind":"mcp_server","session_id":"s-1"}"""))
        assertNull(of(card(tools.replace("\"tools\"", "\"to/ols\""))))
    }

    @Test
    fun `an answer that used servers says which, and a run refused for unchecked servers says why`() = runTest {
        val chat = opened()
        transport.events = {
            flow {
                emit(AnswerEvent.Stream("session", Json.parseToJsonElement("""{"session_id":"s-1"}""")))
                emit(
                    AnswerEvent.Stream(
                        "done",
                        Json.parseToJsonElement("""{"answer":"Looked it up.","session_id":"s-1","mcp_servers":[{"name":"tools","outside_sandbox":true},{"name":"remote","outside_sandbox":true}],"mcp_note":"…"}"""),
                    ),
                )
                emit(AnswerEvent.Done(RelayResponse(200, null)))
            }
        }
        chat.setCapable(true)
        assertTrue(chat.send("Look it up"))
        advanceUntilIdle()
        assertEquals(listOf("tools", "remote"), chat.state.value.messages.last().servers)

        transport.events = { flow { emit(AnswerEvent.Done(RelayResponse(503, Json.parseToJsonElement("""{"error":"mcp_unavailable"}""")))) } }
        assertTrue(chat.send("Again"))
        advanceUntilIdle()
        val failure = checkNotNull(chat.state.value.failure)
        assertEquals(ChatProblem.MCP_OFF, failure.problem)
        // Nothing ran: the same message can be sent again once the approval is revoked or the check passed.
        assertEquals("Again", failure.prompt)
        assertEquals(ChatProblem.UNRECORDED, Chat.problemOf("audit_unavailable", 503))
    }
}
