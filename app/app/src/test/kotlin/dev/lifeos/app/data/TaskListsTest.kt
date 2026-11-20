package dev.lifeos.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskListsTest {
    private val tasks = listOf(Task("t-1", "Sample one"), Task("t-2", "Sample two"), Task("t-3", "Sample three", done = true), Task("t-4", "Sample four"))
    private fun tick(id: String, state: OutboxItem.State = OutboxItem.State.WAITING) =
        OutboxItem("o-$id", OutboxItem.Kind.COMPLETE, 0, taskId = id, state = state)

    @Test
    fun `a ticked task leaves the list while its tick waits or is unclear`() {
        assertEquals(listOf("t-1", "t-2", "t-4"), TaskLists.open(tasks, emptyList()).map { it.id })
        assertEquals(listOf("t-2", "t-4"), TaskLists.open(tasks, listOf(tick("t-1"))).map { it.id })
        assertEquals(listOf("t-1", "t-4"), TaskLists.open(tasks, listOf(tick("t-2", OutboxItem.State.CHECK))).map { it.id })
    }

    @Test
    fun `a refused tick brings the task back`() {
        assertEquals(listOf("t-1", "t-2", "t-4"), TaskLists.open(tasks, listOf(tick("t-1", OutboxItem.State.FAILED))).map { it.id })
    }

    @Test
    fun `a task ticked a moment ago is hidden before anything is sent`() {
        assertEquals(listOf("t-1", "t-2"), TaskLists.open(tasks, emptyList(), justTicked = setOf("t-4")).map { it.id })
    }

    @Test
    fun `only new tasks that still wait are listed as waiting, oldest first`() {
        val outbox = listOf(
            OutboxItem("a", OutboxItem.Kind.TASK, 1, text = "Sample a"),
            OutboxItem("b", OutboxItem.Kind.NOTE, 2, text = "Sample b"),
            OutboxItem("c", OutboxItem.Kind.TASK, 3, text = "Sample c", state = OutboxItem.State.CHECK),
            OutboxItem("d", OutboxItem.Kind.TASK, 4, text = "Sample d"),
            tick("t-1"),
        )
        assertEquals(listOf("a", "d"), TaskLists.waiting(outbox).map { it.id })
    }
}
