package dev.lifeos.app.activity

import dev.lifeos.app.data.BridgeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ScoresTest {
    private fun night(date: String, bed: String, wake: String) = Night(date, bed, wake, estimated = false)
    private fun usage(date: String, minutes: Int, unlocks: Int) = DayUsage(date, minutes, unlocks)

    @Test
    fun `sleep length is full between seven and nine hours and falls in a straight line outside`() {
        assertEquals(100, Scores.sleepLength(7 * 60))
        assertEquals(100, Scores.sleepLength(8 * 60))
        assertEquals(100, Scores.sleepLength(9 * 60))
        assertEquals(50, Scores.sleepLength(5 * 60 + 30)) // halfway between 4 h and 7 h
        assertEquals(67, Scores.sleepLength(6 * 60))
        assertEquals(0, Scores.sleepLength(4 * 60))
        assertEquals(0, Scores.sleepLength(60)) // clamped, never negative
        assertEquals(67, Scores.sleepLength(10 * 60)) // too long counts against it too
        assertEquals(0, Scores.sleepLength(12 * 60))
        assertEquals(0, Scores.sleepLength(15 * 60))
    }

    @Test
    fun `times of day are compared the short way round the clock`() {
        assertEquals(20, Scores.clockDistance(23 * 60 + 50, 10))
        assertEquals(0, Scores.clockDistance(100, 100))
        assertEquals(720, Scores.clockDistance(0, 720))
        // Bed times around midnight have their middle at midnight, not at noon.
        assertEquals(0, Scores.clockMedian(listOf(23 * 60 + 30, 0, 30)))
        assertNull(Scores.clockMedian(emptyList()))
    }

    @Test
    fun `regularity is full up to half an hour off and gone at two hours`() {
        assertEquals(100, Scores.regularity(0))
        assertEquals(100, Scores.regularity(30))
        assertEquals(50, Scores.regularity(75))
        assertEquals(0, Scores.regularity(120))
        assertEquals(0, Scores.regularity(400))
    }

    @Test
    fun `a night alone is scored by its length, with others by seventy and thirty`() {
        val alone = Scores.sleep(night("2027-01-15", "23:00", "07:00"))!!
        assertEquals(SleepScore(score = 100, duration = 100, regularity = null, offMinutes = null), alone)

        val usual = listOf(night("2027-01-12", "23:00", "07:00"), night("2027-01-13", "23:10", "07:00"), night("2027-01-14", "22:50", "07:10"))
        // Two usual nights are not enough to say what is usual.
        assertNull(Scores.sleep(night("2027-01-15", "23:00", "07:00"), usual.take(2))!!.regularity)

        val regular = Scores.sleep(night("2027-01-15", "23:00", "07:00"), usual)!!
        assertEquals(0, regular.offMinutes)
        assertEquals(100, regular.score)

        // Bed 01:00 (2 h late), wake 09:00 (2 h late): 8 h, full length, no regularity.
        val shifted = Scores.sleep(night("2027-01-15", "01:00", "09:00"), usual)!!
        assertEquals(120, shifted.offMinutes)
        assertEquals(0, shifted.regularity)
        assertEquals(70, shifted.score)

        // Short and late: bed 02:00, wake 07:00 = 5 h → length 33; off (180 + 0) / 2 = 90 → regularity 33.
        val short = Scores.sleep(night("2027-01-15", "02:00", "07:00"), usual)!!
        assertEquals(33, short.duration)
        assertEquals(90, short.offMinutes)
        assertEquals(33, short.regularity)
        assertEquals(33, short.score)
    }

    @Test
    fun `a night with unreadable times has no score`() {
        assertNull(Scores.sleep(night("2027-01-15", "late", "07:00")))
    }

    @Test
    fun `digital balance is seventy percent screen time and thirty percent unlocks`() {
        assertEquals(100, Scores.screenTime(0))
        assertEquals(100, Scores.screenTime(180))
        assertEquals(50, Scores.screenTime(330))
        assertEquals(0, Scores.screenTime(480))
        assertEquals(0, Scores.screenTime(900))
        assertEquals(100, Scores.unlocks(50))
        assertEquals(50, Scores.unlocks(100))
        assertEquals(0, Scores.unlocks(150))
        assertEquals(0, Scores.unlocks(999))

        assertEquals(DigitalScore(100, 100, 100), Scores.digital(usage("2027-01-15", 60, 10)))
        assertEquals(DigitalScore(50, 50, 50), Scores.digital(usage("2027-01-15", 330, 100)))
        assertEquals(DigitalScore(70, 100, 0), Scores.digital(usage("2027-01-15", 120, 300)))
        assertEquals(DigitalScore(30, 0, 100), Scores.digital(usage("2027-01-15", 600, 20)))
        assertEquals(DigitalScore(0, 0, 0), Scores.digital(usage("2027-01-15", 600, 200)))
    }

    @Test
    fun `tasks are the finished share of what was due plus what else was finished`() {
        assertNull(Scores.tasks(TaskDay("2027-01-15"))) // nothing due, nothing finished: no score, not zero
        assertEquals(100, Scores.tasks(TaskDay("2027-01-15", due = 0, dueDone = 0, doneOther = 2)))
        assertEquals(33, Scores.tasks(TaskDay("2027-01-15", due = 3, dueDone = 1)))
        assertEquals(0, Scores.tasks(TaskDay("2027-01-15", due = 2)))
        assertEquals(50, Scores.tasks(TaskDay("2027-01-15", due = 2, dueDone = 0, doneOther = 2)))
        assertEquals(100, Scores.tasks(TaskDay("2027-01-15", due = 2, dueDone = 2)))
        // Numbers that cannot be: more finished than due is clamped, negatives give no score.
        assertEquals(100, Scores.tasks(TaskDay("2027-01-15", due = 1, dueDone = 5)))
        assertNull(Scores.tasks(TaskDay("2027-01-15", due = -1, dueDone = 0, doneOther = 3)))
    }

    @Test
    fun `the day is the mean of the parts that exist`() {
        assertNull(Scores.overall(listOf(null, null, null)))
        assertEquals(80, Scores.overall(listOf(80, null, null)))
        assertEquals(70, Scores.overall(listOf(80, 60, null)))
        assertEquals(67, Scores.overall(listOf(100, 100, 0)))
    }

    @Test
    fun `days are listed today first, each with its own night, usage and tasks`() {
        val today = LocalDate.parse("2027-01-15")
        val nights = listOf(night("2027-01-15", "23:00", "07:00"), night("2027-01-13", "02:00", "07:00"))
        val used = listOf(usage("2027-01-15", 330, 100), usage("2027-01-14", 60, 10))
        val tasks = listOf(TaskDay("2027-01-14", due = 2, dueDone = 1), TaskDay("2027-01-13"))
        val days = Scores.days(today, 3, nights, used, tasks)

        assertEquals(listOf("2027-01-15", "2027-01-14", "2027-01-13"), days.map { it.date.toString() })
        // Today: sleep 100 and digital 50, no task numbers → 75.
        assertEquals(listOf(100, 50, null), ScorePart.entries.map { days[0].part(it) })
        assertEquals(75, days[0].overall)
        // Yesterday: no night; digital 100, tasks 50 → 75.
        assertEquals(listOf(null, 100, 50), ScorePart.entries.map { days[1].part(it) })
        assertEquals(75, days[1].overall)
        // The day before: only a short night (5 h → 33); task numbers that give no score.
        assertEquals(listOf(33, null, null), ScorePart.entries.map { days[2].part(it) })
        assertEquals(33, days[2].overall)

        // A day with nothing at all has no score, and averages skip it.
        val empty = Scores.days(today, 2, emptyList(), emptyList(), emptyList())
        assertNull(empty[0].overall)
        assertNull(Scores.average(empty, null))
        assertEquals(61, Scores.average(days, null)) // (75 + 75 + 33) / 3
    }

    @Test
    fun `regularity looks only at the seven mornings before the night`() {
        val today = LocalDate.parse("2027-01-15")
        val old = (20L..22L).map { night(today.minusDays(it).toString(), "23:00", "07:00") }
        val recent = (1L..3L).map { night(today.minusDays(it).toString(), "23:00", "07:00") }
        val tonight = night("2027-01-15", "23:00", "07:00")
        assertNull(Scores.days(today, 1, old + tonight, emptyList(), emptyList())[0].sleep!!.regularity)
        assertEquals(100, Scores.days(today, 1, recent + tonight, emptyList(), emptyList())[0].sleep!!.regularity)
    }

    @Test
    fun `a score has a word`() {
        assertEquals(
            listOf(ScoreBand.GOOD, ScoreBand.GOOD, ScoreBand.FAIR, ScoreBand.FAIR, ScoreBand.LOW, ScoreBand.LOW, ScoreBand.VERY_LOW, ScoreBand.VERY_LOW),
            listOf(100, 80, 79, 60, 59, 40, 39, 0).map(ScoreBand::of),
        )
    }

    @Test
    fun `the task history is read as the computer sends it`() {
        val history = BridgeJson.decodeFromString(
            TaskHistory.serializer(),
            """{"today":"2027-01-15","days":[{"date":"2027-01-15","due":3,"due_done":1,"done_other":2,"new_field":1}]}""",
        )
        assertEquals(TaskDay("2027-01-15", due = 3, dueDone = 1, doneOther = 2), history.days.single())
        assertEquals(60, Scores.tasks(history.days.single()))
    }
}
