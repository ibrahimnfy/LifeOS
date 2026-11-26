package dev.lifeos.app.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The home field while the screen is open (#192): the tiles as the screen draws them, what the
 * finger does to them, and the frame loop's on/off switch. The rules themselves are
 * [TilePhysics]; this class only decides when they run and when the layout is saved.
 *
 * The frame loop runs only while [running] is true: it is switched on by a fling or a slide and
 * switches itself off in [frame] as soon as nothing moves; [hide] ends it at once when the
 * screen is hidden. The layout is saved ([save]) when the tiles come to rest, never per frame.
 *
 * Everything here is called on the main thread (pointer input and the frame callback). One
 * frame is a few dozen float operations for nine tiles, and the screen reads [field] only while
 * placing and drawing, so a frame recomposes nothing.
 */
class HomeField(private val ids: List<String>, private val save: (String) -> Unit) {
    /** Null until the saved layout was read and the field measured: nothing is drawn before. */
    var field by mutableStateOf<Field?>(null)
        private set

    /** Whether the screen must ask for frames. */
    var running by mutableStateOf(false)
        private set

    /** The tile under the finger. */
    var held by mutableStateOf<String?>(null)
        private set

    /** A count per tile that goes up with every bounce off an edge (the screen flashes the tile). */
    val bounces = mutableStateMapOf<String, Int>()

    /** The system's "remove animations": no flying and no slides, tiles just move. */
    var reducedMotion = false

    private var loaded = false
    private var saved: List<SavedTile>? = null
    private var width = 0f
    private var height = 0f
    private var minSize = TilePhysics.MIN_SIZE

    /** The layout as the owner left it; a temporary smaller field (split screen) is fitted from it. */
    private var base: Field? = null
    private var lastSaved: String? = null
    private var lastNanos = 0L
    private var slide: Slide? = null

    private class Slide(val from: Field, val to: Field, var startNanos: Long = -1)

    // ---------------------------------------------------------------- start and size

    /** The stored layout was read (null: none yet). */
    fun loaded(text: String?) {
        if (loaded) return
        loaded = true
        saved = HomeLayout.decode(text)
        lastSaved = text
        place()
    }

    /** The field's size in dp, and the smallest tile for the current font size. */
    fun measured(width: Float, height: Float, minSize: Float = TilePhysics.MIN_SIZE) {
        if (width <= 0f || height <= 0f) return
        if (width == this.width && height == this.height && minSize == this.minSize) return
        this.width = width
        this.height = height
        this.minSize = minSize
        place()
    }

    private fun empty() = Field(width, height, emptyList(), minSize = minSize)

    private fun default(): Field = TilePhysics.scatter(empty(), ids)

    private fun place() {
        if (!loaded || width <= 0f || height <= 0f) return
        val current = field
        if (current == null) {
            field = HomeLayout.restore(saved, default())
            base = field
            return
        }
        // While tiles fly the live field is fitted; at rest the layout the owner left.
        val source = if (running || held != null) current else base ?: current
        slide = null
        field = TilePhysics.fit(source, width, height, minSize)
        if (running || held != null) base = field
    }

    // ---------------------------------------------------------------- the finger

    /** What a touch met: the tile, and whether it was flying (then the touch catches it and is no tap). */
    data class Press(val id: String, val caught: Boolean)

    /** A finger went down at a point of the field (dp). The tile under it stops and comes to the front. */
    fun press(x: Float, y: Float): Press? {
        val current = field ?: return null
        val id = TilePhysics.hit(current, x, y) ?: return null
        val tile = current.tile(id) ?: return null
        val caught = tile.moving || slide != null
        slide = null
        field = TilePhysics.toFront(TilePhysics.moveTo(current, id, tile.x, tile.y), id)
        held = id
        return Press(id, caught)
    }

    fun drag(id: String, x: Float, y: Float) {
        field = TilePhysics.moveTo(field ?: return, id, x, y)
    }

    fun pinch(id: String, size: Float, x: Float, y: Float) {
        field = TilePhysics.resize(field ?: return, id, size, x, y)
    }

    /** The finger left: with a speed (dp per ms) the tile flies on, unless motion is off. */
    fun release(id: String, vx: Float = 0f, vy: Float = 0f) {
        if (held == id) held = null
        val current = field ?: return
        if (!reducedMotion && (vx != 0f || vy != 0f)) {
            field = TilePhysics.fling(current, id, vx, vy)
            start()
        } else if (!running) {
            persist()
        }
    }

    /** "Larger" and "smaller" for a screen reader or a switch: the pinch without two fingers. */
    fun scale(id: String, factor: Float) {
        val current = field ?: return
        val tile = current.tile(id) ?: return
        slide = null
        field = TilePhysics.toFront(TilePhysics.resize(current, id, tile.size * factor, tile.x, tile.y), id)
        if (!running) persist()
    }

    // ---------------------------------------------------------------- layouts

    /** The calm grid. Returns the layout before it, for Undo. */
    fun tidy(): Field? = moveTo { TilePhysics.tidy(it, ids) }

    /** The default layout again. Returns the layout before it, for Undo. */
    fun reset(): Field? = moveTo { default() }

    /** Back to a layout [tidy] or [reset] returned. */
    fun restore(layout: Field) {
        moveTo { TilePhysics.fit(layout, width, height, minSize) }
    }

    private fun moveTo(target: (Field) -> Field): Field? {
        val current = field ?: return null
        val from = TilePhysics.stop(slide?.to ?: current)
        val now = TilePhysics.stop(current)
        val to = target(now)
        held = null
        if (reducedMotion) {
            slide = null
            field = to
            running = false
            persist()
        } else {
            field = now
            slide = Slide(now, to)
            start()
        }
        return from
    }

    // ---------------------------------------------------------------- frames

    private fun start() {
        if (!running) lastNanos = 0L
        running = true
    }

    /**
     * One frame at [nanos] (the frame clock). Returns whether another frame is needed; false
     * also switches [running] off and saves the layout.
     */
    fun frame(nanos: Long): Boolean {
        val current = field
        if (current == null || !running) return finish()
        val dt = if (lastNanos == 0L) 16f else (nanos - lastNanos) / 1_000_000f
        lastNanos = nanos
        val sliding = slide
        if (sliding != null) {
            if (sliding.startNanos < 0) sliding.startNanos = nanos
            val progress = (nanos - sliding.startNanos) / 1_000_000f / TilePhysics.TIDY_MS
            field = TilePhysics.blend(sliding.from, sliding.to, progress)
            if (progress < 1f) return true
            slide = null
            return finish()
        }
        val step = TilePhysics.step(current, dt, held)
        field = step.field
        step.hits.forEach { bounces[it] = (bounces[it] ?: 0) + 1 }
        return if (step.moving) true else finish()
    }

    private fun finish(): Boolean {
        running = false
        lastNanos = 0L
        if (held == null) persist()
        return false
    }

    /** The screen is hidden (another screen, the app in the background): everything stops and is saved. */
    fun hide() {
        val current = field ?: return
        field = TilePhysics.stop(slide?.to ?: current)
        slide = null
        held = null
        running = false
        lastNanos = 0L
        bounces.clear()
        persist()
    }

    private fun persist() {
        val current = field ?: return
        base = current
        val text = HomeLayout.encode(current)
        if (text == lastSaved) return
        lastSaved = text
        save(text)
    }
}
