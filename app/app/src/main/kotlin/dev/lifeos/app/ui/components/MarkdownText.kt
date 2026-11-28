package dev.lifeos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lifeos.app.R
import dev.lifeos.app.data.Markdown
import dev.lifeos.app.data.MdBlock
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/**
 * An answer of the assistant, or ([site]) a description the computer made from a page of the
 * course site: paragraphs, headings, lists and code blocks, with bold, italic, inline code
 * and web links, which the system opens in the browser (see [Markdown] for what is and is not
 * understood). A site's text flows as one paragraph where the assistant's keeps its lines,
 * and its headings are the size of a row title. Every code block has its own "Copy" (#208);
 * [codeCopy] is false while the text is still being written, so that half a block is not copied.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, site: Boolean = false, codeCopy: Boolean = true) {
    val colors = LifeTheme.colors
    val blocks = remember(text, site) { Markdown.blocks(text, joinLines = site) }
    val links = TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                // Items of one list sit close together; everything else has a paragraph's distance.
                Spacer(Modifier.height(if (block is MdBlock.Item && blocks[index - 1] is MdBlock.Item) 3.dp else 8.dp))
            }
            when (block) {
                is MdBlock.Paragraph -> Text(inline(block.text, colors.surface2, links), style = LifeType.body, color = colors.text)
                is MdBlock.Heading -> Text(
                    text = inline(block.text, colors.surface2, links),
                    style = if (block.level <= 2 && !site) LifeType.sectionTitle else LifeType.rowTitle,
                    color = colors.text,
                    modifier = Modifier.semantics { heading() },
                )
                is MdBlock.Item -> Row(Modifier.padding(start = (block.level * 14).dp)) {
                    Text(text = block.marker, style = LifeType.body, color = colors.muted, modifier = Modifier.widthIn(min = 18.dp))
                    Text(inline(block.text, colors.surface2, links), style = LifeType.body, color = colors.text, modifier = Modifier.padding(start = 4.dp))
                }
                is MdBlock.Code -> CodeBlock(block, codeCopy)
            }
        }
    }
}

/**
 * A fenced block: a header with its language (when it names one) and its own "Copy", then the
 * code in a fixed-width font. Long lines scroll sideways instead of wrapping.
 */
@Composable
private fun CodeBlock(block: MdBlock.Code, copy: Boolean) {
    val colors = LifeTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface2),
    ) {
        // The header is not part of a text selection of the answer.
        DisableSelection {
            Row(Modifier.fillMaxWidth().heightIn(min = CopyHeight).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = block.language.orEmpty(),
                    style = LifeType.small.copy(fontFamily = FontFamily.Monospace),
                    color = colors.muted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (copy) CopyWord(text = block.text, label = stringResource(R.string.chat_copy_code))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(text = block.text, style = LifeType.small.copy(fontFamily = FontFamily.Monospace), color = colors.text, softWrap = false)
        }
    }
}

private fun inline(text: String, codeBackground: Color, links: TextLinkStyles): AnnotatedString = buildAnnotatedString {
    for (span in Markdown.spans(text)) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            fontSize = if (span.code) 13.5.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            background = if (span.code) codeBackground else Color.Unspecified,
        )
        val link = span.link
        if (link != null) {
            // Opened in the browser by the system, never inside the app.
            withLink(LinkAnnotation.Url(link, links)) { withStyle(style) { append(span.text) } }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}
