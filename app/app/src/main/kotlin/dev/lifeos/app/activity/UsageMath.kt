package dev.lifeos.app.activity

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One usage event of the system, reduced to what is needed here. */
data class RawEvent(val timeMillis: Long, val type: Int, val pkg: String? = null) {
    companion object {
        // The values of android.app.usage.UsageEvents.Event, repeated so this file needs no Android class.
        const val RESUMED = 1
        const val PAUSED = 2
        const val SCREEN_ON = 15
        const val SCREEN_OFF = 16
        const val UNLOCKED = 18
        const val SHUTDOWN = 26
    }
}

@Serializable
data class AppUse(val pkg: String, val label: String, val minutes: Int)

/** What the phone was used for on one local day. */
@Serializable
data class DayUsage(
    val date: String,
    val screenMinutes: Int,
    val unlocks: Int,
    /** Every app with at least a minute, most used first; the launcher and LifeOS itself are left out. */
    val apps: List<AppUse> = emptyList(),
    /** The day is over, so the numbers will not change any more. */
    val complete: Boolean = false,
    /** The computer has confirmed these numbers. */
    val uploaded: Boolean = false,
)

/** One night. [date] is the morning it ended on; times are local `HH:MM`. */
@Serializable
data class Night(
    val date: String,
    val bed: String,
    val wake: String,
    /** Guessed from when the screen was off, not entered by the owner. */
    val estimated: Boolean,
    val uploaded: Boolean = false,
) {
    /** Minutes asleep; a wake time before the bed time is on the next day. */
    val minutes: Int
        get() {
            val from = LocalTime.parse(bed)
            val to = LocalTime.parse(wake)
            val length = Duration.between(from, to).toMinutes().toInt()
            return if (length <= 0) length + 24 * 60 else length
        }
}

object UsageMath {
    class Raw(val totalMillis: Long, val perPackage: Map<String, Long>, val unlocks: Int)

    /**
     * Foreground time and unlocks between [start] and [end] (a local day).
     *
     * Rule: an app is in the foreground from its "resumed" event until its own "paused"
     * event, another app's "resumed", the screen going off or a shutdown, whichever comes
     * first. Only the part of such a stretch that lies inside the day counts, so a stretch
     * across midnight is split between the two days. [events] must therefore also hold the
     * events of some hours before [start]. A stretch still open at the end counts until
     * [nowMillis] (or the end of the day). Unlocks are the "keyguard hidden" events of the day.
     */
    fun measure(events: List<RawEvent>, start: Long, end: Long, nowMillis: Long): Raw {
        val perPackage = HashMap<String, Long>()
        var unlocks = 0
        var current: String? = null
        var since = 0L

        fun close(at: Long) {
            val pkg = current ?: return
            val from = maxOf(since, start)
            val to = minOf(at, end)
            if (to > from) perPackage[pkg] = (perPackage[pkg] ?: 0) + (to - from)
            current = null
        }

        for (event in events.sortedBy { it.timeMillis }) {
            if (event.timeMillis >= end) break
            when (event.type) {
                RawEvent.RESUMED -> if (event.pkg != null && event.pkg != current) {
                    close(event.timeMillis)
                    current = event.pkg
                    since = event.timeMillis
                }
                RawEvent.PAUSED -> if (event.pkg == current) close(event.timeMillis)
                RawEvent.SCREEN_OFF, RawEvent.SHUTDOWN -> close(event.timeMillis)
                RawEvent.UNLOCKED -> if (event.timeMillis >= start) unlocks++
            }
        }
        close(minOf(end, nowMillis))
        return Raw(perPackage.values.sum(), perPackage, unlocks)
    }

    /**
     * The summary of a day. The total counts everything; the list of apps leaves out
     * [hidden] (the home screen and LifeOS itself) and apps used for less than a minute.
     */
    fun summarize(
        date: LocalDate,
        raw: Raw,
        hidden: Set<String>,
        label: (String) -> String,
        complete: Boolean,
    ): DayUsage = DayUsage(
        date = date.toString(),
        screenMinutes = (raw.totalMillis / 60_000).toInt(),
        unlocks = raw.unlocks,
        apps = raw.perPackage.filterKeys { it !in hidden }
            .map { (pkg, millis) -> AppUse(pkg, label(pkg), (millis / 60_000).toInt()) }
            .filter { it.minutes > 0 }
            .sortedWith(compareByDescending<AppUse> { it.minutes }.thenBy { it.label }),
        complete = complete,
    )

