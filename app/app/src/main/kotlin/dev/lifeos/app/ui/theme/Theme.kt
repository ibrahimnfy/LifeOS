package dev.lifeos.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Text styles of direction D: the system sans-serif, sizes in sp so they scale. */
object LifeType {
    val screenTitle = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    val sectionTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em)
    val featured = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = (-0.01).em)
    val rowTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
    val button = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

    /** The large count of a deadline ticket (days left). */
    val bigNumber = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 28.sp, letterSpacing = (-0.02).em)
}

/**
 * Every Material 3 colour role is filled from the D3 tokens, so no component
 * falls back to the library's default palette. No dynamic (wallpaper) colour.
 */
private fun LifeColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = surface2,
        onPrimaryContainer = text,
        inversePrimary = if (isDark) D3Light.link else D3Dark.link,
        secondary = text,
        onSecondary = bg,
        secondaryContainer = surface2,
        onSecondaryContainer = text,
        tertiary = link,
        onTertiary = bg,
        tertiaryContainer = surface2,
        onTertiaryContainer = text,
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceVariant = surface,
        onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        inverseSurface = text,
        inverseOnSurface = bg,
        error = badge,
        onError = onBadge,
        errorContainer = surface2,
        onErrorContainer = text,
        outline = muted,
        outlineVariant = divider,
        scrim = Color.Black,
        surfaceBright = bg,
        surfaceDim = surface2,
        surfaceContainerLowest = bg,
        surfaceContainerLow = bg,
        surfaceContainer = surface,
        surfaceContainerHigh = surface2,
        surfaceContainerHighest = surface2,
    )
}

@Composable
fun LifeTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) D3Dark else D3Light
    CompositionLocalProvider(LocalLifeColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = Typography(),
            content = content,
        )
    }
}

object LifeTheme {
    val colors: LifeColors
        @Composable @ReadOnlyComposable get() = LocalLifeColors.current
}
