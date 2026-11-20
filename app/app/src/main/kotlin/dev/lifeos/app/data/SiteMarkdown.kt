package dev.lifeos.app.data

/**
 * The little Markdown the bridge writes for an assignment's description: headings, bullet
 * and numbered lists, paragraphs, **bold**, *italic* and [links](https://…). Anything else
 * stays plain text. Only web addresses become links.
 */
object SiteMarkdown {
    enum class Kind { HEADING, BULLET, NUMBERED, PARAGRAPH }

    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val link: String? = null)

    /** [marker] is the number of a numbered item ("2."). */
    data class Block(val kind: Kind, val spans: List<Span>, val marker: String? = null)

    private val HEADING = Regex("^#{1,6}\\s+(.*)$")
    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*(\\d{1,3}[.)])\\s+(.*)$")
    // Underscores are left alone: they are common in file and variable names.
    private val INLINE = Regex("\\*\\*(.+?)\\*\\*|\\[([^\\]]+)]\\(([^)\\s]+)\\)|\\*(?!\\s)(.+?)\\*")

    fun blocks(text: String): List<Block> {
        val result = mutableListOf<Block>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) result += Block(Kind.PARAGRAPH, inline(paragraph.toString().trim()))
            paragraph.clear()
        }
        for (raw in text.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            val numbered = NUMBERED.matchEntire(line)
            when {
                heading != null -> {
                    flush()
                    result += Block(Kind.HEADING, inline(heading.groupValues[1].trim()))
                }
                bullet != null -> {
                    flush()
                    result += Block(Kind.BULLET, inline(bullet.groupValues[1].trim()))
                }
                numbered != null -> {
                    flush()
                    result += Block(Kind.NUMBERED, inline(numbered.groupValues[2].trim()), numbered.groupValues[1])
                }
                line.isBlank() -> flush()
                else -> paragraph.append(if (paragraph.isEmpty()) "" else " ").append(line.trim())
            }
        }
        flush()
        return result
    }

    fun inline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var at = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > at) spans += Span(text.substring(at, match.range.first))
            val g = match.groupValues
            spans += when {
                g[1].isNotEmpty() -> Span(g[1], bold = true)
                g[2].isNotEmpty() -> Span(g[2], link = g[3].takeIf { it.startsWith("https://") || it.startsWith("http://") })
                else -> Span(g[4], italic = true)
            }
            at = match.range.last + 1
        }
        if (at < text.length) spans += Span(text.substring(at))
        return spans
    }
}
