package dev.lifeos.app

import dev.lifeos.app.settings.LanguageSetting
import dev.lifeos.app.settings.ThemeSetting
import dev.lifeos.app.ui.nav.MoreSection
import dev.lifeos.app.ui.nav.Routes
import dev.lifeos.app.ui.nav.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSettingTest {
    @Test
    fun `light is the default when nothing or something unknown is stored`() {
        assertEquals(ThemeSetting.LIGHT, ThemeSetting.fromStored(null))
        assertEquals(ThemeSetting.LIGHT, ThemeSetting.fromStored(""))
        assertEquals(ThemeSetting.LIGHT, ThemeSetting.fromStored("sepia"))
    }

    @Test
    fun `every setting survives a round trip through its stored value`() {
        ThemeSetting.entries.forEach { assertEquals(it, ThemeSetting.fromStored(it.storedValue)) }
    }

    @Test
    fun `only follow-phone depends on the phone's dark mode`() {
        assertFalse(ThemeSetting.LIGHT.isDark(systemIsDark = true))
        assertTrue(ThemeSetting.DARK.isDark(systemIsDark = false))
        assertTrue(ThemeSetting.SYSTEM.isDark(systemIsDark = true))
        assertFalse(ThemeSetting.SYSTEM.isDark(systemIsDark = false))
    }
}

class LanguageSettingTest {
    @Test
    fun `an empty locale list means follow phone`() {
        assertEquals(LanguageSetting.SYSTEM, LanguageSetting.fromLanguageTags(""))
        assertEquals(LanguageSetting.SYSTEM, LanguageSetting.fromLanguageTags(null))
        assertEquals("", LanguageSetting.SYSTEM.toLanguageTags())
    }

    @Test
    fun `region and extra entries are ignored`() {
        assertEquals(LanguageSetting.TURKISH, LanguageSetting.fromLanguageTags("tr"))
        assertEquals(LanguageSetting.TURKISH, LanguageSetting.fromLanguageTags("tr-TR,en-US"))
        assertEquals(LanguageSetting.ENGLISH, LanguageSetting.fromLanguageTags("en-GB"))
        assertEquals(LanguageSetting.ENGLISH, LanguageSetting.fromLanguageTags("EN"))
    }

    @Test
    fun `an unsupported language falls back to follow phone`() {
        assertEquals(LanguageSetting.SYSTEM, LanguageSetting.fromLanguageTags("de-DE"))
    }

    @Test
    fun `every setting survives a round trip through its language tags`() {
        LanguageSetting.entries.forEach {
            assertEquals(it, LanguageSetting.fromLanguageTags(it.toLanguageTags()))
        }
    }
}

class TabTest {
    @Test
    fun `each tab is active on its own route`() {
        Tab.entries.forEach { assertEquals(it, Tab.forRoute(it.route)) }
    }

    @Test
    fun `screens below a tab keep that tab active`() {
        assertEquals(Tab.TASKS, Tab.forRoute(Routes.ALL_TASKS))
        assertEquals(Tab.MORE, Tab.forRoute(Routes.SETTINGS))
        assertEquals(Tab.MORE, Tab.forRoute(Routes.SECTION))
        MoreSection.entries.forEach { assertEquals(Tab.MORE, Tab.forRoute(Routes.section(it))) }
    }

    @Test
    fun `an unknown or missing route falls back to Today`() {
        assertEquals(Tab.TODAY, Tab.forRoute(null))
        assertEquals(Tab.TODAY, Tab.forRoute("elsewhere"))
    }
}
