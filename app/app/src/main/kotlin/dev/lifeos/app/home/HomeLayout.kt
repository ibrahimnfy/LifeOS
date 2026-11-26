package dev.lifeos.app.home

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The tiles of the home field (#192), in the order of "Tidy", which is also the order a screen
 * reader goes through them. Each one opens a screen the app already has. [id] is what the saved
 * layout names; never change one.
 */
enum class HomeTile(val id: String) {
    COACH("coach"),
    TODAY("today"),
    TASKS("tasks"),
    CALENDAR("calendar"),
    CHAT("chat"),
    NOTES("notes"),
    COURSES("courses"),
    ACTIVITY("activity"),
    PROJECTS("projects");

    companion object {
        val ids: List<String> = entries.map { it.id }

        fun fromId(id: String?): HomeTile? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One tile as it is saved: its centre as a fraction of the field (so the layout fits another
 * screen size or rotation), its width in dp and its stacking order.
 */
@Serializable
data class SavedTile(val id: String, val fx: Float, val fy: Float, val size: Float, val z: Int = 0)

@Serializable
data class SavedLayout(val v: Int = HomeLayout.VERSION, val tiles: List<SavedTile> = emptyList())

/** The saved layout of the home field: one short JSON text in the app's settings. */
object HomeLayout {
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(field: Field): String {
        val w = field.width.takeIf { it > 0f } ?: 1f
        val h = field.height.takeIf { it > 0f } ?: 1f
        return json.encodeToString(
            SavedLayout.serializer(),
            SavedLayout(tiles = field.tiles.map { SavedTile(it.id, it.x / w, it.y / h, it.size, it.z) }),
        )
    }

    /**
     * Reads a saved layout. Anything missing, of another version or not readable is null (the
     * default layout is used); single tiles with numbers that make no sense are dropped.
     */
    fun decode(text: String?): List<SavedTile>? {
        if (text.isNullOrBlank()) return null
        val saved = try {
            json.decodeFromString(SavedLayout.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        if (saved.v != VERSION) return null
        return saved.tiles
            .filter { it.fx.isFinite() && it.fy.isFinite() && it.size.isFinite() && it.size > 0f }
            .distinctBy { it.id }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * The saved tiles placed on [default]'s field. A tile the saved layout does not know (a new
     * feature) keeps its default place; one that no longer exists is dropped. Sizes and places
     * are brought inside the field's limits, and the stacking order is made 0, 1, 2, … again.
     */
    fun restore(saved: List<SavedTile>?, default: Field): Field {
        if (saved == null) return default
        val byId = saved.associateBy { it.id }
        val top = saved.maxOfOrNull { it.z } ?: 0
        val placed = default.tiles.map { tile ->
            val s = byId[tile.id] ?: return@map tile.copy(z = top + 1 + tile.z)
            TilePhysics.clamp(default, Tile(tile.id, s.fx * default.width, s.fy * default.height, s.size, z = s.z))
        }
        val order = placed.sortedBy { it.z }.map { it.id }
        return default.copy(tiles = placed.map { it.copy(z = order.indexOf(it.id)) })
    }
}
