package dev.lifeos.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import dev.lifeos.app.ui.theme.LifeDimens
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lifeos.app.data.Glyph
import dev.lifeos.app.data.Look
import dev.lifeos.app.data.Looks
import dev.lifeos.app.data.Pattern
import dev.lifeos.app.data.Tone
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import dev.lifeos.app.ui.theme.ToneAlpha
import dev.lifeos.app.ui.theme.ToneColors

/** The looks of the courses known right now (from the saved course list); provided once at the root. */
val LocalLooks = compositionLocalOf { Looks.EMPTY }

/** The tones of the app's sections: their watermark and heading icon (P2), the deadline card (P3). */
object SectionTones {
    val PICKS = Tone.VIOLET
    val TODAY = Tone.PEACH
    val TASKS = Tone.MINT
    val DEADLINES = Tone.ROSE
}

/** A large, faint icon in a card's corner. Purely decorative. */
class Watermark(val icon: ImageVector, val tone: Tone)

@Composable
fun tone(tone: Tone): ToneColors = LifeTheme.colors.tone(tone)

fun Glyph.icon(): ImageVector = when (this) {
    Glyph.WAVE -> LifeIcons.Wave
    Glyph.CHIP -> LifeIcons.Chip
    Glyph.MATRIX -> LifeIcons.Matrix
    Glyph.PEN -> LifeIcons.Pen
    Glyph.DICE -> LifeIcons.Dice
    Glyph.FLASK -> LifeIcons.Flask
    Glyph.FOLDER -> LifeIcons.Folder
}

