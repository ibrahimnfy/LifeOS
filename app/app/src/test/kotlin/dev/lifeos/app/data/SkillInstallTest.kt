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
import java.time.Instant

/** Skill installs: cards that wait across runs, and the per-session skills switch (#97). */
@OptIn(ExperimentalCoroutinesApi::class)
class SkillInstallTest {
    private val transport = FakeTransport()
    private val api = Api(transport)
    private val store = object : ChatStore {
        var pointer: ChatPointer? = ChatPointer("s-1", "/home/owner/project-alpha", "project-alpha")
        override fun load(): ChatPointer? = pointer
        override fun save(pointer: ChatPointer?) {
            this.pointer = pointer
        }
    }

    /** The phone's running clock, moved by hand; the wall clock is far off to show which one is used. */
    private var uptime = 50_000L
    private val wall = 1_900_000_000_000L

    private fun TestScope.chat() = Chat(api, store, this, { wall }, { true }, { uptime })

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun event(name: String, data: String) = AnswerEvent.Stream(name, json(data))
    private fun done() = AnswerEvent.Done(RelayResponse(200, null))

    private fun card(state: String, id: String = "a1b2c3d4e5f6", hoursLeft: Long = 20): String {
        val times = """"created_at":"${Instant.ofEpochMilli(wall - (24 - hoursLeft) * 3_600_000)}","expires_at":"${Instant.ofEpochMilli(wall + hoursLeft * 3_600_000)}""""
        val head = """"id":"$id","kind":"skill_install","session_id":"s-1","folder":"/home/owner/project-alpha","source":"https://skills.example.org/tidy.zip""""
        return when (state) {
            "ready" -> """{$head,"state":"ready","name":"tidy","target":"/home/owner/project-alpha/.claude/skills/tidy","description":"Tidy the notes.","warnings":["shell_lines","has_scripts"],"files":[{"path":"SKILL.md","size":812},{"path":"scripts/run.py","size":300}],"total_size":1112,$times}"""
            "failed" -> """{$head,"state":"failed","error":"there is no SKILL.md at the top","error_code":"source_refused",$times}"""
            else -> """{$head,"state":"downloading",$times}"""
        }
    }

    private fun pending(vararg cards: String) = transport.answer("GET /sessions/s-1/interactions", 200, """{"pending":[${cards.joinToString(",")}]}""")
    private fun posts() = transport.calls.filter { it.method == "POST" && "/interactions/" in it.path }

    /** The conversation as it is opened: nothing runs, one exchange is there. */
    private suspend fun TestScope.opened(vararg cards: String): Chat {
        transport.answer("GET /sessions/s-1/queue", 200, """{"running":false,"paused":null,"queue":[]}""")
        transport.answer("GET /sessions/s-1/history", 200, """{"exchanges":[{"user":"Which skill would help?","assistant":"One for tidying.","complete":true}],"older":null}""")
        pending(*cards)
        val chat = chat()
        chat.restore()
        chat.load()
        advanceUntilIdle()
        return chat
    }

    @Test
    fun `a card that waits across runs is shown when the conversation is opened, with its whole day`() = runTest {
        val chat = opened(card("ready"))
        assertEquals(ChatPhase.IDLE, chat.state.value.phase)
        val shown = chat.state.value.interactions.single()
        assertEquals(Interaction.Kind.SKILL_INSTALL, shown.kind)
        assertTrue(shown.crossRun)
        // Twenty hours are left by the computer's end time: not cut down to the hour of a run's question.
        assertEquals(uptime + 20 * 3_600_000L, shown.deadline)
        val install = checkNotNull(shown.install)
        assertEquals("https://skills.example.org/tidy.zip", install.source)
        assertEquals("/home/owner/project-alpha/.claude/skills/tidy", install.target)
        assertEquals(listOf("SKILL.md" to 812L, "scripts/run.py" to 300L), install.files.map { it.path to it.size })
        assertEquals(listOf("shell_lines", "has_scripts"), install.warnings)
        assertEquals(1112L, install.totalSize)
        assertTrue(shown.open(uptime) && shown.canApprove)
    }

