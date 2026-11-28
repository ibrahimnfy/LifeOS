package dev.lifeos.app.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What one direction of a [SwipeRow] does: its words, its icon, and whether it removes something. */
class SwipeAction(val label: String, val icon: ImageVector, val danger: Boolean = false, val onAction: () -> Unit)

/**
 * When a swipe counts (#207). A row acts only when the finger lets go beyond the threshold:
 * a good third of the row's width, and never less than [MIN_DP]. How fast the finger moved
 * does not matter, so a quick flick while scrolling the list deletes nothing; dragging back
 * below the threshold before letting go cancels.
 */
object SwipeRules {
    const val FRACTION = 0.35f
    const val MIN_DP = 96f

    /** How far a row travels at most, as a part of its width. */
    const val TRAVEL = 0.6f

    enum class Side { RIGHT, LEFT }

    fun threshold(widthPx: Float, minPx: Float): Float = min(max(minPx, widthPx * FRACTION), widthPx * TRAVEL * 0.9f)

    /** The row's offset while it is dragged: only towards a side that has an action, and not past [TRAVEL]. */
    fun clamp(offset: Float, widthPx: Float, right: Boolean, left: Boolean): Float =
        offset.coerceIn(if (left) -widthPx * TRAVEL else 0f, if (right) widthPx * TRAVEL else 0f)

    /** The side whose action runs when the finger lets go at [offset]; null: the row slides back. */
    fun released(offset: Float, threshold: Float): Side? = when {
        threshold <= 0f -> null
        offset >= threshold -> Side.RIGHT
        offset <= -threshold -> Side.LEFT
        else -> null
    }
}

/**
 * A list row that can be swiped (#207): to the right for [right] (complete, or edit), to the
 * left for [left] (delete). The side that opens shows what will happen (icon and words) and
 * fills with colour once letting go would do it. The row always slides back: what it did is
 * the caller's to show (the row leaves the list, an editor opens), with Undo where it applies.
 *
 * [content] gets a modifier that tells screen readers the same actions; put it on the row's
 * own element (the one that is clicked), so TalkBack offers them in its actions menu.
 */
@Composable
fun SwipeRow(
    modifier: Modifier = Modifier,
    right: SwipeAction? = null,
    left: SwipeAction? = null,
    content: @Composable (Modifier) -> Unit,
) {
    val actions = Modifier.semantics {
        customActions = listOfNotNull(right, left).map { action ->
            CustomAccessibilityAction(action.label) {
                action.onAction()
                true
            }
        }
    }
    if (right == null && left == null) {
        Box(modifier) { content(Modifier) }
        return
    }
    val colors = LifeTheme.colors
    val haptics = LocalHapticFeedback.current
    val minPx = with(LocalDensity.current) { SwipeRules.MIN_DP.dp.toPx() }
    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    val onRight by rememberUpdatedState(right)
    val onLeft by rememberUpdatedState(left)
    val threshold = SwipeRules.threshold(width.toFloat(), minPx)
    val reached = SwipeRules.released(offset, threshold)
    // One tick when the threshold is crossed, in either direction: the finger feels the point of no return.
    LaunchedEffect(reached != null) { if (offset != 0f) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val drag = rememberDraggableState { delta ->
        offset = SwipeRules.clamp(offset + delta, width.toFloat(), onRight != null, onLeft != null)
    }
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .clipToBounds(),
    ) {
        val shown = if (offset > 0f) right else if (offset < 0f) left else null
        if (shown != null) {
            val fill = when {
                reached == null -> colors.surface2
                shown.danger -> colors.badge
                else -> colors.primary
            }
            val ink = when {
                reached == null -> colors.muted
                shown.danger -> colors.onBadge
                else -> colors.onPrimary
            }
            // Only the strip the row has left is painted, so a row needs no background of its own.
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithContent {
                        val strip = abs(offset)
                        val start = if (offset > 0f) 0f else size.width - strip
                        clipRect(start, 0f, start + strip, size.height) {
                            drawRect(fill)
                            this@drawWithContent.drawContent()
                        }
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, if (offset > 0f) Alignment.Start else Alignment.End),
            ) {
                Icon(shown.icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                Text(shown.label, style = LifeType.label, color = ink, maxLines = 1)
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    state = drag,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        when (SwipeRules.released(offset, threshold)) {
                            SwipeRules.Side.RIGHT -> onRight?.onAction?.invoke()
                            SwipeRules.Side.LEFT -> onLeft?.onAction?.invoke()
                            null -> Unit
                        }
                        // A row that stays (an editor opened, or the action was refused) slides back.
                        animate(offset, 0f) { value, _ -> offset = value }
                    },
                ),
        ) {
            content(actions)
        }
    }
}
