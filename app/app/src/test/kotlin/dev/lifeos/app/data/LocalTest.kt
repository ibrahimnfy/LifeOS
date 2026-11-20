package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
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

class LocalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000_000L
    private val phoneId = "0123456789abcdef"

    private fun store() = ConfirmedStore(File(folder.root, "outbox/confirmed.json")) { now }

    @Test
    fun `a new note and task are sent with the phone's id and the stored item is read back`() = runTest {
        val transport = FakeTransport()
        transport.answer("POST /notes", 201, """{"saved":true,"repeat":false,"id":"i-aaaaaaaaaaaa","at":"2027-01-15T09:30+03:00","client_id":"$phoneId"}""")
        transport.answer("POST /tasks", 200, """{"id":"t-20270115-01","title":"Sample","due":"2027-01-20","client_id":"$phoneId","repeat":true}""")
        val api = Api(transport)
        val note = api.addNote("Sample note", phoneId)
        assertEquals(JsonPrimitive(phoneId), transport.calls.last().body!!.jsonObject["client_id"])
        assertEquals("i-aaaaaaaaaaaa", note.id)
        assertEquals("2027-01-15 09:30", LocalItems.noteMoment(note.at))
        val task = api.addTask("Sample", "2027-01-20", phoneId).task()!!
        assertEquals("t-20270115-01", task.id)
        assertEquals("2027-01-20", task.due)
        // A repeat of a task removed on the computer meanwhile: nothing to show.
        transport.answer("POST /tasks", 200, """{"id":"t-1","gone":true,"client_id":"$phoneId","repeat":true}""")
        assertNull(api.addTask("Sample", null, phoneId).task())
        // Without an id of the phone (old items) nothing is added to the body.
        api.addNote("Old", null)
        assertFalse(transport.calls.last().body!!.jsonObject.containsKey("client_id"))
    }

    @Test
    fun `only new notes and tasks with a usable id are re-sent by themselves`() {
        assertEquals(phoneId, OutboxItem(phoneId, OutboxItem.Kind.NOTE, 0, text = "x").clientId)
        assertTrue(OutboxItem(phoneId, OutboxItem.Kind.TASK, 0, text = "x").repeatable)
        assertNull(OutboxItem("short", OutboxItem.Kind.NOTE, 0, text = "x").clientId)
        assertFalse(OutboxItem("short", OutboxItem.Kind.NOTE, 0, text = "x").repeatable)
        assertNull(OutboxItem(phoneId, OutboxItem.Kind.EVENT_CREATE, 0).clientId)
        assertFalse(OutboxItem(phoneId, OutboxItem.Kind.NOTE_EDIT, 0).repeatable)
    }

    @Test
    fun `an unclear send of a new task is simply repeated and does not wait for the owner`() = runTest {
        val failures = ArrayDeque(listOf(ApiException(ApiException.Kind.UNCERTAIN)))
        var sent = 0
        val outbox = Outbox(OutboxStore(File(folder.root, "outbox.json")), { now }) {
            sent++
            failures.removeFirstOrNull()?.let { throw it }
        }
        outbox.add(OutboxItem(phoneId, OutboxItem.Kind.TASK, now, text = "Sample"))
        assertEquals(0, outbox.drain())
        assertEquals(OutboxItem.State.WAITING, outbox.items.value.single().state)
        assertEquals(1, outbox.drain())
        assertEquals(2, sent)
        assertTrue(outbox.items.value.isEmpty())
    }

    @Test
    fun `a task is shown at once - waiting, then confirmed, then from the computer's list, never twice`() {
        val store = store()
        val listAt = now - 10
        // Saved on the phone, not sent yet.
        val waiting = listOf(OutboxItem(phoneId, OutboxItem.Kind.TASK, now, text = "New task", due = "2027-01-20"))
        val first = LocalItems.withTasks(listOf(Task("t-old", "Old")), LocalItems.tasks(waiting, store.items.value, listAt))
        assertEquals(listOf("t-old", LocalItems.PREFIX + phoneId), first.map { it.id })
        assertTrue(LocalItems.isWaiting(first[1]))
        // Confirmed: the item leaves the outbox, the stored task takes its place.
        store.add(Confirmed(phoneId, now, task = Task("t-new", "New task", due = "2027-01-20")))
        val second = LocalItems.withTasks(listOf(Task("t-old", "Old")), LocalItems.tasks(emptyList(), store.items.value, listAt))
        assertEquals(listOf("t-old", "t-new"), second.map { it.id })
        assertFalse(LocalItems.isWaiting(second[1]))
        // The computer's list has it now: each task once.
        val server = listOf(Task("t-old", "Old"), Task("t-new", "New task", due = "2027-01-20"))
        assertEquals(listOf("t-old", "t-new"), LocalItems.withTasks(server, LocalItems.tasks(emptyList(), store.items.value, listAt)).map { it.id })
        // A list asked for after the confirmation makes the entry unnecessary.
        now += 1_000
        store.caughtUp(tasks = true, askedAtMillis = now)
        assertTrue(store.items.value.isEmpty())
    }

    @Test
    fun `a list older than the confirmation still shows the confirmed item, a newer one does not need it`() {
        val store = store()
        store.add(Confirmed(phoneId, now, task = Task("t-new", "New")))
        assertEquals(listOf("t-new"), LocalItems.tasks(emptyList(), store.items.value, now - 1).map { it.id })
        // A newer list without it: it was done or removed on the computer since.
        assertTrue(LocalItems.tasks(emptyList(), store.items.value, now + 1).isEmpty())
    }

    @Test
    fun `a list asked for before the confirmation keeps the entry`() {
        val store = store()
        store.add(Confirmed(phoneId, now, task = Task("t-new", "New")))
        store.caughtUp(tasks = true, askedAtMillis = now - 5)
        assertEquals(1, store.items.value.size)
        // Notes are a list of their own.
        store.caughtUp(tasks = false, askedAtMillis = now + 5)
        assertEquals(1, store.items.value.size)
    }

    @Test
    fun `confirmed notes come first until the computer's list has them, and survive a restart`() {
        val store = store()
        store.add(Confirmed("aaaaaaaaaaaaaaaa", now, note = NoteSummary("i-1", at = "2027-01-15 09:00", preview = "First")))
        store.add(Confirmed("bbbbbbbbbbbbbbbb", now + 1, note = NoteSummary("i-2", at = "2027-01-15 09:05", preview = "Second")))
        assertEquals(listOf("i-2", "i-1"), LocalItems.notes(emptyList(), store.items.value, now - 1).map { it.id })
        assertEquals(listOf("i-2"), LocalItems.notes(listOf(NoteSummary("i-1")), store.items.value, now - 1).map { it.id })
        assertEquals(2, store().items.value.size)
        store.wipe()
        assertTrue(store().items.value.isEmpty())
    }

    @Test
    fun `a refused new task is not shown in the lists (it has its own notice)`() {
        val refused = OutboxItem(phoneId, OutboxItem.Kind.TASK, now, text = "x", state = OutboxItem.State.FAILED)
        assertTrue(LocalItems.tasks(listOf(refused), emptyList(), 0).isEmpty())
    }
}
