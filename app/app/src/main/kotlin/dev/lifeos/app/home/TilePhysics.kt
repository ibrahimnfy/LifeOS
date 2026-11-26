package dev.lifeos.app.home

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One tile of the home field (#192). [x] and [y] are the tile's centre, [size] its width; the
 * height follows from the width ([TilePhysics.HEIGHT_RATIO]). Lengths are in dp, speeds in dp
 * per millisecond, so the feel is the same on every screen density. [z] is the stacking order:
 * the highest one is on top.
 */
data class Tile(
    val id: String,
    val x: Float,
    val y: Float,
    val size: Float,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val z: Int = 0,
) {
    val height: Float get() = size * TilePhysics.HEIGHT_RATIO
    val moving: Boolean get() = vx != 0f || vy != 0f
}

/**
 * The free field and its tiles. [minSize] and [maxSize] are the wanted limits of a tile's
 * width; a field smaller than that narrows them (see [TilePhysics.sizeIn]).
 */
data class Field(
    val width: Float,
    val height: Float,
    val tiles: List<Tile>,
    val minSize: Float = TilePhysics.MIN_SIZE,
    val maxSize: Float = TilePhysics.MAX_SIZE,
) {
    fun tile(id: String): Tile? = tiles.firstOrNull { it.id == id }
    val atRest: Boolean get() = tiles.none { it.moving }
}

/** One frame: the field after it, whether anything still moves, and the tiles that hit an edge. */
data class Step(val field: Field, val moving: Boolean, val hits: Set<String>)

/**
 * The physics of the home tiles as plain functions: no Android or Compose types, so every rule
 * is tested on the JVM (TilePhysicsTest). The numbers are those of prototype C (CSS pixels there
 * are dp here).
 */
object TilePhysics {
    /** Time constant of the friction, in ms: the speed falls to 37 % in this time. */
    const val FRICTION_MS = 1100f

    /** The part of the speed a tile keeps when it bounces off an edge. */
    const val RESTITUTION = 0.88f

    /** The highest speed of a fling along one axis, in dp per ms. */
    const val MAX_SPEED = 2.6f

    /** Below this speed (|vx| + |vy|, dp per ms) a tile stops. */
    const val REST_SPEED = 0.006f

    /** A frame never counts for more than this many ms (a stalled frame must not teleport tiles). */
    const val MAX_FRAME_MS = 32f

    const val MIN_SIZE = 76f
    const val MAX_SIZE = 210f
    const val HEIGHT_RATIO = 0.78f

    /** Free space kept above the tiles. */
    const val TOP_PAD = 8f

    /** Two tiles collide only when together they are faster than this (dp per ms). */
    private const val COLLIDE_SPEED = 0.15f
    private const val COLLIDE_RESTITUTION = 0.8f

    /** Tidy: the margin at the sides, the gap between tiles, the duration of the slide. */
    const val TIDY_MARGIN = 16f
    const val TIDY_GAP = 10f
    const val TIDY_MS = 520f
    private const val TIDY_COLUMNS = 3

    /** The default layout: centre as fractions of the field, and the size as a factor of the tidy cell. */
    private val SCATTER = listOf(
        floatArrayOf(0.50f, 0.17f, 1.50f),
        floatArrayOf(0.30f, 0.43f, 1.25f),
        floatArrayOf(0.76f, 0.40f, 1.00f),
        floatArrayOf(0.68f, 0.64f, 1.15f),
        floatArrayOf(0.26f, 0.69f, 1.00f),
        floatArrayOf(0.50f, 0.88f, 0.82f),
        floatArrayOf(0.18f, 0.90f, 0.85f),
        floatArrayOf(0.84f, 0.88f, 0.80f),
        floatArrayOf(0.85f, 0.15f, 0.78f),
    )

    // ---------------------------------------------------------------- limits

    /** The largest width a tile may have in this field: never wider or taller than the field. */
    fun maxSizeIn(field: Field): Float {
        val byHeight = (field.height - TOP_PAD) / HEIGHT_RATIO
        return max(1f, min(field.maxSize, min(field.width, byHeight)))
    }

    /** [size] between the field's limits. In a field smaller than the minimum the maximum wins. */
    fun sizeIn(field: Field, size: Float): Float {
        val hi = maxSizeIn(field)
        val lo = min(field.minSize, hi)
        val wanted = if (size.isFinite()) size else lo
        return min(hi, max(lo, wanted))
    }

    /** A coordinate kept between [lo] and [hi]; when there is no room at all, the middle. */
    private fun within(value: Float, lo: Float, hi: Float): Float = when {
        hi < lo -> (lo + hi) / 2
        !value.isFinite() -> (lo + hi) / 2
        else -> min(hi, max(lo, value))
    }

    /** The tile with a legal size, wholly inside the field. */
    fun clamp(field: Field, tile: Tile): Tile {
        val size = sizeIn(field, tile.size)
        val h = size * HEIGHT_RATIO
        return tile.copy(
            size = size,
            x = within(tile.x, size / 2, field.width - size / 2),
            y = within(tile.y, h / 2 + TOP_PAD, field.height - h / 2),
        )
    }

    private fun Field.change(id: String, change: (Tile) -> Tile): Field =
        copy(tiles = tiles.map { if (it.id == id) clamp(this, change(it)) else it })

    // ---------------------------------------------------------------- one frame

    /**
     * Moves every flying tile by [dtMillis]: inertia, friction, a bounce off each edge, soft
     * knocks between moving tiles. The tile under the finger ([held]) is left alone. A tile
     * slower than [REST_SPEED] stops; [Step.moving] is false once all have stopped, and the
     * caller's frame loop ends there.
     */
    fun step(field: Field, dtMillis: Float, held: String? = null): Step {
        val dt = if (dtMillis.isFinite()) min(MAX_FRAME_MS, max(0f, dtMillis)) else 0f
        val decay = exp(-dt / FRICTION_MS)
        val hits = mutableSetOf<String>()
        val moved = field.tiles.map { tile ->
            if (tile.id == held || !tile.moving) return@map tile
            val w2 = tile.size / 2
            val h2 = tile.height / 2
            var x = tile.x + tile.vx * dt
            var y = tile.y + tile.vy * dt
            var vx = tile.vx
            var vy = tile.vy
            val left = w2
            val right = field.width - w2
            val top = h2 + TOP_PAD
            val bottom = field.height - h2
            if (right < left) {
                x = (left + right) / 2
                vx = 0f
            } else if (x < left) {
                x = left
                vx = abs(vx) * RESTITUTION
                hits += tile.id
            } else if (x > right) {
                x = right
                vx = -abs(vx) * RESTITUTION
                hits += tile.id
            }
            if (bottom < top) {
                y = (top + bottom) / 2
                vy = 0f
            } else if (y < top) {
                y = top
                vy = abs(vy) * RESTITUTION
                hits += tile.id
            } else if (y > bottom) {
                y = bottom
                vy = -abs(vy) * RESTITUTION
                hits += tile.id
            }
            vx *= decay
            vy *= decay
            if (abs(vx) + abs(vy) < REST_SPEED) {
                vx = 0f
                vy = 0f
            }
            tile.copy(x = x, y = y, vx = vx, vy = vy)
        }
        val tiles = collide(moved, held)
        return Step(field.copy(tiles = tiles), moving = tiles.any { it.id != held && it.moving }, hits = hits)
    }

    /**
     * Soft knocks between moving tiles (as circles, elastic). Nothing pushes tiles apart: tiles
     * that come to rest on top of each other stay overlapped.
     */
    private fun collide(tiles: List<Tile>, held: String?): List<Tile> {
        val out = tiles.toMutableList()
        for (i in out.indices) {
            for (j in i + 1 until out.size) {
                val a = out[i]
                val b = out[j]
                if (a.id == held || b.id == held) continue
                if (abs(a.vx) + abs(a.vy) + abs(b.vx) + abs(b.vy) < COLLIDE_SPEED) continue
                val dx = b.x - a.x
                val dy = b.y - a.y
                val distance = hypot(dx, dy)
                val reach = 0.4f * (a.height + b.height)
                if (distance >= reach || distance < 0.001f) continue
                val nx = dx / distance
                val ny = dy / distance
                val closing = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
                if (closing >= 0f) continue // already moving apart
                val massA = a.size * a.size
                val massB = b.size * b.size
                val impulse = -(1 + COLLIDE_RESTITUTION) * closing / (1 / massA + 1 / massB)
                out[i] = a.copy(vx = a.vx - impulse * nx / massA, vy = a.vy - impulse * ny / massA)
                out[j] = b.copy(vx = b.vx + impulse * nx / massB, vy = b.vy + impulse * ny / massB)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- the finger

    /** The topmost tile under a point, or null on the empty field. */
    fun hit(field: Field, x: Float, y: Float): String? = field.tiles
        .filter { abs(x - it.x) <= it.size / 2 && abs(y - it.y) <= it.height / 2 }
        .maxByOrNull { it.z }
        ?.id

    /** The touched tile comes to the front; the others keep their order. Ranks stay 0, 1, 2, … */
    fun toFront(field: Field, id: String): Field {
        if (field.tile(id) == null) return field
        val order = field.tiles.sortedBy { it.z }.map { it.id }.filter { it != id } + id
        return field.copy(tiles = field.tiles.map { it.copy(z = order.indexOf(it.id)) })
    }

    /** The tile follows the finger: its centre at ([x], [y]) as far as the edges allow, at rest. */
    fun moveTo(field: Field, id: String, x: Float, y: Float): Field =
        field.change(id) { it.copy(x = x, y = y, vx = 0f, vy = 0f) }

    /** A pinch (or the "larger"/"smaller" actions): a new width between the limits, the centre at ([x], [y]). */
    fun resize(field: Field, id: String, size: Float, x: Float, y: Float): Field =
        field.change(id) { it.copy(size = size, x = x, y = y, vx = 0f, vy = 0f) }

    /** Let go: the tile flies on with the finger's speed (dp per ms), each axis capped at [MAX_SPEED]. */
    fun fling(field: Field, id: String, vx: Float, vy: Float): Field = field.change(id) {
        it.copy(vx = within(vx, -MAX_SPEED, MAX_SPEED), vy = within(vy, -MAX_SPEED, MAX_SPEED))
    }

    /** Everything stops where it is (the screen was hidden, or motion is switched off). */
    fun stop(field: Field): Field = field.copy(tiles = field.tiles.map { clamp(field, it.copy(vx = 0f, vy = 0f)) })

    // ---------------------------------------------------------------- the field changes

    /**
     * The field got another size (rotation, split screen, a card above it): every tile keeps
     * its place as a fraction of the field and is brought inside; sizes follow the new limits.
     */
    fun fit(field: Field, width: Float, height: Float, minSize: Float = field.minSize, maxSize: Float = field.maxSize): Field {
        val kx = if (field.width > 0f && width > 0f) width / field.width else 1f
        val ky = if (field.height > 0f && height > 0f) height / field.height else 1f
        val next = field.copy(width = max(0f, width), height = max(0f, height), minSize = minSize, maxSize = maxSize)
        return next.copy(tiles = field.tiles.map { clamp(next, it.copy(x = it.x * kx, y = it.y * ky)) })
    }

    // ---------------------------------------------------------------- layouts

    /**
     * The calm grid of "Tidy": equal tiles in the order of [ids], three columns or more (more
     * when that makes the tiles larger, as in landscape), centred. The result is at rest and
     * keeps each tile's stacking order.
     */
    fun tidy(field: Field, ids: List<String> = field.tiles.map { it.id }): Field {
        val count = ids.size
        if (count == 0) return field
        val hi = maxSizeIn(field)
        var columns = min(TIDY_COLUMNS, count)
        var cell = 0f
        for (candidate in min(TIDY_COLUMNS, count)..count) {
            val rows = ceil(count / candidate.toFloat()).toInt()
            val byWidth = (field.width - 2 * TIDY_MARGIN - (candidate - 1) * TIDY_GAP) / candidate
            val byHeight = (field.height - TOP_PAD - TIDY_MARGIN - (rows - 1) * TIDY_GAP) / rows / HEIGHT_RATIO
            val size = min(hi, min(byWidth, byHeight))
            if (size > cell + 0.5f) {
                cell = size
                columns = candidate
            }
        }
        cell = max(1f, cell)
        val rows = ceil(count / columns.toFloat()).toInt()
        val h = cell * HEIGHT_RATIO
        val left = (field.width - (columns * cell + (columns - 1) * TIDY_GAP)) / 2
        val top = max(TOP_PAD, (field.height - (rows * h + (rows - 1) * TIDY_GAP)) / 2)
        // The grid's own size is used as it is (it may be below the pinch minimum on a small field).
        val grid = field.copy(minSize = min(field.minSize, cell))
        val known = field.tiles.associateBy { it.id }
        return grid.copy(
            tiles = ids.mapIndexed { index, id ->
                val column = index % columns
                val row = index / columns
                clamp(
                    grid,
                    Tile(
                        id = id,
                        x = left + column * (cell + TIDY_GAP) + cell / 2,
                        y = top + row * (h + TIDY_GAP) + h / 2,
                        size = cell,
                        z = known[id]?.z ?: index,
                    ),
                )
            },
        ).copy(minSize = field.minSize)
    }

    /** The default layout: tiles of different sizes spread over the field, the first ones largest. */
    fun scatter(field: Field, ids: List<String>): Field {
        val cell = (field.width - 2 * TIDY_MARGIN - 2 * TIDY_GAP) / TIDY_COLUMNS
        return field.copy(
            tiles = ids.mapIndexed { index, id ->
                val spot = SCATTER[index % SCATTER.size]
                clamp(
                    field,
                    Tile(
                        id = id,
                        x = spot[0] * field.width,
                        y = TOP_PAD + 20f + spot[1] * (field.height - 40f),
                        size = cell * spot[2],
                        z = ids.size - 1 - index,
                    ),
                )
            },
        )
    }

    /**
     * The slide between two layouts: [progress] 0 is [from], 1 is [to], eased out (fast first,
     * then settling). Tiles keep the stacking order of [to] and do not fly.
     */
    fun blend(from: Field, to: Field, progress: Float): Field {
        val k = if (progress.isFinite()) min(1f, max(0f, progress)) else 1f
        val eased = 1 - (1 - k) * (1 - k) * (1 - k)
        val start = from.tiles.associateBy { it.id }
        return to.copy(
            tiles = to.tiles.map { target ->
                val a = start[target.id] ?: return@map target
                target.copy(
                    x = a.x + (target.x - a.x) * eased,
                    y = a.y + (target.y - a.y) * eased,
                    size = a.size + (target.size - a.size) * eased,
                    vx = 0f,
                    vy = 0f,
                )
            },
        )
    }
}
