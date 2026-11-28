package dev.lifeos.app.ui

import dev.lifeos.app.data.Task
import dev.lifeos.app.ui.screens.TaskGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** The tasks screen's lists and its quick-add dates (#210). */
class TaskGroupsTest {
    @Test
    fun `the plan's order comes first, then the tasks the plan does not have`() {
        val plan = listOf(Task(id = "t-2", title = "Second"), Task(id = "t-1", title = "First"))
        val server = listOf(Task(id = "t-1", title = "First"), Task(id = "t-3", title = "Third"), Task(id = "t-2", title = "Second"))
        assertEquals(listOf("t-2", "t-1", "t-3"), TaskGroups.merged(plan, server).map { it.id })
        assertEquals(listOf("t-1", "t-3", "t-2"), TaskGroups.merged(emptyList(), server).map { it.id })
    }

    @Test
    fun `a quick-add date is today, tomorrow, a picked day or none`() {
        val today = LocalDate.of(2027, 1, 31)
        assertEquals("2027-01-31", TaskGroups.due(TaskGroups.TODAY, today))
        assertEquals("2027-02-01", TaskGroups.due(TaskGroups.TOMORROW, today))
        assertEquals("2027-03-05", TaskGroups.due("2027-03-05", today))
        assertNull(TaskGroups.due(null, today))
    }
}
