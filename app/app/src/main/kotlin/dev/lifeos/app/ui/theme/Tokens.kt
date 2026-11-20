package dev.lifeos.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.lifeos.app.data.Tone

/**
 * The colour tokens of visual direction D3, made warmer (docs/design/mockups/README.md): the D3
 * base tokens below plus the seven soft tones ([ToneColors]). Names follow the mockups' CSS
 * custom properties.
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
    val warn: Color,
    val grad1: Color,
    val grad2: Color,
    val grad3: Color,
    val isDark: Boolean,
) {
    /** The featured/active gradient: violet through pink to orange, top-left to bottom-right. */
    val gradient: Brush get() = Brush.linearGradient(listOf(grad1, grad2, grad3))

    /** The colours of one tone in this appearance. */
    fun tone(tone: Tone): ToneColors = (if (isDark) TonesDark else TonesLight).getValue(tone)
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
    warn = Color(0xFFB45309),
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
    warn = Color(0xFFFFB454),
    grad1 = Color(0xFF8577FF),
    grad2 = Color(0xFFEC5FB2),
    grad3 = Color(0xFFFF9458),
    isDark = true,
)

/**
 * One soft tone of the warmer design: the card tint, the second tint (icon tiles, round marks,
 * card edge, labels) and the ink (icons, numbers, accents, course names). Contrast of every pair
 * is in docs/design/mockups/README.md ("Contrast").
 */
@Immutable
data class ToneColors(val card: Color, val second: Color, val ink: Color)

private fun tone(card: Long, second: Long, ink: Long) = ToneColors(Color(card), Color(second), Color(ink))

val TonesLight: Map<Tone, ToneColors> = mapOf(
    Tone.VIOLET to tone(0xFFF0EDFC, 0xFFE0DAFA, 0xFF5443C4),
    Tone.PEACH to tone(0xFFFCEFE5, 0xFFF8DCC6, 0xFF9A420C),
    Tone.MINT to tone(0xFFE8F5EE, 0xFFCDEADB, 0xFF176B45),
    Tone.SKY to tone(0xFFE8F1FB, 0xFFCFE2F7, 0xFF1B5CA3),
    Tone.ROSE to tone(0xFFFBECEF, 0xFFF6D4DB, 0xFFA8233C),
    Tone.SAND to tone(0xFFF9F2DC, 0xFFF0E1AE, 0xFF765700),
    Tone.TEAL to tone(0xFFE4F4F2, 0xFFC6E7E3, 0xFF0D6862),
)

val TonesDark: Map<Tone, ToneColors> = mapOf(
    Tone.VIOLET to tone(0xFF1E1C31, 0xFF2C2850, 0xFFB5AAFF),
    Tone.PEACH to tone(0xFF261E19, 0xFF3B2B20, 0xFFFFB17A),
    Tone.MINT to tone(0xFF16241E, 0xFF1E3A2D, 0xFF7CDBA8),
    Tone.SKY to tone(0xFF152030, 0xFF1D324A, 0xFF8EC5FF),
    Tone.ROSE to tone(0xFF28191E, 0xFF42232C, 0xFFFF9CAE),
    Tone.SAND to tone(0xFF24200F, 0xFF3A3218, 0xFFEFCD6A),
    Tone.TEAL to tone(0xFF132423, 0xFF1A3A38, 0xFF6ED6CD),
)

/** Opacities of the decorations: watermark icons (P2) and the ornaments' fills and lines (P3). */
object ToneAlpha {
    const val WATERMARK = 0.08f
    const val FILL = 0.14f
    const val LIGHT_LINE = 0.25f
    const val LINE = 0.4f
}

/** Shared metrics of direction D. */
object LifeDimens {
    val pad = 16.dp
    val ring = 3.dp
    val navHeight = 64.dp
    val minTouch = 48.dp
    val hairline = 1.dp
}

val LocalLifeColors = staticCompositionLocalOf { D3Light }
