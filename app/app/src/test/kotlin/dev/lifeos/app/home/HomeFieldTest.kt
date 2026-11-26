package dev.lifeos.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the frame loop runs, when it stops and when the layout is saved. */
class HomeFieldTest {
    private val saves = mutableListOf<String>()
    private val frameNanos = 16_000_000L

    private fun ready(stored: String? = null, reducedMotion: Boolean = false): HomeField {
        val tiles = HomeField(HomeTile.ids) { saves += it }
        tiles.reducedMotion = reducedMotion
        tiles.loaded(stored)
        tiles.measured(412f, 640f)
        return tiles
    }

    /** Asks for frames as the screen does: only while [HomeField.running]. Returns how many were drawn. */
    private fun runFrames(tiles: HomeField, limit: Int = 4000): Int {
        var now = 1_000_000_000L
        var frames = 0
        while (tiles.running && frames < limit) {
            now += frameNanos
            frames++
            if (!tiles.frame(now)) break
        }
        return frames
    }

    private fun centre(tiles: HomeField, id: String): Pair<Float, Float> = tiles.field!!.tile(id)!!.let { it.x to it.y }

    @Test
    fun `nothing is shown before both the saved layout and the size are known`() {
        val sizeFirst = HomeField(HomeTile.ids) { saves += it }
        sizeFirst.measured(412f, 640f)
        assertNull(sizeFirst.field)
        sizeFirst.loaded(null)
        assertNotNull(sizeFirst.field)

        val layoutFirst = HomeField(HomeTile.ids) { saves += it }
        layoutFirst.loaded(null)
        assertNull(layoutFirst.field)
        layoutFirst.measured(0f, 0f) // not laid out yet
        assertNull(layoutFirst.field)
        layoutFirst.measured(412f, 640f)
        assertEquals(HomeTile.ids, layoutFirst.field!!.tiles.map { it.id })
        // Opening the screen moves nothing and saves nothing.
        assertFalse(layoutFirst.running)
        assertTrue(saves.isEmpty())
    }

    @Test
    fun `a fling starts the frame loop and it stops by itself when the tiles rest`() {
        val tiles = ready()
        assertFalse(tiles.running)
        val (x, y) = centre(tiles, "notes")
        val press = tiles.press(x, y)!!
        assertEquals("notes", press.id)
        assertFalse(press.caught)
        assertEquals("notes", tiles.held)
        tiles.drag("notes", x + 30f, y - 20f)
        assertFalse(tiles.running) // a drag needs no frames: the finger moves the tile
        tiles.release("notes", 1.8f, -1.2f)
        assertNull(tiles.held)
        assertTrue(tiles.running)

        val frames = runFrames(tiles)
        assertFalse(tiles.running)
        assertTrue("ran $frames frames", frames in 30..700)
        assertTrue(tiles.field!!.atRest)
        assertFalse(tiles.frame(99_000_000_000L)) // a late frame does nothing
        // Saved once, at rest, not per frame.
        assertEquals(1, saves.size)
        assertNotEquals(x + 30f, tiles.field!!.tile("notes")!!.x)
    }

    @Test
    fun `hiding the screen stops everything at once and saves`() {
        val tiles = ready()
        val (x, y) = centre(tiles, "coach")
        tiles.press(x, y)
        tiles.drag("coach", x + 10f, y + 40f)
        tiles.release("coach", 2f, 2f)
        var now = 5_000_000_000L
        repeat(5) {
            now += frameNanos
            assertTrue(tiles.frame(now))
        }
        tiles.hide()
        assertFalse(tiles.running)
        assertTrue(tiles.field!!.atRest)
        assertEquals(1, saves.size)
        assertFalse(tiles.frame(now + frameNanos))
        assertEquals(1, saves.size)
        // Hidden while a finger was down: the tile is let go too.
        val (cx, cy) = centre(tiles, "coach")
        tiles.press(cx, cy)
        tiles.hide()
        assertNull(tiles.held)
    }

    @Test
    fun `with animations removed nothing flies and tidy does not slide`() {
        val tiles = ready(reducedMotion = true)
        val (x, y) = centre(tiles, "notes")
        tiles.press(x, y)
        tiles.drag("notes", x + 40f, y - 30f)
        val dropped = centre(tiles, "notes")
        tiles.release("notes", 2f, -2f)
        assertFalse(tiles.running)
        assertEquals(dropped, centre(tiles, "notes")) // the tile just moved to where it was dropped
        assertEquals(1, saves.size)

        tiles.tidy()
        assertFalse(tiles.running)
        assertEquals(TilePhysics.tidy(tiles.field!!, HomeTile.ids).tiles.map { it.x }, tiles.field!!.tiles.map { it.x })
        assertEquals(2, saves.size)
    }

