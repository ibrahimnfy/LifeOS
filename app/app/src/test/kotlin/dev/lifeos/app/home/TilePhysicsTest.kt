package dev.lifeos.app.home

import dev.lifeos.app.settings.HomeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TilePhysicsTest {
    private val frame = 16f

    private fun field(vararg tiles: Tile, width: Float = 400f, height: Float = 700f) = Field(width, height, tiles.toList())

    private fun inside(field: Field, tile: Tile): Boolean =
        tile.x - tile.size / 2 >= -0.01f && tile.x + tile.size / 2 <= field.width + 0.01f &&
            tile.y - tile.height / 2 >= TilePhysics.TOP_PAD - 0.01f && tile.y + tile.height / 2 <= field.height + 0.01f

    /** Runs frames until nothing moves; fails when that takes longer than a minute of frames. */
    private fun settle(start: Field, held: String? = null): Pair<Field, Int> {
        var field = start
        repeat(4000) { frames ->
            val step = TilePhysics.step(field, frame, held)
            field = step.field
            if (!step.moving) return field to frames + 1
        }
        throw AssertionError("the tiles never came to rest")
    }

    // ---- inertia and friction

    @Test
    fun `a flung tile flies on in the direction and with the speed of the fling`() {
        val start = TilePhysics.fling(field(Tile("a", 200f, 350f, 100f)), "a", 1f, -0.5f)
        val step = TilePhysics.step(start, frame)
        val tile = step.field.tile("a")!!
        assertEquals(200f + 16f, tile.x, 0.01f)
        assertEquals(350f - 8f, tile.y, 0.01f)
        assertTrue(step.moving)
        assertTrue(step.hits.isEmpty())
    }

    @Test
    fun `a faster fling travels farther`() {
        fun travelled(speed: Float): Float {
            val start = TilePhysics.fling(field(Tile("a", 60f, 350f, 100f), width = 100_000f), "a", speed, 0f)
            return settle(start).first.tile("a")!!.x - 60f
        }
        val slow = travelled(0.5f)
        val fast = travelled(2f)
        assertTrue(slow > 100f)
        // Without an edge in the way the distance is the speed times the friction's time constant.
        assertEquals(4f, fast / slow, 0.1f)
        assertEquals(2f * TilePhysics.FRICTION_MS, fast, 2f * TilePhysics.FRICTION_MS * 0.02f)
    }

    @Test
    fun `friction slows a tile every frame and it comes to rest`() {
        val start = TilePhysics.fling(field(Tile("a", 200f, 350f, 100f)), "a", 2f, 1.3f)
        val first = TilePhysics.step(start, frame).field.tile("a")!!
        assertTrue(abs(first.vx) < 2f && abs(first.vy) < 1.3f)
        val (rest, frames) = settle(start)
        assertTrue(rest.atRest)
        assertEquals(0f, rest.tile("a")!!.vx, 0f)
        // About seven seconds for the fastest fling: the loop does not run for long.
        assertTrue("took $frames frames", frames < 600)
        assertTrue(inside(rest, rest.tile("a")!!))
    }

    @Test
    fun `a field at rest reports no movement, so the frame loop stops`() {
        val still = field(Tile("a", 100f, 100f, 100f), Tile("b", 300f, 400f, 120f))
        val step = TilePhysics.step(still, frame)
        assertFalse(step.moving)
        assertEquals(still, step.field)
        assertTrue(still.atRest)
    }

    @Test
    fun `the speed of a fling is capped`() {
        val flung = TilePhysics.fling(field(Tile("a", 200f, 350f, 100f)), "a", 40f, -40f).tile("a")!!
        assertEquals(TilePhysics.MAX_SPEED, flung.vx, 0f)
        assertEquals(-TilePhysics.MAX_SPEED, flung.vy, 0f)
        val broken = TilePhysics.fling(field(Tile("a", 200f, 350f, 100f)), "a", Float.NaN, Float.POSITIVE_INFINITY).tile("a")!!
        assertTrue(broken.vx.isFinite() && broken.vy.isFinite())
    }

    @Test
    fun `a stalled frame counts for one long frame at most`() {
        val start = TilePhysics.fling(field(Tile("a", 200f, 350f, 100f)), "a", 1f, 0f)
        val tile = TilePhysics.step(start, 5_000f).field.tile("a")!!
        assertEquals(200f + TilePhysics.MAX_FRAME_MS, tile.x, 0.01f)
    }

    // ---- edges

    @Test
    fun `a tile bounces off every edge and keeps most of its speed`() {
        val f = field(Tile("a", 200f, 350f, 100f))
        fun bounce(x: Float, y: Float, vx: Float, vy: Float): Pair<Tile, Step> {
            val start = TilePhysics.fling(TilePhysics.moveTo(f, "a", x, y), "a", vx, vy)
            val step = TilePhysics.step(start, frame)
            return step.field.tile("a")!! to step
        }
        val decay = kotlin.math.exp(-frame / TilePhysics.FRICTION_MS)

        val (right, rightStep) = bounce(349f, 350f, 1f, 0f)
        assertEquals(350f, right.x, 0.01f) // 400 − half the width
        assertEquals(-TilePhysics.RESTITUTION * decay, right.vx, 0.001f)
        assertEquals(setOf("a"), rightStep.hits)

        val (left, _) = bounce(51f, 350f, -1f, 0f)
        assertEquals(50f, left.x, 0.01f)
        assertTrue(left.vx > 0f)

        val (top, _) = bounce(200f, 48f, 0f, -1f)
        assertEquals(39f + TilePhysics.TOP_PAD, top.y, 0.01f)
        assertTrue(top.vy > 0f)

        val (bottom, bottomStep) = bounce(200f, 660f, 0f, 1f)
        assertEquals(700f - 39f, bottom.y, 0.01f)
        assertTrue(bottom.vy < 0f)
        assertEquals(setOf("a"), bottomStep.hits)
    }

    @Test
    fun `a tile never leaves the field however it is thrown`() {
        var f = TilePhysics.fling(field(Tile("a", 200f, 350f, 150f)), "a", 2.6f, -2.1f)
        var bounces = 0
        repeat(4000) {
            val step = TilePhysics.step(f, frame)
            f = step.field
            bounces += step.hits.size
            assertTrue(inside(f, f.tile("a")!!))
            if (!step.moving) return@repeat
        }
        assertTrue(f.atRest)
        assertTrue("bounced $bounces times", bounces >= 3)
    }

    // ---- the finger

    @Test
    fun `the tile under the finger is not moved by the physics`() {
        val start = TilePhysics.fling(field(Tile("a", 200f, 350f, 100f), Tile("b", 100f, 100f, 100f)), "a", 1f, 1f)
        val step = TilePhysics.step(start, frame, held = "a")
        assertEquals(200f, step.field.tile("a")!!.x, 0f)
        assertFalse(step.moving) // only the held tile has a speed: nothing to animate
    }

    @Test
    fun `dragging keeps the tile inside the field and at rest`() {
        val f = TilePhysics.fling(field(Tile("a", 200f, 350f, 100f)), "a", 1f, 1f)
        val dragged = TilePhysics.moveTo(f, "a", -500f, 9_000f).tile("a")!!
        assertEquals(50f, dragged.x, 0f)
        assertEquals(700f - 39f, dragged.y, 0.01f)
        assertFalse(dragged.moving)
    }

    @Test
    fun `a touch brings the tile to the front and keeps the order of the others`() {
        val f = field(Tile("a", 100f, 100f, 100f, z = 0), Tile("b", 110f, 110f, 100f, z = 1), Tile("c", 120f, 120f, 100f, z = 2))
        val front = TilePhysics.toFront(f, "a")
        assertEquals(mapOf("b" to 0, "c" to 1, "a" to 2), front.tiles.associate { it.id to it.z })
        assertEquals(front, TilePhysics.toFront(front, "a"))
        assertEquals(f, TilePhysics.toFront(f, "nobody"))
        // However often tiles are touched, the ranks stay small.
        var many = f
        repeat(500) { many = TilePhysics.toFront(many, listOf("a", "b", "c")[it % 3]) }
        assertEquals(setOf(0, 1, 2), many.tiles.map { it.z }.toSet())
    }

    @Test
    fun `a touch finds the topmost of overlapping tiles`() {
        val f = field(Tile("a", 100f, 100f, 100f, z = 0), Tile("b", 120f, 110f, 100f, z = 1))
        assertEquals("b", TilePhysics.hit(f, 110f, 105f))
        assertEquals("a", TilePhysics.hit(TilePhysics.toFront(f, "a"), 110f, 105f))
        assertEquals("a", TilePhysics.hit(f, 55f, 100f)) // only a is here
        assertNull(TilePhysics.hit(f, 300f, 500f))
        // The height is 0.78 of the width: just below the tile is the empty field.
        assertNull(TilePhysics.hit(field(Tile("a", 100f, 100f, 100f)), 100f, 145f))
    }

    // ---- size

    @Test
    fun `a pinch changes the size between the smallest and the largest`() {
        val f = field(Tile("a", 200f, 350f, 100f))
        assertEquals(150f, TilePhysics.resize(f, "a", 150f, 200f, 350f).tile("a")!!.size, 0f)
        assertEquals(TilePhysics.MIN_SIZE, TilePhysics.resize(f, "a", 5f, 200f, 350f).tile("a")!!.size, 0f)
        assertEquals(TilePhysics.MAX_SIZE, TilePhysics.resize(f, "a", 900f, 200f, 350f).tile("a")!!.size, 0f)
        assertEquals(TilePhysics.MIN_SIZE, TilePhysics.resize(f, "a", Float.NaN, 200f, 350f).tile("a")!!.size, 0f)
    }

    @Test
    fun `the smallest tile is still a full touch target`() {
        assertTrue(TilePhysics.MIN_SIZE >= 48f)
        assertTrue(TilePhysics.MIN_SIZE * TilePhysics.HEIGHT_RATIO >= 48f)
    }

    @Test
    fun `a tile that grows at the edge stays inside`() {
        val f = field(Tile("a", 380f, 690f, 76f))
        val grown = TilePhysics.resize(f, "a", 210f, 380f, 690f)
        assertTrue(inside(grown, grown.tile("a")!!))
        assertEquals(210f, grown.tile("a")!!.size, 0f)
    }

    @Test
    fun `larger limits for a large font are respected`() {
        val f = Field(400f, 700f, listOf(Tile("a", 200f, 350f, 100f)), minSize = 120f)
        assertEquals(120f, TilePhysics.resize(f, "a", 80f, 200f, 350f).tile("a")!!.size, 0f)
    }

    // ---- the field changes

    @Test
    fun `after a rotation every tile is inside and keeps its place in proportion`() {
        val portrait = field(Tile("a", 100f, 175f, 100f), Tile("b", 350f, 660f, 100f), Tile("c", 200f, 350f, 210f))
        val landscape = TilePhysics.fit(portrait, 800f, 300f)
        landscape.tiles.forEach { assertTrue("${it.id} is outside", inside(landscape, it)) }
        assertEquals(200f, landscape.tile("a")!!.x, 0.01f)
        assertEquals(75f, landscape.tile("a")!!.y, 0.01f)
        // Back again: a tile that was not pushed by an edge is where it was.
        val back = TilePhysics.fit(landscape, 400f, 700f)
        assertEquals(100f, back.tile("a")!!.x, 0.01f)
        assertEquals(175f, back.tile("a")!!.y, 0.01f)
    }

    @Test
    fun `a field smaller than a tile does not crash and shrinks the tiles`() {
        val f = field(Tile("a", 200f, 350f, 210f), Tile("b", 10f, 10f, 76f))
        for ((w, h) in listOf(0f to 0f, 50f to 40f, 400f to 30f, 30f to 700f, 400f to 0f)) {
            val small = TilePhysics.fit(f, w, h)
            small.tiles.forEach {
                assertTrue(it.x.isFinite() && it.y.isFinite() && it.size.isFinite())
                assertTrue(it.size <= maxOf(1f, w) + 0.01f)
            }
            // Flying, dragging, pinching and tidying still work in it.
            val flying = TilePhysics.fling(small, "a", 2f, 2f)
            val (rest, _) = settle(flying)
            assertTrue(rest.atRest)
            TilePhysics.moveTo(small, "a", 5f, 5f)
            TilePhysics.resize(small, "a", 500f, 5f, 5f)
            TilePhysics.tidy(small)
            TilePhysics.scatter(small, listOf(Spot("a", 0.2f, 0.2f, 1f), Spot("b", 0.8f, 0.8f, 1.5f)))
        }
        // And back to a real screen: everything is inside again.
        val again = TilePhysics.fit(TilePhysics.fit(f, 0f, 0f), 400f, 700f)
        again.tiles.forEach { assertTrue(inside(again, it)) }
    }

    @Test
    fun `stopping keeps every place and ends all movement`() {
        val f = TilePhysics.fling(field(Tile("a", 200f, 350f, 100f), Tile("b", 100f, 100f, 100f)), "a", 2f, 1f)
        val stopped = TilePhysics.stop(f)
        assertTrue(stopped.atRest)
        assertEquals(200f, stopped.tile("a")!!.x, 0f)
    }

    // ---- knocks

    @Test
    fun `a flying tile knocks a resting one away and the two may overlap at rest`() {
        val f = TilePhysics.fling(field(Tile("a", 100f, 350f, 100f), Tile("b", 190f, 350f, 100f)), "a", 1.5f, 0f)
        var current = f
        var knocked = false
        repeat(60) {
            current = TilePhysics.step(current, frame).field
            if (current.tile("b")!!.vx > 0f) knocked = true
        }
        assertTrue(knocked)
        // Resting tiles are never pushed apart.
        val overlapped = field(Tile("a", 100f, 100f, 100f), Tile("b", 110f, 105f, 100f))
        assertEquals(overlapped, TilePhysics.step(overlapped, frame).field)
    }

    // ---- layouts

    @Test
    fun `tidy puts the tiles in a calm grid of equal size, in order, without overlap`() {
        val ids = HomeTile.ids
        val wild = TilePhysics.fling(TilePhysics.scatter(Field(412f, 640f, emptyList()), HomeTile.spots), "coach", 2f, 2f)
        val tidy = TilePhysics.tidy(wild, ids)
        assertTrue(tidy.atRest)
        assertEquals(ids, tidy.tiles.map { it.id })
        assertEquals(1, tidy.tiles.map { it.size }.toSet().size)
        tidy.tiles.forEach { assertTrue(inside(tidy, it)) }
        // Three columns on a phone: rows from top to bottom, columns from left to right.
        val rows = tidy.tiles.chunked(3)
        assertEquals(3, rows.size)
        rows.forEach { row ->
            assertEquals(1, row.map { it.y }.toSet().size)
            assertTrue(row[0].x < row[1].x && row[1].x < row[2].x)
        }
        assertTrue(rows[0][0].y < rows[1][0].y && rows[1][0].y < rows[2][0].y)
        // The prototype's grid: cells of (412 - 52) / 3 = 120, the first one 16 dp from the left.
        assertEquals(120f, tidy.tiles.first().size, 0.01f)
        assertEquals(16f + 60f, tidy.tiles.first().x, 0.01f)
        assertTrue(tidy.tiles.first().y - tidy.tiles.first().height / 2 >= TilePhysics.TIDY_TOP)
        for (a in tidy.tiles) for (b in tidy.tiles) {
            if (a.id < b.id) assertTrue(abs(a.x - b.x) >= a.size || abs(a.y - b.y) >= a.height)
        }
        // The stacking order is kept, so nothing jumps in front during the slide.
        assertEquals(wild.tiles.associate { it.id to it.z }, tidy.tiles.associate { it.id to it.z })
    }

    @Test
    fun `tidy fits small, wide and large screens`() {
        val ids = HomeTile.ids
        for ((w, h) in listOf(320f to 420f, 800f to 280f, 1200f to 700f, 360f to 250f)) {
            val tidy = TilePhysics.tidy(Field(w, h, emptyList()), ids)
            tidy.tiles.forEach {
                assertTrue("$w x $h: ${it.id} is outside", inside(tidy, it))
                assertTrue(it.size <= TilePhysics.MAX_SIZE)
            }
            for (a in tidy.tiles) for (b in tidy.tiles) {
                if (a.id < b.id) assertTrue("$w x $h: ${a.id} over ${b.id}", abs(a.x - b.x) >= a.size - 0.01f || abs(a.y - b.y) >= a.height - 0.01f)
            }
        }
        // Landscape uses more columns, so the tiles stay large enough to touch.
        val wide = TilePhysics.tidy(Field(800f, 280f, emptyList()), ids)
        assertTrue(wide.tiles.first().size >= 76f)
    }

    @Test
    fun `the default layout holds every tile inside the field`() {
        val scatter = TilePhysics.scatter(Field(412f, 640f, emptyList()), HomeTile.spots)
        assertEquals(HomeTile.ids, scatter.tiles.map { it.id })
        scatter.tiles.forEach { assertTrue(inside(scatter, it)) }
        assertEquals(HomeTile.ids.indices.toSet(), scatter.tiles.map { it.z }.toSet())
        assertTrue(scatter.atRest)
        assertTrue(scatter.tile("coach")!!.size > scatter.tile("projects")!!.size)
        // The prototype's numbers: a cell of floor((412 - 52) / 3) = 120, the coach 1.5 cells wide at the top centre.
        assertEquals(180f, scatter.tile("coach")!!.size, 0f)
        assertEquals(206f, scatter.tile("coach")!!.x, 0.01f)
        assertEquals(8f + 20f + 0.17f * 600f, scatter.tile("coach")!!.y, 0.01f)
        assertEquals(0.26f * 412f, scatter.tile("chats")!!.x, 0.01f)
        // Later tiles lie on top, as later elements do in the prototype.
        assertEquals("projects", scatter.tiles.maxByOrNull { it.z }!!.id)
    }

    @Test
    fun `the slide to the grid starts at the old places and ends exactly on the grid`() {
        val from = TilePhysics.scatter(Field(412f, 640f, emptyList()), HomeTile.spots)
        val to = TilePhysics.tidy(from)
        assertEquals(from.tiles.map { it.x }, TilePhysics.blend(from, to, 0f).tiles.map { it.x })
        assertEquals(to, TilePhysics.blend(from, to, 1f))
        assertEquals(to, TilePhysics.blend(from, to, 7f))
        val half = TilePhysics.blend(from, to, 0.5f).tile("coach")!!
        val a = from.tile("coach")!!
        val b = to.tile("coach")!!
        // Eased out: at half the time most of the way is done.
        assertEquals(a.y + (b.y - a.y) * 0.875f, half.y, 0.01f)
        assertFalse(half.moving)
    }

    // ---- the saved layout

    @Test
    fun `places, sizes and the stacking order survive saving`() {
        val field = TilePhysics.toFront(TilePhysics.resize(TilePhysics.scatter(Field(412f, 640f, emptyList()), HomeTile.spots), "notes", 180f, 150f, 300f), "notes")
        val saved = HomeLayout.decode(HomeLayout.encode(field))
        assertNotNull(saved)
        val restored = HomeLayout.restore(saved, TilePhysics.tidy(field))
        field.tiles.forEach { tile ->
            val back = restored.tile(tile.id)!!
            assertEquals(tile.x, back.x, 0.01f)
            assertEquals(tile.y, back.y, 0.01f)
            assertEquals(tile.size, back.size, 0.01f)
            assertEquals(tile.z, back.z)
        }
    }

    @Test
    fun `a saved layout fits another screen`() {
        val field = TilePhysics.scatter(Field(412f, 640f, emptyList()), HomeTile.spots)
        val wide = TilePhysics.scatter(Field(900f, 320f, emptyList()), HomeTile.spots)
        val restored = HomeLayout.restore(HomeLayout.decode(HomeLayout.encode(field)), wide)
        restored.tiles.forEach { assertTrue(inside(restored, it)) }
        assertEquals(900f, restored.width, 0f)
    }

    @Test
    fun `a layout that cannot be read falls back to the default`() {
        val default = TilePhysics.scatter(Field(412f, 640f, emptyList()), HomeTile.spots)
        for (text in listOf(null, "", "   ", "not json", "{}", """{"v":2,"tiles":[{"id":"coach","fx":0.5,"fy":0.5,"size":100}]}""", """{"v":1,"tiles":"x"}""")) {
            assertNull(text, HomeLayout.decode(text))
            assertEquals(default, HomeLayout.restore(HomeLayout.decode(text), default))
        }
    }

    @Test
    fun `unknown, missing and broken tiles of a saved layout are handled one by one`() {
        val default = TilePhysics.scatter(Field(412f, 640f, emptyList()), HomeTile.spots)
        val text = """{"v":1,"tiles":[
            {"id":"notes","fx":0.5,"fy":0.5,"size":120,"z":4},
            {"id":"gone","fx":0.1,"fy":0.1,"size":100,"z":9},
            {"id":"agenda","fx":7.0,"fy":-3.0,"size":9000,"z":2},
            {"id":"chats","fx":0.2,"fy":0.2,"size":-5,"z":1}
        ]}"""
        val restored = HomeLayout.restore(HomeLayout.decode(text), default)
        assertEquals(HomeTile.ids, restored.tiles.map { it.id })
        assertEquals(206f, restored.tile("notes")!!.x, 0.01f)
        assertEquals(120f, restored.tile("notes")!!.size, 0f)
        // Numbers out of range are brought inside; a tile without usable numbers keeps its default place.
        assertTrue(inside(restored, restored.tile("agenda")!!))
        assertEquals(TilePhysics.MAX_SIZE, restored.tile("agenda")!!.size, 0f)
        assertEquals(default.tile("chats")!!.x, restored.tile("chats")!!.x, 0f)
        assertEquals(HomeTile.ids.indices.toSet(), restored.tiles.map { it.z }.toSet())
    }

    // ---- the tiles and the setting

    @Test
    fun `tile ids are unique and found again`() {
        assertEquals(HomeTile.entries.size, HomeTile.ids.toSet().size)
        // The nine of prototype C, in its order.
        assertEquals(listOf("coach", "today", "agenda", "courses", "notes", "chats", "activity", "sessions", "projects"), HomeTile.ids)
        HomeTile.entries.forEach { assertEquals(it, HomeTile.fromId(it.id)) }
        assertNull(HomeTile.fromId("gone"))
    }

    @Test
    fun `the home style defaults to the tiles`() {
        assertEquals(HomeStyle.TILES, HomeStyle.Default)
        assertEquals(HomeStyle.TILES, HomeStyle.fromStored(null))
        assertEquals(HomeStyle.TILES, HomeStyle.fromStored("something"))
        assertEquals(HomeStyle.CLASSIC, HomeStyle.fromStored("classic"))
        HomeStyle.entries.forEach { assertEquals(it, HomeStyle.fromStored(it.storedValue)) }
    }
}
