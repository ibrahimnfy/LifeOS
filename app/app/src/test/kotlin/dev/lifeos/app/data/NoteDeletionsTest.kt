package dev.lifeos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A deletion in its undo time survives the end of the app (#191): kept on storage, completed at the next start. */
class NoteDeletionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var ids = 0

    private fun outbox() = Outbox(OutboxStore(File(folder.root, "outbox.json")), { 0L }) { }

    /** The app as it starts: everything read again from storage. */
    private fun start(outbox: Outbox = outbox(), resolve: (String) -> String? = { null }) =
        outbox to NoteDeletions(PendingDeleteStore(File(folder.root, "deleting.json")), outbox, { 0L }, { "d${ids++}" }, resolve)

    private fun deletes(outbox: Outbox) = outbox.items.value.filter { it.kind == OutboxItem.Kind.NOTE_DELETE }.map { it.noteId }

    @Test
    fun `a deletion still in its undo time is completed at the next start`() {
        val (outbox, deletions) = start()
        assertTrue(deletions.begin("i-aaaaaaaaaaaa", "Sample"))
        assertEquals(setOf("i-aaaaaaaaaaaa"), deletions.hidden.value)
        assertEquals(emptyList<String?>(), deletes(outbox))
        // The app dies here; nothing was sent. The next start finds the deletion and queues it.
        val (later, again) = start()
        assertEquals(setOf("i-aaaaaaaaaaaa"), again.hidden.value)
        assertTrue(again.finishLeftOver())
        assertEquals(listOf<String?>("i-aaaaaaaaaaaa"), deletes(later))
        assertEquals("Sample", later.items.value.single().text)
        assertEquals(emptySet<String>(), again.hidden.value)
        // And only once: a third start has nothing left to do.
        val (third, last) = start()
        assertFalse(last.finishLeftOver())
        assertEquals(1, deletes(third).size)
    }

    @Test
    fun `undo takes it back for good, and only in time`() {
        val (outbox, deletions) = start()
        deletions.begin("i-aaaaaaaaaaaa", "Sample")
        assertTrue(deletions.undo("i-aaaaaaaaaaaa"))
        assertFalse(deletions.finish("i-aaaaaaaaaaaa"))
        assertEquals(emptyList<String?>(), deletes(outbox))
        val (later, again) = start()
        assertFalse(again.finishLeftOver())
        assertEquals(emptyList<String?>(), deletes(later))
        // Once it is on its way, there is nothing to take back.
        again.begin("i-bbbbbbbbbbbb", "")
        assertTrue(again.finish("i-bbbbbbbbbbbb"))
        assertFalse(again.undo("i-bbbbbbbbbbbb"))
        assertEquals(listOf<String?>("i-bbbbbbbbbbbb"), deletes(later))
    }

    @Test
    fun `a replay after a crash between the two writes queues no second deletion`() {
        val (outbox, deletions) = start()
        deletions.begin("i-aaaaaaaaaaaa", "Sample")
        // The outbox item was written, the app died before the pending entry was cleared.
        outbox.add(OutboxItem("d-old", OutboxItem.Kind.NOTE_DELETE, 0, noteId = "i-aaaaaaaaaaaa"))
        val (later, again) = start()
        again.finishLeftOver()
        assertEquals(listOf<String?>("i-aaaaaaaaaaaa"), deletes(later))
        assertEquals(emptySet<String>(), again.hidden.value)
    }

    @Test
    fun `a note that never left the phone is dropped, also after a restart`() {
        val (outbox, deletions) = start()
        outbox.add(OutboxItem("a1a1a1a1a1a1a1a1", OutboxItem.Kind.NOTE, 0, text = "Sample"))
        deletions.begin("local-a1a1a1a1a1a1a1a1", "Sample")
        val (later, again) = start()
        assertFalse(again.finishLeftOver()) // nothing to send
        assertEquals(emptyList<OutboxItem>(), later.items.value)
    }

    @Test
    fun `a note made here that arrived meanwhile stays hidden and is deleted by the computer's id`() {
        val (_, deletions) = start()
        deletions.begin("local-a1a1a1a1a1a1a1a1", "Sample")
        // The note reached the computer during the undo time: the lists now show it under its id.
        deletions.retarget("local-a1a1a1a1a1a1a1a1", "i-cccccccccccc")
        assertEquals(setOf("local-a1a1a1a1a1a1a1a1", "i-cccccccccccc"), deletions.hidden.value)
        // The app dies; what it knew in memory is gone, the id it learnt is not.
        val (later, again) = start()
        assertEquals(setOf("local-a1a1a1a1a1a1a1a1", "i-cccccccccccc"), again.hidden.value)
        assertTrue(again.finishLeftOver())
        assertEquals(listOf<String?>("i-cccccccccccc"), deletes(later))
    }

    @Test
    fun `the id of a note made here is also found where the confirmed notes are kept`() {
        val (_, deletions) = start()
        deletions.begin("local-a1a1a1a1a1a1a1a1", "Sample")
        val (later, again) = start { name -> "i-dddddddddddd".takeIf { name == "local-a1a1a1a1a1a1a1a1" } }
        assertTrue(again.finishLeftOver())
        assertEquals(listOf<String?>("i-dddddddddddd"), deletes(later))
    }

    @Test
    fun `nothing is deleted while an edit of the note is on its way`() {
        val (outbox, deletions) = start()
        outbox.add(OutboxItem("e1", OutboxItem.Kind.NOTE_EDIT, 0, text = "Changed", noteId = "i-aaaaaaaaaaaa", attempts = 1))
        assertFalse(deletions.begin("i-aaaaaaaaaaaa", "Sample"))
        assertEquals(emptySet<String>(), deletions.hidden.value)
        // An edit that never left is dropped with the deletion.
        outbox.add(OutboxItem("e2", OutboxItem.Kind.NOTE_EDIT, 0, text = "Changed", noteId = "i-bbbbbbbbbbbb"))
        assertTrue(deletions.begin("i-bbbbbbbbbbbb", "Sample"))
        assertTrue(deletions.finish("i-bbbbbbbbbbbb"))
        assertEquals(listOf("e1", "d0"), outbox.items.value.map { it.id })
    }

    @Test
    fun `unpairing forgets what was being deleted`() {
        val (_, deletions) = start()
        deletions.begin("i-aaaaaaaaaaaa", "Sample")
        deletions.wipe()
        assertEquals(emptySet<String>(), deletions.hidden.value)
        assertFalse(start().second.finishLeftOver())
    }
}
