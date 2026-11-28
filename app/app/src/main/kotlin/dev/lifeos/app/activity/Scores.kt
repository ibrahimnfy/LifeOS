package dev.lifeos.app.activity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

/** One day of `GET /tasks/history`: counts only, as the computer keeps them (#206). */
@Serializable
data class TaskDay(
    val date: String,
    /** Tasks due that day, open or finished. */
    val due: Int = 0,
    /** Those of them that were finished on that day or before it. */
    @SerialName("due_done") val dueDone: Int = 0,
    /** Other tasks finished that day (due on another day, or without a date). */
    @SerialName("done_other") val doneOther: Int = 0,
)

@Serializable
data class TaskHistory(val today: String = "", val days: List<TaskDay> = emptyList())

/** A score in a word, so that it is never told by its number or a colour alone. */
enum class ScoreBand {
    GOOD,
    FAIR,
    LOW,
    VERY_LOW;

    companion object {
        const val GOOD_FROM = 80
        const val FAIR_FROM = 60
        const val LOW_FROM = 40

        fun of(score: Int): ScoreBand = when {
            score >= GOOD_FROM -> GOOD
            score >= FAIR_FROM -> FAIR
            score >= LOW_FROM -> LOW
            else -> VERY_LOW
        }
    }
}

/** The three parts of a day's score, in the order they are always shown. */
enum class ScorePart { SLEEP, DIGITAL, TASKS }

/** What the sleep score of one night is made of. [regularity] is null while too few other nights are known. */
data class SleepScore(val score: Int, val duration: Int, val regularity: Int?, val offMinutes: Int?)

/** What the digital balance score of one day is made of. */
data class DigitalScore(val score: Int, val screen: Int, val unlocks: Int)

/**
 * One day with everything its scores were worked out from. A part without data has no score
 * (null), never 0; [overall] is the mean of the parts that have one.
 */
data class DayScore(
    val date: LocalDate,
    val night: Night?,
    val usage: DayUsage?,
    val tasks: TaskDay?,
    val sleep: SleepScore?,
    val digital: DigitalScore?,
    val task: Int?,
) {
    fun part(part: ScorePart): Int? = when (part) {
        ScorePart.SLEEP -> sleep?.score
        ScorePart.DIGITAL -> digital?.score
        ScorePart.TASKS -> task
    }

    val overall: Int? get() = Scores.overall(ScorePart.entries.map(::part))
}

/**
 * The daily scores (#206): pure functions, 0 to 100, higher is better. Every target is a
 * constant here, so the formulas can be read (and changed) in one place:
 *
 * - **Sleep** = 70 % length + 30 % regularity (length alone while regularity is unknown).
 *   Length: 100 between [SLEEP_LOW] and [SLEEP_HIGH] minutes; falling in a straight line to 0
 *   at [SLEEP_SHORT_ZERO] (too short) and at [SLEEP_LONG_ZERO] (too long). Regularity: how far
 *   bed time and wake time are, on average, from the median of the other nights of the seven
 *   before it (at least [REGULAR_MIN_NIGHTS] of them): 100 up to [REGULAR_FULL] minutes off,
 *   falling in a straight line to 0 at [REGULAR_ZERO].
 * - **Digital balance** = 70 % screen time + 30 % unlocks. Screen time: 100 up to
 *   [SCREEN_FULL] minutes, 0 from [SCREEN_ZERO]. Unlocks: 100 up to [UNLOCKS_FULL], 0 from
 *   [UNLOCKS_ZERO]. Straight lines in between.
 * - **Tasks** = finished ÷ counted × 100, where counted is the tasks due that day plus every
 *   other task finished that day, and finished is the due ones done on that day or before it
 *   plus those others. A day on which nothing was due and nothing was finished has no score.
 * - **Day** = the mean of the parts that have a score.
 */
object Scores {
    const val SLEEP_LOW = 7 * 60
    const val SLEEP_HIGH = 9 * 60
    const val SLEEP_SHORT_ZERO = 4 * 60
    const val SLEEP_LONG_ZERO = 12 * 60
    const val REGULAR_FULL = 30
    const val REGULAR_ZERO = 120
    const val REGULAR_MIN_NIGHTS = 3
    const val REGULAR_NIGHTS_BACK = 7
    const val SCREEN_FULL = 3 * 60
    const val SCREEN_ZERO = 8 * 60
    const val UNLOCKS_FULL = 50
    const val UNLOCKS_ZERO = 150
    const val MAIN_WEIGHT = 0.7
    const val SIDE_WEIGHT = 0.3

    /** The days the tab lists, and the longest trend. */
    const val LIST_DAYS = 7
    const val TREND_DAYS = 14

    private const val DAY_MINUTES = 24 * 60

    /** 100 at [full] and before it, 0 at [zero] and beyond, a straight line between; [full] may lie on either side of [zero]. */
    fun falling(value: Double, full: Double, zero: Double): Double {
        if (full == zero) return if (value == full) 100.0 else 0.0
        return ((zero - value) / (zero - full) * 100.0).coerceIn(0.0, 100.0)
    }

