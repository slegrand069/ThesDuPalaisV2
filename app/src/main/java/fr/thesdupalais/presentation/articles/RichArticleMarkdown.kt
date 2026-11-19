package fr.thesdupalais.presentation.articles

import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

data class ArticleTocEntry(val level: Int, val title: String, val anchor: String)
data class RenderedArticleMarkdown(val html: String, val toc: List<ArticleTocEntry>)

/** Safe CommonMark/GFM rendering model; raw HTML stays disabled and no network assets are emitted. */
object RichArticleMarkdown {
    private val extensions: List<Extension> = listOf(
        TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create(),
    )
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).build()

    fun render(markdown: String): RenderedArticleMarkdown {
        val safeTokens = linkedMapOf<String, String>()
        var source = markdown
        val legacyTags = Regex("<u>|</u>|<color\\s+#([0-9a-fA-F]{6})>|</color>")
        legacyTags.findAll(markdown).forEachIndexed { index, match ->
            val replacement = when {
                match.value == "<u>" -> "<u>"
                match.value == "</u>" -> "</u>"
                match.value == "</color>" -> "</span>"
                else -> "<span style=\"color:#${match.groupValues[1]}\">"
            }
            val token = "TDPLEGACYTAG${index}TOKEN"
            safeTokens[token] = replacement
            source = source.replaceFirst(match.value, token)
        }
        val document = parser.parse(source)
        val headings = mutableListOf<Pair<Heading, String>>()
        document.accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) {
                val text = StringBuilder().also { appendPlainText(heading.firstChild, it) }
                if (heading.level in 2..3 && text.isNotBlank()) headings += heading to text.toString()
                super.visit(heading)
            }
        })
        val counts = mutableMapOf<String, Int>()
        val toc = headings.map { (heading, title) ->
            val slug = title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').ifBlank { "section" }
            val occurrence = counts.merge(slug, 1, Int::plus)!!
            ArticleTocEntry(heading.level, title, if (occurrence == 1) slug else "$slug-$occurrence")
        }
        var headingIndex = 0
        var html = renderer.render(document).replace(Regex("<h([23])>(.*?)</h\\1>")) { match ->
            val entry = toc.getOrNull(headingIndex++) ?: return@replace match.value
            "<h${match.groupValues[1]} id=\"${entry.anchor}\">${match.groupValues[2]}</h${match.groupValues[1]}>"
        }
        safeTokens.forEach { (token, tag) -> html = html.replace(token, tag) }
        return RenderedArticleMarkdown(html, if (toc.size >= 3) toc else emptyList())
    }

    private fun appendPlainText(node: Node?, output: StringBuilder) {
        var child = node
        while (child != null) {
            if (child is Text) output.append(child.literal)
            else if (child.firstChild != null) appendPlainText(child.firstChild, output)
            child = child.next
        }
    }
}
