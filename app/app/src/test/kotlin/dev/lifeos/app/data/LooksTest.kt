package dev.lifeos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LooksTest {
    private fun course(id: Long, name: String = "Course $id", short: String = "C$id") = Course(id, name, short)

    @Test
    fun `a course's look depends on its id, not on the order of the list`() {
        val a = Looks.of(listOf(course(11), course(12), course(13)))
        val b = Looks.of(listOf(course(13), course(11), course(12)))
        for (id in listOf(11L, 12L, 13L)) assertEquals(a.course(id), b.course(id))
        // 11 mod 7 = 4 (sand), 11 mod 6 = 5 (flask); 12 mod 7 = 5 (mint), 12 mod 6 = 0 (wave).
        assertEquals(Look(Tone.SAND, Glyph.FLASK, Pattern.CIRCLES), a.course(11))
        assertEquals(Look(Tone.MINT, Glyph.WAVE, Pattern.WAVES), a.course(12))
    }

    @Test
    fun `courses whose ids want the same colour get different ones while there are free ones`() {
        // 3 and 10 both want tone 3; 3 and 9 both want icon 3.
        val looks = Looks.of(listOf(course(3), course(10), course(9)))
        val tones = listOf(3L, 9L, 10L).map { looks.course(it).tone }
        assertEquals(3, tones.toSet().size)
        assertEquals(Tone.PEACH, looks.course(3).tone)
        assertNotEquals(looks.course(3).glyph, looks.course(9).glyph)
        // The one with the smaller id keeps the colour it wants.
        assertEquals(Tone.PEACH, Looks.of(listOf(course(10), course(3))).course(3).tone)
    }

    @Test
    fun `eight courses share a colour only once all seven are taken`() {
        val looks = Looks.of((1L..8L).map { course(it) })
        assertEquals(7, (1L..7L).map { looks.course(it).tone }.toSet().size)
        assertEquals(looks.course(1).tone, looks.course(8).tone)
    }

    @Test
    fun `a task's course is found by short name, name or id, and gets the course's look`() {
        val looks = Looks.of(listOf(course(11, "Signals and Systems", "SIG 201"), course(12)))
        assertEquals(looks.course(11), looks.forTask(Task("t", "x", course = "sig201")))
        assertEquals(looks.course(11), looks.forTask(Task("t", "x", course = "Signals and Systems")))
        assertEquals(looks.course(12), looks.forTask(Task("t", "x", course = "12")))
        assertNull(looks.forTask(Task("t", "x")))
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
