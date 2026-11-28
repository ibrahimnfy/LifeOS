package dev.lifeos.app.data

/** One block of an answer, top to bottom. */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock

    /** A list item: [marker] is "•" or the item's own number ("2."); [level] 0 is the outermost list. */
    data class Item(val marker: String, val level: Int, val text: String) : MdBlock

    /**
     * A fenced block, shown in a fixed-width font exactly as written. [language]: the word
     * after the opening fence ("kotlin", "bash"), when there is one; it only labels the block.
     */
    data class Code(val text: String, val language: String? = null) : MdBlock
}

/** A run of text inside a block. [link]: a web address the text leads to. */
data class MdSpan(val text: String, val bold: Boolean = false, val code: Boolean = false, val italic: Boolean = false, val link: String? = null)

/**
 * The one Markdown reader of the app, for the assistant's answers and for the descriptions
 * the computer writes for assignments: paragraphs, headings, lists, fenced code, and inside a
 * line bold, italic, inline code and links to web addresses. Everything else (tables,
 * quotes) is shown as the text it is. It never fails: an answer that is still being written,
 * with an open fence, an open `**` or half a link, is shown as far as it goes.
 */
object Markdown {
    private val FENCE = Regex("^\\s*(`{3,}|~{3,})(.*)$")
    private val LANGUAGE = Regex("^[A-Za-z0-9+#._-]{1,24}")
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$")
    private const val MAX_LEVEL = 3

    /**
     * [joinLines]: the lines of a paragraph are one flowing text (a description converted from
     * a web page); otherwise they stay lines, as the assistant wrote them.
     */
    fun blocks(text: String, joinLines: Boolean = false): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        var code: StringBuilder? = null
        var fence = ""
        var language: String? = null
        var inItem = false

        fun flush() {
            if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.setLength(0)
        }

