package dev.lifeos.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The colour tokens of visual direction D3 (docs/design/mockups/README.md).
 * Names follow the mockups' CSS custom properties.
 */
@Immutable
data class LifeColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val divider: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val onPrimary: Color,
    val link: Color,
    val badge: Color,
    val onBadge: Color,
    val ok: Color,
    val grad1: Color,
    val grad2: Color,
    val grad3: Color,
    val isDark: Boolean,
) {
    /** The featured/active gradient: violet through pink to orange, top-left to bottom-right. */
    val gradient: Brush get() = Brush.linearGradient(listOf(grad1, grad2, grad3))
}

val D3Light = LifeColors(
    bg = Color(0xFFF8F8FA),
    surface = Color(0xFFEEEEF2),
    surface2 = Color(0xFFE3E3E9),
    divider = Color(0xFFD9D9E1),
    text = Color(0xFF17171C),
    muted = Color(0xFF595966),
    primary = Color(0xFF1E60E6),
    onPrimary = Color(0xFFFFFFFF),
    link = Color(0xFF1852C8),
    badge = Color(0xFFD62438),
    onBadge = Color(0xFFFFFFFF),
    ok = Color(0xFF177A41),
    grad1 = Color(0xFF6A5CF2),
    grad2 = Color(0xFFD845A4),
    grad3 = Color(0xFFE8651E),
    isDark = false,
)

val D3Dark = LifeColors(
    bg = Color(0xFF101218),
    surface = Color(0xFF1A1D25),
    surface2 = Color(0xFF252933),
    divider = Color(0xFF303541),
    text = Color(0xFFECEDF2),
    muted = Color(0xFFA1A5B1),
    primary = Color(0xFF2165E8),
    onPrimary = Color(0xFFFFFFFF),
    link = Color(0xFF7DAFFF),
    badge = Color(0xFFDC2A3E),
    onBadge = Color(0xFFFFFFFF),
    ok = Color(0xFF45D688),
    grad1 = Color(0xFF8577FF),
    grad2 = Color(0xFFEC5FB2),
    grad3 = Color(0xFFFF9458),
    isDark = true,
)

/** Shared metrics of direction D. */
object LifeDimens {
    val pad = 16.dp
    val ring = 3.dp
    val navHeight = 64.dp
    val minTouch = 48.dp
    val hairline = 1.dp
}

val LocalLifeColors = staticCompositionLocalOf { D3Light }
