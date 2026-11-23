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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Moving between conversations by asking (#164) and moving a blank chat's files (#163), as the
 * app shows them (#168): a switch of a fresh answer, the card to pick from, marker lines that
 * never show, and the approval card for a move.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSwitchTest {
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

    private fun answers(vararg events: AnswerEvent) {
        transport.events = { flow { events.forEach { emit(it) } } }
    }

    private val open = """{"action":"open","sessions":[{"id":"s-9","title":"Numbers","folder_label":"project-alpha","kind":"project","colour":2,"last_activity":"2027-01-15T08:01:40Z"}],"folder":null}"""
    private val choose = """{"action":"choose","sessions":[{"id":"s-8","title":"Numbers","folder_label":"Chats","kind":"chat","colour":0,"last_activity":null},{"id":"s-9","title":"Files","folder_label":"project-alpha","kind":"project","colour":2,"last_activity":null}],"folder":null}"""
    private val new = """{"action":"new","sessions":[],"folder":{"path":"/home/owner/project-alpha","label":"project-alpha","kind":"project","colour":2}}"""

    /** A blank chat's answer that ends with [switch]; the streamed text still has the marker line. */
    private fun answerWithSwitch(switch: String, line: String = "[[switch: s-9]]") = answers(
        event("session", """{"session_id":"s-1"}"""),
        event("delta", """{"text":"Opening it.\n"}"""),
        event("delta", """{"text":${JsonPrimitive(line)}}"""),
        event("done", """{"answer":"Opening it.","session_id":"s-1","files":[],"switch":$switch}"""),
        done(),
    )

    private fun texts(chat: Chat) = chat.state.value.messages.map { (if (it.fromOwner) "me:" else "it:") + it.text }

    // ------------------------------------------------------------------ the switch item

    @Test
    fun `a fresh answer that opens one conversation switches once, and the screen takes it`() = runTest {
        val chat = chat()
        chat.newChat()
        answerWithSwitch(open)
        chat.send("Open the chat about numbers")
        advanceUntilIdle()
        val switch = checkNotNull(chat.state.value.switchTo)
        assertNull(chat.state.value.choices)
        assertEquals(listOf("me:Open the chat about numbers", "it:Opening it."), texts(chat))
        // The step: the conversation, with its folder's label, kind and colour; no path is named.
        val step = switch.step() as SwitchStep.Open
        assertEquals("s-9", step.session.id)
        assertEquals("project-alpha", step.session.folderName)
        assertEquals(2, step.session.colour)
        // Taken once: a second look finds nothing.
        assertEquals(switch, chat.takeSwitch())
        assertNull(chat.takeSwitch())
        assertNull(chat.state.value.switchTo)
    }

    @Test
    fun `a switch that waited too long for the screen becomes the card instead`() = runTest {
        val chat = chat()
        chat.newChat()
        answerWithSwitch(open)
        chat.send("Open the chat about numbers")
        advanceUntilIdle()
        // The owner had left the conversation; the screen comes back after more than a minute.
        uptime += Chat.SWITCH_FRESH_MILLIS + 1
        assertNull(chat.takeSwitch())
        assertNull(chat.state.value.switchTo)
        assertEquals(listOf("s-9"), chat.state.value.choices?.sessions?.map { it.id })
        // A refused proposal and other codes the bridge may add are worded too.
        assertEquals(FileMove.Problem.INSTRUCTIONS, FileMove.problemOf("file_instructions"))
        assertEquals(FileMove.Problem.OTHER, FileMove.problemOf("something_new"))
    }

    @Test
    fun `several conversations are a card to pick from, gone with the next message`() = runTest {
        val chat = chat()
        chat.newChat()
        answerWithSwitch(choose, line = "[[switch: s-8, s-9]]")
        chat.send("One of my chats about numbers or files")
        advanceUntilIdle()
        assertNull(chat.state.value.switchTo)
        val card = checkNotNull(chat.state.value.choices)
        assertEquals(listOf("s-8", "s-9"), card.sessions.map { it.id })
        assertNull(card.step())
        // A pick is an "open" of that one.
        assertEquals("s-8", (ChatSwitch.open(card.sessions[0]).step() as SwitchStep.Open).session.id)

        answers(event("done", """{"answer":"Fine","session_id":"s-1"}"""), done())
        chat.send("Never mind")
        assertNull(chat.state.value.choices)
        advanceUntilIdle()
        assertNull(chat.state.value.choices)
    }

    @Test
    fun `a new conversation in a folder starts empty there, and its first message names the folder`() = runTest {
        val chat = chat()
        chat.newChat()
        answerWithSwitch(new, line = "[[switch-new: project-alpha]]")
        chat.send("Start a chat in my project folder")
        advanceUntilIdle()
        val step = checkNotNull(chat.takeSwitch()).step() as SwitchStep.New
        val folder = checkNotNull(step.folder)
        assertEquals("/home/owner/project-alpha", folder.path)
        assertEquals(2, folder.colour)

        chat.newChat(folder)
        assertEquals("project-alpha", chat.state.value.folder?.label)
        assertEquals(2, chat.state.value.folder?.colour)
        answers(event("done", """{"answer":"Hi","session_id":"s-2"}"""), done())
        chat.send("Hello")
        advanceUntilIdle()
        assertEquals("/ask", transport.calls.last().path)
        assertEquals(JsonPrimitive("/home/owner/project-alpha"), (transport.calls.last().body as kotlinx.serialization.json.JsonObject)["folder"])
    }

    @Test
    fun `a new conversation among the blank chats or in the memory folder`() = runTest {
        val blank = ChatSwitch.of(json("""{"action":"new","sessions":[],"folder":{"path":"/home/owner/LifeOS-Chats","label":"Chats","kind":"chat","colour":0}}"""))
        assertEquals(SwitchStep.New(null), blank?.step())
        val memory = ChatSwitch.of(json("""{"action":"new","sessions":[],"folder":{"path":"/home/owner/brain","label":"brain","kind":"memory","colour":1}}""")) as ChatSwitch
        val folder = checkNotNull((memory.step() as SwitchStep.New).folder)
        assertTrue(folder.isMemory)

        // Since #163 no folder means a blank chat: the memory folder is named like any other.
        val chat = chat()
        chat.newChat(folder)
        answers(event("done", """{"answer":"Hi","session_id":"s-2"}"""), done())
        chat.send("Hello")
        advanceUntilIdle()
        assertEquals(JsonPrimitive("/home/owner/brain"), (transport.calls.last().body as kotlinx.serialization.json.JsonObject)["folder"])
    }

    @Test
    fun `the coach is opened on its own screen`() {
        val coach = ChatSwitch.of(json("""{"action":"open","sessions":[{"id":"c-1","title":"Coach","folder_label":"Coach","kind":"coach","colour":1}],"folder":null}"""))
        assertEquals(SwitchStep.Coach, coach?.step())
    }

    @Test
    fun `an item the app cannot use is no switch`() {
        assertNull(ChatSwitch.of(null))
        assertNull(ChatSwitch.of(json("""{"action":"open","sessions":[],"folder":null}""")))
        assertNull(ChatSwitch.of(json("""{"action":"open","sessions":[{"id":"../x"}],"folder":null}""")))
        assertNull(ChatSwitch.of(json("""{"action":"new","sessions":[],"folder":{"path":"","label":"x","kind":"project"}}""")))
        assertNull(ChatSwitch.of(json("""{"action":"jump","sessions":[{"id":"s-1"}]}""")))
        assertNull(ChatSwitch.of(json("""["open"]""")))
        // At most five to choose from, each once.
        val many = (1..7).joinToString(",") { """{"id":"s-$it"},{"id":"s-$it"}""" }
        assertEquals(5, ChatSwitch.of(json("""{"action":"choose","sessions":[$many]}"""))?.sessions?.size)
    }

    @Test
    fun `a conversation read again, or after a lock, never switches by itself`() = runTest {
        val chat = chat()
        chat.newChat()
        answerWithSwitch(open)
        chat.send("Open the chat about numbers")
        advanceUntilIdle()
        assertTrue(chat.state.value.switchTo != null)
        // A lock drops it, and reading the conversation again does not bring it back.
        chat.onLocked()
        assertNull(chat.state.value.switchTo)
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":false,"paused":null,"queue":[]}""")
        transport.answer("GET /sessions/s-1/history", 200, """{"exchanges":[{"user":"Open the chat about numbers","assistant":"Opening it.","complete":true}],"older":null}""")
        chat.load()
        advanceUntilIdle()
        assertNull(chat.state.value.switchTo)
        assertNull(chat.state.value.choices)
        assertEquals(listOf("me:Open the chat about numbers", "it:Opening it."), texts(chat))
    }

    @Test
    fun `a followed answer offers its switch on a card, and a replayed one offers nothing`() = runTest {
        store.pointer = ChatPointer("s-1", "/home/owner/project-alpha", "project-alpha")
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":true,"paused":null,"queue":[]}""")
        transport.answer("GET /sessions/s-1/history", 200, """{"exchanges":[{"user":"Open the chat about numbers","assistant":"","complete":false}],"older":null}""")
        fun live(caughtUp: Boolean) {
            transport.live = {
                flow {
                    emit(event("live", """{"session_id":"s-1","running":true,"followable":true,"queue_id":null}"""))
                    if (caughtUp) emit(event("replay_end", """{"events":0}"""))
                    emit(event("done", """{"answer":"Opening it.","session_id":"s-1","switch":$open}"""))
                    emit(done())
                }
            }
        }
        live(caughtUp = true)
        val chat = chat()
        chat.restore()
        chat.load()
        advanceUntilIdle()
        assertNull(chat.state.value.switchTo)
        assertEquals(listOf("s-9"), chat.state.value.choices?.sessions?.map { it.id })

        // The same answer as a replay (the run had ended before the app listened): no card at all.
        live(caughtUp = false)
        chat.onLocked()
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":true,"paused":null,"queue":[]}""")
        chat.load()
        advanceUntilIdle()
        assertNull(chat.state.value.switchTo)
        assertNull(chat.state.value.choices)
    }

    // ------------------------------------------------------------------ marker lines

    @Test
    fun `switch and move lines never show, neither while written nor in the history`() {
        val text = "Here.\n[[switch: s-9]]\n[[switch-new: project-alpha]]\n[[move-files: project-alpha | report.md]]\nEnd"
        assertEquals("Here.\nEnd", Chat.visible(text))
        assertEquals("Inline [[switch: x]] stays", Chat.visible("Inline [[switch: x]] stays"))
        // While it is written, a line that is becoming a marker is held back until it is whole.
        assertEquals("Here.", Chat.streaming("Here.\n[[swi"))
        assertEquals("Here.", Chat.streaming("Here.\n["))
        assertEquals("Here.", Chat.streaming("Here.\n  [[move-files: project-alpha | rep"))
        assertEquals("Here.", Chat.streaming("Here.\n[[switch: s-9]]"))
        // Anything else is shown as it comes.
        assertEquals("Here.\n[link", Chat.streaming("Here.\n[link"))
        assertEquals("Here.\n[[note]] x", Chat.streaming("Here.\n[[note]] x"))
        assertEquals("", Chat.streaming("[[file: a"))
    }

    @Test
    fun `the streamed answer hides the marker line before the done event replaces it`() = runTest {
        val chat = chat()
        chat.newChat()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"s-1"}"""))
                emit(event("delta", """{"text":"Opening it.\n[[swi"}"""))
                gate.await()
                emit(event("delta", """{"text":"tch: s-9]]"}"""))
                emit(event("done", """{"answer":"Opening it.","session_id":"s-1","switch":$open}"""))
                emit(done())
            }
        }
        chat.send("Open it")
        advanceUntilIdle()
        assertEquals(listOf("me:Open it", "it:Opening it."), texts(chat))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("me:Open it", "it:Opening it."), texts(chat))
    }

    // ------------------------------------------------------------------ moving a chat's files

    private val expires = Instant.ofEpochMilli(wall + 20 * 3_600_000L).toString()
    private val created = Instant.ofEpochMilli(wall - 4 * 3_600_000L).toString()
    private val moveCard = """{"id":"m1b2c3d4e5f6","kind":"move_files","session_id":"s-1","folder":"/home/owner/LifeOS-Chats/2027-01-15-a1b2c3","state":"ready","target":"/home/owner/project-alpha/notes","target_label":"project-alpha/notes","files":[{"path":"report.md","size":812},{"path":"data/table.csv","size":300}],"total_size":1112,"created_at":"$created","expires_at":"$expires"}"""

    private suspend fun TestScope.proposed(): Chat {
        val chat = chat()
        chat.newChat()
        answers(
            event("session", """{"session_id":"s-1"}"""),
            event("delta", """{"text":"I can move them.\n[[move-files: project-alpha/notes | report.md | data/table.csv]]"}"""),
            event("done", """{"answer":"I can move them.","session_id":"s-1","files":[],"file_moves":[$moveCard,{"kind":"move_files","state":"refused","target":".secret","error":"the target is not an allowed folder","error_code":"target_not_allowed"}]}"""),
            done(),
        )
        chat.send("Put the report into my project")
        advanceUntilIdle()
        return chat
    }

    @Test
    fun `a proposed move is an approval card that waits across runs, a refused one says why`() = runTest {
        val chat = proposed()
        assertEquals(listOf("me:Put the report into my project", "it:I can move them."), texts(chat))
        val (card, refused) = chat.state.value.interactions
        assertEquals(Interaction.Kind.MOVE_FILES, card.kind)
        assertTrue(card.crossRun && card.open(uptime))
        val move = checkNotNull(card.move)
        assertEquals("project-alpha/notes", move.shownTarget)
        assertEquals(listOf("report.md" to 812L, "data/table.csv" to 300L), move.files.map { it.path to it.size })
        assertEquals(1112L, move.totalSize)
        assertNull(move.problemKind)
        // The refused one has nothing to answer.
        assertEquals(FileMove.Problem.TARGET, refused.move?.problemKind)
        assertTrue(!refused.open(uptime))

        // The next message's run does not close it.
        answers(event("done", """{"answer":"Sure","session_id":"s-1"}"""), done())
        chat.send("Something else first")
        advanceUntilIdle()
        assertEquals(Interaction.Status.WAITING, chat.state.value.interactions.first { it.id == card.id }.status)
    }

    @Test
    fun `allowing a move shows what went and what stayed`() = runTest {
        val chat = proposed()
        transport.answer(
            "POST /sessions/s-1/interactions/m1b2c3d4e5f6",
            200,
            """{"answered":true,"id":"m1b2c3d4e5f6","moved":false,"files":["report.md"],"failed":["data/table.csv"],"target":"/home/owner/project-alpha/notes","target_label":"project-alpha/notes"}""",
        )
        chat.answerApproval("m1b2c3d4e5f6", approve = true)
        advanceUntilIdle()
        assertEquals("""{"approve":true}""", transport.calls.last().body.toString())
        val card = chat.state.value.interactions.first { it.id == "m1b2c3d4e5f6" }
        // It broke off part-way: not done as a whole, and the card says which file stays.
        assertEquals(Interaction.Status.NOT_DONE, card.status)
        assertEquals(listOf("report.md"), card.move?.moved)
        assertEquals(listOf("data/table.csv"), card.move?.failed)
        assertEquals(Interaction.Status.ALLOWED, Interaction.settled(true, json("""{"moved":true,"files":["a"],"failed":[]}""")))
        assertEquals(Interaction.Status.REFUSED, Interaction.settled(false, json("""{"moved":false}""")))
    }

    @Test
    fun `a move the computer refuses for now leaves the card waiting, with the reason`() = runTest {
        val chat = proposed()
        val url = "POST /sessions/s-1/interactions/m1b2c3d4e5f6"
        transport.answer("GET /sessions/s-1/interactions", 200, """{"pending":[$moveCard]}""")
        for ((status, code, refusal) in listOf(
            Triple(409, "file_exists", Interaction.Refusal.FILE_EXISTS),
            Triple(409, "files_changed", Interaction.Refusal.FILES_CHANGED),
            Triple(503, "audit_unavailable", Interaction.Refusal.UNRECORDED),
            Triple(409, "file_missing", Interaction.Refusal.CANNOT_MOVE),
        )) {
            transport.answer(url, status, """{"error":"$code","message":"…"}""")
            chat.answerApproval("m1b2c3d4e5f6", approve = true)
            advanceUntilIdle()
            val card = chat.state.value.interactions.first { it.id == "m1b2c3d4e5f6" }
            assertEquals(Interaction.Status.WAITING, card.status)
            assertEquals(refusal, card.refusal)
            assertTrue(!card.sendFailed)
        }
        // A skill install's unknown refusal is still "not delivered", as before.
        assertNull(Interaction.refusal(ApiException(ApiException.Kind.HTTP, 409, "file_missing"), Interaction.Kind.SKILL_INSTALL))
    }

    @Test
    fun `a move that waits is shown when the conversation is read again`() = runTest {
        store.pointer = ChatPointer("s-1", "/home/owner/LifeOS-Chats/2027-01-15-a1b2c3", "Chats", kind = FolderKinds.CHAT, colour = 0)
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":false,"paused":null,"queue":[]}""")
        // The history keeps the proposal's line: it is not shown.
        transport.answer("GET /sessions/s-1/history", 200, """{"exchanges":[{"user":"Move it","assistant":"I can move them.\n[[move-files: project-alpha/notes | report.md]]","complete":true}],"older":null}""")
        transport.answer("GET /sessions/s-1/interactions", 200, """{"pending":[$moveCard]}""")
        val chat = chat()
        chat.restore()
        assertTrue(chat.state.value.folder?.blank == true)
        chat.load()
        advanceUntilIdle()
        assertEquals(listOf("me:Move it", "it:I can move them."), texts(chat))
        val card = chat.state.value.interactions.single()
        assertEquals(Interaction.Kind.MOVE_FILES, card.kind)
        assertEquals(uptime + 20 * 3_600_000L, card.deadline)
    }

    // ------------------------------------------------------------------ blank chats

    @Test
    fun `blank chats switched off on the computer are worded, with the computer's reason`() = runTest {
        val chat = chat()
        chat.newChat()
        transport.events = {
            flow { emit(AnswerEvent.Done(RelayResponse(503, json("""{"error":"chat_root_unusable","message":"blank chats are switched off: the chat folder is a git repository or inside one"}""")))) }
        }
        chat.send("Hi")
        advanceUntilIdle()
        val failure = checkNotNull(chat.state.value.failure)
        assertEquals(ChatProblem.CHAT_ROOT_UNUSABLE, failure.problem)
        assertEquals("the chat folder is a git repository or inside one", failure.detail)
        assertEquals("Hi", failure.prompt)
        // Other problems carry no reason of the computer's.
        assertNull(Chat.detailOf(ChatProblem.FAILED, "x: y"))
    }

    @Test
    fun `a blank chat continued from the list is still a blank chat, and learns its folder's colour`() = runTest {
        val chat = chat()
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":false,"paused":null,"queue":[]}""")
        transport.answer("GET /sessions/s-1/history", 200, """{"exchanges":[],"older":null}""")
        transport.answer("GET /sessions/s-1", 200, """{"id":"s-1","folder":"/home/owner/LifeOS-Chats/2027-01-15-a1b2c3","folder_key":"chats","folder_label":"Chats","kind":"chat","colour":4}""")
        // As a switch names it: no path, its kind.
        chat.open(SwitchSession("s-1", "Numbers", "Chats", FolderKinds.CHAT, 4).summary())
        assertTrue(chat.state.value.folder?.blank == true)
        advanceUntilIdle()
        val folder = checkNotNull(chat.state.value.folder)
        assertTrue(folder.blank)
        assertEquals("/home/owner/LifeOS-Chats/2027-01-15-a1b2c3", folder.path)
        assertEquals(4, folder.colour)
        // The pointer keeps what was learned, for the next start.
        assertEquals(ChatPointer("s-1", "/home/owner/LifeOS-Chats/2027-01-15-a1b2c3", "Chats", false, FolderKinds.CHAT, 4), store.pointer)
        // A first message of a new blank chat names no folder; of a project, its path.
        assertNull(ChatFolder.BLANK.requested)
        assertEquals("/p", ChatFolder.of(Folder("/p", "p")).requested)
        assertNull(ChatFolder.of(Folder("/c", "Chats", FolderKinds.CHAT)).requested)
    }

    private val findFolder = """{"action":"choose_folder","sessions":[],"folder":null,"folders":[""" +
        """{"path":"/home/owner/Documents/Notes","label":"Notes","kind":"project","colour":0,"allowed":false,"where":"~/Documents"},""" +
        """{"path":"/home/owner/work/Notes","label":"Notes","kind":"project","colour":3,"allowed":true,"where":"~/work"}]}"""

    @Test
    fun `folders found by name are a card, and a pick asks the computer to open one first`() = runTest {
        val chat = chat()
        chat.newChat()
        answerWithSwitch(findFolder, line = "[[switch-find: notes]]")
        chat.send("Find my notes folder and let's work there")
        advanceUntilIdle()
        // Never done by itself, also in the answer to this phone's own message.
        assertNull(chat.state.value.switchTo)
        val card = checkNotNull(chat.state.value.choices)
        assertTrue(card.isChoice)
        assertEquals(listOf("~/Documents", "~/work"), card.folders.map { it.where })
        assertEquals(listOf(false, true), card.folders.map { it.allowed })
        assertNull(card.step())
        val pick = ChatSwitch.pick(card.folders[0])
        assertEquals(SwitchStep.Pick("/home/owner/Documents/Notes"), pick.step())

        // The computer opens it (it may add it first); the new chat starts in the folder it answers.
        transport.answer("POST /folders/open", 200, """{"added":true,"folder":{"path":"/home/owner/Documents/Notes","label":"Notes","kind":"project","colour":4}}""")
        val opened = api.openFolder("/home/owner/Documents/Notes")
        assertEquals(JsonPrimitive("/home/owner/Documents/Notes"), (transport.calls.last().body as kotlinx.serialization.json.JsonObject)["path"])
        assertTrue(opened.added)
        val folder = checkNotNull(opened.folder.asFolder())
        assertEquals(4, folder.colour)
        chat.newChat(folder)
        answers(event("done", """{"answer":"Hi","session_id":"s-2"}"""), done())
        chat.send("Hello")
        advanceUntilIdle()
        assertEquals(JsonPrimitive("/home/owner/Documents/Notes"), (transport.calls.last().body as kotlinx.serialization.json.JsonObject)["folder"])
    }

    @Test
    fun `a folder card the app cannot use is none, and at most five folders are offered`() {
        assertNull(ChatSwitch.of(json("""{"action":"choose_folder","sessions":[],"folders":[]}""")))
        assertNull(ChatSwitch.of(json("""{"action":"choose_folder","folders":[{"path":"relative","label":"x"}]}""")))
        val many = (1..7).joinToString(",") { """{"path":"/home/owner/f$it/Notes","label":"Notes"},{"path":"/home/owner/f$it/Notes","label":"Notes"}""" }
        assertEquals(5, ChatSwitch.of(json("""{"action":"choose_folder","folders":[$many]}"""))?.folders?.size)
        // A pick is only ever made on the phone: an answer cannot ask for one.
        assertNull(ChatSwitch.of(json("""{"action":"pick_folder","folders":[{"path":"/home/owner/x","label":"x"}]}""")))
    }

    @Test
    fun `find and remove lines never show`() {
        assertEquals("Here.\nEnd", Chat.visible("Here.\n[[switch-find: thesis]]\n[[switch-remove: old-site]]\nEnd"))
        assertEquals("Here.", Chat.streaming("Here.\n[[switch-fi"))
        assertEquals("Here.", Chat.streaming("Here.\n[[switch-remove: old"))
    }
}