    @Test
    fun `it stays through the next message and the end of that run`() = runTest {
        val chat = opened(card("ready"))
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"s-1"}"""))
                emit(event("interaction", """{"id":"q9","session_id":"s-1","kind":"approval","command":"git push","folder":"/home/owner/project-alpha","category":"push","created_at":"2027-01-15T08:00:00Z","expires_at":"2027-01-15T08:10:00Z"}"""))
                emit(event("done", """{"answer":"Done","session_id":"s-1"}"""))
                emit(done())
            }
        }
        assertTrue(chat.send("Go on"))
        advanceUntilIdle()
        val cards = chat.state.value.interactions.associateBy { it.id }
        // The run's own card ended with the run; the install still waits.
        assertEquals(Interaction.Status.GONE, cards.getValue("q9").status)
        assertEquals(Interaction.Status.WAITING, cards.getValue("a1b2c3d4e5f6").status)

        // The message after that drops the settled card of the run, not the install.
        assertTrue(chat.send("And again"))
        assertEquals(listOf("a1b2c3d4e5f6"), chat.state.value.interactions.map { it.id })
        advanceUntilIdle()
    }

    @Test
    fun `a skill that is being downloaded cannot be installed, and becomes ready by asking again`() = runTest {
        val chat = opened(card("downloading"))
        assertEquals(SkillInstall.State.DOWNLOADING, chat.state.value.interactions.single().install?.state)
        chat.answerApproval("a1b2c3d4e5f6", approve = true)
        runCurrent()
        assertTrue(posts().isEmpty())

        pending(card("ready"))
        chat.refreshCards()
        val ready = chat.state.value.interactions.single()
        assertEquals(SkillInstall.State.READY, ready.install?.state)
        assertEquals("tidy", ready.install?.name)

        transport.answer("POST /sessions/s-1/interactions/a1b2c3d4e5f6", 200, """{"answered":true,"id":"a1b2c3d4e5f6","installed":true,"name":"tidy","target":"/home/owner/project-alpha/.claude/skills/tidy"}""")
        chat.answerApproval("a1b2c3d4e5f6", approve = true)
        runCurrent()
        assertEquals("""{"approve":true}""", posts().single().body.toString())
        assertEquals(Interaction.Status.ALLOWED, chat.state.value.interactions.single().status)
    }

    @Test
    fun `a failed download says why and can only be put away`() = runTest {
        val chat = opened(card("failed"))
        val failed = chat.state.value.interactions.single()
        assertEquals(SkillInstall.Problem.SOURCE_REFUSED, failed.install?.problem)
        assertFalse(failed.canApprove)
        transport.answer("POST /sessions/s-1/interactions/a1b2c3d4e5f6", 200, """{"answered":true,"id":"a1b2c3d4e5f6","installed":false}""")
        chat.answerApproval("a1b2c3d4e5f6", approve = false)
        runCurrent()
        assertEquals("""{"approve":false}""", posts().single().body.toString())
        assertEquals(Interaction.Status.REFUSED, chat.state.value.interactions.single().status)
    }

    @Test
    fun `a yes the computer refuses leaves the card waiting, with the reason, and it can be answered again`() = runTest {
        val path = "POST /sessions/s-1/interactions/a1b2c3d4e5f6"
        val chat = opened(card("ready"))

        suspend fun TestScope.yes(status: Int, body: String): Interaction {
            transport.answer(path, status, body)
            chat.answerApproval("a1b2c3d4e5f6", approve = true)
            runCurrent()
            return chat.state.value.interactions.single()
        }
        for ((code, status, refusal) in listOf(
            Triple("still_downloading", 409, Interaction.Refusal.STILL_DOWNLOADING),
            Triple("skill_exists", 409, Interaction.Refusal.EXISTS),
            Triple("audit_unavailable", 503, Interaction.Refusal.UNRECORDED),
            Triple("install_failed", 500, Interaction.Refusal.WRITE_FAILED),
        )) {
            val looks = transport.calls.count { it.method == "GET" && it.path.endsWith("/interactions") }
            val card = yes(status, """{"error":"$code"}""")
            assertEquals(code, Interaction.Status.WAITING, card.status)
            assertEquals(code, refusal, card.refusal)
            // Refused is not "could not be sent"; the card still shows its files and is open for an answer.
            assertFalse(card.sendFailed)
            assertTrue(card.open(uptime) && card.canApprove)
            assertEquals(2, card.install?.files?.size)
            // The computer's list is looked at again: what the card shows may be behind.
            assertEquals(looks + 1, transport.calls.count { it.method == "GET" && it.path.endsWith("/interactions") })
        }
        // Anything else did not get through.
        val unsent = yes(500, """{"error":"internal"}""")
        assertEquals(Interaction.Status.WAITING, unsent.status)
        assertTrue(unsent.sendFailed)
        assertNull(unsent.refusal)

        // A no is always taken, and clears what was said before.
        transport.answer(path, 200, """{"answered":true,"id":"a1b2c3d4e5f6","installed":false}""")
        chat.answerApproval("a1b2c3d4e5f6", approve = false)
        runCurrent()
        val declined = chat.state.value.interactions.single()
        assertEquals(Interaction.Status.REFUSED, declined.status)
        assertNull(declined.refusal)
    }

    @Test
    fun `an answer that another one overtook closes the card`() = runTest {
        val chat = opened(card("ready"))
        transport.answer("POST /sessions/s-1/interactions/a1b2c3d4e5f6", 404, """{"error":"not_pending"}""")
        chat.answerApproval("a1b2c3d4e5f6", approve = true)
        runCurrent()
        assertEquals(Interaction.Status.GONE, chat.state.value.interactions.single().status)
    }

    @Test
    fun `a card answered elsewhere or out of time is closed by the computer's list`() = runTest {
        val chat = opened(card("ready"))
        pending()
        chat.refreshCards()
        assertEquals(Interaction.Status.GONE, chat.state.value.interactions.single().status)
    }

    @Test
    fun `a capable answer's proposals become cards, a refused one says so, and the marker line is not shown`() = runTest {
        val chat = opened()
        val gate = CompletableDeferred<Unit>()
        transport.events = {
            flow {
                emit(event("session", """{"session_id":"s-1"}"""))
                emit(event("delta", """{"text":"This would help.\n[[skill-install: https://skills.example.org/tidy.zip]]\n"}"""))
                gate.await()
                emit(
                    event(
                        "done",
                        """{"answer":"This would help.","session_id":"s-1","skill_installs":[${card("downloading", hoursLeft = 24)},{"kind":"skill_install","source":"http://skills.example.org/plain.zip","state":"refused","error":"only HTTPS sources can be installed","error_code":"source_not_https"}]}""",
                    ),
                )
                emit(done())
            }
        }
        assertTrue(chat.send("Which skill would help?"))
        runCurrent()
        // While it is written the line that proposes the install is not part of the text.
        assertEquals(ChatPhase.ANSWERING, chat.state.value.phase)
        assertEquals("This would help.", chat.state.value.messages.last().text)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("This would help.", chat.state.value.messages.last().text)
        val (proposed, refused) = chat.state.value.interactions
        assertEquals(Interaction.Status.WAITING, proposed.status)
        // Just made: the whole day lies ahead, by the computer's own two times.
        assertEquals(uptime + 24 * 3_600_000L, proposed.deadline)
        assertEquals(SkillInstall.State.REFUSED, refused.install?.state)
        assertEquals(SkillInstall.Problem.NOT_HTTPS, refused.install?.problem)
        assertFalse(refused.open(uptime))
        assertEquals("http://skills.example.org/plain.zip", refused.install?.source)
    }

    @Test
    fun `the bridge's words for a failed proposal are told apart, and marker lines of earlier answers are hidden`() {
        val codes = mapOf(
            "source_not_https" to SkillInstall.Problem.NOT_HTTPS,
            "audit_unavailable" to SkillInstall.Problem.UNRECORDED,
            "source_refused" to SkillInstall.Problem.SOURCE_REFUSED,
            "source_too_large" to SkillInstall.Problem.TOO_LARGE,
            "source_unreadable" to SkillInstall.Problem.UNREADABLE,
            "skill_exists" to SkillInstall.Problem.EXISTS,
            "download_failed" to SkillInstall.Problem.DOWNLOAD,
        )
        for ((code, problem) in codes) assertEquals(problem, SkillInstall.problem(code))
        // A word this app does not know, or none: still a card that says it cannot be installed.
        assertEquals(SkillInstall.Problem.OTHER, SkillInstall.problem("something_new"))
        assertEquals(SkillInstall.Problem.OTHER, SkillInstall.problem(null))
        // The sentence is not read: only the word decides.
        val failed = BridgeJson.decodeFromString(InteractionData.serializer(), """{"id":"a1","kind":"skill_install","source":"https://x.example.org/s.zip","state":"failed","error":"the download is larger than 5 MB","error_code":"source_unreadable"}""")
        assertEquals(SkillInstall.Problem.UNREADABLE, SkillInstall.of(failed).problem)
        assertEquals("Before\nAfter", Chat.visible("Before\n  [[skill-install: https://skills.example.org/x.zip]]\nAfter"))
        // Without an id or a source there is nothing to show.
        assertNull(Interaction.of(InteractionData(id = "a1", kind = "skill_install"), 0, 0, fresh = true))
    }

    @Test
    fun `the skills switch is one field of the session on the computer`() = runTest {
        transport.answer("PATCH /sessions/s-1", 200, """{"id":"s-1","skills":true}""")
        val changed = api.patchSession("s-1", SessionEdits.skills(true))
        assertTrue(changed.skills)
        assertEquals("""{"skills":true}""", transport.calls.single().body.toString())
        assertEquals("""{"skills":false}""", SessionEdits.skills(false).toString())
    }
}
