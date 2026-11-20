package dev.lifeos.app.data

import java.util.Locale

/** The seven soft tones of the warmer design (docs/design/mockups/README.md), in palette order. */
enum class Tone { SKY, TEAL, VIOLET, PEACH, SAND, MINT, ROSE }

/** A course's own line icon. */
enum class Glyph { WAVE, CHIP, MATRIX, PEN, DICE, FLASK, FOLDER }

/** A course's geometric ornament on the Courses screens. */
enum class Pattern { WAVES, GRID, DOTS, ARCS, TRIANGLES, CIRCLES }

/** How a course (or a project) looks wherever it appears: its colour, its icon and its ornament. */
data class Look(val tone: Tone, val glyph: Glyph, val pattern: Pattern)

/**
 * The looks of the courses, worked out from their ids only, so every screen shows a course the
 * same way and nothing is stored or picked by hand.
 *
 * Rule: courses are taken in the order of their ids. Each one wants the tone at
 * `id mod 7` and the icon (with its ornament) at `id mod 6`; if another course with a smaller
 * id has it already, it takes the next free one (while there are free ones). So a course keeps
 * its look as long as the set of courses stays the same, and two courses share a colour only
 * when there are more than seven.
 *
 * A project, or a task's course name that matches no known course, gets a look from a stable
 * hash of its name; projects always carry the folder icon.
 */
class Looks private constructor(private val byCourse: Map<Long, Look>, private val courses: List<Course>) {
    fun course(id: Long): Look = byCourse[id] ?: wanted(id)

    /** The look of a task's course or project, or null when it has neither. */
    fun forTask(task: Task): Look? {
        task.course?.takeIf { it.isNotBlank() }?.let { name ->
            val known = match(name)
            return if (known != null) course(known.id) else named(name, project = false)
        }
        return task.project?.takeIf { it.isNotBlank() }?.let { named(it, project = true) }
    }

    /** The course a task's `course` field names: by id, short name or name, ignoring case and spaces. */
    fun match(name: String): Course? {
        val key = norm(name)
        return courses.firstOrNull { it.id.toString() == key || norm(it.shortname) == key || norm(it.name) == key }
    }

    override fun equals(other: Any?): Boolean = other is Looks && other.byCourse == byCourse && other.courses == courses

    override fun hashCode(): Int = byCourse.hashCode()

    companion object {
        private val TONES = Tone.entries
        private val COURSE_GLYPHS = listOf(Glyph.WAVE, Glyph.CHIP, Glyph.MATRIX, Glyph.PEN, Glyph.DICE, Glyph.FLASK)
        private val PATTERNS = listOf(Pattern.WAVES, Pattern.GRID, Pattern.DOTS, Pattern.ARCS, Pattern.TRIANGLES, Pattern.CIRCLES)

        val EMPTY = of(emptyList())

        fun of(courses: List<Course>): Looks {
            val tonesTaken = mutableSetOf<Int>()
            val glyphsTaken = mutableSetOf<Int>()
            val looks = LinkedHashMap<Long, Look>()
            for (course in courses.distinctBy { it.id }.sortedBy { it.id }) {
                val tone = free(Math.floorMod(course.id, TONES.size.toLong()).toInt(), TONES.size, tonesTaken)
                val glyph = free(Math.floorMod(course.id, COURSE_GLYPHS.size.toLong()).toInt(), COURSE_GLYPHS.size, glyphsTaken)
                looks[course.id] = Look(TONES[tone], COURSE_GLYPHS[glyph], PATTERNS[glyph])
            }
            return Looks(looks, courses)
        }

        private fun free(wanted: Int, size: Int, taken: MutableSet<Int>): Int {
            val pick = if (taken.size >= size) wanted else (0 until size).map { (wanted + it) % size }.first { it !in taken }
            taken += pick
            return pick
        }

        private fun wanted(id: Long): Look {
            val glyph = Math.floorMod(id, COURSE_GLYPHS.size.toLong()).toInt()
            return Look(TONES[Math.floorMod(id, TONES.size.toLong()).toInt()], COURSE_GLYPHS[glyph], PATTERNS[glyph])
        }

        private fun named(name: String, project: Boolean): Look {
            val hash = stableHash(norm(name))
            val glyph = if (project) Glyph.FOLDER else COURSE_GLYPHS[Math.floorMod(hash / 7, COURSE_GLYPHS.size.toLong()).toInt()]
            val pattern = if (project) Pattern.CIRCLES else PATTERNS[COURSE_GLYPHS.indexOf(glyph)]
            return Look(TONES[Math.floorMod(hash, TONES.size.toLong()).toInt()], glyph, pattern)
        }

        private fun norm(text: String): String = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        /** FNV-1a over the UTF-8 bytes: the same on every phone and every run. */
        fun stableHash(text: String): Long {
            var hash = 0xcbf29ce484222325uL
            for (byte in text.toByteArray()) {
                hash = hash xor byte.toUByte().toULong()
                hash *= 0x100000001b3uL
            }
            return (hash shr 1).toLong()
        }
    }
}