    fun dayBounds(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
        date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

/** What the Screen time tab shows, worked out without Android classes. */
object UsageView {
    /** How many days back the phone is measured each time; the system keeps its events about a week. */
    const val MEASURE_DAYS = 7

    /**
     * Whether a measurement holds anything. A day without a single event is not "no use": the
     * system has forgotten it, or the phone was off. Such a day is neither kept nor sent.
     */
    fun hasData(raw: UsageMath.Raw): Boolean = raw.totalMillis > 0 || raw.unlocks > 0

    /** The last [count] days up to [today], oldest first; a day without a measurement is null. */
    fun window(days: List<DayUsage>, today: LocalDate, count: Int): List<Pair<LocalDate, DayUsage?>> {
        val byDate = days.associateBy { it.date }
        return (count - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { it to byDate[it.toString()] } }
    }

    /** The average screen time of the measured days that are over (today is not), or null. */
    fun average(window: List<Pair<LocalDate, DayUsage?>>): Int? {
        val measured = window.mapNotNull { it.second }.filter { it.complete }
        return if (measured.isEmpty()) null else measured.sumOf { it.screenMinutes } / measured.size
    }

    /** The day to show below the chart: the chosen one if it was measured, else the newest measured. */
    fun selected(window: List<Pair<LocalDate, DayUsage?>>, chosen: LocalDate?): DayUsage? =
        window.firstOrNull { it.first == chosen }?.second ?: window.lastOrNull { it.second != null }?.second
}

/**
 * A guess of last night from when the phone was not used.
 *
 * Rule: a "quiet stretch" runs from the screen going off to the next unlock (the screen
 * lighting up for a notification does not end it). For the morning of [date], the longest
 * quiet stretch counts that ends between 03:00 and 13:00 of that day and lasts between
 * 3 and 14 hours. If there is none, there is no guess.
 */
object SleepEstimate {
    private val EARLIEST_WAKE: LocalTime = LocalTime.of(3, 0)
    private val LATEST_WAKE: LocalTime = LocalTime.of(13, 0)
    private const val MIN_HOURS = 3L
    private const val MAX_HOURS = 14L

    fun night(events: List<RawEvent>, date: LocalDate, zone: ZoneId): Night? {
        val wakeFrom = ZonedDateTime.of(date, EARLIEST_WAKE, zone).toInstant().toEpochMilli()
        val wakeUntil = ZonedDateTime.of(date, LATEST_WAKE, zone).toInstant().toEpochMilli()
        var best: Pair<Long, Long>? = null
        var offAt: Long? = null
        for (event in events.sortedBy { it.timeMillis }) {
            when (event.type) {
                RawEvent.SCREEN_OFF -> if (offAt == null) offAt = event.timeMillis
                RawEvent.UNLOCKED -> {
                    val from = offAt
                    offAt = null
                    if (from == null) continue
                    val length = event.timeMillis - from
                    val plausible = event.timeMillis in wakeFrom..wakeUntil &&
                        length >= Duration.ofHours(MIN_HOURS).toMillis() && length <= Duration.ofHours(MAX_HOURS).toMillis()
                    if (plausible && (best == null || length > best.second - best.first)) best = from to event.timeMillis
                }
            }
        }
        val (bed, wake) = best ?: return null
        return Night(date.toString(), clock(bed, zone), clock(wake, zone), estimated = true)
    }

    private fun clock(millis: Long, zone: ZoneId): String =
        java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
}

/** What the Sleep tab shows and when the morning question is due, without Android classes. */
object SleepView {
    private val CLOCK = Regex("([01]\\d|2[0-3]):[0-5]\\d")

    /** The last [count] mornings up to [today], oldest first; a night without entry or estimate is null. */
    fun window(nights: List<Night>, today: LocalDate, count: Int): List<Pair<LocalDate, Night?>> {
        val byDate = nights.associateBy { it.date }
        return (count - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { it to byDate[it.toString()] } }
    }

    /** The average length of the nights in the window (entered and estimated), or null. */
    fun average(window: List<Pair<LocalDate, Night?>>): Int? {
        val known = window.mapNotNull { it.second }
        return if (known.isEmpty()) null else known.sumOf { it.minutes } / known.size
    }

    /** The night that ended this morning ([today]), entered or estimated. */
    fun lastNight(nights: List<Night>, today: LocalDate): Night? = nights.firstOrNull { it.date == today.toString() }

    /** The morning question is asked only while last night has not been entered (an estimate does not count). */
    fun needsEntry(nights: List<Night>, today: LocalDate): Boolean = nights.none { it.date == today.toString() && !it.estimated }

    /** A time as the bridge takes it: `HH:MM`, 24 hours. */
    fun validClock(text: String): Boolean = CLOCK.matches(text)

    /** An entry the owner can save: two valid, different times. */
    fun validEntry(bed: String, wake: String): Boolean = validClock(bed) && validClock(wake) && bed != wake
}

/** When the morning question is asked next: today at [time] if that is still ahead, else tomorrow. */
object SleepPromptTime {
    fun next(now: ZonedDateTime, time: LocalTime): ZonedDateTime {
        val today = ZonedDateTime.of(now.toLocalDate(), time, now.zone)
        return if (today.isAfter(now)) today else ZonedDateTime.of(now.toLocalDate().plusDays(1), time, now.zone)
    }

    fun parse(text: String?): LocalTime = try {
        LocalTime.parse(text)
    } catch (_: Exception) {
        DEFAULT
    }

    val DEFAULT: LocalTime = LocalTime.of(5, 0)
}
