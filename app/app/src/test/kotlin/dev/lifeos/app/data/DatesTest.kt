package dev.lifeos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DatesTest {
    private val today = LocalDate.of(2027, 1, 15)
    private fun task(id: String, due: String?) = Task(id, "Sample $id", due = due)
    private fun event(id: String, date: String, end: String? = null, start: String? = null) =
        Event(id, "Sample $id", date, end, start, allDay = start == null)

    @Test
    fun `a task belongs to overdue, today, upcoming or no date`() {
        assertEquals(TaskSection.OVERDUE, Dates.sectionOf("2027-01-14", today))
        assertEquals(TaskSection.TODAY, Dates.sectionOf("2027-01-15", today))
        assertEquals(TaskSection.UPCOMING, Dates.sectionOf("2027-01-16", today))
        assertEquals(TaskSection.NO_DATE, Dates.sectionOf(null, today))
        assertEquals(TaskSection.NO_DATE, Dates.sectionOf("soon", today))
        assertEquals(TaskSection.NO_DATE, Dates.sectionOf("2027-02-30", today))
    }

    @Test
    fun `the day changes at local midnight, not at UTC midnight`() {
        val moment = Instant.parse("2027-01-15T21:30:00Z")
        val east = moment.atZone(ZoneId.of("Europe/Istanbul")).toLocalDate() // already the 16th
        val west = moment.atZone(ZoneId.of("America/New_York")).toLocalDate() // still the 15th
        assertEquals(TaskSection.OVERDUE, Dates.sectionOf("2027-01-15", east))
        assertEquals(TaskSection.TODAY, Dates.sectionOf("2027-01-15", west))
        assertEquals(TaskSection.TODAY, Dates.sectionOf("2027-01-16", east))
        assertEquals(TaskSection.UPCOMING, Dates.sectionOf("2027-01-16", west))
    }

    @Test
    fun `sections come in a fixed order, sorted by date, without empty ones`() {
        val tasks = listOf(task("a", null), task("b", "2027-01-20"), task("c", "2027-01-10"), task("d", "2027-01-17"), task("e", "2027-01-01"), task("f", null))
        val sections = Dates.sections(tasks, today)
        assertEquals(listOf(TaskSection.OVERDUE, TaskSection.UPCOMING, TaskSection.NO_DATE), sections.map { it.first })
        assertEquals(listOf("e", "c"), sections[0].second.map { it.id })
        assertEquals(listOf("d", "b"), sections[1].second.map { it.id })
        assertEquals(listOf("a", "f"), sections[2].second.map { it.id }) // file order kept
        assertTrue(Dates.sections(emptyList(), today).isEmpty())
    }

    @Test
    fun `the agenda groups events by day, all-day first, then by start`() {
        val events = listOf(event("late", "2027-01-16", start = "15:00"), event("early", "2027-01-16", start = "09:00"), event("allday", "2027-01-16"), event("other", "2027-01-18"))
        val agenda = Dates.agenda(events, LocalDate.of(2027, 1, 8), LocalDate.of(2027, 3, 16))
        assertEquals(listOf(LocalDate.of(2027, 1, 16), LocalDate.of(2027, 1, 18)), agenda.map { it.first })
        assertEquals(listOf("allday", "early", "late"), agenda[0].second.map { it.id })
    }

    @Test
    fun `an event of several days appears on each of its days inside the range`() {
        val events = listOf(event("trip", "2027-01-30", end = "2027-02-02"), event("before", "2027-01-05", end = "2027-01-09"), event("bad", "x"), event("backwards", "2027-01-20", end = "2027-01-10"))
        val agenda = Dates.agenda(events, LocalDate.of(2027, 1, 8), LocalDate.of(2027, 1, 31))
        assertEquals(
            listOf("2027-01-08" to "before", "2027-01-09" to "before", "2027-01-20" to "backwards", "2027-01-30" to "trip", "2027-01-31" to "trip"),
            agenda.flatMap { (day, list) -> list.map { day.toString() to it.id } },
        )
    }

    // ---- which coach message is shown

    @Test
    fun `no message without messages, the only one when there is one`() {
        assertNull(CoachRotation.pick(emptyList(), listOf("m1"), { 0 }))
        assertEquals("m1", CoachRotation.pick(listOf("m1"), listOf("m1"), { 0 }))
    }

    @Test
    fun `the same message never comes twice in a row`() {
        val ids = listOf("m1", "m2", "m3")
        var recent = emptyList<String>()
        var last: String? = null
        val random = java.util.Random(7)
        repeat(200) {
            val picked = CoachRotation.pick(ids, recent, random::nextInt)!!
            assertNotEquals(last, picked)
            recent = CoachRotation.remember(recent, picked, ids)
            last = picked
        }
    }

    @Test
    fun `a message does not return before the others had their turn`() {
        val ids = listOf("m1", "m2", "m3", "m4")
        var recent = emptyList<String>()
        val random = java.util.Random(3)
        val shown = mutableListOf<String>()
        repeat(40) {
            val picked = CoachRotation.pick(ids, recent, random::nextInt)!!
            recent = CoachRotation.remember(recent, picked, ids)
            shown += picked
        }
        // With four messages, any three in a row are three different ones.
        assertEquals(ids.toSet(), shown.toSet())
        shown.windowed(3).forEach { assertEquals(3, it.toSet().size) }
    }

    @Test
    fun `only messages not shown recently are candidates, and vanished ones are forgotten`() {
        assertEquals("m3", CoachRotation.pick(listOf("m1", "m2", "m3"), listOf("m1", "m2"), { 0 }))
        assertEquals("m2", CoachRotation.pick(listOf("m1", "m2"), listOf("m2", "m1"), { 0 }))
        val six = listOf("m1", "m2", "m3", "m4", "m5", "m6")
        assertEquals(listOf("m2", "m1", "m3"), CoachRotation.remember(listOf("gone", "m2", "m1"), "m3", six))
        assertEquals(listOf("m1", "m3", "m4"), CoachRotation.remember(listOf("m2", "m1", "m3"), "m4", six))
        assertEquals(listOf("m1"), CoachRotation.remember(listOf("m2", "m3"), "m1", listOf("m1", "m2", "m3")))
    }
}
