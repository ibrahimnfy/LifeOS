package dev.lifeos.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.lifeos.app.data.Tone
import dev.lifeos.app.ui.screens.HomeColors
import dev.lifeos.app.ui.theme.D3Dark
import dev.lifeos.app.ui.theme.D3Light
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contrast of everything written on the home field (#192), light and dark: text on a tile
 * (the tile is opaque, so nothing shows through), and the captions on the field itself. WCAG AA
 * for normal text is 4.5 : 1; graphics (icons, borders that carry meaning) need 3 : 1.
 */
class HomeColorsTest {
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    @Test
    fun `text on a tile is readable in every tone, light and dark`() {
        for (colors in listOf(D3Light, D3Dark)) {
            val tile = HomeColors.tile(colors)
            assertTrue(contrast(colors.text, tile) >= 7f)
            assertTrue(contrast(HomeColors.hud(colors), tile) >= 4.5f) // the coach's live line, the buttons
            for (tone in Tone.entries) {
                val toned = colors.tone(tone)
                val live = contrast(toned.ink, tile) // the live line
                assertTrue("$tone on a tile (dark=${colors.isDark}): $live", live >= 4.5f)
                val icon = contrast(toned.ink, toned.second) // the icon on its round mark
                assertTrue("$tone icon (dark=${colors.isDark}): $icon", icon >= 3f)
            }
            // The coach's icon on its mark.
            assertTrue(contrast(colors.grad2, colors.surface2) >= 3f)
        }
    }

    @Test
    fun `captions and tiles stand out from the field`() {
        for (colors in listOf(D3Light, D3Dark)) {
            val field = HomeColors.field(colors)
            assertTrue(contrast(colors.muted, field) >= 4.5f)
            assertTrue(contrast(HomeColors.hud(colors), field) >= 4.5f)
            assertTrue(contrast(colors.text, field) >= 7f) // the screen title
            // A tile's edge is its tone's ink, a little transparent, on the tile: a clear boundary on the field.
            val tile = HomeColors.tile(colors)
            val k = HomeColors.EDGE_ALPHA
            for (tone in Tone.entries) {
                val ink = colors.tone(tone).ink
                val edge = Color(
                    red = ink.red * k + tile.red * (1 - k),
                    green = ink.green * k + tile.green * (1 - k),
                    blue = ink.blue * k + tile.blue * (1 - k),
                )
                val ratio = contrast(edge, field)
                assertTrue("$tone edge (dark=${colors.isDark}): $ratio", ratio >= 3f)
            }
        }
    }
}
