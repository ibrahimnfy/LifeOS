package dev.lifeos.app.data

import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "/" commands in the composer and the model of a conversation (#95). */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandsTest {
    private val transport = FakeTransport()
    private val api = Api(transport)
    private val store = object : ChatStore {
        var pointer: ChatPointer? = null
        override fun load(): ChatPointer? = pointer
        override fun save(pointer: ChatPointer?) {
            this.pointer = pointer
        }
    }

    private fun TestScope.chat() = Chat(api, store, this, { 1_000L })
    private fun json(text: String) = Json.parseToJsonElement(text)

    private val list = CommandList(
        enabled = true,
        switchedOn = true,
        prefix = "folder:",
        commands = listOf(
            SlashCommand("deploy-notes", "command", "Publish the notes", "<target>"),
            SlashCommand("summarise", "skill", "Summarise a file", warnings = listOf("has_scripts")),
            SlashCommand("notes-index", "skill", "Rebuild the index"),
        ),
    )

    @Test
    fun `the list is offered while the name is typed and narrows with it`() {
        assertEquals("", Commands.typing("/"))
        assertEquals("su", Commands.typing("/su"))
        // Not while writing an ordinary message, and no longer once the name is complete.
        assertNull(Commands.typing("see /su"))
        assertNull(Commands.typing("/summarise notes.md"))
        assertNull(Commands.typing("/summarise "))

        assertEquals(listOf("deploy-notes", "summarise", "notes-index"), Commands.matching(list, "/").map { it.name })
        // Names that start with what is typed come first, then those that contain it.
        assertEquals(listOf("notes-index", "deploy-notes"), Commands.matching(list, "/no").map { it.name })
        assertEquals(listOf("summarise"), Commands.matching(list, "/SUM").map { it.name })
        // The bridge's own prefix may be typed too.
        assertEquals(listOf("summarise"), Commands.matching(list, "/folder:sum").map { it.name })
        assertEquals(emptyList<SlashCommand>(), Commands.matching(list, "/xyz"))
        assertEquals(emptyList<SlashCommand>(), Commands.matching(list, "/summarise now"))
    }

    @Test
    fun `choosing a command completes its name and a known command is recognised`() {
        assertEquals("/summarise ", Commands.complete(list.commands[1]))
        assertEquals("summarise", Commands.recognised(list, "/summarise notes.md")?.name)
        assertEquals("summarise", Commands.recognised(list, "/folder:summarise")?.name)
        assertEquals("/summarise".length, Commands.recognisedLength(list, "/summarise notes.md"))
        assertEquals("/folder:summarise".length, Commands.recognisedLength(list, "/folder:summarise"))
        // Not a command of this folder, or not at the start: nothing is highlighted.
        assertEquals(0, Commands.recognisedLength(list, "/summar"))
        assertEquals(0, Commands.recognisedLength(list, "please /summarise"))
        assertEquals(0, Commands.recognisedLength(null, "/summarise"))
        // Where the folder's skills are not enabled for the session nothing is highlighted either.
        assertEquals(0, Commands.recognisedLength(list.copy(enabled = false), "/summarise notes.md"))
        assertTrue(Commands.unknown(list, "/other thing"))
        assertFalse(Commands.unknown(list, "/summarise x"))
        assertFalse(Commands.unknown(list, "a path like /usr/bin"))
        assertFalse(Commands.unknown(list, "/ alone"))
    }

    @Test
    fun `the commands of a session are read with their switches`() = runTest {
        transport.answer(
            "GET /sessions/s-1/commands",
            200,
            """{"enabled":false,"switched_on":true,"reason":"the capable mode is switched off","capable_only":true,"prefix":"folder:","commands":[{"name":"summarise","kind":"skill","description":"Summarise a file","argument_hint":null,"warnings":["shell_lines","requests_tools"]}]}""",
        )
        val read = api.commands("s-1")
        assertFalse(read.enabled)
        assertTrue(read.switchedOn)
        assertEquals("folder:", read.prefix)
        assertEquals(SlashCommand("summarise", "skill", "Summarise a file", null, listOf("shell_lines", "requests_tools")), read.commands.single())
        assertEquals(ChatProblem.COMMAND_UNKNOWN, Chat.problemOf("command_not_allowed", 400))
        assertEquals(ChatProblem.SKILLS_OFF, Chat.problemOf("skills_unavailable", 503))
    }

    // ------------------------------------------------------------------ model and effort

    @Test
    fun `the label shows the choice and the defaults underneath`() {
        val defaults = ModelChoices(listOf("opus", "sonnet"), listOf("low", "high"), "opus", "high")
        assertEquals("opus · high", ModelChoice().label(defaults))
        assertEquals("sonnet · high", ModelChoice(model = "sonnet").label(defaults))
        assertEquals("sonnet · low", ModelChoice("sonnet", "low").label(defaults))
        assertEquals("", ModelChoice().label(null))
        assertEquals("sonnet", ModelChoice("sonnet").label(null))
    }

    @Test
    fun `a new conversation names its model with the first message`() = runTest {
        val chat = chat()
        chat.newChat(Folder("/home/owner/brain", "brain", "memory", default = true))
        chat.setModel(ModelChoice("sonnet", "low"))
        // Nothing to store yet: the computer does not know the conversation.
        assertTrue(transport.calls.isEmpty())
        transport.events = { flow { emit(AnswerEvent.Stream("session", json("""{"session_id":"s-1"}"""))); emit(AnswerEvent.Stream("done", json("""{"answer":"ok"}"""))) } }
        chat.send("Hi")
        advanceUntilIdle()
        assertEquals(json("""{"prompt":"Hi","stream":true,"interactive":true,"model":"sonnet","effort":"low"}"""), transport.calls.last().body)
        // From then on the computer remembers it: later messages do not repeat it.
        chat.send("Again")
        advanceUntilIdle()
        assertEquals(json("""{"prompt":"Again","stream":true,"interactive":true}"""), transport.calls.last().body)
        assertEquals(ModelChoice("sonnet", "low"), chat.state.value.choice)
    }

    @Test
    fun `changing the model of a session is stored on the computer, and taken back if that fails`() = runTest {
        val chat = chat()
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":false,"queue":[]}""")
        transport.answer("GET /sessions/s-1/history", 200, """{"exchanges":[],"older":null}""")
        chat.open(SessionSummary("s-1", "Plan", "/home/owner/brain", autoGroup = "brain", model = "opus"))
        advanceUntilIdle()
        assertEquals(ModelChoice("opus", null), chat.state.value.choice)

        transport.answer("PATCH /sessions/s-1", 200, """{"id":"s-1","title":"Plan","folder":"/home/owner/brain","model":null,"effort":"low"}""")
        chat.setModel(ModelChoice(null, "low"))
        advanceUntilIdle()
        // Back to the default is a JSON null, not a missing field.
        assertEquals(json("""{"model":null,"effort":"low"}"""), transport.calls.last().body)
        assertEquals(ModelChoice(null, "low"), chat.state.value.choice)

        transport.answer("PATCH /sessions/s-1", 400, """{"error":"bad_request"}""")
        chat.setModel(ModelChoice("unknown-model", "low"))
        assertEquals("unknown-model", chat.state.value.choice.model)
        advanceUntilIdle()
        assertEquals(ModelChoice(null, "low"), chat.state.value.choice)
        assertEquals(ChatProblem.SETTING_FAILED, chat.state.value.failure?.problem)
    }

    @Test
    fun `a restored conversation learns its model from the session list, once`() = runTest {
        store.pointer = ChatPointer("s-1", "/home/owner/brain", "brain", memory = true)
        val chat = chat()
        chat.restore()
        assertFalse(chat.state.value.choiceKnown)
        chat.noteChoice(SessionSummary("s-2", model = "sonnet"))
        assertFalse(chat.state.value.choiceKnown)
        chat.noteChoice(SessionSummary("s-1", model = "sonnet", effort = "high"))
        assertEquals(ModelChoice("sonnet", "high"), chat.state.value.choice)
        // What the owner chooses afterwards is not overwritten by an older list.
        transport.answer("PATCH /sessions/s-1", 200, """{"id":"s-1","model":"opus"}""")
        chat.setModel(ModelChoice("opus", "high"))
        chat.noteChoice(SessionSummary("s-1", model = "sonnet"))
        advanceUntilIdle()
        assertEquals(ModelChoice("opus", "high"), chat.state.value.choice)
        // The lock keeps the choice.
        chat.onLocked()
        assertEquals(ModelChoice("opus", "high"), chat.state.value.choice)
    }
}