/** A course or project name as a small label in its colour, with its icon. */
@Composable
fun CourseLabel(look: Look, name: String, modifier: Modifier = Modifier) {
    val colors = tone(look.tone)
    Row(
        modifier = modifier
            .heightIn(min = 20.dp)
            .background(colors.second, RoundedCornerShape(10.dp))
            .padding(start = 6.dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(look.glyph.icon(), contentDescription = null, tint = colors.ink, modifier = Modifier.size(13.dp))
        Text(
            text = name,
            style = LifeType.label,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * A course's round mark: its icon in its ink. On a grey card the mark is the second tint; on
 * the course's own tinted card ([onTint]) it is the background colour, so it stands out.
 */
@Composable
fun CourseMark(look: Look, modifier: Modifier = Modifier, size: Dp = 44.dp, onTint: Boolean = false, shape: Shape = CircleShape, icon: ImageVector? = null) {
    val colors = tone(look.tone)
    Box(
        modifier
            .size(size)
            .background(if (onTint) LifeTheme.colors.bg else colors.second, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon ?: look.glyph.icon(), contentDescription = null, tint = colors.ink, modifier = Modifier.size(size * 0.48f))
    }
}

/**
 * P3: tiles of different sizes: the first one wide, the others two by two, the inner corners
 * of each pair a little sharper. [cell] draws one item with the shape and size it gets
 * (Courses, and the folders of the Sessions screen, #168).
 */
@Composable
fun <T> TileGrid(items: List<T>, cell: @Composable (item: T, shape: Shape, modifier: Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.firstOrNull()?.let { cell(it, RoundedCornerShape(20.dp), Modifier.fillMaxWidth()) }
        items.drop(1).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                cell(pair[0], RoundedCornerShape(topStart = 20.dp, topEnd = 8.dp, bottomEnd = 20.dp, bottomStart = 20.dp), Modifier.weight(1f).fillMaxHeight())
                if (pair.size > 1) {
                    cell(pair[1], RoundedCornerShape(topStart = 8.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp), Modifier.weight(1f).fillMaxHeight())
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One P3 tile in [look]'s colour: its ornament and mark on top ([icon] instead of the look's
 * own glyph), then [title] (two lines at most) and one muted line. [badge] sits top right.
 */
@Composable
fun ToneTile(
    look: Look,
    title: String,
    subtitle: String,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    badge: (@Composable () -> Unit)? = null,
) {
    val colors = LifeTheme.colors
    val toned = colors.tone(look.tone)
    Column(
        modifier = modifier
            .clip(shape)
            .background(toned.card)
            .border(LifeDimens.hairline, toned.second, shape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Ornament(look.pattern, look.tone, Modifier.matchParentSize())
            CourseMark(look, Modifier.padding(start = 10.dp, top = 8.dp), size = 32.dp, onTint = true, icon = icon)
            if (badge != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp)) { badge() }
        }
        Text(
            title,
            style = LifeType.rowTitle,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
        )
        Text(
            text = subtitle,
            style = LifeType.small,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
        )
    }
}

/**
 * A small geometric ornament in a tone's colours, anchored at the right edge of its box:
 * the second tint for filled shapes, the ink at 14 % for light fills, the ink at 25–40 % for
 * thin lines. Purely decorative; no text sits on it.
 */
@Composable
fun Ornament(pattern: Pattern, tone: Tone, modifier: Modifier = Modifier) {
    val colors = tone(tone)
    Canvas(modifier.clipToBounds()) { drawOrnament(pattern, colors) }
}

private fun DrawScope.drawOrnament(pattern: Pattern, colors: ToneColors) {
    val w = size.width
    val h = size.height
    val unit = 1.dp.toPx()
    val fill = colors.second
    val light = colors.ink.copy(alpha = ToneAlpha.FILL)
    val line = Stroke(width = 1.5f * unit)
    val ink = colors.ink.copy(alpha = ToneAlpha.LINE)
    val faint = colors.ink.copy(alpha = ToneAlpha.LIGHT_LINE)
    fun u(value: Float) = value * unit
    when (pattern) {
        Pattern.CIRCLES -> {
            drawCircle(fill, radius = h * 0.62f, center = Offset(w - u(34f), h * 0.35f))
            drawCircle(light, radius = h * 0.38f, center = Offset(w - u(78f), h * 0.95f))
            drawCircle(ink, radius = h * 0.2f, center = Offset(w - u(18f), h * 0.9f), style = line)
            repeat(4) { drawCircle(light, radius = u(1.6f), center = Offset(w - u(128f) + it * u(9f), u(12f))) }
        }
        Pattern.ARCS -> {
            repeat(4) {
                val r = u(12f + it * 11f)
                drawArc(ink, 180f, 90f, false, topLeft = Offset(w - r, h - r), size = Size(2 * r, 2 * r), style = line)
            }
            drawCircle(fill, radius = u(7f), center = Offset(w - u(70f), h * 0.32f))
        }
        Pattern.DOTS -> {
            for (row in 0 until 4) {
                for (col in 0 until 7) {
                    drawCircle(if (col + row < 5) light else fill, radius = u(1.8f), center = Offset(w - u(12f) - col * u(11f), u(9f) + row * u(11f)))
                }
            }
            drawCircle(fill, radius = u(14f), center = Offset(w - u(26f), h - u(6f)))
        }
        Pattern.WAVES -> {
            repeat(3) { index ->
                val y = u(8f + index * 9f)
                val path = Path().apply {
                    moveTo(w - u(170f), y)
                    repeat(12) { step -> relativeQuadraticTo(u(7f), if (step % 2 == 0) -u(8f) else u(8f), u(14f), 0f) }
                }
                drawPath(path, if (index == 1) ink else faint, style = line)
            }
            drawCircle(fill, radius = u(12f), center = Offset(w - u(24f), u(14f)))
        }
        Pattern.GRID -> {
            for (row in 0 until 3) {
                for (col in 0 until 5) {
                    val topLeft = Offset(w - u(22f) - col * u(16f), u(6f) + row * u(16f))
                    if ((row + col) % 3 == 0) {
                        drawRoundRect(fill, topLeft, Size(u(10f), u(10f)), CornerRadius(u(2f)))
                    } else {
                        drawRoundRect(ink, topLeft, Size(u(10f), u(10f)), CornerRadius(u(2f)), style = line)
                    }
                }
            }
        }
        Pattern.TRIANGLES -> {
            repeat(5) { index ->
                val x = w - u(30f) - index * u(22f)
                val path = Path().apply {
                    moveTo(x, h - u(6f))
                    lineTo(x + u(10f), h - u(23f))
                    lineTo(x + u(20f), h - u(6f))
                    close()
                }
                if (index % 2 == 1) drawPath(path, ink, style = line) else drawPath(path, fill)
            }
            drawCircle(light, radius = u(5f), center = Offset(w - u(140f), u(14f)))
        }
    }
}