    @Test
    fun `tidy slides into the grid, stops there and can be taken back`() {
        val tiles = ready()
        val before = tiles.tidy()!!
        assertTrue(tiles.running)
        val frames = runFrames(tiles)
        // About half a second of frames, then the loop is off.
        assertTrue("ran $frames frames", frames in 25..45)
        assertFalse(tiles.running)
        val grid = tiles.field!!
        assertEquals(1, grid.tiles.map { it.size }.toSet().size)
        assertEquals(1, saves.size)

        tiles.restore(before)
        runFrames(tiles)
        assertEquals(before.tiles.map { it.x to it.y }, tiles.field!!.tiles.map { it.x to it.y })
        assertEquals(before.tiles.map { it.size }, tiles.field!!.tiles.map { it.size })
    }

    @Test
    fun `reset brings the default layout back`() {
        val tiles = ready()
        val default = tiles.field!!
        tiles.tidy()
        runFrames(tiles)
        assertNotEquals(default.tiles.map { it.x }, tiles.field!!.tiles.map { it.x })
        tiles.reset()
        runFrames(tiles)
        assertEquals(default.tiles.map { Triple(it.x, it.y, it.size) }, tiles.field!!.tiles.map { Triple(it.x, it.y, it.size) })
    }

    @Test
    fun `a touch on a flying tile catches it and one on the empty field does nothing`() {
        val tiles = ready()
        val (x, y) = centre(tiles, "coach")
        tiles.press(x, y)
        tiles.drag("coach", x, y + 5f)
        tiles.release("coach", 0.4f, 0f)
        tiles.frame(1_000_000_000L)
        val (fx, fy) = centre(tiles, "coach")
        val press = tiles.press(fx, fy)!!
        assertTrue(press.caught)
        assertFalse(tiles.field!!.tile("coach")!!.moving)
        tiles.release("coach")

        assertNull(tiles.press(-50f, -50f))
        assertNull(tiles.held)
    }

    @Test
    fun `the touched tile is on top and stays there after a restart`() {
        val tiles = ready()
        val (x, y) = centre(tiles, "projects")
        tiles.press(x, y)
        tiles.release("projects")
        val top = tiles.field!!.tiles.maxByOrNull { it.z }!!.id
        assertEquals("projects", top)
        tiles.pinch("projects", 150f, x, y)
        tiles.release("projects")

        val again = ready(stored = saves.last())
        assertEquals("projects", again.field!!.tiles.maxByOrNull { it.z }!!.id)
        assertEquals(150f, again.field!!.tile("projects")!!.size, 0.01f)
        assertEquals(tiles.field!!.tiles.map { it.x to it.y }, again.field!!.tiles.map { it.x to it.y })
    }

    @Test
    fun `larger and smaller change the size within the limits without a pinch`() {
        val tiles = ready()
        val size = tiles.field!!.tile("notes")!!.size
        tiles.scale("notes", 1.25f)
        assertEquals(size * 1.25f, tiles.field!!.tile("notes")!!.size, 0.01f)
        repeat(20) { tiles.scale("notes", 1.25f) }
        assertEquals(TilePhysics.MAX_SIZE, tiles.field!!.tile("notes")!!.size, 0f)
        repeat(20) { tiles.scale("notes", 0.8f) }
        assertEquals(TilePhysics.MIN_SIZE, tiles.field!!.tile("notes")!!.size, 0f)
        assertTrue(saves.isNotEmpty())
        assertFalse(tiles.running)
    }

    @Test
    fun `a rotation keeps every tile inside and turning back restores the layout`() {
        val tiles = ready()
        val portrait = tiles.field!!
        tiles.measured(900f, 300f)
        val landscape = tiles.field!!
        landscape.tiles.forEach {
            assertTrue(it.x - it.size / 2 >= -0.01f && it.x + it.size / 2 <= 900.01f)
            assertTrue(it.y + it.height / 2 <= 300.01f)
        }
        tiles.measured(412f, 640f)
        // Nothing was touched in landscape, so the layout the owner left is back exactly.
        assertEquals(portrait, tiles.field)
        assertTrue(saves.isEmpty())
    }

    @Test
    fun `a bounce off an edge is counted for the flash`() {
        val tiles = ready()
        val (x, y) = centre(tiles, "coach")
        tiles.press(x, y)
        tiles.drag("coach", x + 5f, y)
        tiles.release("coach", 2.6f, 0f)
        runFrames(tiles)
        assertTrue((tiles.bounces["coach"] ?: 0) >= 1)
        tiles.hide()
        assertTrue(tiles.bounces.isEmpty())
    }
}
