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
                MdBlock.Code("val x = 1\n  indented\n\n# not a heading", "kotlin"),
                MdBlock.Paragraph("Done."),
            ),
            Markdown.blocks(text),
        )
    }

    @Test
    fun `a code block knows its language and ends only at its own fence`() {
        // The word after the fence labels the block; what follows that word is dropped.
        assertEquals(listOf(MdBlock.Code("ls -la", "bash")), Markdown.blocks("```bash\nls -la\n```"))
        assertEquals(listOf(MdBlock.Code("x", "c++")), Markdown.blocks("``` c++ title=\"a.cpp\"\nx\n```"))
        assertEquals(listOf(MdBlock.Code("x", "python")), Markdown.blocks("~~~python\nx\n~~~"))
        assertEquals(listOf(MdBlock.Code("plain")), Markdown.blocks("```\nplain\n```"))
        assertEquals(listOf(MdBlock.Code("x")), Markdown.blocks("```{.weird}\nx\n```"))
        // A longer fence shows shorter ones, and a fence with a word, as its content.
        val nested = "````markdown\nUse:\n```kotlin\nval x = 1\n```\n````\nAfter."
        assertEquals(
            listOf(MdBlock.Code("Use:\n```kotlin\nval x = 1\n```", "markdown"), MdBlock.Paragraph("After.")),
            Markdown.blocks(nested),
        )
        assertEquals(listOf(MdBlock.Code("a\n~~~\nb")), Markdown.blocks("```\na\n~~~\nb\n```"))
        assertEquals(listOf(MdBlock.Code("a\n```text\nb")), Markdown.blocks("```\na\n```text\nb\n```"))
        // Two blocks keep their own words; an open one keeps its word while it is written.
        assertEquals(
            listOf(MdBlock.Code("a", "sh"), MdBlock.Paragraph("then"), MdBlock.Code("b", "json")),
            Markdown.blocks("```sh\na\n```\nthen\n```json\nb\n```"),
        )
        assertEquals(listOf(MdBlock.Code("val x", "kotlin")), Markdown.blocks("```kotlin\nval x"))
        // Code written between fences on one line is inline code, not the start of a block.
        assertEquals(listOf(MdBlock.Paragraph("```ls -la```\nand more")), Markdown.blocks("```ls -la```\nand more"))
        // Exactly what was written is what a copy gets: indentation and empty lines inside stay.
        assertEquals(listOf(MdBlock.Code("  a\n\n\tb")), Markdown.blocks("```\n  a\n\n\tb\n\n```"))
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

    @Test
    fun `italic and web links are read, and nothing else is taken for them`() {
        assertEquals(listOf(MdSpan("Show your "), MdSpan("working", italic = true)), Markdown.spans("Show your *working*"))
        assertEquals(
            listOf(MdSpan("One "), MdSpan("PDF", link = "https://example.invalid/hand-in"), MdSpan(" file")),
            Markdown.spans("One [PDF](https://example.invalid/hand-in) file"),
        )
        // Only web addresses become links; the text of any other link stays.
        assertEquals(listOf(MdSpan("and "), MdSpan("this"), MdSpan(" is no link")), Markdown.spans("and [this](javascript:alert) is no link"))
        // Inside each other.
        assertEquals(
            listOf(MdSpan("a ", bold = true), MdSpan("b", bold = true, italic = true), MdSpan(" c", bold = true)),
            Markdown.spans("**a *b* c**"),
        )
        assertEquals(listOf(MdSpan("the ", link = "https://example.invalid/"), MdSpan("docs", bold = true, link = "https://example.invalid/")), Markdown.spans("[the **docs**](https://example.invalid/)"))
        // Stars and underscores that are just that.
        for (plain in listOf("*.kt and *.md", "2*3*4", "a * b * c", "file_name_v2 and __init__", "5 * (2 + 3) *", "rate: 4* or 5*")) {
            assertEquals(plain, listOf(MdSpan(plain)), Markdown.spans(plain))
        }
    }

    @Test
    fun `half a link or half an emphasis stays text while an answer is written`() {
        for (plain in listOf("See [the docs](https://example.inv", "See [the docs]", "See [the docs](", "This is *importa", "This is **importa", "Run `ls -la")) {
            assertEquals(plain, listOf(MdSpan(plain)), Markdown.spans(plain))
        }
    }

    @Test
    fun `a description from the course site flows as paragraphs with headings, lists and links`() {
        val text = """
            # Task
            Solve problems **1-4** on the
            attached sheet.

            - Show your *working*
            2. Hand in one [PDF](https://example.invalid/hand-in) file
            Keep file_name_v2 as it is, and [this](javascript:alert) is no link.
        """.trimIndent()
        val blocks = Markdown.blocks(text, joinLines = true)
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Task"),
                MdBlock.Paragraph("Solve problems **1-4** on the attached sheet."),
                MdBlock.Item("•", 0, "Show your *working*"),
                MdBlock.Item("2.", 0, "Hand in one [PDF](https://example.invalid/hand-in) file"),
                MdBlock.Paragraph("Keep file_name_v2 as it is, and [this](javascript:alert) is no link."),
            ),
            blocks,
        )
        assertEquals(listOf("Solve problems ", "1-4", " on the attached sheet."), Markdown.spans((blocks[1] as MdBlock.Paragraph).text).map { it.text })
        // The assistant's own lines stay lines.
        assertEquals(MdBlock.Paragraph("Solve problems **1-4** on the\nattached sheet."), Markdown.blocks(text)[1])
    }
}
