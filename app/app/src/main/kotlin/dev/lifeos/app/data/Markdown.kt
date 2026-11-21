package dev.lifeos.app.data

/** One block of an answer, top to bottom. */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock

    /** A list item: [marker] is "•" or the item's own number ("2."); [level] 0 is the outermost list. */
    data class Item(val marker: String, val level: Int, val text: String) : MdBlock

    /** A fenced block, shown in a fixed-width font exactly as written. */
    data class Code(val text: String) : MdBlock
}

/** A run of text inside a block. */
data class MdSpan(val text: String, val bold: Boolean = false, val code: Boolean = false)

/**
 * The small part of Markdown the assistant's answers use most: paragraphs, headings, lists,
 * fenced code, and bold and inline code inside a line. Everything else (tables, links,
 * quotes) is shown as the text it is. It never fails: an answer that is still being written,
 * with an open fence or an open `**`, is shown as far as it goes.
 */
object Markdown {
    private val FENCE = Regex("^\\s*(```|~~~).*$")
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$")
    private const val MAX_LEVEL = 3

    fun blocks(text: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        var code: StringBuilder? = null
        var inItem = false

        fun flush() {
            if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.setLength(0)
        }

        for (line in text.replace("\r\n", "\n").split('\n')) {
            val open = code
            if (open != null) {
                if (FENCE.matches(line)) {
                    blocks += MdBlock.Code(open.toString().trimEnd('\n'))
                    code = null
                } else {
                    open.append(line).append('\n')
                }
                continue
            }
            if (FENCE.matches(line)) {
                flush()
                inItem = false
                code = StringBuilder()
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
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line.trimEnd())
                }
            }
        }
        flush()
        // A fence that is still open (the answer is being written): what there is so far.
        code?.let { blocks += MdBlock.Code(it.toString().trimEnd('\n')) }
        return blocks
    }

    private fun level(indent: String): Int = (indent.replace("\t", "  ").length / 2).coerceAtMost(MAX_LEVEL)

    /** `**bold**` and `` `code` `` inside one block; unmatched marks stay as they are. */
    fun spans(text: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        val plain = StringBuilder()
        var bold = false
        var index = 0

        fun flush() {
            if (plain.isNotEmpty()) spans += MdSpan(plain.toString(), bold = bold)
            plain.setLength(0)
        }

        while (index < text.length) {
            val char = text[index]
            if (char == '`') {
                val end = text.indexOf('`', index + 1)
                if (end > index + 1) {
                    flush()
                    spans += MdSpan(text.substring(index + 1, end), bold = bold, code = true)
                    index = end + 1
                    continue
                }
            } else if (char == '*' && text.startsWith("**", index)) {
                // Opens only when it also closes; closes whenever it is open.
                if (bold || text.indexOf("**", index + 2) > index + 2) {
                    flush()
                    bold = !bold
                    index += 2
                    continue
                }
            }
            plain.append(char)
            index++
        }
        flush()
        return spans
    }
}
