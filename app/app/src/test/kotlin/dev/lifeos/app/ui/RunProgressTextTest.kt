package dev.lifeos.app.ui

import dev.lifeos.app.R
import dev.lifeos.app.ui.screens.RunProgressText
import org.junit.Assert.assertEquals
import org.junit.Test

/** The line under "Working…" (#199): which words name a step, and how its detail is joined. */
class RunProgressTextTest {
    @Test
    fun `every tool of a run has its words, an unknown one the general ones`() {
        assertEquals(R.string.chat_progress_command, RunProgressText.labelRes("Bash"))
        assertEquals(R.string.chat_progress_read, RunProgressText.labelRes("Read"))
        assertEquals(R.string.chat_progress_edit, RunProgressText.labelRes("Edit"))
        assertEquals(R.string.chat_progress_edit, RunProgressText.labelRes("NotebookEdit"))
        assertEquals(R.string.chat_progress_write, RunProgressText.labelRes("Write"))
        assertEquals(R.string.chat_progress_search, RunProgressText.labelRes("Grep"))
        assertEquals(R.string.chat_progress_search, RunProgressText.labelRes("Glob"))
        assertEquals(R.string.chat_progress_web, RunProgressText.labelRes("WebFetch"))
        assertEquals(R.string.chat_progress_web, RunProgressText.labelRes("WebSearch"))
        assertEquals(R.string.chat_progress_helper, RunProgressText.labelRes("Agent"))
        assertEquals(R.string.chat_progress_step, RunProgressText.labelRes("mcp__notes__search"))
        assertEquals(R.string.chat_progress_step, RunProgressText.labelRes(""))
    }

    @Test
    fun `the detail follows the words, and a step without one shows the words alone`() {
        assertEquals("Editing a file: app.py", RunProgressText.line("Editing a file", " app.py "))
        assertEquals("Running a command", RunProgressText.line("Running a command", "  "))
    }
}
