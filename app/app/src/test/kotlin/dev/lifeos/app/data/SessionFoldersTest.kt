package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sessions screen by folder (#168): one tile per folder from the bridge, all blank chats
 * one tile, a folder's conversations pinned first. Custom groups are gone; titles and pins stay.
 */
class SessionFoldersTest {
    private val transport = FakeTransport()
    private val api = Api(transport)

    private fun session(id: String, minute: Int, key: String = "/home/owner/project-alpha", kind: String = FolderKinds.PROJECT, pinned: Boolean = false) =
        SessionSummary(
            id,
            "Title $id",
            folder = if (kind == FolderKinds.CHAT) "/home/owner/LifeOS-Chats/2027-01-15-$id" else key,
            lastActivity = "2027-01-15T08:%02d:00Z".format(minute),
            folderKey = key,
            folderLabel = if (kind == FolderKinds.CHAT) "Chats" else key.substringAfterLast('/'),
            kind = kind,
            pinned = pinned,
        )

    @Test
    fun `the list is read with each session's folder and the folders for the tiles`() = runTest {
        transport.answer(
            "GET /sessions",
            200,
            """{"sessions":[{"id":"s-1","title":"My name for it","detected_title":"What is open?","folder":"/home/owner/LifeOS-Chats/2027-01-15-a1b2c3","last_activity":"2027-01-15T08:00:00Z","in_use":false,"pinned":true,"notify_replies":true,"folder_key":"chats","folder_label":"Chats","kind":"chat","colour":0}],"total":1,
               "folders":[{"key":"chats","name":"Chats","kind":"chat","path":"/home/owner/LifeOS-Chats","colour":0,"count":12,"last_activity":"2027-01-15T08:00:00Z"},
                          {"key":"coach","name":"Coach","kind":"coach","path":"/home/owner/brain","colour":1,"count":2,"last_activity":null},
                          {"key":"/home/owner/project-alpha","name":"project-alpha","kind":"project","path":"/home/owner/project-alpha","colour":3,"count":0,"last_activity":null}]}""",
        )
        val list = api.get(ChatEndpoints.sessions).first
        val session = list.sessions.single()
        assertEquals("chats", session.tileKey)
        assertTrue(session.isChat)
        assertEquals("What is open?", session.detectedTitle)
        assertEquals("My name for it", session.shownTitle)
        val tiles = SessionLists.tiles(list)
        // The bridge's order, its counts (over all sessions, not only the listed ones) and colours.
        assertEquals(listOf("chats", "coach", "/home/owner/project-alpha"), tiles.map { it.key })
        assertEquals(listOf(12, 2, 0), tiles.map { it.count })
        assertEquals(listOf(0, 1, 3), tiles.map { it.colour })
        assertEquals(listOf(FolderKinds.CHAT, FolderKinds.COACH, FolderKinds.PROJECT), tiles.map { it.kind })
    }

    @Test
    fun `all blank chats are one folder, listed pinned first and then newest first`() {
        val sessions = listOf(
            session("a", 1, key = "chats", kind = FolderKinds.CHAT),
            session("b", 5, key = "chats", kind = FolderKinds.CHAT),
            session("c", 3, key = "chats", kind = FolderKinds.CHAT, pinned = true),
            session("d", 9),
            session("e", 2, key = "chats", kind = FolderKinds.CHAT, pinned = true),
        )
        assertEquals(listOf("c", "e", "b", "a"), SessionLists.inFolder(sessions, "chats").map { it.id })
        assertEquals(listOf("d"), SessionLists.inFolder(sessions, "/home/owner/project-alpha").map { it.id })
        assertTrue(SessionLists.inFolder(sessions, "/home/owner/elsewhere").isEmpty())
    }

    @Test
    fun `a bridge that sends no folders still gets tiles, made from the sessions`() {
        val sessions = listOf(
            session("a", 1),
            session("b", 7, key = "chats", kind = FolderKinds.CHAT),
            session("c", 4),
            SessionSummary("old", "No folder fields", "/home/owner/notes", "2027-01-15T08:00:00Z"),
        )
        val tiles = SessionLists.tiles(SessionList(sessions))
        // The folder with the newest session first; without a key, the session's own folder.
        assertEquals(listOf("chats", "/home/owner/project-alpha", "/home/owner/notes"), tiles.map { it.key })
        assertEquals(listOf(1, 2, 1), tiles.map { it.count })
        assertEquals("notes", tiles.last().name)
        assertTrue(SessionLists.tiles(SessionList()).isEmpty())
    }

    @Test
    fun `a folder's colour is a stable place in the palette`() {
        assertEquals(Looks.folder(0), Looks.folder(0))
        assertEquals(Tone.SKY, Looks.folder(0).tone)
        assertEquals(Tone.TEAL, Looks.folder(1).tone)
        // Past the seven tones the palette starts again; a negative number is still a colour.
        assertEquals(Looks.folder(2).tone, Looks.folder(9).tone)
        assertEquals(Tone.ROSE, Looks.folder(-1).tone)
        assertEquals(Glyph.FOLDER, Looks.folder(4).glyph)
        // Neighbouring folders differ in colour and ornament.
        assertFalse(Looks.folder(3).tone == Looks.folder(4).tone || Looks.folder(3).pattern == Looks.folder(4).pattern)
    }

    @Test
    fun `renaming and pinning send only that field, and groups are not asked for any more`() = runTest {
        transport.answer("PATCH /sessions/s-1", 200, """{"id":"s-1","title":"New name","pinned":true}""")
        api.patchSession("s-1", SessionEdits.title("  New \n name  "))
        assertEquals("""{"title":"New name"}""", transport.calls.last().body.toString())
        api.patchSession("s-1", SessionEdits.title("   "))
        assertEquals("""{"title":null}""", transport.calls.last().body.toString())
        api.patchSession("s-1", SessionEdits.pinned(true))
        assertEquals("""{"pinned":true}""", transport.calls.last().body.toString())
        assertTrue(transport.calls.none { it.path.startsWith("/session-groups") })
    }

    @Test
    fun `a title is one line of at most 120 characters`() {
        assertEquals(120, SessionEdits.line("x".repeat(300), SessionEdits.MAX_TITLE).length)
        assertEquals("a b", SessionEdits.line(" a\n\tb ", 10))
    }

    @Test
    fun `a refusal says what it was`() {
        assertEquals(SessionEdits.Problem.GONE, SessionEdits.problemOf(ApiException(ApiException.Kind.HTTP, 404, "session_not_found")))
        assertEquals(SessionEdits.Problem.GONE, SessionEdits.problemOf(ApiException(ApiException.Kind.HTTP, 404, "reminder_not_found")))
        assertEquals(SessionEdits.Problem.REFUSED, SessionEdits.problemOf(ApiException(ApiException.Kind.HTTP, 400, "bad_request")))
        assertEquals(SessionEdits.Problem.NOT_REACHED, SessionEdits.problemOf(ApiException(ApiException.Kind.NOT_SENT)))
        assertEquals(SessionEdits.Problem.UNCERTAIN, SessionEdits.problemOf(ApiException(ApiException.Kind.UNCERTAIN)))
        assertEquals(SessionEdits.Problem.FAILED, SessionEdits.problemOf(ApiException(ApiException.Kind.HTTP, 500, "internal")))
    }
}
