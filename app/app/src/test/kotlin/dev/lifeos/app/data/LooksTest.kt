package dev.lifeos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LooksTest {
    private fun course(id: Long, name: String = "Course $id", short: String = "C$id") = Course(id, name, short)

    @get:org.junit.Rule
    val folder = org.junit.rules.TemporaryFolder()

    @Test
    fun `new courses take the next free colour and icon in palette order, by id`() {
        val slots = LookAssignments.update(emptyMap(), listOf(course(13), course(11), course(12)))
        assertEquals(mapOf(11L to Slot(0, 0), 12L to Slot(1, 1), 13L to Slot(2, 2)), slots)
        val looks = Looks.of(listOf(course(11), course(12), course(13)), slots)
        assertEquals(Look(Tone.SKY, Glyph.WAVE, Pattern.WAVES), looks.course(11))
        assertEquals(Look(Tone.TEAL, Glyph.CHIP, Pattern.GRID), looks.course(12))
    }

    @Test
    fun `adding a course never changes another course's colour`() {
        val first = LookAssignments.update(emptyMap(), listOf(course(20), course(30)))
        // A course with a smaller id arrives later: the others keep theirs, it takes the next free one.
        val next = LookAssignments.update(first, listOf(course(10), course(20), course(30)))
        assertEquals(first[20], next[20])
        assertEquals(first[30], next[30])
        assertEquals(Slot(2, 2), next[10])
    }

    @Test
    fun `a removed course frees its colour, a re-added course takes a free one again`() {
        val three = LookAssignments.update(emptyMap(), listOf(course(1), course(2), course(3)))
        val without = LookAssignments.update(three, listOf(course(1), course(3)))
        assertNull(without[2])
        assertEquals(three[1], without[1])
        assertEquals(three[3], without[3])
        // A new course takes the freed colour; the course that came back takes the next free one.
        val withNew = LookAssignments.update(without, listOf(course(1), course(3), course(4)))
        assertEquals(three[2], withNew[4])
        val readded = LookAssignments.update(withNew, listOf(course(1), course(2), course(3), course(4)))
        assertEquals(Slot(3, 3), readded[2])
        assertEquals(withNew[4], readded[4])
    }

    @Test
    fun `an empty course list changes nothing`() {
        val slots = LookAssignments.update(emptyMap(), listOf(course(1)))
        assertEquals(slots, LookAssignments.update(slots, emptyList()))
    }

    @Test
    fun `eight courses share a colour only once all seven are taken`() {
        val slots = LookAssignments.update(emptyMap(), (1L..8L).map { course(it) })
        val looks = Looks.of((1L..8L).map { course(it) }, slots)
        assertEquals(7, (1L..7L).map { looks.course(it).tone }.toSet().size)
        assertEquals(looks.course(1).tone, looks.course(8).tone)
        assertNotEquals(looks.course(1).glyph, looks.course(6).glyph)
    }

    @Test
    fun `the assignment survives a restart and is gone after wiping`() {
        val store = LookStore(java.io.File(folder.root, "looks/looks.json"))
        assertEquals(emptyMap<Long, Slot>(), store.load())
        val slots = LookAssignments.update(emptyMap(), listOf(course(5), course(7)))
        store.save(slots)
        assertEquals(slots, LookStore(java.io.File(folder.root, "looks/looks.json")).load())
        store.wipe()
        assertEquals(emptyMap<Long, Slot>(), store.load())
    }

    @Test
    fun `a task's course is found by short name, name or id, and gets the course's look`() {
        val looks = Looks.of(listOf(course(11, "Signals and Systems", "SIG 201"), course(12)))
        assertEquals(looks.course(11), looks.forTask(Task("t", "x", course = "sig201")))
        assertEquals(looks.course(11), looks.forTask(Task("t", "x", course = "Signals and Systems")))
        assertEquals(looks.course(12), looks.forTask(Task("t", "x", course = "12")))
        assertNull(looks.forTask(Task("t", "x")))
        // The bridge files a course's tasks under its folder: the short name as a slug plus the id.
        val withFolder = Looks.of(listOf(course(5, "Example course A", "EX 101"), course(7, "Example course B", "EX-102")))
        assertEquals(5L, withFolder.match("ex-101-5")?.id)
        assertEquals(7L, withFolder.match("ex-102-7")?.id)
        assertEquals(withFolder.course(5), withFolder.forTask(Task("t", "x", course = "ex-101-5")))
        // A folder whose id is another course's is not that course.
        assertNull(withFolder.match("ex-101-7"))
    }

    @Test
    fun `projects and unknown course names get a stable look from their name`() {
        val project = Looks.EMPTY.forTask(Task("t", "x", project = "Project Alpha"))!!
        assertEquals(Glyph.FOLDER, project.glyph)
        assertEquals(project, Looks.of(listOf(course(1))).forTask(Task("u", "y", project = "project alpha")))
        val unknown = Looks.EMPTY.forTask(Task("t", "x", course = "Some course"))!!
        assertEquals(unknown, Looks.EMPTY.forTask(Task("v", "z", course = "SOME COURSE")))
        // The hash is fixed (FNV-1a), so the colour is the same on every phone and run.
        assertEquals(Looks.stableHash("projectalpha"), Looks.stableHash("projectalpha"))
    }

    @Test
    fun `days until a deadline count calendar days in the phone's zone`() {
        val zone = ZoneId.of("Europe/Istanbul")
        val today = LocalDate.of(2027, 1, 15)
        val lateTonight = today.atTime(23, 30).atZone(zone).toEpochSecond()
        val earlyTomorrow = today.plusDays(1).atTime(0, 30).atZone(zone).toEpochSecond()
        assertEquals(0, Dates.daysUntil(lateTonight, today, zone))
        assertEquals(1, Dates.daysUntil(earlyTomorrow, today, zone))
        assertEquals(-1, Dates.daysUntil(today.minusDays(1).atTime(12, 0).atZone(zone).toEpochSecond(), today, zone))
    }
}
