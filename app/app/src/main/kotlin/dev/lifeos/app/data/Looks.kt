package dev.lifeos.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * Which colour and icon a course was given (indexes into the palette and the icons). Kept on
 * the phone ([LookStore]) so a course keeps its look for as long as it exists.
 */
@Serializable
data class Slot(val tone: Int, val glyph: Int)

/**
 * The owner's rule (docs/decisions.md, 2026-11-21): a course keeps the colour and icon it was
 * first given for as long as it exists; a new course takes the next free colour; existing
 * courses are never re-coloured.
 */
object LookAssignments {
    /**
     * The assignment after a new course list: courses still there keep their slot; a course
     * that is gone frees its slot; new courses (in id order) take the first free tone and the
     * first free icon in palette order (the least used one once all are taken). An empty list
     * changes nothing: it may only mean that courses are not set up right now.
     */
    fun update(previous: Map<Long, Slot>, courses: List<Course>, tones: Int = Tone.entries.size, glyphs: Int = Looks.COURSE_GLYPH_COUNT): Map<Long, Slot> {
        if (courses.isEmpty()) return previous
        val ids = courses.map { it.id }.toSet()
        val result = LinkedHashMap(previous.filterKeys { it in ids })
        for (id in ids.sorted().filter { it !in result }) {
            result[id] = Slot(firstFree(result.values.map { it.tone }, tones), firstFree(result.values.map { it.glyph }, glyphs))
        }
        return result
    }

    private fun firstFree(taken: List<Int>, size: Int): Int {
        val counts = IntArray(size)
        taken.filter { it in 0 until size }.forEach { counts[it]++ }
        val least = counts.min()
        return counts.indexOfFirst { it == least }
    }
}

/** The assignment on storage: one small JSON file, replaced atomically; deleted on unpair. */
class LookStore(private val file: File) {
    private val serializer = MapSerializer(String.serializer(), Slot.serializer())

    fun load(): Map<Long, Slot> = try {
        BridgeJson.decodeFromString(serializer, file.readText()).mapNotNull { (key, slot) -> key.toLongOrNull()?.let { it to slot } }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    fun save(slots: Map<Long, Slot>) {
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".new")
            temporary.writeText(BridgeJson.encodeToString(serializer, slots.mapKeys { it.key.toString() }))
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // Not written: the next course list tries again; the looks in memory stay as they are.
        }
    }

    fun wipe() {
        file.delete()
    }
}

/**
 * The looks of the courses, from the stored assignment ([LookAssignments]), so every screen
 * shows a course the same way. A course not assigned yet shows a look from its id until the
 * next course list assigns it.
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

    /** A project's look (#152): the same as on its tasks (`project` field), always with the folder icon. */
    fun project(name: String): Look = named(name, project = true)

    /**
     * The course a task's `course` field names: its course folder (`ex-101-5`, the bridge's
     * short name plus course id), its id, short name or name, ignoring case and spaces.
     */
    fun match(name: String): Course? {
        val key = norm(name)
        FOLDER.find(name.trim())?.let { found ->
            val id = found.groupValues[2].toLongOrNull()
            courses.firstOrNull { it.id == id && norm(it.shortname) == norm(found.groupValues[1]) }?.let { return it }
        }
        return courses.firstOrNull { it.id.toString() == key || norm(it.shortname) == key || norm(it.name) == key }
    }

    override fun equals(other: Any?): Boolean = other is Looks && other.byCourse == byCourse && other.courses == courses

    override fun hashCode(): Int = byCourse.hashCode()

    companion object {
        /** A course folder as the bridge names it: the short name in lowercase with hyphens, then `-<id>`. */
        private val FOLDER = Regex("^([a-z0-9-]+?)-(\\d+)$")
        private val TONES = Tone.entries
        private val COURSE_GLYPHS = listOf(Glyph.WAVE, Glyph.CHIP, Glyph.MATRIX, Glyph.PEN, Glyph.DICE, Glyph.FLASK)
        private val PATTERNS = listOf(Pattern.WAVES, Pattern.GRID, Pattern.DOTS, Pattern.ARCS, Pattern.TRIANGLES, Pattern.CIRCLES)

        val EMPTY = of(emptyList())

        const val COURSE_GLYPH_COUNT = 6

        /** The looks for [courses] from their stored [slots] (by default: as if assigned afresh). */
        fun of(courses: List<Course>, slots: Map<Long, Slot> = LookAssignments.update(emptyMap(), courses)): Looks {
            val looks = slots.mapValues { (_, slot) ->
                val glyph = Math.floorMod(slot.glyph, COURSE_GLYPHS.size)
                Look(TONES[Math.floorMod(slot.tone, TONES.size)], COURSE_GLYPHS[glyph], PATTERNS[glyph])
            }
            return Looks(looks, courses)
        }

        /**
         * A conversation folder's look (#168) from the bridge's stable [colour] number: the
         * palette's tone and an ornament at that place. The folder icon stands in for the
         * glyph; the screens show the icon of the folder's kind.
         */
        fun folder(colour: Int): Look =
            Look(TONES[Math.floorMod(colour, TONES.size)], Glyph.FOLDER, PATTERNS[Math.floorMod(colour, PATTERNS.size)])

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
