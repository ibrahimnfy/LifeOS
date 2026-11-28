package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
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

class NotesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val transport = FakeTransport()
    private val api = Api(transport)

    private val listAnswer = """{"notes":[
        {"id":"i-aaaaaaaaaaaa","kind":"inbox","title":null,"at":"2027-01-15 09:30","preview":"First line\nsecond line"},
        {"id":"n-sample-topic","kind":"topic","title":"Sample topic","at":"2027-01-10","preview":"Body of the topic"}]}"""

    private fun edit(noteId: String, text: String, state: OutboxItem.State = OutboxItem.State.WAITING, sending: Boolean = false) =
        OutboxItem("o-$noteId-$text", OutboxItem.Kind.NOTE_EDIT, 0, text = text, noteId = noteId, state = state, sending = sending)

    @Test
    fun `the list, a search and one note are read with GET only`() = runTest {
        transport.answer("GET /notes", 200, listAnswer)
        transport.answer("GET /notes/i-aaaaaaaaaaaa", 200, """{"id":"i-aaaaaaaaaaaa","kind":"inbox","title":null,"at":"2027-01-15 09:30","text":"First line\nsecond line"}""")
        val notes = api.get(Endpoints.notes).first.notes
        assertEquals(listOf("i-aaaaaaaaaaaa", "n-sample-topic"), notes.map { it.id })
        // Every note of the list can be edited (#202): a capture and a topic note.
        assertTrue(notes[0].editable)
        assertTrue(notes[1].editable && notes[1].topic && !notes[0].topic)
        assertEquals(emptyMap<String, String>(), transport.calls.last().query)

        api.get(Endpoints.noteSearch("sample"))
        assertEquals(mapOf("query" to "sample"), transport.calls.last().query)

        val note = api.get(Endpoints.note("i-aaaaaaaaaaaa")).first
        assertEquals("First line\nsecond line", note.text)
        assertTrue(transport.calls.all { it.method == "GET" })
    }

    @Test
    fun `a search never takes the place of the saved list`() {
        // The screen's saved copy is stored under the endpoint's key; searches and single notes must not share it.
        assertEquals("notes", Endpoints.notes.key)
        assertTrue(Endpoints.noteSearch("x").key != Endpoints.notes.key)
        assertTrue(Endpoints.note("i-1").key != Endpoints.notes.key)
    }

    @Test
    fun `edits of notes and tasks are sent as PATCH with the bridge's fields`() = runTest {
        transport.answer("PATCH /notes/i-aaaaaaaaaaaa", 200, """{"id":"i-bbbbbbbbbbbb","kind":"inbox","title":null,"at":"2027-01-15 09:30","text":"New"}""")
        transport.answer("PATCH /tasks/t-1", 200, """{"id":"t-1","title":"Renamed","done":false}""")
        api.editNote("i-aaaaaaaaaaaa", "New")
        assertEquals(JsonPrimitive("New"), transport.calls.last().body!!.jsonObject["text"])

        api.editTask("t-1", "Renamed", "2027-02-01")
        var body = transport.calls.last().body!!.jsonObject
        assertEquals("PATCH", transport.calls.last().method)
        assertEquals(JsonPrimitive("Renamed"), body["title"])
        assertEquals(JsonPrimitive("2027-02-01"), body["due"])

        api.editTask("t-1", "Renamed", null)
        body = transport.calls.last().body!!.jsonObject
        // An explicit null removes the date on the computer.
        assertEquals(JsonNull, body["due"])
    }

    @Test
    fun `a waiting edit is shown in place of the computer's text`() {
        val notes = BridgeJson.decodeFromString(NoteList.serializer(), listAnswer).notes
        val rows = NoteLists.rows(notes, listOf(edit("i-aaaaaaaaaaaa", "Changed text")))
        assertEquals("Changed text", rows[0].text)
        assertTrue(rows[0].pending != null)
        assertEquals("Body of the topic", rows[1].text)
        assertNull(rows[1].pending)
        // A refused edit is not shown as the note's text.
        val refused = NoteLists.rows(notes, listOf(edit("i-aaaaaaaaaaaa", "Refused", OutboxItem.State.FAILED)))
        assertEquals("First line\nsecond line", refused[0].text)
    }

    @Test
    fun `a capture can be edited unless an earlier edit is already on its way`() {
        val notes = BridgeJson.decodeFromString(NoteList.serializer(), listAnswer).notes
        val capture = notes[0]
        assertTrue(NoteLists.canEdit(capture, emptyList()))
        assertTrue(NoteLists.canEdit(capture, listOf(edit(capture.id, "waiting"))))
        assertFalse(NoteLists.canEdit(capture, listOf(edit(capture.id, "sending", sending = true))))
        assertFalse(NoteLists.canEdit(capture, listOf(edit(capture.id, "unclear", OutboxItem.State.CHECK))))
        // A topic note too (#202; see ListActionsTest for its rules).
        assertTrue(NoteLists.canEdit(notes[1], emptyList()))
    }

    @Test
    fun `waiting captures are listed newest first`() {
        val outbox = listOf(
            OutboxItem("a", OutboxItem.Kind.NOTE, 1, text = "older"),
            OutboxItem("b", OutboxItem.Kind.TASK, 2, text = "a task"),
            OutboxItem("c", OutboxItem.Kind.NOTE, 3, text = "newer"),
            OutboxItem("d", OutboxItem.Kind.NOTE, 4, text = "unclear", state = OutboxItem.State.CHECK),
        )
        assertEquals(listOf("newer", "older"), NoteLists.waiting(outbox).map { it.text })
    }

    @Test
    fun `the saved list is searched like the computer searches`() {
        val notes = BridgeJson.decodeFromString(NoteList.serializer(), listAnswer).notes
        assertEquals(listOf("i-aaaaaaaaaaaa"), notes.filter { NoteLists.matches(it, "SECOND") }.map { it.id })
        assertEquals(listOf("n-sample-topic"), notes.filter { NoteLists.matches(it, " sample topic ") }.map { it.id })
        assertEquals(2, notes.count { NoteLists.matches(it, "") })
    }

    @Test
    fun `a row shows a title and the rest`() {
        assertEquals("First line" to "second line", NoteLists.headline(null, "First line\n\nsecond line"))
        assertEquals("Only" to null, NoteLists.headline(null, "Only"))
        assertEquals("Sample topic" to "Body text", NoteLists.headline("Sample topic", "Body\ntext"))
    }

    @Test
    fun `a waiting task edit changes the task everywhere it is listed`() {
        val tasks = listOf(Task("t-1", "Old title", due = "2027-01-10"), Task("t-2", "Other"))
        val outbox = listOf(
            OutboxItem("e1", OutboxItem.Kind.TASK_EDIT, 0, text = "First rename", due = "2027-01-11", taskId = "t-1"),
            OutboxItem("e2", OutboxItem.Kind.TASK_EDIT, 1, text = "Second rename", due = null, taskId = "t-1"),
        )
        val shown = TaskLists.open(tasks, outbox)
        assertEquals("Second rename", shown[0].title)
        assertNull(shown[0].due)
        assertEquals("Other", shown[1].title)
        val refused = TaskLists.open(tasks, listOf(outbox[0].copy(state = OutboxItem.State.FAILED)))
        assertEquals("Old title", refused[0].title)
    }

    // ---- the outbox with edits

    private val sent = mutableListOf<OutboxItem>()
    private val failures = ArrayDeque<ApiException>()

    private var now = 1_000L

    private fun outbox() = Outbox(OutboxStore(File(folder.root, "outbox.json")), { now }) { item ->
        sent += item
        failures.removeFirstOrNull()?.let { throw it }
    }

    @Test
    fun `a waiting edit can be revised, one on its way cannot`() = runTest {
        val outbox = outbox()
        outbox.add(edit("i-1", "first"))
        assertTrue(outbox.revise("o-i-1-first", "second"))
        assertEquals("second", outbox.items.value.single().text)
        failures += ApiException(ApiException.Kind.UNCERTAIN)
        outbox.drain()
        assertEquals(OutboxItem.State.CHECK, outbox.items.value.single().state)
        assertFalse(outbox.revise("o-i-1-first", "third"))
        assertEquals("second", sent.single().text)
    }

    @Test
    fun `a task edit is repeated after an unclear attempt, a note edit waits for the owner`() = runTest {
        val outbox = outbox()
        outbox.add(OutboxItem("t", OutboxItem.Kind.TASK_EDIT, 0, text = "Title", taskId = "t-1"))
        failures += ApiException(ApiException.Kind.UNCERTAIN)
        outbox.drain()
        assertEquals(OutboxItem.State.WAITING, outbox.items.value.single().state)
        assertEquals(1, outbox.drain())

        outbox.add(edit("i-1", "text"))
        failures += ApiException(ApiException.Kind.UNCERTAIN)
        outbox.drain()
        assertEquals(OutboxItem.State.CHECK, outbox.items.value.single().state)
    }

    @Test
    fun `a note edit answered with not found counts as done only after an unclear attempt`() = runTest {
        val outbox = outbox()
        // First attempt: no answer. Sent again by the owner: the note has its new id already.
        outbox.add(edit("i-1", "text"))
        failures += ApiException(ApiException.Kind.UNCERTAIN)
        outbox.drain()
        now += Outbox.SETTLE_MILLIS + 1
        assertTrue(outbox.sendAgain("o-i-1-text"))
        failures += ApiException(ApiException.Kind.HTTP, 404, "note_not_found")
        assertEquals(1, outbox.drain())
        assertTrue(outbox.items.value.isEmpty())

        // A first attempt answered with not found: the note is really gone.
        outbox.add(edit("i-2", "text"))
        failures += ApiException(ApiException.Kind.HTTP, 404, "note_not_found")
        assertEquals(0, outbox.drain())
        assertEquals(OutboxItem.State.FAILED, outbox.items.value.single().state)
    }
}
