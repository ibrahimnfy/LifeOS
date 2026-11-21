package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What a tapped task opens, and ticks and edits of a task that is still waiting on the phone (#167). */
class TaskTapTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val course = Course(id = 5, shortname = "EX 101", name = "Example course")

    private fun assignmentTask(course: String? = "ex-101-5", marker: String? = "moodle-42") =
        Task("t-20270115-01", "EX 101: Essay", due = "2027-01-20", course = course, assignment = marker)

    @Test
    fun `a task of a course assignment opens that assignment`() {
        assertEquals(TaskTarget.AssignmentPage(5, 42), TaskTarget.of(assignmentTask()))
        // The course list knows the course: its id is taken from there.
        assertEquals(TaskTarget.AssignmentPage(5, 42), TaskTarget.of(assignmentTask(course = "EX 101")) { if (it == "EX 101") course else null })
        assertEquals(TaskTarget.AssignmentPage(5, 42), TaskTarget.of(assignmentTask()) { Looks.of(listOf(course)).match(it) })
        assertEquals(TaskTarget.AssignmentPage(7, 42), TaskTarget.of(assignmentTask(course = "course-7")))
    }

    @Test
    fun `every other task opens its card, never nothing`() {
        assertEquals(TaskTarget.Card, TaskTarget.of(Task("t-20270115-02", "Call the bank")))
        assertEquals(TaskTarget.Card, TaskTarget.of(Task("t-20270115-03", "Read chapter 3", course = "ex-101-5")))
        assertEquals(TaskTarget.Card, TaskTarget.of(assignmentTask(marker = "other-42")))
        assertEquals(TaskTarget.Card, TaskTarget.of(assignmentTask(marker = "moodle-0")))
        assertEquals(TaskTarget.Card, TaskTarget.of(assignmentTask(marker = "moodle-")))
        // The course cannot be told: no folder id, not in the course list.
        assertEquals(TaskTarget.Card, TaskTarget.of(assignmentTask(course = null)))
        assertEquals(TaskTarget.Card, TaskTarget.of(assignmentTask(course = "Example")))
        // A task still waiting on the phone has no assignment.
        assertEquals(TaskTarget.Card, TaskTarget.of(assignmentTask().copy(id = LocalItems.PREFIX + "0123456789abcdef")))
    }

    @Test
    fun `the bridge's assignment marker and the coach's next step are read`() {
        val task = BridgeJson.decodeFromString(
            Task.serializer(),
            """{"id":"t-20270115-01","title":"EX 101: Essay","done":false,"due":"2027-01-20","project":null,"course":"ex-101-5","added":"2027-01-10","assignment":"moodle-42","next":"Outline the first part","effort":"small"}""",
        )
        assertEquals("moodle-42", task.assignment)
        assertEquals("Outline the first part", task.next)
        assertEquals(TaskTarget.AssignmentPage(5, 42), TaskTarget.of(task))
    }

    // ---- a task made on this phone that has not reached the computer yet

    private val phoneId = "0123456789abcdef"
    private val localId = LocalItems.PREFIX + phoneId
    private fun newTask(state: OutboxItem.State = OutboxItem.State.WAITING) = OutboxItem(phoneId, OutboxItem.Kind.TASK, 0, text = "Sample", state = state)

    @Test
    fun `a tick or an edit names the computer's id once it is known`() {
        assertEquals("t-1", LocalItems.currentId("t-1", emptyMap(), emptyList()))
        assertEquals(localId, LocalItems.currentId(localId, emptyMap(), emptyList()))
        assertEquals("t-20270115-04", LocalItems.currentId(localId, mapOf(localId to "t-20270115-04"), emptyList()))
        val confirmed = listOf(Confirmed(phoneId, 0, task = Task("t-20270115-05", "Sample")))
        assertEquals("t-20270115-05", LocalItems.currentId(localId, emptyMap(), confirmed))
    }

    @Test
    fun `a tick of a waiting task waits behind it, and is dropped when the task never arrives`() {
        assertEquals(LocalItems.SendTo.Computer("t-1"), LocalItems.sendTo("t-1", emptyMap(), emptyList(), emptyList()))
        assertEquals(LocalItems.SendTo.Later, LocalItems.sendTo(localId, emptyMap(), emptyList(), listOf(newTask())))
        assertEquals(LocalItems.SendTo.Nowhere, LocalItems.sendTo(localId, emptyMap(), emptyList(), listOf(newTask(OutboxItem.State.FAILED))))
        assertEquals(LocalItems.SendTo.Nowhere, LocalItems.sendTo(localId, emptyMap(), emptyList(), emptyList()))
        assertEquals(
            LocalItems.SendTo.Computer("t-20270115-04"),
            LocalItems.sendTo(localId, mapOf(localId to "t-20270115-04"), emptyList(), listOf(newTask())),
        )
    }

    @Test
    fun `offline, a waiting task is edited and ticked, and both reach the computer under its id`() = runTest {
        val sent = mutableListOf<String>()
        val known = mutableMapOf<String, String>()
        lateinit var outbox: Outbox
        // The same steps as the app's own outbox (LifeOsCore): the task is stored, then what was done to it is renamed.
        outbox = Outbox(OutboxStore(File(folder.root, "outbox.json")), { 0L }) { item ->
            when (item.kind) {
                OutboxItem.Kind.TASK -> {
                    sent += "add ${item.text} ${item.due}"
                    known[LocalItems.PREFIX + item.id] = "t-20270115-09"
                    outbox.retarget(LocalItems.PREFIX + item.id, "t-20270115-09")
                }
                OutboxItem.Kind.COMPLETE, OutboxItem.Kind.TASK_EDIT -> when (val to = LocalItems.sendTo(item.taskId!!, known, emptyList(), outbox.items.value)) {
                    is LocalItems.SendTo.Computer -> sent += "${item.kind} ${to.id}"
                    LocalItems.SendTo.Later -> throw ApiException(ApiException.Kind.NOT_SENT)
                    LocalItems.SendTo.Nowhere -> Unit
                }
                else -> Unit
            }
        }
        outbox.add(newTask())
        // An edit before it left: the task itself leaves with the new title and date.
        assertTrue(outbox.revise(phoneId, "Sample, changed", "2027-01-21"))
        // A tick (after its undo time) waits under the phone's id.
        outbox.add(OutboxItem("tick-0000000000000001", OutboxItem.Kind.COMPLETE, 1, text = "Sample, changed", taskId = localId))
        // An edit made while the task was on its way would wait the same way.
        outbox.add(OutboxItem("edit-0000000000000001", OutboxItem.Kind.TASK_EDIT, 2, text = "Sample, later", taskId = localId))
        assertEquals(3, outbox.drain())
        assertEquals(listOf("add Sample, changed 2027-01-21", "COMPLETE t-20270115-09", "TASK_EDIT t-20270115-09"), sent)
        assertTrue(outbox.items.value.isEmpty())
    }

    @Test
    fun `a tick of a task the computer refused is not sent and nothing else is held up`() = runTest {
        val sent = mutableListOf<String>()
        lateinit var outbox: Outbox
        outbox = Outbox(OutboxStore(File(folder.root, "outbox.json")), { 0L }) { item ->
            when (item.kind) {
                OutboxItem.Kind.TASK -> throw ApiException(ApiException.Kind.HTTP, 400, "bad_request")
                OutboxItem.Kind.COMPLETE -> when (val to = LocalItems.sendTo(item.taskId!!, emptyMap(), emptyList(), outbox.items.value)) {
                    is LocalItems.SendTo.Computer -> sent += "tick ${to.id}"
                    LocalItems.SendTo.Later -> throw ApiException(ApiException.Kind.NOT_SENT)
                    LocalItems.SendTo.Nowhere -> Unit
                }
                else -> sent += item.text
            }
        }
        outbox.add(newTask())
        outbox.add(OutboxItem("tick-0000000000000001", OutboxItem.Kind.COMPLETE, 1, text = "Sample", taskId = localId))
        outbox.add(OutboxItem("note-0000000000000001", OutboxItem.Kind.NOTE, 2, text = "A note"))
        outbox.drain()
        assertEquals(listOf("A note"), sent)
        assertEquals(listOf(OutboxItem.State.FAILED), outbox.items.value.map { it.state })
    }
}
