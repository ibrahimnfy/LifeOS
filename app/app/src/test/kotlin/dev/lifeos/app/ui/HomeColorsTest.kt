package dev.lifeos.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import dev.lifeos.app.data.Tone
import dev.lifeos.app.ui.screens.HomeColors
import dev.lifeos.app.ui.theme.D3Dark
import dev.lifeos.app.ui.theme.D3Light
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colours of the home field are prototype C's (#195), and what is written on a glass tile
 * that lies on the field is readable, light and dark: WCAG AA for normal text is 4.5 : 1. Where
 * one tile lies on another, the lower one shows through the glass (14 % in light, 22 % in
 * dark), as in the prototype; the last test says how much contrast is left in the worst case.
 */
class HomeColorsTest {
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    @Test
    fun `the glass and the field have the prototype's values`() {
        // --glass: rgba(248, 248, 250, 0.86) and rgba(16, 18, 24, 0.78); --field: #f1f2f6 and #080b12.
        assertEquals(Color(248, 248, 250).copy(alpha = 0.86f), HomeColors.glass(D3Light))
        assertEquals(Color(16, 18, 24).copy(alpha = 0.78f), HomeColors.glass(D3Dark))
        assertEquals(Color(0xFFF1F2F6), HomeColors.field(D3Light))
        assertEquals(Color(0xFF080B12), HomeColors.field(D3Dark))
        // --hud: #1852c8 and #7dafff; --hud-dim is the muted text colour.
        assertEquals(Color(0xFF1852C8), HomeColors.hud(D3Light))
        assertEquals(Color(0xFF7DAFFF), HomeColors.hud(D3Dark))
        assertEquals(0.55f, HomeColors.EDGE_ALPHA, 0f)
        assertEquals(0.7f, HomeColors.RING_ALPHA, 0f)
        assertEquals(0.38f, HomeColors.GLOW_ALPHA, 0f)
    }

    @Test
    fun `text on a glass tile over the field is readable in every tone, light and dark`() {
        for (colors in listOf(D3Light, D3Dark)) {
            val tile = HomeColors.glass(colors).compositeOver(HomeColors.field(colors))
            assertTrue(contrast(colors.text, tile) >= 7f)
            assertTrue(contrast(HomeColors.hud(colors), tile) >= 4.5f) // the coach's live line, "Tidy up"
            for (tone in Tone.entries) {
                val toned = colors.tone(tone)
                val live = contrast(toned.ink, tile) // the live line
                assertTrue("$tone on a tile (dark=${colors.isDark}): $live", live >= 4.5f)
                val icon = contrast(toned.ink, toned.second) // the icon on its round mark
                assertTrue("$tone icon (dark=${colors.isDark}): $icon", icon >= 3f)
            }
            assertTrue(contrast(colors.grad2, colors.surface2) >= 3f) // the coach's icon on its mark
        }
    }

    @Test
    fun `captions stand out from the field`() {
        for (colors in listOf(D3Light, D3Dark)) {
            val field = HomeColors.field(colors)
            assertTrue(contrast(colors.muted, field) >= 4.5f)
            assertTrue(contrast(HomeColors.hud(colors), field) >= 4.5f)
            assertTrue(contrast(colors.text, field) >= 7f) // the screen title
        }
    }

    @Test
    fun `text stays readable where a tile lies on another tile's text`() {
        // The worst case the glass allows: the lower tile's main text right behind the upper tile's.
        for (colors in listOf(D3Light, D3Dark)) {
            val lower = HomeColors.glass(colors).compositeOver(HomeColors.field(colors))
            val behind = HomeColors.glass(colors).compositeOver(colors.text.compositeOver(lower))
            val text = contrast(colors.text, behind)
            assertTrue("text over text (dark=${colors.isDark}): $text", text >= 7f)
            for (tone in Tone.entries) {
                val ratio = contrast(colors.tone(tone).ink, behind)
                assertTrue("$tone over text (dark=${colors.isDark}): $ratio", ratio >= 4.5f)
            }
        }
    }
}