        for (line in text.replace("\r\n", "\n").split('\n')) {
            val open = code
            // "```code```" on one line is inline code, not a fence.
            val marker = FENCE.matchEntire(line)?.takeUnless { it.groupValues[1][0] == '`' && '`' in it.groupValues[2] }
            if (open != null) {
                if (marker != null && closes(marker, fence)) {
                    blocks += MdBlock.Code(open.toString().trimEnd('\n'), language)
                    code = null
                } else {
                    open.append(line).append('\n')
                }
                continue
            }
            if (marker != null) {
                flush()
                inItem = false
                code = StringBuilder()
                fence = marker.groupValues[1]
                language = LANGUAGE.find(marker.groupValues[2].trim())?.value
                continue
            }
            if (line.isBlank()) {
                flush()
                inItem = false
                continue
            }
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            val numbered = NUMBERED.matchEntire(line)
            when {
                heading != null -> {
                    flush()
                    inItem = false
                    blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim().trimEnd('#').trim())
                }
                bullet != null -> {
                    flush()
                    inItem = true
                    blocks += MdBlock.Item("•", level(bullet.groupValues[1]), bullet.groupValues[2].trim())
                }
                numbered != null -> {
                    flush()
                    inItem = true
                    blocks += MdBlock.Item(numbered.groupValues[2] + ".", level(numbered.groupValues[1]), numbered.groupValues[3].trim())
                }
                // An indented line right after a list item continues that item.
                inItem && line.startsWith(" ") && blocks.lastOrNull() is MdBlock.Item -> {
                    val item = blocks.removeAt(blocks.lastIndex) as MdBlock.Item
                    blocks += item.copy(text = item.text + " " + line.trim())
                }
                else -> {
                    inItem = false
                    if (paragraph.isNotEmpty()) paragraph.append(if (joinLines) ' ' else '\n')
                    paragraph.append(if (joinLines) line.trim() else line.trimEnd())
                }
            }
        }
        flush()
        // A fence that is still open (the answer is being written): what there is so far.
        code?.let { blocks += MdBlock.Code(it.toString().trimEnd('\n'), language) }
        return blocks
    }

    /**
     * A fence closes the block that [opened] with the same mark, at least as long, and
     * nothing after it: so a block can show shorter fences, and "```text", as its content.
     */
    private fun closes(marker: MatchResult, opened: String): Boolean {
        val mark = marker.groupValues[1]
        return mark[0] == opened[0] && mark.length >= opened.length && marker.groupValues[2].isBlank()
    }

    private fun level(indent: String): Int = (indent.replace("\t", "  ").length / 2).coerceAtMost(MAX_LEVEL)

    /**
     * `**bold**`, `*italic*`, `` `code` `` and `[links](https://…)` inside one block; marks
     * without their other half stay as they are. Underscores are left alone (file and variable
     * names), a `*` counts only where it hugs a word (`a * b`, `*.kt` and `2*3` stay text),
     * and only web addresses become links (the text of any other link is shown without one).
     */
    fun spans(text: String): List<MdSpan> = buildList { scan(text, bold = false, italic = false, link = null, out = this) }

    private fun scan(text: String, bold: Boolean, italic: Boolean, link: String?, out: MutableList<MdSpan>) {
        val plain = StringBuilder()
        var index = 0

        fun flush() {
            if (plain.isNotEmpty()) out += MdSpan(plain.toString(), bold = bold, italic = italic, link = link)
            plain.setLength(0)
        }

        while (index < text.length) {
            val char = text[index]
            if (char == '`') {
                val end = text.indexOf('`', index + 1)
                if (end > index + 1) {
                    flush()
                    out += MdSpan(text.substring(index + 1, end), bold = bold, code = true, italic = italic, link = link)
                    index = end + 1
                    continue
                }
            } else if (char == '*' && text.startsWith("**", index)) {
                // Opens only when it also closes.
                val end = if (bold) -1 else text.indexOf("**", index + 2)
                if (end > index + 2) {
                    flush()
                    scan(text.substring(index + 2, end), bold = true, italic = italic, link = link, out = out)
                    index = end + 2
                } else {
                    plain.append("**")
                    index += 2
                }
                continue
            } else if (char == '*' && !italic) {
                val end = italicEnd(text, index)
                if (end > 0) {
                    flush()
                    scan(text.substring(index + 1, end), bold = bold, italic = true, link = link, out = out)
                    index = end + 1
                    continue
                }
            } else if (char == '[' && link == null) {
                val middle = text.indexOf("](", index + 1)
                val end = if (middle > index + 1) text.indexOf(')', middle + 2) else -1
                val label = if (end > middle + 2) text.substring(index + 1, middle) else ""
                val address = if (end > middle + 2) text.substring(middle + 2, end) else ""
                if (label.isNotEmpty() && '[' !in label && '\n' !in label && address.none { it.isWhitespace() }) {
                    flush()
                    val web = address.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    // Not a web address: its text, without a link.
                    val before = out.size
                    scan(label, bold = bold, italic = italic, link = web, out = out)
                    if (web == null && out.size == before) out += MdSpan(label, bold = bold, italic = italic)
                    index = end + 1
                    continue
                }
            }
            plain.append(char)
            index++
        }
        flush()
    }

    /** Where the italic that opens at [start] closes, or -1 when the `*` there is just a star. */
    private fun italicEnd(text: String, start: Int): Int {
        val next = text.getOrNull(start + 1) ?: return -1
        if (next.isWhitespace() || next == '*') return -1
        if (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '*')) return -1
        var index = start + 2
        while (index < text.length) {
            val char = text[index]
            if (char == '\n') return -1
            if (char == '*' && !text[index - 1].isWhitespace() && text[index - 1] != '*' && text.getOrNull(index + 1) != '*' &&
                text.getOrNull(index + 1)?.isLetterOrDigit() != true
            ) {
                return index
            }
            index++
        }
        return -1
    }
}
