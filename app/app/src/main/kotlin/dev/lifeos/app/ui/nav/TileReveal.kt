package dev.lifeos.app.ui.nav

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.lifeos.app.ui.theme.LifeTheme
import kotlin.math.max

/**
 * Where a page opened from the home grows out of and shrinks back into (#195, prototype C's
 * container transform): a tile's bounds, or a circle around a list row's mark, in root pixels.
 */
data class RevealOrigin(val left: Float, val top: Float, val right: Float, val bottom: Float, val circle: Boolean = false) {
    fun encode(): String = listOf(left, top, right, bottom, if (circle) 1f else 0f).joinToString(",")

    companion object {
        fun decode(text: String): RevealOrigin? {
            val parts = text.split(',').mapNotNull { it.toFloatOrNull() }
            return if (parts.size == 5 && parts.all { it.isFinite() }) RevealOrigin(parts[0], parts[1], parts[2], parts[3], parts[4] == 1f) else null
        }
    }
}

/** The last tile tapped on the home: the page it opens takes [origin] once ([tapped]). */
class RevealState {
    var origin: RevealOrigin? = null
        private set
    var tapped: Boolean = false

    fun tap(from: RevealOrigin?) {
        origin = from
        tapped = from != null
    }
}

/** Material 3 emphasized easing and the prototype's durations: open 420 ms, close 260 ms, content fade 160 ms. */
object RevealMotion {
    val Open = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Close = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val OPEN_MS = 420
    const val CLOSE_MS = 260
    const val FADE_MS = 160
    const val FADE_DELAY_MS = 110
}

/**
 * A screen a home tile opens. Tapped on the home, it grows out of the tile; going back to the
 * home, it shrinks into it again. Opened any other way it is an ordinary screen with the app's
 * usual transition. The system's "remove animations" makes both instant.
 */
fun NavGraphBuilder.tilePage(
    route: String,
    reveal: RevealState,
    homeOnTop: () -> Boolean,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = { if (reveal.tapped && initialState.destination.route == Routes.TODAY) EnterTransition.None else null },
    popExitTransition = { if (reveal.origin != null && targetState.destination.route == Routes.TODAY) ExitTransition.KeepUntilTransitionsFinished else null },
) { entry ->
    TilePage(reveal, homeOnTop()) { content(entry) }
}

@Composable
private fun AnimatedContentScope.TilePage(state: RevealState, homeOnTop: Boolean, content: @Composable () -> Unit) {
    // Non-null only in the composition that follows a tile tap; kept (saved) for the way back.
    val taken = remember { if (state.tapped) state.origin else null }
    LaunchedEffect(Unit) { state.tapped = false }
    val saved = rememberSaveable { taken?.encode().orEmpty() }
    val origin = remember(saved) { RevealOrigin.decode(saved) }
    if (origin == null) {
        content()
        return
    }
    val entering = taken != null
    val target: (EnterExitState) -> Float = { state ->
        when (state) {
            EnterExitState.Visible -> 1f
            EnterExitState.PreEnter -> if (entering) 0f else 1f
            EnterExitState.PostExit -> if (homeOnTop) 0f else 1f
        }
    }
    val progress = transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) tween(RevealMotion.OPEN_MS, easing = RevealMotion.Open) else tween(RevealMotion.CLOSE_MS, easing = RevealMotion.Close)
        },
        label = "tile page",
        targetValueByState = { target(it) },
    )
    val alpha = transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) tween(RevealMotion.FADE_MS, RevealMotion.FADE_DELAY_MS, LinearEasing) else tween(RevealMotion.FADE_MS, easing = LinearEasing)
        },
        label = "tile page content",
        targetValueByState = { target(it) },
    )
    RevealBox(origin, { progress.value }, { alpha.value }, content)
}

/**
 * Draws [content] through a window that grows from [origin] to the whole screen as [progress]
 * goes from 0 to 1: a rounded rectangle from a tile (on the card colour first, as the tile's
 * page), a circle from a list row. The content itself fades in with [contentAlpha].
 */
@Composable
fun RevealBox(origin: RevealOrigin, progress: () -> Float, contentAlpha: () -> Float, content: @Composable () -> Unit) {
    val colors = LifeTheme.colors
    var offset by remember { mutableStateOf(Offset.Zero) }
    val path = remember { Path() }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { offset = it.positionInRoot() }
            // Nothing under the page is touched while it is on screen.
            .pointerInput(Unit) { }
            .drawWithContent {
                val p = progress().coerceIn(0f, 1f)
                if (p >= 1f) {
                    drawRect(colors.bg)
                    drawContent()
                    return@drawWithContent
                }
                fun between(from: Float, to: Float) = from + (to - from) * p
                path.rewind()
                if (origin.circle) {
                    val centre = Offset((origin.left + origin.right) / 2 - offset.x, (origin.top + origin.bottom) / 2 - offset.y)
                    val radius = between((origin.right - origin.left) / 2, 1.5f * max(size.width, size.height))
                    path.addOval(Rect(centre, radius))
                } else {
                    path.addRoundRect(
                        RoundRect(
                            left = between(origin.left - offset.x, 0f),
                            top = between(origin.top - offset.y, 0f),
                            right = between(origin.right - offset.x, size.width),
                            bottom = between(origin.bottom - offset.y, size.height),
                            cornerRadius = CornerRadius(between(18.dp.toPx(), 0f)),
                        ),
                    )
                }
                clipPath(path) {
                    drawRect(if (origin.circle) colors.bg else lerp(colors.surface, colors.bg, p))
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha().coerceIn(0f, 1f) }) { content() }
    }
}
