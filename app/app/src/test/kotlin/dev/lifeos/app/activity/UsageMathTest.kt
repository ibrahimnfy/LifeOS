package dev.lifeos.app.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageMathTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private val day = LocalDate.of(2027, 1, 15)
    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private fun resumed(time: String, pkg: String) = RawEvent(at(time), RawEvent.RESUMED, pkg)
    private fun paused(time: String, pkg: String) = RawEvent(at(time), RawEvent.PAUSED, pkg)
    private fun off(time: String) = RawEvent(at(time), RawEvent.SCREEN_OFF)
    private fun on(time: String) = RawEvent(at(time), RawEvent.SCREEN_ON)
    private fun unlock(time: String) = RawEvent(at(time), RawEvent.UNLOCKED)
    private fun measure(events: List<RawEvent>, date: LocalDate = day, now: String = "2027-01-20T00:00:00"): UsageMath.Raw {
        val (start, end) = UsageMath.dayBounds(date, zone)
        return UsageMath.measure(events, start, end, at(now))
    }
    private fun minutes(raw: UsageMath.Raw) = raw.perPackage.mapValues { it.value / 60_000 }

    @Test
    fun `resumed and paused events are paired per app`() {
        val raw = measure(
            listOf(
                resumed("2027-01-15T09:00:00", "app.a"), paused("2027-01-15T09:10:00", "app.a"),
                resumed("2027-01-15T09:10:01", "app.b"), paused("2027-01-15T09:30:01", "app.b"),
                resumed("2027-01-15T12:00:00", "app.a"), paused("2027-01-15T12:05:00", "app.a"),
            ),
        )
        assertEquals(mapOf("app.a" to 15L, "app.b" to 20L), minutes(raw))
        assertEquals(35L, raw.totalMillis / 60_000)
    }

    @Test
    fun `a stretch across midnight is split between the two days`() {
        val events = listOf(resumed("2027-01-14T23:40:00", "app.a"), paused("2027-01-15T00:25:00", "app.a"))
        assertEquals(mapOf("app.a" to 20L), minutes(measure(events, day.minusDays(1))))
        assertEquals(mapOf("app.a" to 25L), minutes(measure(events, day)))
        assertEquals(emptyMap<String, Long>(), minutes(measure(events, day.plusDays(1))))
    }

    @Test
    fun `another app, the screen going off or a shutdown end a stretch that has no pause event`() {
        val raw = measure(
            listOf(
                resumed("2027-01-15T10:00:00", "app.a"),
                resumed("2027-01-15T10:10:00", "app.b"),
                off("2027-01-15T10:15:00"),
                paused("2027-01-15T10:15:02", "app.b"),
                resumed("2027-01-15T11:00:00", "app.c"),
                RawEvent(at("2027-01-15T11:03:00"), RawEvent.SHUTDOWN),
            ),
        )
        assertEquals(mapOf("app.a" to 10L, "app.b" to 5L, "app.c" to 3L), minutes(raw))
    }

    @Test
    fun `moving between two screens of one app does not count twice or lose time`() {
        val raw = measure(
            listOf(
                resumed("2027-01-15T10:00:00", "app.a"),
                resumed("2027-01-15T10:05:00", "app.a"), // the next screen is resumed first
                paused("2027-01-15T10:05:01", "app.a"), // then the previous one is paused
                resumed("2027-01-15T10:05:02", "app.a"),
                paused("2027-01-15T10:20:00", "app.a"),
            ),
        )
        assertEquals(19L, raw.totalMillis / 60_000) // one second of bookkeeping is lost, nothing is doubled
    }

    @Test
    fun `an open stretch counts until now today and until midnight on a past day`() {
        val events = listOf(resumed("2027-01-15T22:00:00", "app.a"))
        assertEquals(mapOf("app.a" to 30L), minutes(measure(events, now = "2027-01-15T22:30:00")))
        assertEquals(mapOf("app.a" to 120L), minutes(measure(events, now = "2027-01-16T08:00:00")))
    }

    @Test
    fun `unlocks are counted within the local day only`() {
        val events = listOf(unlock("2027-01-14T23:59:59"), unlock("2027-01-15T00:00:00"), unlock("2027-01-15T13:00:00"), unlock("2027-01-15T23:59:59"), unlock("2027-01-16T00:00:00"), on("2027-01-15T14:00:00"))
        assertEquals(3, measure(events).unlocks)
        assertEquals(1, measure(events, day.minusDays(1)).unlocks)
    }

    @Test
    fun `a day with a clock change still runs from local midnight to local midnight`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val (start, end) = UsageMath.dayBounds(LocalDate.of(2027, 3, 28), berlin) // 23 hours
        assertEquals(23L, (end - start) / 3_600_000)
        val (start2, end2) = UsageMath.dayBounds(LocalDate.of(2027, 10, 31), berlin) // 25 hours
        assertEquals(25L, (end2 - start2) / 3_600_000)
    }

    @Test
    fun `the summary keeps everything in the total and hides the home screen and LifeOS from the apps`() {
        val raw = UsageMath.Raw(
            totalMillis = 100 * 60_000L,
            perPackage = mapOf("home" to 30 * 60_000L, "dev.lifeos.app" to 10 * 60_000L, "app.a" to 40 * 60_000L, "app.b" to 19 * 60_000L + 59_000, "app.c" to 30_000L),
            unlocks = 12,
        )
        val summary = UsageMath.summarize(day, raw, hidden = setOf("home", "dev.lifeos.app"), label = { it.uppercase() }, complete = true)
        assertEquals("2027-01-15", summary.date)
        assertEquals(100, summary.screenMinutes)
        assertEquals(12, summary.unlocks)
        assertEquals(listOf(AppUse("app.a", "APP.A", 40), AppUse("app.b", "APP.B", 19)), summary.apps)
    }

    // ---- sleep estimate

    private fun night(vararg events: RawEvent) = SleepEstimate.night(events.toList(), day, zone)

    @Test
    fun `the night is the longest quiet stretch that ends in the morning`() {
        val found = night(
            off("2027-01-14T21:00:00"), unlock("2027-01-14T21:30:00"),
            off("2027-01-14T23:40:00"), on("2027-01-15T03:10:00"), off("2027-01-15T03:10:20"), // a notification lights the screen
            unlock("2027-01-15T07:05:00"),
            off("2027-01-15T07:30:00"), unlock("2027-01-15T12:00:00"), // 4.5 h, shorter
        )
        assertEquals(Night("2027-01-15", "23:40", "07:05", estimated = true), found)
        assertEquals(7 * 60 + 25, found!!.minutes)
    }

    @Test
    fun `stretches that are too short, too long or end at the wrong time are no night`() {
        assertNull(night(off("2027-01-15T01:00:00"), unlock("2027-01-15T03:30:00"))) // 2.5 h
        assertNull(night(off("2027-01-14T12:00:00"), unlock("2027-01-15T08:00:00"))) // 20 h: the phone lay somewhere
        assertNull(night(off("2027-01-14T22:00:00"), unlock("2027-01-15T02:59:00"))) // woke before 03:00
        assertNull(night(off("2027-01-15T06:00:00"), unlock("2027-01-15T13:01:00"))) // ends after 13:00
        assertNull(night(off("2027-01-13T23:00:00"), unlock("2027-01-14T07:00:00"))) // another morning
        assertNull(night(unlock("2027-01-15T07:00:00"))) // no screen-off known
        assertNull(night())
    }

    @Test
    fun `the limits themselves are accepted`() {
        assertEquals("03:00", night(off("2027-01-14T23:00:00"), unlock("2027-01-15T03:00:00"))?.wake)
        assertEquals("13:00", night(off("2027-01-14T23:00:00"), unlock("2027-01-15T13:00:00"))?.wake)
        assertEquals("05:00", night(off("2027-01-15T02:00:00"), unlock("2027-01-15T05:00:00"))?.wake) // exactly 3 h
    }

    @Test
    fun `a night's length handles midnight`() {
        assertEquals(480, Night("2027-01-15", "23:30", "07:30", false).minutes)
        assertEquals(360, Night("2027-01-15", "01:00", "07:00", false).minutes)
    }

    // ---- when the morning question is asked

    @Test
    fun `the next prompt is today if the time is still ahead, else tomorrow`() {
        val five = LocalTime.of(5, 0)
        fun now(text: String) = LocalDateTime.parse(text).atZone(zone)
        assertEquals(now("2027-01-15T05:00:00"), SleepPromptTime.next(now("2027-01-15T04:59:59"), five))
        assertEquals(now("2027-01-16T05:00:00"), SleepPromptTime.next(now("2027-01-15T05:00:00"), five))
        assertEquals(now("2027-01-16T05:00:00"), SleepPromptTime.next(now("2027-01-15T23:30:00"), five))
        assertEquals(now("2027-02-01T05:00:00"), SleepPromptTime.next(now("2027-01-31T12:00:00"), five)) // month change
    }

    @Test
    fun `the prompt follows the local clock through a time-zone and a clock change`() {
        val five = LocalTime.of(5, 0)
        val newYork = ZoneId.of("America/New_York")
        val tokyo = ZoneId.of("Asia/Tokyo")
        val moment = ZonedDateTime.of(2027, 1, 15, 18, 0, 0, 0, newYork) // already 08:00 on the 16th in Tokyo
        assertEquals(LocalDate.of(2027, 1, 16), SleepPromptTime.next(moment, five).toLocalDate())
        assertEquals(LocalDate.of(2027, 1, 17), SleepPromptTime.next(moment.withZoneSameInstant(tokyo), five).toLocalDate())
        // The night the clocks go forward in Berlin: 02:30 does not exist, the prompt comes at 03:30.
        val berlin = ZoneId.of("Europe/Berlin")
        val next = SleepPromptTime.next(ZonedDateTime.of(2027, 3, 27, 12, 0, 0, 0, berlin), LocalTime.of(2, 30))
        assertEquals(LocalDateTime.parse("2027-03-28T03:30:00"), next.toLocalDateTime())
        // A day of 23 hours: the alarm is 23 hours after the previous one, at the same local time.
        val before = SleepPromptTime.next(ZonedDateTime.of(2027, 3, 27, 6, 0, 0, 0, berlin), five)
        val after = SleepPromptTime.next(before, five)
        assertEquals(23L, java.time.Duration.between(before, after).toHours())
    }

    @Test
    fun `an unreadable time setting falls back to five o'clock`() {
        assertEquals(LocalTime.of(5, 0), SleepPromptTime.parse(null))
        assertEquals(LocalTime.of(5, 0), SleepPromptTime.parse("25:99"))
        assertEquals(LocalTime.of(6, 30), SleepPromptTime.parse("06:30"))
    }
}
