package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's own organisation of sessions (#74): groups, titles, pins. */
class SessionGroupsTest {
    private val transport = FakeTransport()
    private val api = Api(transport)

    private fun session(id: String, minute: Int, folder: String = "/home/owner/project-alpha", group: String? = null, pinned: Boolean = false) =
        SessionSummary(id, "Title $id", folder, "2027-01-15T08:%02d:00Z".format(minute), autoGroup = folder.substringAfterLast('/'), pinned = pinned, group = group)

    private val groups = listOf(SessionGroup("g1", "Thesis"), SessionGroup("g2", "Errands"))

    @Test
    fun `the session list is read with the owner's groups and each session's own`() = runTest {
        transport.answer(
            "GET /sessions",
            200,
            """{"sessions":[{"id":"s-1","title":"My name for it","detected_title":"What is open?","folder":"/home/owner/brain","last_activity":"2027-01-15T08:00:00Z","in_use":false,"group":"g1","auto_group":"brain","pinned":true,"notify_replies":true,"model":null,"effort":null,"skills":false}],"total":1,"groups":[{"id":"g1","name":"Thesis"}]}""",
        )
        val list = api.get(ChatEndpoints.sessions).first
        assertEquals(listOf(SessionGroup("g1", "Thesis")), list.groups)
        assertEquals("g1", list.sessions[0].group)
        assertEquals("What is open?", list.sessions[0].detectedTitle)
        assertEquals("My name for it", list.sessions[0].shownTitle)
    }

    @Test
    fun `groups come in the owner's order with their sessions newest first, empty ones too`() {
        val sessions = listOf(session("a", 1, group = "g2"), session("b", 5, group = "g2"), session("c", 3))
        val own = SessionLists.inGroups(sessions, groups)
        assertEquals(listOf("Thesis", "Errands"), own.map { it.group.name })
        assertTrue(own[0].sessions.isEmpty())
        assertEquals(listOf("b", "a"), own[1].sessions.map { it.id })
    }

    @Test
    fun `a session in a group is not listed under its folder, a pinned one in neither`() {
        val sessions = listOf(session("a", 1, group = "g1"), session("b", 2), session("c", 3, group = "g1", pinned = true))
        assertEquals(listOf("b"), SessionLists.byFolder(sessions, groups).flatMap { it.sessions }.map { it.id })
        assertEquals(listOf("a"), SessionLists.inGroups(sessions, groups).flatMap { it.sessions }.map { it.id })
        assertEquals(listOf("c"), SessionLists.pinned(sessions).map { it.id })
    }

    @Test
    fun `a session whose group is gone is listed under its folder again`() {
        val sessions = listOf(session("a", 1, group = "deleted"), session("b", 2, folder = "/home/owner/brain"))
        assertEquals(listOf("b", "a"), SessionLists.byFolder(sessions, groups).flatMap { it.sessions }.map { it.id })
        assertTrue(SessionLists.inGroups(sessions, groups).all { it.sessions.isEmpty() })
    }

    @Test
    fun `renaming, pinning and moving send only that field`() = runTest {
        transport.answer("PATCH /sessions/s-1", 200, """{"id":"s-1","title":"New name","pinned":true,"group":"g1"}""")
        api.patchSession("s-1", SessionEdits.title("  New \n name  "))
        assertEquals("""{"title":"New name"}""", transport.calls.last().body.toString())
        api.patchSession("s-1", SessionEdits.title("   "))
        assertEquals("""{"title":null}""", transport.calls.last().body.toString())
        api.patchSession("s-1", SessionEdits.pinned(true))
        assertEquals("""{"pinned":true}""", transport.calls.last().body.toString())
        api.patchSession("s-1", SessionEdits.group("g1"))
        assertEquals("""{"group":"g1"}""", transport.calls.last().body.toString())
        val answer = api.patchSession("s-1", SessionEdits.group(null))
        assertEquals("""{"group":null}""", transport.calls.last().body.toString())
        assertEquals("g1", answer.group)
    }

    @Test
    fun `a title is one line of at most 120 characters, a group's name of 60`() {
        assertEquals(120, SessionEdits.line("x".repeat(300), SessionEdits.MAX_TITLE).length)
        assertEquals("\"" + "y".repeat(60) + "\"", SessionEdits.name("y".repeat(80))["name"].toString())
        assertEquals("a b", SessionEdits.line(" a\n\tb ", 10))
    }

    @Test
    fun `groups are made, renamed and deleted on the computer`() = runTest {
        transport.answer("POST /session-groups", 201, """{"id":"g9","name":"Thesis"}""")
        transport.answer("PATCH /session-groups/g9", 200, """{"id":"g9","name":"Thesis 2"}""")
        transport.answer("DELETE /session-groups/g9", 200, """{"deleted":true}""")
        assertEquals(SessionGroup("g9", "Thesis"), api.createGroup(" Thesis "))
        assertEquals("""{"name":"Thesis"}""", transport.calls.last().body.toString())
        assertEquals("Thesis 2", api.renameGroup("g9", "Thesis 2").name)
        api.deleteGroup("g9")
        assertEquals("DELETE", transport.calls.last().method)
        assertNull(transport.calls.last().body)
    }

    @Test
    fun `a refusal says what it was`() = runTest {
        transport.answer("POST /session-groups", 409, """{"error":"group_exists","message":"a group with this name already exists"}""")
        val refused = runCatching { api.createGroup("Thesis") }.exceptionOrNull() as ApiException
        assertEquals(SessionEdits.Problem.GROUP_EXISTS, SessionEdits.problemOf(refused))
        assertEquals(SessionEdits.Problem.GONE, SessionEdits.problemOf(ApiException(ApiException.Kind.HTTP, 404, "group_not_found")))
        assertEquals(SessionEdits.Problem.GONE, SessionEdits.problemOf(ApiException(ApiException.Kind.HTTP, 404, "session_not_found")))
        assertEquals(SessionEdits.Problem.REFUSED, SessionEdits.problemOf(ApiException(ApiException.Kind.HTTP, 400, "bad_request")))
        assertEquals(SessionEdits.Problem.NOT_REACHED, SessionEdits.problemOf(ApiException(ApiException.Kind.NOT_SENT)))
        assertEquals(SessionEdits.Problem.UNCERTAIN, SessionEdits.problemOf(ApiException(ApiException.Kind.UNCERTAIN)))
        assertEquals(SessionEdits.Problem.FAILED, SessionEdits.problemOf(ApiException(ApiException.Kind.HTTP, 500, "internal")))
    }
}
