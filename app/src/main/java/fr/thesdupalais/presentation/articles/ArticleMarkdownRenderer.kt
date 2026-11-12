package fr.thesdupalais.presentation.articles

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

enum class ArticleMarkdownBlockKind { HEADING_1, HEADING_2, HEADING_3, PARAGRAPH, BULLET, NUMBERED }

data class ArticleMarkdownBlock(
    val kind: ArticleMarkdownBlockKind,
    val text: AnnotatedString,
    val marker: String? = null,
)

/** Small, safe renderer for the article subset. It produces Compose spans and never interprets HTML. */
object ArticleMarkdownRenderer {
    private val heading = Regex("^ {0,3}(#{1,3})[ \\t]+(.+?)\\s*#*\\s*$")
    private val bullet = Regex("^\\s*[-*+]\\s+(.+)$")
    private val numbered = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")

    fun render(markdown: String): List<ArticleMarkdownBlock> {
        val blocks = mutableListOf<ArticleMarkdownBlock>()
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += ArticleMarkdownBlock(
                    ArticleMarkdownBlockKind.PARAGRAPH,
                    renderInline(paragraph.joinToString("\n")),
                )
                paragraph.clear()
            }
        }

        markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if (line.isBlank()) {
                flushParagraph()
                return@forEach
            }
            val headingMatch = heading.matchEntire(line)
            val bulletMatch = bullet.matchEntire(line)
            val numberedMatch = numbered.matchEntire(line)
            when {
                headingMatch != null -> {
                    flushParagraph()
                    val kind = when (headingMatch.groupValues[1].length) {
                        1 -> ArticleMarkdownBlockKind.HEADING_1
                        2 -> ArticleMarkdownBlockKind.HEADING_2
                        else -> ArticleMarkdownBlockKind.HEADING_3
                    }
                    blocks += ArticleMarkdownBlock(kind, renderInline(headingMatch.groupValues[2]))
                }
                bulletMatch != null -> {
                    flushParagraph()
                    blocks += ArticleMarkdownBlock(ArticleMarkdownBlockKind.BULLET, renderInline(bulletMatch.groupValues[1]), "•")
                }
                numberedMatch != null -> {
                    flushParagraph()
                    blocks += ArticleMarkdownBlock(
                        ArticleMarkdownBlockKind.NUMBERED,
                        renderInline(numberedMatch.groupValues[2]),
                        "${numberedMatch.groupValues[1]}.",
                    )
                }
                else -> paragraph += line
            }
        }
        flushParagraph()
        return blocks
    }

    fun renderInline(source: String): AnnotatedString = renderInline(source, 0)

    private fun renderInline(source: String, depth: Int): AnnotatedString {
        if (depth >= MAX_INLINE_NESTING) return AnnotatedString(source)
        return buildAnnotatedString { appendInline(source, depth) }
    }

    private fun AnnotatedString.Builder.appendInline(source: String, depth: Int) {
        var index = 0
        while (index < source.length) {
            if (source.startsWith("<u>", index)) {
                val close = source.indexOf("</u>", index + 3)
                if (close >= 0) {
                    val inner = renderInline(source.substring(index + 3, close), depth + 1)
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(inner) }
                    index = close + 4
                    continue
                }
            }

            if (source.startsWith("<color", index) && source.getOrNull(index + 6)?.let { it.isWhitespace() || it == '>' } == true) {
                val openingEnd = source.indexOf('>', index + 6)
                if (openingEnd >= 0) {
                    val opening = source.substring(index, openingEnd + 1)
                    val close = source.indexOf("</color>", openingEnd + 1)
                    val contentEnd = if (close >= 0) close else source.length
                    val inner = renderInline(source.substring(openingEnd + 1, contentEnd), depth + 1)
                    val parsedColor = if (close >= 0) COLOR_TAG.matchEntire(opening)?.groupValues?.get(1)?.let(::parseRgb) else null
                    if (parsedColor == null) append(inner)
                    else withStyle(SpanStyle(color = parsedColor)) { append(inner) }
                    index = if (close >= 0) close + "</color>".length else source.length
                    continue
                }
            }

            if (source.startsWith("</color>", index)) {
                index += "</color>".length
                continue
            }

            if (source.startsWith("**", index)) {
                val close = source.indexOf("**", index + 2)
                if (close > index + 2) {
                    val inner = renderInline(source.substring(index + 2, close), depth + 1)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inner) }
                    index = close + 2
                    continue
                }
            } else if (source[index] == '*') {
                val close = source.indexOf('*', index + 1)
                if (close > index + 1 && source.getOrNull(close + 1) != '*') {
                    val inner = renderInline(source.substring(index + 1, close), depth + 1)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inner) }
                    index = close + 1
                    continue
                }
            }

            append(source[index])
            index++
        }
    }

    private fun parseRgb(hex: String): Color? = runCatching {
        val red = hex.substring(0, 2).toInt(16) / 255f
        val green = hex.substring(2, 4).toInt(16) / 255f
        val blue = hex.substring(4, 6).toInt(16) / 255f
        Color(red = red, green = green, blue = blue)
    }.getOrNull()

    private val COLOR_TAG = Regex("<color\\s+#([0-9a-fA-F]{6})>")
    private const val MAX_INLINE_NESTING = 64
}
