package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Deleting tasks (#197), marking course assignments by hand (#196), editing every note (#202):
 * the calls, what the lists show while a write waits, and the outbox.
 */
class ListActionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val transport = FakeTransport()
    private val api = Api(transport)

    private fun mark(id: String, assignment: Long, mark: String?, state: OutboxItem.State = OutboxItem.State.WAITING, sending: Boolean = false, attempts: Int = 0) =
        OutboxItem(id, OutboxItem.Kind.ASSIGNMENT_MARK, 0, text = "Sample", courseId = 5, assignmentId = assignment, mark = mark, state = state, sending = sending, attempts = attempts)

    private fun delete(id: String, taskId: String, state: OutboxItem.State = OutboxItem.State.WAITING) =
        OutboxItem(id, OutboxItem.Kind.TASK_DELETE, 0, text = "Sample", taskId = taskId, state = state)

    // ---- the calls

    @Test
    fun `tasks are deleted, restored and reopened by their own routes`() = runTest {
        for (key in listOf("DELETE /tasks/t-1", "POST /tasks/t-1/restore", "POST /tasks/t-1/reopen")) transport.answer(key, 200, """{"id":"t-1"}""")
        api.deleteTask("t-1")
        api.restoreTask("t-1")
        api.reopenTask("t-1")
        assertEquals(listOf("DELETE /tasks/t-1", "POST /tasks/t-1/restore", "POST /tasks/t-1/reopen"), transport.calls.map { "${it.method} ${it.path}" })
    }

    @Test
    fun `a mark is a PUT with done, removed or null`() = runTest {
        transport.answer("PUT /courses/5/assignments/41/mark", 200, """{"mark":"done","previous":null,"task":"t-1"}""")
        api.markAssignment(5, 41, AssignmentMarks.DONE)
        assertEquals(JsonPrimitive("done"), (transport.calls.last().body as JsonObject)["mark"])
        api.markAssignment(5, 41, null)
        assertEquals(JsonNull, (transport.calls.last().body as JsonObject)["mark"])
    }

    @Test
    fun `the assignment list carries the marks and the removed ones apart`() {
        val answer = """{"course_id":5,"assignments":[{"id":41,"name":"Sample one","mark":"done","marked_on":"2027-01-15"},{"id":42,"name":"Sample two"}],
            "removed":[{"id":43,"name":"Sample three","mark":"removed","marked_on":"2027-01-14"}]}"""
        val data = BridgeJson.decodeFromString(Assignments.serializer(), answer)
        assertEquals(listOf("done" to "2027-01-15", null to null), data.assignments.map { it.mark to it.markedOn })
        assertEquals(listOf(43L), data.removed.map { it.id })
        // An answer of a bridge without marks reads as before.
        assertEquals(emptyList<Assignment>(), BridgeJson.decodeFromString(Assignments.serializer(), """{"assignments":[{"id":1}]}""").removed)
    }

    // ---- marks while they wait

    private val data = Assignments(
        assignments = listOf(Assignment(41, "Sample one"), Assignment(42, "Sample two", mark = "done")),
        removed = listOf(Assignment(43, "Sample three", mark = "removed")),
    )

    @Test
    fun `a waiting mark is shown at once, and a refused one is not`() {
        assertEquals("done", AssignmentMarks.shown(41, null, listOf(mark("m1", 41, "done"))))
        // Taking a mark off is a mark too: the waiting "no mark" wins over the computer's "done".
        assertNull(AssignmentMarks.shown(42, "done", listOf(mark("m1", 42, null))))
        assertEquals("done", AssignmentMarks.shown(42, "done", listOf(mark("m1", 42, null, OutboxItem.State.FAILED))))
        // The latest one counts.
        assertEquals("removed", AssignmentMarks.shown(41, null, listOf(mark("m1", 41, "done"), mark("m2", 41, "removed"))))
    }

    @Test
    fun `the lists move an assignment as its waiting mark says`() {
        val outbox = listOf(mark("m1", 41, "removed"), mark("m2", 43, null))
        val lists = AssignmentMarks.lists(data, outbox)
        assertEquals(listOf(42L, 43L), lists.listed.map { it.id })
        assertEquals(listOf(41L), lists.removed.map { it.id })
        assertNull(lists.listed.first { it.id == 43L }.mark)
        // Without anything waiting: the computer's own lists.
        assertEquals(listOf(41L, 42L) to listOf(43L), AssignmentMarks.lists(data, emptyList()).let { found -> found.listed.map { it.id } to found.removed.map { it.id } })
    }

    @Test
    fun `the task of an assignment marked a moment ago leaves the task lists with it`() {
        val tasks = listOf(Task("t-1", "Sample one", assignment = "moodle-41"), Task("t-2", "Sample two", assignment = "moodle-42"), Task("t-3", "Sample three"))
        assertEquals(listOf("t-2", "t-3"), TaskLists.open(tasks, listOf(mark("m1", 41, "done"))).map { it.id })
        assertEquals(listOf("t-1", "t-3"), TaskLists.open(tasks, listOf(mark("m1", 42, "removed"))).map { it.id })
        // A mark taken off closes nothing, and neither does a refused one.
        assertEquals(3, TaskLists.open(tasks, listOf(mark("m1", 41, "done"), mark("m2", 41, null))).size)
        assertEquals(3, TaskLists.open(tasks, listOf(mark("m1", 41, "done", OutboxItem.State.FAILED))).size)
    }

    @Test
    fun `a new mark replaces one that never left, and sends nothing when that is enough`() {
        // Nothing waits: the mark is sent unless the computer has it already.
        assertEquals(AssignmentMarks.Plan(emptyList(), send = true), AssignmentMarks.plan(41, "done", null, emptyList()))
        assertEquals(AssignmentMarks.Plan(emptyList(), send = false), AssignmentMarks.plan(41, "done", "done", emptyList()))
        // "Done" never left the phone and is taken back: withdrawn, nothing sent.
        assertEquals(AssignmentMarks.Plan(listOf("m1"), send = false), AssignmentMarks.plan(41, null, null, listOf(mark("m1", 41, "done"))))
        // ... or changed to "removed": the one that waited is replaced.
        assertEquals(AssignmentMarks.Plan(listOf("m1"), send = true), AssignmentMarks.plan(41, "removed", null, listOf(mark("m1", 41, "done"))))
        // One that is on its way cannot be withdrawn: the new one follows it.
        val sending = listOf(mark("m1", 41, "done", sending = true))
        assertEquals(AssignmentMarks.Plan(emptyList(), send = true), AssignmentMarks.plan(41, null, null, sending))
        assertEquals(AssignmentMarks.Plan(emptyList(), send = false), AssignmentMarks.plan(41, "done", null, sending))
        // Another assignment's marks are left alone.
        assertEquals(AssignmentMarks.Plan(emptyList(), send = true), AssignmentMarks.plan(41, "done", null, listOf(mark("m1", 42, "done"))))
    }

    // ---- tasks being deleted

    @Test
    fun `a deleted task leaves the list while its deletion waits, and comes back when refused`() {
        val tasks = listOf(Task("t-1", "Sample one"), Task("t-2", "Sample two"))
        assertEquals(listOf("t-2"), TaskLists.open(tasks, listOf(delete("d1", "t-1"))).map { it.id })
        assertEquals(listOf("t-1", "t-2"), TaskLists.open(tasks, listOf(delete("d1", "t-1", OutboxItem.State.FAILED))).map { it.id })
        // In its undo time nothing is in the outbox yet: the core hides it by id.
        assertEquals(listOf("t-1"), TaskLists.open(tasks, emptyList(), justTicked = setOf("t-2")).map { it.id })
    }

    @Test
    fun `a task that never left the phone is dropped, any other is deleted on the computer`() {
        fun own(state: OutboxItem.State = OutboxItem.State.WAITING, sending: Boolean = false, attempts: Int = 0) =
            OutboxItem("a1", OutboxItem.Kind.TASK, 0, text = "Sample", state = state, sending = sending, attempts = attempts)
        assertEquals(TaskLists.Deletion.Delete("t-1"), TaskLists.deletion("t-1", emptyList()))
        assertEquals(TaskLists.Deletion.Discard("a1"), TaskLists.deletion("local-a1", listOf(own())))
        assertEquals(TaskLists.Deletion.Discard("a1"), TaskLists.deletion("local-a1", listOf(own(OutboxItem.State.FAILED))))
        // On its way, or tried without an answer: it may be on the computer, so the deletion waits behind it.
        assertEquals(TaskLists.Deletion.Delete("local-a1"), TaskLists.deletion("local-a1", listOf(own(sending = true))))
        assertEquals(TaskLists.Deletion.Delete("local-a1"), TaskLists.deletion("local-a1", listOf(own(attempts = 1))))
    }

    // ---- the outbox

    @Test
    fun `deletions and marks are repeated safely, and a missing task counts as deleted`() = runTest {
        val sent = mutableListOf<String>()
        val outbox = Outbox(OutboxStore(File(folder.root, "outbox.json")), { 0L }) { item ->
            sent += item.id
            if (item.taskId == "t-gone") throw ApiException(ApiException.Kind.HTTP, 404, "task_not_found")
            if (item.assignmentId == 99L) throw ApiException(ApiException.Kind.HTTP, 404, "assignment_not_found")
        }
        assertTrue(delete("d0", "t-1").repeatable && mark("m0", 41, "done").repeatable)
        outbox.add(delete("d1", "t-gone"))
        outbox.add(delete("d2", "local-a1"))
        outbox.add(mark("m1", 41, "done"))
        outbox.add(mark("m2", 99, "done"))
        // The task made here reached the computer: its deletion names the computer's id.
        outbox.retarget("local-a1", "t-7")
        assertEquals(3, outbox.drain())
        assertEquals(listOf("d1", "d2", "m1", "m2"), sent)
        // An assignment the computer does not know is a refusal the owner sees, not a silent success.
        assertEquals(listOf("m2" to OutboxItem.State.FAILED), outbox.items.value.map { it.id to it.state })
    }

    @Test
    fun `only what never left the phone can be withdrawn`() {
        val outbox = Outbox(OutboxStore(File(folder.root, "outbox.json")), { 0L }) { }
        outbox.add(mark("m1", 41, "done"))
        outbox.add(mark("m2", 42, "done", attempts = 1))
        outbox.add(mark("m3", 43, "done", state = OutboxItem.State.CHECK))
        assertTrue(outbox.withdraw("m1"))
        assertFalse(outbox.withdraw("m2"))
        assertFalse(outbox.withdraw("m3"))
        assertFalse(outbox.withdraw("nothing"))
        assertEquals(listOf("m2", "m3"), outbox.items.value.map { it.id })
    }

    @Test
    fun `an outbox written before marks existed still loads`() {
        val file = File(folder.root, "old.json")
        file.writeText("""[{"id":"a1","kind":"COMPLETE","createdAtMillis":1,"taskId":"t-1"}]""")
        val loaded = OutboxStore(file).load()
        assertEquals(listOf("t-1"), loaded.map { it.taskId })
        assertNull(loaded.single().mark)
    }

    // ---- every note can be edited (#202)

    @Test
    fun `a topic note is edited like a capture, and its edit can be repeated`() {
        val topic = NoteSummary("n-sample-topic", "topic", "Sample topic", "2027-01-10", "Body")
        fun edit(state: OutboxItem.State = OutboxItem.State.WAITING, sending: Boolean = false) =
            OutboxItem("e1", OutboxItem.Kind.NOTE_EDIT, 0, text = "Changed", noteId = topic.id, state = state, sending = sending)
        assertTrue(NoteLists.canEdit(topic, emptyList()))
        assertTrue(NoteLists.canEdit(topic, listOf(edit())))
        assertFalse(NoteLists.canEdit(topic, listOf(edit(sending = true))))
        // Its id stays, so an edit with a lost answer is simply sent again, and the next edit finds the note.
        assertTrue(edit().repeatable)
        assertFalse(edit().copy(noteId = "i-aaaaaaaaaaaa").repeatable)
        assertTrue(NoteLists.canEdit(topic, listOf(edit(OutboxItem.State.CHECK))))
        // A filed capture is in no list and stays as it is.
        assertFalse(NoteLists.canEdit(NoteSummary("i-aaaaaaaaaaaa", "filed"), emptyList()))
    }

    @Test
    fun `the list knows when its preview is the whole note, and how long a note may get`() {
        assertEquals("Short", NoteLists.wholeText(NoteSummary("i-aaaaaaaaaaaa", preview = "Short")))
        assertNull(NoteLists.wholeText(NoteSummary("i-aaaaaaaaaaaa", preview = "x".repeat(NoteLists.PREVIEW))))
        assertEquals(NoteLists.MAX_NOTE, NoteLists.maxText("i-aaaaaaaaaaaa"))
        assertEquals(NoteLists.MAX_TOPIC_NOTE, NoteLists.maxText("n-sample-topic"))
    }

    // ---- the course sync in words (#207)

    @Test
    fun `a sync now says what happened`() {
        val idle = SyncState(configured = true)
        assertEquals(SyncStart.STARTED, SyncStart.of(refused = false, now = idle.copy(running = true)))
        assertEquals(SyncStart.RUNNING, SyncStart.of(refused = true, now = idle.copy(running = true)))
        assertEquals(SyncStart.TOO_SOON, SyncStart.of(refused = true, now = idle))
        assertEquals(SyncStart.NOT_SET_UP, SyncStart.of(refused = true, now = SyncState(configured = false)))
    }
}
