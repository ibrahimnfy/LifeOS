package dev.lifeos.app.data

import dev.lifeos.app.ui.components.whenLabel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The command bar (#185): requests, answers, failures, and a note's reminder. */
class CommandBarTest {
    private val transport = FakeTransport()
    private val api = Api(transport)
    private val istanbul = ZoneId.of("Europe/Istanbul")
    private val now = ZonedDateTime.of(2027, 1, 15, 9, 30, 12, 500, istanbul)

    @Test
    fun `a request carries its id, the app's language and the phone's time and zone`() {
        val body = CommandBar.body("a".repeat(32), "  yarın 10'da hatırlat  ", null, "tr-TR", now)
        assertEquals(JsonPrimitive("a".repeat(32)), body["request_id"])
        assertEquals(JsonPrimitive("yarın 10'da hatırlat"), body["text"])
        assertEquals(JsonPrimitive("tr"), body["language"])
        assertEquals(JsonPrimitive("2027-01-15T09:30:12+03:00"), body["now"])
        assertEquals(JsonPrimitive("Europe/Istanbul"), body["timezone"])
        assertNull(body["choice"])
        assertNull(body["previous"])
        // A pick sends its token and no text; an answer sends the earlier request and question.
        val pick = CommandBar.body("b".repeat(32), null, "token", "en-US", now.withZoneSameInstant(ZoneId.of("UTC")))
        assertEquals(JsonPrimitive("token"), pick["choice"])
        assertNull(pick["text"])
        assertNull(pick["timezone"]) // "UTC" is not a region name the computer is sent
        val answer = CommandBar.body("c".repeat(32), "Task", null, "en", now, CommandPrevious("milk", "A note or a task?"))
        assertEquals(JsonPrimitive("milk"), answer["previous"]!!.jsonObject["request"])
        assertEquals("en", CommandBar.language("de-DE"))
        assertEquals("tr-TR", CommandBar.speechLanguage("tr"))
        assertEquals("en-US", CommandBar.speechLanguage("en-GB"))
    }

    private val answerJson = """{"request_id":"r","results":[
        {"action":"add_task","ok":true,"title":"Buy milk","when":"2027-01-16","undo":"u1","error":null},
        {"action":"open_screen","ok":true,"title":"tasks","when":null,"undo":null,"error":null,"perform":{"action":"open_screen","screen":"tasks"}},
        {"action":"open_course","ok":true,"title":"Physics","perform":{"action":"open_course","course_id":42}}],
        "question":null}"""

    @Test
    fun `an answer is read with what the app performs`() = runTest {
        transport.answer("POST /command", 200, answerJson)
        val answer = api.command(CommandBar.body("r".repeat(16), "x", null, "en", now))
        assertEquals(listOf("add_task", "open_screen", "open_course"), answer.results.map { it.action })
        assertEquals("u1", answer.results[0].undo)
        assertEquals("2027-01-16", answer.results[0].moment)
        assertEquals("open_screen", answer.results[1].performs)
        assertEquals("tasks", answer.results[1].field("screen"))
        assertEquals(42L, answer.results[2].number("course_id"))
        // The first result that moves elsewhere: the bar closes and the app opens it.
        assertEquals("open_screen", CommandBar.navigation(answer)?.performs)
    }

    @Test
    fun `a question and an undo are read`() = runTest {
        transport.answer(
            "POST /command", 200,
            """{"results":[],"question":{"kind":"task","action":"complete_task","text":null,
               "options":[{"label":"Read one","detail":null,"choice":"t1"},{"label":"Read two","choice":"t2"}]}}""",
        )
        val answer = api.command(CommandBar.body("q".repeat(16), "done reading", null, "en", now))
        assertEquals("task", answer.question?.kind)
        assertEquals(listOf("t1", "t2"), answer.question?.options?.map { it.choice })
        assertNull(CommandBar.navigation(answer))
        transport.answer("POST /command/undo", 200, """{"undone":true,"action":"add_task"}""")
        assertTrue(api.commandUndo("u1").undone)
        assertEquals(JsonPrimitive("u1"), transport.calls.last().body!!.jsonObject["undo"])
    }

    @Test
    fun `failures lead to the outbox, a repeat, or a note`() {
        assertEquals(CommandBar.Failure.OFFLINE, CommandBar.failureOf(ApiException(ApiException.Kind.NOT_SENT)))
        assertEquals(CommandBar.Failure.LOST, CommandBar.failureOf(ApiException(ApiException.Kind.UNCERTAIN)))
        assertEquals(CommandBar.Failure.NOT_UNDERSTOOD, CommandBar.failureOf(ApiException(ApiException.Kind.HTTP, 422, "not_understood")))
        assertEquals(CommandBar.Failure.REFUSED, CommandBar.failureOf(ApiException(ApiException.Kind.HTTP, 429, "busy")))
        // Never a silent fallback to saving after an unclear attempt: it may have been done.
        assertFalse(CommandBar.failureOf(ApiException(ApiException.Kind.UNCERTAIN)) == CommandBar.Failure.OFFLINE)
    }

    @Test
    fun `a note is sent with its reminder and read back with it`() = runTest {
        transport.answer("POST /notes", 201, """{"saved":true,"id":"i-aaaaaaaaaaaa","at":"2027-01-15T09:30+03:00","reminder":{"id":"r1","note_id":"i-aaaaaaaaaaaa","at":"2027-01-16T07:00:00Z"}}""")
        api.addNote("call the office", "c".repeat(16), "2027-01-16T10:00:00+03:00", "Europe/Istanbul")
        val body = transport.calls.last().body!!.jsonObject
        assertEquals(JsonPrimitive("2027-01-16T10:00:00+03:00"), body["remind_at"])
        assertEquals(JsonPrimitive("Europe/Istanbul"), body["timezone"])
        api.addNote("plain")
        assertNull(transport.calls.last().body!!.jsonObject["remind_at"])

        val note = BridgeJson.decodeFromString(
            Note.serializer(),
            """{"id":"i-a","kind":"inbox","text":"x","reminder":{"id":"r1","session_id":null,"note_id":"i-a","at":"2027-01-16T07:00:00Z","state":"scheduled"}}""",
        )
        assertEquals("i-a", note.reminder?.noteId)
        assertNull(note.reminder?.sessionId)
        // Session lists are not confused by a note's reminder.
        assertEquals(emptySet<String>(), Reminders.sessionsWithOne(listOf(note.reminder!!)))
    }

    @Test
    fun `the outbox keeps a note's reminder with its zone`() {
        val item = OutboxItem("n1", OutboxItem.Kind.NOTE, 0, text = "x", remindAt = "2027-01-16T10:00:00+03:00", zone = "Europe/Istanbul")
        val back = BridgeJson.decodeFromString(OutboxItem.serializer(), BridgeJson.encodeToString(OutboxItem.serializer(), item))
        assertEquals(item, back)
        // Items stored before reminders existed are read as they were.
        val old = BridgeJson.decodeFromString(OutboxItem.serializer(), """{"id":"n2","kind":"NOTE","createdAtMillis":0,"text":"y"}""")
        assertNull(old.remindAt)
    }

    @Test
    fun `times of the bridge are shown on the phone's clock`() {
        val locale = Locale.ENGLISH
        assertEquals("Sat 16 Jan, 10:00", whenLabel("2027-01-16T07:00:00Z", locale, istanbul))
        assertEquals("Mon 18 Jan, 14:00", whenLabel("2027-01-18 14:00", locale, istanbul))
        assertEquals("Wed 20 Jan", whenLabel("2027-01-20", locale, istanbul))
        assertNull(whenLabel(null, locale, istanbul))
    }

    @Test
    fun `a reply to the coach carries the coach's last message, and an answer says what it understood`() {
        val said = "x".repeat(CommandBar.MAX_SAID) + " Shall I add it?"
        val reply = CommandBar.body("d".repeat(32), "yes, add it", null, "en", now, coachSaid = said)
        val sent = (reply["coach_said"] as JsonPrimitive).content
        assertEquals(CommandBar.MAX_SAID, sent.length)
        assertTrue(sent.endsWith("Shall I add it?"))
        // Never with a pick (the question was the computer's own), never when there is none.
        assertNull(CommandBar.body("e".repeat(32), null, "token", "en", now, coachSaid = said)["coach_said"])
        assertNull(CommandBar.body("f".repeat(32), "hello", null, "en", now, coachSaid = " ")["coach_said"])

        val both = BridgeJson.decodeFromString(
            CommandAnswer.serializer(),
            """{"request_id":"r","results":[{"action":"add_task","ok":true,"title":"Buy milk","undo":"t"}],"question":null,
               "understood":"Add a task: buy milk","talk":"How did I sleep?","cannot":null}""",
        )
        assertEquals("Add a task: buy milk", both.understood)
        assertEquals("How did I sleep?", both.talk)
        assertTrue(CommandBar.shows(both))
        val talk = BridgeJson.decodeFromString(CommandAnswer.serializer(), """{"results":[],"talk":"How are you?"}""")
        assertFalse(CommandBar.shows(talk)) // only conversation: nothing for the bar to show
        val cannot = BridgeJson.decodeFromString(CommandAnswer.serializer(), """{"results":[],"cannot":"The app cannot send e-mail."}""")
        assertTrue(CommandBar.shows(cannot))
        assertEquals("tr-TR", CommandBar.SPEECH_FIRST)
        assertEquals(listOf("en-US"), CommandBar.SPEECH_ALSO.toList())
    }
}
