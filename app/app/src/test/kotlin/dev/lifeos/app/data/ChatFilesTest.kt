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

/** Files the assistant offers in an answer (#96). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatFilesTest {
    private val transport = FakeTransport()
    private val store = object : ChatStore {
        var pointer: ChatPointer? = null
        override fun load(): ChatPointer? = pointer
        override fun save(pointer: ChatPointer?) {
            this.pointer = pointer
        }
    }
    private var uptime = 10_000L

    private fun TestScope.chat() = Chat(Api(transport), store, this, { 1_000L }, { true }, { uptime })

    private fun answer(done: String) {
        transport.events = {
            flow {
                emit(AnswerEvent.Stream("session", Json.parseToJsonElement("""{"session_id":"s-1"}""")))
                emit(AnswerEvent.Stream("done", Json.parseToJsonElement(done)))
                emit(AnswerEvent.Done(RelayResponse(200, null)))
            }
        }
    }

    @Test
    fun `an offered file becomes a card and nothing is fetched before the owner asks`() = runTest {
        val chat = chat()
        chat.newChat(Folder("/home/owner/brain", "brain", "memory", default = true))
        answer(
            """{"answer":"Here it is.","session_id":"s-1","files":[{"offer":"Zm9vYmFyLW9mZmVy","name":"plan.pdf","type":"application/pdf","size":120000}],""" +
                """"files_refused":[{"name":"video.mp4","reason":"larger than the limit of 50 MB"},{"name":"id_key","reason":"hidden files and folders are not sent"},{"name":"x","reason":"something new"}]}""",
        )
        chat.send("Send me the plan")
        advanceUntilIdle()
        val message = chat.state.value.messages.last()
        assertEquals("Here it is.", message.text)
        val (offered, large, hidden, other) = message.files
        // The download is asked for with the offer's id and the session it belongs to.
        assertEquals(FileOffer("Zm9vYmFyLW9mZmVy", "plan.pdf", "application/pdf", 120000, "s-1"), offered.offer)
        assertEquals("plan.pdf", offered.name)
        assertNull(offered.refused)
        assertEquals(OfferedFile.Refusal.TOO_LARGE, large.refused)
        assertEquals("video.mp4", large.name)
        assertNull(large.offer)
        assertEquals(OfferedFile.Refusal.HIDDEN, hidden.refused)
        assertEquals(OfferedFile.Refusal.OTHER, other.refused)
        // Only the conversation was asked for: no request for the file itself.
        assertTrue(transport.calls.none { it.path.startsWith("/files/") })

        // The offer runs out on the computer after 15 minutes; the card stops a little earlier.
        assertTrue(offered.open(uptime))
        assertTrue(offered.open(uptime + 13 * 60_000))
        assertFalse(offered.open(uptime + 14 * 60_000))
        assertFalse(large.open(uptime))
    }

    @Test
    fun `an answer that is only a file still gets its card`() = runTest {
        val chat = chat()
        chat.newChat(Folder("/home/owner/brain", "brain", "memory", default = true))
        answer("""{"answer":"","session_id":"s-1","files":[{"offer":"abc_DEF-123","name":"notes.md","type":"text/markdown","size":10},{"offer":"../../x","name":"bad","size":1}]}""")
        chat.send("The notes please")
        advanceUntilIdle()
        val message = chat.state.value.messages.last()
        assertFalse(message.fromOwner)
        assertEquals("", message.text)
        // An offer whose id could not be part of a request path is left out.
        assertEquals(listOf("notes.md"), message.files.map { it.name })
    }

    @Test
    fun `an earlier answer keeps the file's name but not the offer`() = runTest {
        store.pointer = ChatPointer("s-1", "/home/owner/brain", "brain", memory = true)
        val chat = chat()
        chat.restore()
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":false,"paused":null,"queue":[]}""")
        transport.answer(
            "GET /sessions/s-1/history",
            200,
            """{"exchanges":[{"user":"The plan please","assistant":"Here it is.\n\n[[file: notes/alpha/plan.pdf]]\n[[file: `/home/owner/brain/outline.md`]]","complete":true},""" +
                """{"user":"Thanks","assistant":"[[file: last.txt]]","complete":true}],"older":null}""",
        )
        chat.load()
        advanceUntilIdle()
        val messages = chat.state.value.messages
        assertEquals("Here it is.", messages[1].text)
        assertEquals(listOf("plan.pdf", "outline.md"), messages[1].files.map { it.name })
        assertTrue(messages[1].files.all { it.offer == null && it.refused == null })
        assertEquals("", messages[3].text)
        assertEquals(listOf("last.txt"), messages[3].files.map { it.name })
    }

    @Test
    fun `only lines of their own offer a file`() {
        assertEquals(listOf("a.md", "b c.pdf"), Chat.offeredNames("Text\n[[file: x/a.md]]\n  [[file:  /p/b c.pdf ]]  \nmore"))
        assertEquals(emptyList<String>(), Chat.offeredNames("Inline [[file: x]] is text, and [[file: ]] names nothing"))
        assertEquals(emptyList<String>(), Chat.offeredNames("[[file: ]]"))
        assertEquals(OfferedFile.Refusal.OUTSIDE, OfferedFile.refusal("outside the allowed folders"))
        assertEquals(OfferedFile.Refusal.TOO_MANY, OfferedFile.refusal("too many files in one answer"))
        assertEquals(OfferedFile.Refusal.OTHER, OfferedFile.refusal("not a regular file"))
    }
}
