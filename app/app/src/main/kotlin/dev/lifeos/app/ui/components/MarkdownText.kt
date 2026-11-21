package dev.lifeos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lifeos.app.data.Markdown
import dev.lifeos.app.data.MdBlock
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/**
 * An answer of the assistant: paragraphs, headings, lists and code blocks, with bold and
 * inline code (see [Markdown] for what is and is not understood).
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    val blocks = remember(text) { Markdown.blocks(text) }
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                // Items of one list sit close together; everything else has a paragraph's distance.
                Spacer(Modifier.height(if (block is MdBlock.Item && blocks[index - 1] is MdBlock.Item) 3.dp else 8.dp))
            }
            when (block) {
                is MdBlock.Paragraph -> Text(inline(block.text, colors.surface2), style = LifeType.body, color = colors.text)
                is MdBlock.Heading -> Text(
                    text = inline(block.text, colors.surface2),
                    style = if (block.level <= 2) LifeType.sectionTitle else LifeType.rowTitle,
                    color = colors.text,
                    modifier = Modifier.semantics { heading() },
                )
                is MdBlock.Item -> Row(Modifier.padding(start = (block.level * 14).dp)) {
                    Text(text = block.marker, style = LifeType.body, color = colors.muted, modifier = Modifier.widthIn(min = 18.dp))
                    Text(inline(block.text, colors.surface2), style = LifeType.body, color = colors.text, modifier = Modifier.padding(start = 4.dp))
                }
                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surface2, RoundedCornerShape(10.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(text = block.text, style = LifeType.small.copy(fontFamily = FontFamily.Monospace), color = colors.text, softWrap = false)
                }
            }
        }
    }
}

private fun inline(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    for (span in Markdown.spans(text)) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            fontSize = if (span.code) 13.5.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            background = if (span.code) codeBackground else Color.Unspecified,
        )
        withStyle(style) { append(span.text) }
    }
}
