package dev.lifeos.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test
    fun `paragraphs, headings, lists and code are told apart`() {
        val text = listOf(
            "# Plan",
            "Three things are open:",
            "still the same paragraph",
            "",
            "- Draft the outline",
            "  due today",
            "- Review the notes",
            "  1. first the short ones",
            "2) Write the summary",
            "",
            "```kotlin",
            "val x = 1",
            "  indented",
            "",
            "# not a heading",
            "```",
            "Done.",
        ).joinToString("\n")
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Plan"),
                MdBlock.Paragraph("Three things are open:\nstill the same paragraph"),
                MdBlock.Item("•", 0, "Draft the outline due today"),
                MdBlock.Item("•", 0, "Review the notes"),
                MdBlock.Item("1.", 1, "first the short ones"),
                MdBlock.Item("2.", 0, "Write the summary"),
                MdBlock.Code("val x = 1\n  indented\n\n# not a heading"),
                MdBlock.Paragraph("Done."),
            ),
            Markdown.blocks(text),
        )
    }

    @Test
    fun `an answer that is still being written is shown as far as it goes`() {
        assertEquals(listOf(MdBlock.Paragraph("Look:"), MdBlock.Code("$ ls")), Markdown.blocks("Look:\n```\n$ ls"))
        assertEquals(listOf(MdBlock.Paragraph("Look:"), MdBlock.Code("")), Markdown.blocks("Look:\n```"))
        assertEquals(emptyList<MdBlock>(), Markdown.blocks(" \n\n"))
        // What is not handled stays readable as text.
        assertEquals(listOf(MdBlock.Paragraph("| a | b |\n|---|---|\n| 1 | 2 |")), Markdown.blocks("| a | b |\n|---|---|\n| 1 | 2 |"))
        assertEquals(listOf(MdBlock.Paragraph("2027 was a year."), MdBlock.Paragraph("#hashtag")), Markdown.blocks("2027 was a year.\n\n#hashtag"))
    }

    @Test
    fun `bold and inline code inside a line`() {
        assertEquals(
            listOf(MdSpan("Run "), MdSpan("ls -la", code = true), MdSpan(" in "), MdSpan("the folder", bold = true), MdSpan(".")),
            Markdown.spans("Run `ls -la` in **the folder**."),
        )
        // Marks without a partner are text.
        assertEquals(listOf(MdSpan("2 ** 3 and a ` tick")), Markdown.spans("2 ** 3 and a ` tick"))
        assertEquals(listOf(MdSpan("a * b * c")), Markdown.spans("a * b * c"))
        assertEquals(listOf(MdSpan("x", bold = true), MdSpan("**y", code = true)), Markdown.spans("**x**`**y`"))
        assertEquals(emptyList<MdSpan>(), Markdown.spans(""))
    }
}
