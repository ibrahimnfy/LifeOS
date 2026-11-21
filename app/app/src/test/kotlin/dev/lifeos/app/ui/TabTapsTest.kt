package dev.lifeos.app.ui

import dev.lifeos.app.ui.nav.Routes
import dev.lifeos.app.ui.nav.Tab
import dev.lifeos.app.ui.nav.TabTaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A bottom-bar tab always opens its root screen (#166). */
class TabTapsTest {
    @Test
    fun `no tab keeps or brings back a sub-page`() {
        Tab.entries.forEach { tab ->
            val options = TabTaps.options(tab)
            assertEquals(tab.route, options.route)
            assertTrue(options.popUpToStart)
            assertFalse(options.saveState)
            assertFalse(options.restoreState)
            assertTrue(options.singleTop)
        }
    }

    @Test
    fun `a tap on the selected tab goes back to its root`() {
        assertEquals(listOf(Routes.TODAY, Routes.TASKS), TabTaps.stackAfter(listOf(Routes.TODAY, Routes.TASKS, Routes.ALL_TASKS), Tab.TASKS))
        assertEquals(listOf(Routes.TODAY, Routes.CHAT), TabTaps.stackAfter(listOf(Routes.TODAY, Routes.CHAT, Routes.CHAT_THREAD), Tab.CHAT))
        assertEquals(
            listOf(Routes.TODAY, Routes.MORE),
            TabTaps.stackAfter(listOf(Routes.TODAY, Routes.MORE, Routes.COURSES, "more/course/5", "more/course/5/assignment/7"), Tab.MORE),
        )
        assertEquals(listOf(Routes.TODAY), TabTaps.stackAfter(listOf(Routes.TODAY, Routes.COACH), Tab.TODAY))
        assertEquals(listOf(Routes.TODAY), TabTaps.stackAfter(listOf(Routes.TODAY), Tab.TODAY))
    }

    @Test
    fun `a tap on another tab opens its root, whatever was open there before`() {
        // Settings was open under More; Chat is tapped, then More again: More's root, not Settings.
        val chat = TabTaps.stackAfter(listOf(Routes.TODAY, Routes.MORE, Routes.SETTINGS), Tab.CHAT)
        assertEquals(listOf(Routes.TODAY, Routes.CHAT), chat)
        assertEquals(listOf(Routes.TODAY, Routes.MORE), TabTaps.stackAfter(chat, Tab.MORE))
        assertEquals(listOf(Routes.TODAY), TabTaps.stackAfter(listOf(Routes.TODAY, Routes.CHAT, Routes.CHAT_SESSIONS), Tab.TODAY))
    }

    @Test
    fun `every tab marks itself as selected on its root`() {
        Tab.entries.forEach { assertEquals(it, Tab.forRoute(it.route)) }
    }
}
