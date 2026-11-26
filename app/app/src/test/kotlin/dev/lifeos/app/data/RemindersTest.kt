package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Session reminders and the reply-notification switch (#75). */
class RemindersTest {
    private val transport = FakeTransport()
    private val api = Api(transport)
    private val zone = ZoneId.of("Europe/Istanbul")

    private fun reminder(id: String, session: String, at: String, state: String = "scheduled") = Reminder(id, session, at, state = state)

    @Test
    fun `the time goes out with its offset and the zone's name, the note only when there is one`() {
        val at = checkNotNull(Reminders.moment("2027-01-15", "09:30", zone))
        assertEquals(
            """{"session_id":"s-1","at":"2027-01-15T09:30:00+03:00","note":"Call back","repeat":"weekly","timezone":"Europe/Istanbul"}""",
            Reminders.body("s-1", at, "  Call back ", Reminders.Repeat.WEEKLY).toString(),
        )
        // A change names no session; an emptied note is removed on the computer.
        assertEquals(
            """{"at":"2027-01-15T09:30:00+03:00","note":null,"repeat":"none","timezone":"Europe/Istanbul"}""",
            Reminders.body(null, at, "  ", Reminders.Repeat.ONCE).toString(),
        )
    }

    @Test
    fun `a zone without a name the computer knows is left out, the offset still says the time`() {
        val at = checkNotNull(Reminders.moment("2027-01-15", "09:30", ZoneOffset.ofHours(3)))
        val body = Reminders.body("s-1", at, "", Reminders.Repeat.DAILY)
        assertFalse("timezone" in body)
        assertEquals("\"2027-01-15T09:30:00+03:00\"", body["at"].toString())
    }

    @Test
    fun `an unreadable day or time gives no moment`() {
        assertNull(Reminders.moment("2027-02-30", "09:30", zone))
        assertNull(Reminders.moment("2027-01-15", "25:00", zone))
    }

    @Test
    fun `a one-off reminder needs a time ahead, a repeating one does not`() {
        val now = Instant.parse("2027-01-15T07:00:00Z")
        val past = Reminders.moment("2027-01-15", "09:30", zone)
        val ahead = Reminders.moment("2027-01-15", "10:30", zone)
        assertFalse(Reminders.usable(past, Reminders.Repeat.ONCE, now))
        assertTrue(Reminders.usable(ahead, Reminders.Repeat.ONCE, now))
        assertTrue(Reminders.usable(past, Reminders.Repeat.DAILY, now))
        assertFalse(Reminders.usable(null, Reminders.Repeat.DAILY, now))
    }

    @Test
    fun `a session's reminders to come are listed next first, and only those sessions get a bell`() {
        val all = listOf(
            reminder("r-2", "s-1", "2027-01-17T09:00:00Z"),
            reminder("r-1", "s-1", "2027-01-16T09:00:00Z"),
            reminder("r-3", "s-1", "2027-01-10T09:00:00Z", state = "fired"),
            reminder("r-4", "s-2", "2027-01-10T09:00:00Z", state = "fired"),
            reminder("r-5", "s-3", "2027-01-18T09:00:00Z"),
        )
        assertEquals(listOf("r-1", "r-2"), Reminders.scheduled(all, "s-1").map { it.id })
        assertEquals(setOf("s-1", "s-3"), Reminders.sessionsWithOne(all))
    }

    @Test
    fun `reminders are made, changed and deleted on the computer`() = runTest {
        val stored = """{"id":"r-9","session_id":"s-1","at":"2027-01-15T06:30:00Z","note":"Call back","repeat":"weekly","timezone":"Europe/Istanbul","state":"scheduled","last_fired":null}"""
        transport.answer("POST /reminders", 201, stored)
        transport.answer("PATCH /reminders/r-9", 200, stored)
        transport.answer("DELETE /reminders/r-9", 200, """{"deleted":true}""")
        transport.answer("GET /reminders", 200, """{"reminders":[$stored]}""")
        val at = checkNotNull(Reminders.moment("2027-01-15", "09:30", zone))
        val made = api.createReminder(Reminders.body("s-1", at, "Call back", Reminders.Repeat.WEEKLY))
        assertEquals("r-9", made.id)
        assertEquals(Instant.parse("2027-01-15T06:30:00Z"), made.moment)
        assertEquals(Reminders.Repeat.WEEKLY, Reminders.Repeat.of(made.repeat))
        api.updateReminder("r-9", Reminders.body(null, at, "", Reminders.Repeat.ONCE))
        assertEquals("PATCH", transport.calls.last().method)
        api.deleteReminder("r-9")
        assertEquals("/reminders/r-9", transport.calls.last().path)
        assertEquals(listOf("r-9"), api.get(ChatEndpoints.reminders).first.reminders.map { it.id })
        assertEquals(SessionEdits.Problem.GONE, SessionEdits.problemOf(ApiException(ApiException.Kind.HTTP, 404, "reminder_not_found")))
    }

    @Test
    fun `reply notifications are on unless switched off, and the switch sends only that`() = runTest {
        assertTrue(SessionSummary("s-1").notifyReplies)
        transport.answer("PATCH /sessions/s-1", 200, """{"id":"s-1","notify_replies":false}""")
        assertFalse(api.patchSession("s-1", SessionEdits.notifyReplies(false)).notifyReplies)
        assertEquals("""{"notify_replies":false}""", transport.calls.last().body.toString())
    }

    // ---- which conversation a notification leads to

    /** Each session is read by itself (#154), not looked up in the list of the newest hundred. */
    private fun sessions() {
        transport.answer("GET /sessions/s-1", 200, """{"id":"s-1","title":"A","folder":"/home/owner/brain","followable":false,"queue":[],"queue_paused":null}""")
        transport.answer("GET /sessions/s-2", 200, """{"id":"s-2","title":"B","folder":"/home/owner/project-alpha"}""")
        transport.answer("GET /sessions/c-1", 200, """{"id":"c-1","title":"Coach","folder":"/home/owner/brain","pinned":true}""")
        transport.answer("GET /sessions/s-404", 404, """{"error":"session_not_found"}""")
    }

    @Test
    fun `a fired reminder still leads to its session, with the folder it runs in`() = runTest {
        sessions()
        transport.answer("GET /reminders", 200, """{"reminders":[{"id":"r-1","session_id":"s-1","at":"2027-01-15T06:30:00Z","note":"Call back","repeat":"none","state":"fired"}]}""")
        transport.answer("GET /coach/chat", 200, """{"session_id":"c-1","started":true}""")
        transport.answer("GET /folders", 200, """{"folders":[{"path":"/home/owner/brain","label":"brain","kind":"memory","default":true}]}""")
        val reminder = checkNotNull(SessionLookup.reminder(api, "r-1"))
        assertEquals("Call back", reminder.note)
        val found = SessionLookup.find(api, checkNotNull(reminder.sessionId)) as SessionLookup.Found.Session
        assertEquals("s-1", found.session.id)
        assertTrue(found.memory)
        assertFalse((SessionLookup.find(api, "s-2") as SessionLookup.Found.Session).memory)
        assertNull(SessionLookup.reminder(api, "r-404"))
    }

    @Test
    fun `the coach's session leads to the coach's screen, an unknown one nowhere`() = runTest {
        sessions()
        transport.answer("GET /coach/chat", 200, """{"session_id":"c-1","started":true}""")
        assertEquals(SessionLookup.Found.Coach, SessionLookup.find(api, "c-1"))
        assertEquals(SessionLookup.Found.Missing, SessionLookup.find(api, "s-404"))
        transport.fail("GET /sessions/s-1", ApiException(ApiException.Kind.NOT_SENT))
        assertEquals(SessionLookup.Found.Unreachable, SessionLookup.find(api, "s-1"))
        // The list is not asked for at all.
        assertTrue(transport.calls.none { it.path == "/sessions" })
    }

    @Test
    fun `without the coach's answer or the folders a session is still opened`() = runTest {
        sessions()
        val found = SessionLookup.find(api, "s-2") as SessionLookup.Found.Session
        assertEquals("s-2", found.session.id)
        assertFalse(found.memory)
    }

    @Test
    fun `a note's reminder names the note, and a filed note still carries its reminder`() = runTest {
        val at = checkNotNull(Reminders.moment("2027-01-15", "09:30", zone))
        assertEquals(
            """{"note_id":"i-aaaaaaaaaaaa","at":"2027-01-15T09:30:00+03:00","repeat":"daily","timezone":"Europe/Istanbul"}""",
            Reminders.forNote("i-aaaaaaaaaaaa", at, Reminders.Repeat.DAILY).toString(),
        )
        // A capture the tidy-up filed (#191) is still answered under its id: read-only, with its reminder.
        transport.answer(
            "GET /notes/i-aaaaaaaaaaaa",
            200,
            """{"id":"i-aaaaaaaaaaaa","kind":"filed","title":null,"at":"2027-01-14 09:00","text":"Sample","filed_in":"projects/example-project.md",
                "reminder":{"id":"r1","session_id":null,"note_id":"i-aaaaaaaaaaaa","at":"2027-01-15T06:30:00Z","repeat":"none","state":"scheduled"}}""",
        )
        val note = api.get(Endpoints.note("i-aaaaaaaaaaaa")).first
        assertTrue(note.filed)
        assertFalse(note.editable)
        assertEquals("projects/example-project.md", note.filedIn)
        assertEquals("i-aaaaaaaaaaaa", note.reminder?.noteId)
        assertTrue(note.reminder?.scheduled == true)
    }
}
