package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Deleting a note (#184): the calls, what happens to what waits, and the outbox. */
class NoteDeleteTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val transport = FakeTransport()
    private val api = Api(transport)

    private fun note(id: String, state: OutboxItem.State = OutboxItem.State.WAITING, sending: Boolean = false, attempts: Int = 0) =
        OutboxItem(id, OutboxItem.Kind.NOTE, 0, text = "Sample $id", state = state, sending = sending, attempts = attempts)

    private fun edit(id: String, noteId: String, state: OutboxItem.State = OutboxItem.State.WAITING, sending: Boolean = false, attempts: Int = 0) =
        OutboxItem(id, OutboxItem.Kind.NOTE_EDIT, 0, text = "Changed", noteId = noteId, state = state, sending = sending, attempts = attempts)

    private fun delete(id: String, noteId: String) = OutboxItem(id, OutboxItem.Kind.NOTE_DELETE, 0, noteId = noteId)

    @Test
    fun `a deletion is a DELETE and an undo of the command bar a restore`() = runTest {
        transport.answer("DELETE /notes/i-aaaaaaaaaaaa", 200, """{"deleted":true,"id":"i-aaaaaaaaaaaa","kind":"inbox"}""")
        transport.answer("POST /notes/i-aaaaaaaaaaaa/restore", 200, """{"id":"i-aaaaaaaaaaaa","kind":"inbox","text":"x","restored":true}""")
        transport.answer("PATCH /notes/i-aaaaaaaaaaaa", 200, """{"id":"i-bbbbbbbbbbbb","kind":"inbox","text":"New"}""")
        api.deleteNote("i-aaaaaaaaaaaa")
        assertEquals("DELETE", transport.calls.last().method)
        api.restoreNote("i-aaaaaaaaaaaa")
        assertEquals("/notes/i-aaaaaaaaaaaa/restore", transport.calls.last().path)
        // An edit returns the note's new id, so a deletion queued behind it can follow.
        assertEquals("i-bbbbbbbbbbbb", api.editNote("i-aaaaaaaaaaaa", "New"))
    }

    @Test
    fun `a note that never left the phone is simply dropped`() {
        val outbox = listOf(note("a1"))
        assertEquals(NoteLists.Deletion.Discard("a1"), NoteLists.deletion("local-a1", outbox))
        // A refused one too: it will never reach the computer.
        assertEquals(NoteLists.Deletion.Discard("a1"), NoteLists.deletion("local-a1", listOf(note("a1", OutboxItem.State.FAILED))))
    }

    @Test
    fun `a note that may have arrived is deleted after it, by the computer's id`() {
        // On its way, or tried without an answer: it may be on the computer already.
        for (own in listOf(note("a1", sending = true), note("a1", attempts = 1))) {
            assertEquals(NoteLists.Deletion.Delete("local-a1"), NoteLists.deletion("local-a1", listOf(own)))
        }
        // It arrived meanwhile: the deletion names the computer's id.
        assertEquals(NoteLists.Deletion.Delete("i-cccccccccccc"), NoteLists.deletion("local-a1", emptyList()) { "i-cccccccccccc" })
    }

    @Test
    fun `an edit that never left is dropped and the original note deleted`() {
        val outbox = listOf(edit("e1", "i-aaaaaaaaaaaa"))
        assertEquals(NoteLists.Deletion.Delete("i-aaaaaaaaaaaa", dropEdit = "e1"), NoteLists.deletion("i-aaaaaaaaaaaa", outbox))
        assertEquals(NoteLists.Deletion.Delete("i-aaaaaaaaaaaa"), NoteLists.deletion("i-aaaaaaaaaaaa", emptyList()))
    }

    @Test
    fun `no deletion while an edit is on its way or may have arrived`() {
        for (pending in listOf(
            edit("e1", "i-aaaaaaaaaaaa", sending = true),
            edit("e1", "i-aaaaaaaaaaaa", state = OutboxItem.State.CHECK),
            edit("e1", "i-aaaaaaaaaaaa", attempts = 1),
        )) {
            assertFalse(NoteLists.canDelete("i-aaaaaaaaaaaa", listOf(pending)))
        }
        // A refused edit changed nothing: the note can go.
        assertTrue(NoteLists.canDelete("i-aaaaaaaaaaaa", listOf(edit("e1", "i-aaaaaaaaaaaa", state = OutboxItem.State.FAILED))))
    }

    @Test
    fun `deleted notes leave the list, the search and the waiting notes`() {
        val notes = listOf(NoteSummary("i-aaaaaaaaaaaa"), NoteSummary("i-bbbbbbbbbbbb"), NoteSummary("n-topic", "topic"))
        val outbox = listOf(note("a1"), note("a2"), delete("d1", "i-bbbbbbbbbbbb"))
        val hidden = NoteLists.hidden(outbox, justDeleted = setOf("n-topic", "local-a2"))
        assertEquals(listOf("i-aaaaaaaaaaaa"), NoteLists.rows(notes, outbox, hidden).map { it.note.id })
        assertEquals(listOf("a1"), NoteLists.waiting(outbox, hidden).map { it.id })
        // A refused deletion shows the note again (its notice says why).
        val refused = listOf(delete("d1", "i-bbbbbbbbbbbb").copy(state = OutboxItem.State.FAILED))
        assertEquals(2, NoteLists.rows(notes.take(2), refused, NoteLists.hidden(refused, emptySet())).size)
    }

    @Test
    fun `the outbox treats a missing note as deleted and follows a new id`() = runTest {
        val sent = mutableListOf<String?>()
        val outbox = Outbox(OutboxStore(File(folder.root, "outbox.json")), { 0L }) { item ->
            sent += item.noteId
            if (item.noteId == "i-gone00000000") throw ApiException(ApiException.Kind.HTTP, 404, "note_not_found")
        }
        assertTrue(delete("d0", "x").repeatable)
        outbox.add(delete("d1", "i-gone00000000"))
        outbox.add(edit("e1", "i-aaaaaaaaaaaa"))
        outbox.add(delete("d2", "local-a1"))
        // The note made here reached the computer, and an edit gave another note a new id.
        assertTrue(outbox.retargetNote("local-a1", "i-dddddddddddd"))
        outbox.retargetNote("i-aaaaaaaaaaaa", "i-eeeeeeeeeeee")
        assertEquals(3, outbox.drain())
        assertEquals(listOf("i-gone00000000", "i-eeeeeeeeeeee", "i-dddddddddddd"), sent)
        assertNull(outbox.items.value.firstOrNull())
    }
}
