package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The owner's own note on a task (#170): read, sent, shown while waiting, kept apart from the coach's next step. */
class TaskNoteTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val phoneId = "0123456789abcdef"
    private val localId = LocalItems.PREFIX + phoneId

    @Test
    fun `the note is read apart from the coach's next step, and old answers have none`() {
        val task = BridgeJson.decodeFromString(
            Task.serializer(),
            """{"id":"t-20270115-01","title":"Essay","done":false,"due":null,"project":null,"course":null,"added":"2027-01-10","next":"Outline it","note":"Two pages [at most]"}""",
        )
        assertEquals("Two pages [at most]", task.note)
        assertEquals("Outline it", task.next)
        val old = BridgeJson.decodeFromString(Task.serializer(), """{"id":"t-1","title":"Old","done":false}""")
        assertNull(old.note)
        val saved = BridgeJson.decodeFromString(TaskSaved.serializer(), """{"id":"t-2","title":"New","note":"Kept","repeat":false}""")
        assertEquals("Kept", saved.task()!!.note)
    }

    @Test
    fun `a new task sends its note only when it has one`() = runTest {
        val transport = FakeTransport()
        transport.answer("POST /tasks", 201, """{"id":"t-20270115-01","title":"Sample","note":"Bring the notes","repeat":false}""")
        val api = Api(transport)
        val task = api.addTask("Sample", null, phoneId, "  Bring the notes ").task()!!
        assertEquals(JsonPrimitive("Bring the notes"), transport.calls.last().body!!.jsonObject["note"])
        assertEquals("Bring the notes", task.note)
        api.addTask("Sample", null, phoneId, "   ")
        assertFalse("note" in transport.calls.last().body!!.jsonObject)
        api.addTask("Sample", null, phoneId)
        assertFalse("note" in transport.calls.last().body!!.jsonObject)
    }

    @Test
    fun `an edit sends the note only when it changes it, and an empty one removes it`() = runTest {
        val transport = FakeTransport()
        transport.answer("PATCH /tasks/t-1", 200, """{"id":"t-1","title":"Renamed","done":false}""")
        val api = Api(transport)
        api.editTask("t-1", "Renamed", null)
        assertFalse("note" in transport.calls.last().body!!.jsonObject)
        api.editTask("t-1", "Renamed", null, "New note")
        assertEquals(JsonPrimitive("New note"), transport.calls.last().body!!.jsonObject["note"])
        api.editTask("t-1", "Renamed", null, "")
        assertEquals(JsonNull, transport.calls.last().body!!.jsonObject["note"])
    }

    @Test
    fun `what an edit does to the note`() {
        assertNull(TaskNote.change("Same", "  Same "))
        assertNull(TaskNote.change(null, ""))
        assertNull(TaskNote.change("Kept", null))
        assertEquals("", TaskNote.change("Old", "  "))
        assertEquals("New", TaskNote.change("Old", "New "))
        assertEquals("New", TaskNote.change(null, "New"))
    }

    @Test
    fun `the field keeps one paragraph of at most 500 characters`() {
        assertEquals("first second  third", TaskNote.typed("first\nsecond\r\tthird"))
        assertEquals("bell", TaskNote.typed("be\u0007ll"))
        assertEquals(500, TaskNote.typed("x".repeat(600)).length)
        assertEquals(TaskNote.MAX, 500)
    }

    @Test
    fun `a waiting edit shows its note, and an edit without one leaves the note alone`() {
        val task = Task("t-1", "Essay", note = "Old note", next = "Outline it")
        fun edit(note: String?) = OutboxItem("e1", OutboxItem.Kind.TASK_EDIT, 0, text = "Essay", taskId = "t-1", note = note)
        assertEquals("New note", TaskLists.open(listOf(task), listOf(edit("New note"))).single().note)
        assertNull(TaskLists.open(listOf(task), listOf(edit(""))).single().note)
        // An edit stored before notes existed (no note in it) and a title-only edit.
        val kept = TaskLists.open(listOf(task), listOf(edit(null))).single()
        assertEquals("Old note", kept.note)
        assertEquals("Outline it", kept.next)
    }

    @Test
    fun `an edit stored before notes existed is read without one`() {
        val stored = """[{"id":"e1","kind":"TASK_EDIT","createdAtMillis":0,"text":"Essay","taskId":"t-1"}]"""
        val item = BridgeJson.decodeFromString(ListSerializer(OutboxItem.serializer()), stored).single()
        assertNull(item.note)
    }

    @Test
    fun `a task waiting on the phone shows its note, and an edit before it leaves goes with it`() = runTest {
        val sent = mutableListOf<String>()
        lateinit var outbox: Outbox
        // The same steps as the app's own outbox (LifeOsCore).
        outbox = Outbox(OutboxStore(File(folder.root, "outbox.json")), { 0L }) { item ->
            when (item.kind) {
                OutboxItem.Kind.TASK -> {
                    sent += "add ${item.text} | ${TaskNote.of(item.note)}"
                    outbox.retarget(LocalItems.PREFIX + item.id, "t-20270115-09")
                }
                OutboxItem.Kind.TASK_EDIT -> when (val to = LocalItems.sendTo(item.taskId!!, emptyMap(), emptyList(), outbox.items.value)) {
                    is LocalItems.SendTo.Computer -> sent += "edit ${to.id} | ${item.note}"
                    LocalItems.SendTo.Later -> throw ApiException(ApiException.Kind.NOT_SENT)
                    LocalItems.SendTo.Nowhere -> Unit
                }
                else -> Unit
            }
        }
        outbox.add(OutboxItem(phoneId, OutboxItem.Kind.TASK, 0, text = "Sample", note = "First note"))
        val shown = LocalItems.tasks(outbox.items.value, emptyList(), 0).single()
        assertEquals(localId, shown.id)
        assertEquals("First note", shown.note)
        // A title-only change keeps the note; a note change replaces it.
        assertTrue(outbox.revise(phoneId, "Sample, renamed", null, unsentOnly = true, note = TaskNote.change(shown.note, "First note")))
        assertEquals("First note", outbox.items.value.single().note)
        assertTrue(outbox.revise(phoneId, "Sample, renamed", null, unsentOnly = true, note = TaskNote.change(shown.note, "Second note")))
        assertEquals("Second note", LocalItems.tasks(outbox.items.value, emptyList(), 0).single().note)
        // An edit made while it was on its way waits behind it and carries its own note change.
        outbox.add(OutboxItem("edit-0000000000000001", OutboxItem.Kind.TASK_EDIT, 1, text = "Sample, renamed", taskId = localId, note = ""))
        assertEquals(2, outbox.drain())
        assertEquals(listOf("add Sample, renamed | Second note", "edit t-20270115-09 | "), sent)
    }

    @Test
    fun `a second edit of a waiting edit keeps the first one's note unless it changes it`() = runTest {
        val outbox = Outbox(OutboxStore(File(folder.root, "outbox.json")), { 0L }) { throw ApiException(ApiException.Kind.NOT_SENT) }
        outbox.add(OutboxItem("e1", OutboxItem.Kind.TASK_EDIT, 0, text = "Essay", taskId = "t-1", note = "Changed"))
        assertTrue(outbox.revise("e1", "Essay, renamed", "2027-01-20"))
        assertEquals("Changed", outbox.items.value.single().note)
        assertTrue(outbox.revise("e1", "Essay, renamed", "2027-01-20", note = ""))
        assertEquals("", outbox.items.value.single().note)
    }
}