    fun sleepLength(minutes: Int): Int = when {
        minutes < SLEEP_LOW -> falling(minutes.toDouble(), SLEEP_LOW.toDouble(), SLEEP_SHORT_ZERO.toDouble())
        minutes > SLEEP_HIGH -> falling(minutes.toDouble(), SLEEP_HIGH.toDouble(), SLEEP_LONG_ZERO.toDouble())
        else -> 100.0
    }.roundToInt()

    /** Minutes between two times of day, the short way round the clock (23:50 and 00:10 are 20 apart). */
    fun clockDistance(a: Int, b: Int): Int = abs(a - b).let { minOf(it, DAY_MINUTES - it) }

    /**
     * The middle of some times of day on the clock's circle: the one of them with the smallest
     * total distance to the others (so times around midnight do not average to noon).
     */
    fun clockMedian(minutes: List<Int>): Int? = minutes.minByOrNull { candidate -> minutes.sumOf { clockDistance(candidate, it) } }

    private fun minuteOfDay(clock: String): Int? = runCatching { LocalTime.parse(clock) }.getOrNull()?.let { it.hour * 60 + it.minute }

    /** How many minutes [night]'s bed and wake times are off the usual ones of [others], on average; null with too few of them. */
    fun offUsual(night: Night, others: List<Night>): Int? {
        val bed = minuteOfDay(night.bed) ?: return null
        val wake = minuteOfDay(night.wake) ?: return null
        val beds = others.mapNotNull { minuteOfDay(it.bed) }
        val wakes = others.mapNotNull { minuteOfDay(it.wake) }
        if (beds.size < REGULAR_MIN_NIGHTS || wakes.size < REGULAR_MIN_NIGHTS) return null
        val usualBed = clockMedian(beds) ?: return null
        val usualWake = clockMedian(wakes) ?: return null
        return ((clockDistance(bed, usualBed) + clockDistance(wake, usualWake)) / 2.0).roundToInt()
    }

    fun regularity(offMinutes: Int): Int = falling(offMinutes.toDouble(), REGULAR_FULL.toDouble(), REGULAR_ZERO.toDouble()).roundToInt()

    /** The sleep score of [night] against [others], the known nights of the seven mornings before it. */
    fun sleep(night: Night, others: List<Night> = emptyList()): SleepScore? {
        val minutes = runCatching { night.minutes }.getOrNull() ?: return null
        val length = sleepLength(minutes)
        val off = offUsual(night, others)
        val regular = off?.let(::regularity)
        val score = if (regular == null) length else (MAIN_WEIGHT * length + SIDE_WEIGHT * regular).roundToInt()
        return SleepScore(score, length, regular, off)
    }

    fun screenTime(minutes: Int): Int = falling(minutes.toDouble(), SCREEN_FULL.toDouble(), SCREEN_ZERO.toDouble()).roundToInt()

    fun unlocks(count: Int): Int = falling(count.toDouble(), UNLOCKS_FULL.toDouble(), UNLOCKS_ZERO.toDouble()).roundToInt()

    fun digital(day: DayUsage): DigitalScore {
        val screen = screenTime(day.screenMinutes)
        val unlock = unlocks(day.unlocks)
        return DigitalScore((MAIN_WEIGHT * screen + SIDE_WEIGHT * unlock).roundToInt(), screen, unlock)
    }

    /** Null when nothing was due and nothing was finished that day (or the numbers make no sense). */
    fun tasks(day: TaskDay): Int? {
        val counted = day.due + day.doneOther
        val finished = day.dueDone + day.doneOther
        if (counted <= 0 || day.due < 0 || day.dueDone < 0 || day.doneOther < 0) return null
        return (finished.coerceAtMost(counted) * 100.0 / counted).roundToInt()
    }

    /** The mean of the parts that have a score; null when none has. */
    fun overall(parts: List<Int?>): Int? = parts.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    /**
     * The last [count] days up to [today], **today first**. A day's night is the one that ended
     * on its morning; its regularity looks at the nights of the seven mornings before it.
     */
    fun days(today: LocalDate, count: Int, nights: List<Night>, usage: List<DayUsage>, tasks: List<TaskDay>): List<DayScore> {
        val nightBy = nights.associateBy { it.date }
        val usageBy = usage.associateBy { it.date }
        val tasksBy = tasks.associateBy { it.date }
        return (0 until count).map { back ->
            val day = today.minusDays(back.toLong())
            val key = day.toString()
            val night = nightBy[key]
            val before = (1..REGULAR_NIGHTS_BACK).mapNotNull { nightBy[day.minusDays(it.toLong()).toString()] }
            val used = usageBy[key]
            val taskDay = tasksBy[key]
            DayScore(
                date = day,
                night = night,
                usage = used,
                tasks = taskDay,
                sleep = night?.let { sleep(it, before) },
                digital = used?.let(::digital),
                task = taskDay?.let(::tasks),
            )
        }
    }

    /** The mean of a part (null: the day's overall score) over the days that have it. */
    fun average(days: List<DayScore>, part: ScorePart?): Int? = overall(days.map { if (part == null) it.overall else it.part(part) })
}
