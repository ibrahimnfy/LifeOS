package dev.lifeos.app.ui

import dev.lifeos.app.ui.components.SwipeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When a swiped row acts (#207): only beyond the threshold, never by speed alone. */
class SwipeRulesTest {
    private val width = 1000f
    private val min = 250f

    @Test
    fun `a row acts only when it is let go beyond a good third of its width`() {
        val threshold = SwipeRules.threshold(width, min)
        assertEquals(350f, threshold, 0.01f)
        assertNull(SwipeRules.released(349f, threshold))
        assertEquals(SwipeRules.Side.RIGHT, SwipeRules.released(350f, threshold))
        assertEquals(SwipeRules.Side.LEFT, SwipeRules.released(-600f, threshold))
        // Dragged back before letting go: nothing happens.
        assertNull(SwipeRules.released(-120f, threshold))
        assertNull(SwipeRules.released(0f, threshold))
    }

    @Test
    fun `a narrow row still needs a real distance, and one it can reach`() {
        // The minimum counts where a third of the width would be too little ...
        assertEquals(250f, SwipeRules.threshold(600f, min), 0.01f)
        // ... but never more than the row can travel.
        val narrow = SwipeRules.threshold(300f, min)
        assertEquals(162f, narrow, 0.01f)
        assertEquals(SwipeRules.Side.RIGHT, SwipeRules.released(SwipeRules.clamp(5000f, 300f, right = true, left = true), narrow))
        // A row that has not been measured yet never acts.
        assertNull(SwipeRules.released(10f, SwipeRules.threshold(0f, min)))
    }

    @Test
    fun `a row moves only towards a side that has an action, and not past its travel`() {
        assertEquals(0f, SwipeRules.clamp(200f, width, right = false, left = true), 0.01f)
        assertEquals(-200f, SwipeRules.clamp(-200f, width, right = false, left = true), 0.01f)
        assertEquals(0f, SwipeRules.clamp(-200f, width, right = true, left = false), 0.01f)
        assertEquals(600f, SwipeRules.clamp(900f, width, right = true, left = true), 0.01f)
        assertEquals(-600f, SwipeRules.clamp(-900f, width, right = true, left = true), 0.01f)
    }
}
