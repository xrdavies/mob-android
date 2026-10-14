package com.xrdavies.mob.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.xrdavies.mob.data.MdBlock
import com.xrdavies.mob.data.parseMarkdown

private val INLINE = Regex("`([^`]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)|(https?://[^\\s)]*[^\\s).,;:!?'\"])")

/** Inline Markdown: `code`, **bold**, *italic*, [links](url) and bare URLs. */
fun inlineMarkdown(text: String, codeBackground: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var pos = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(pos, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(g[1]) }
            g[2].isNotEmpty() || g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(inlineMarkdown(g[2].ifEmpty { g[3] }, codeBackground, linkColor))
            }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]) }
            else -> {
                val label = g[5].ifEmpty { g[7] }
                val url = g[6].ifEmpty { g[7] }
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                    append(label)
                }
            }
        }
        pos = m.range.last + 1
    }
    append(text.substring(pos))
}

/** Renders an agent reply. Text is selectable so it can be copied. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(text) { parseMarkdown(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val link = MaterialTheme.colorScheme.primary
    val body = MaterialTheme.typography.bodyLarge.copy(color = color)
    fun inline(s: String) = inlineMarkdown(s, codeBg, link)
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in blocks) when (b) {
                is MdBlock.Heading -> Text(
                    inline(b.text),
                    style = (if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall).copy(color = color),
                    fontWeight = FontWeight.SemiBold,
                )
                is MdBlock.Paragraph -> Text(inline(b.text), style = body)
                is MdBlock.ListItem -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                    Text(b.marker, style = body, modifier = Modifier.widthIn(min = 20.dp))
                    Text(inline(b.text), style = body)
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    Spacer(Modifier.width(10.dp))
                    Text(inline(b.text), style = body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                }
                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(codeBg, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(b.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant))
                }
                MdBlock.Rule -> HorizontalDivider()
            }
        }
    }
}
