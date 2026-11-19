package fr.thesdupalais.presentation.articles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichArticleMarkdownTest {
    @Test fun `renders required GFM blocks and formatting`() {
        val rendered = RichArticleMarkdown.render("""## Thé
**gras** *italique* ~~barré~~

> citation

| A | B |
|---|---:|
| x | 2 |

- [ ] à faire
- [x] fait

---""")
        assertTrue(rendered.html.contains("<strong>gras</strong>"))
        assertTrue(rendered.html.contains("<em>italique</em>"))
        assertTrue(rendered.html.contains("<del>barré</del>"))
        assertTrue(rendered.html.contains("<blockquote>"))
        assertTrue(rendered.html.contains("<table>"))
        assertTrue(rendered.html.contains("align=\"right\""))
        assertTrue(rendered.html.contains("disabled"))
        assertTrue(rendered.html.contains("<hr"))
    }

    @Test fun `toc is compact and duplicate headings get unique anchors`() {
        val short = RichArticleMarkdown.render("## One\n\n## Two")
        assertTrue(short.toc.isEmpty())
        val full = RichArticleMarkdown.render("## L'été\n\n## L'été\n\n### Sous-section")
        assertEquals(listOf("l-été", "l-été-2", "sous-section"), full.toc.map { it.anchor })
        assertEquals(listOf(2, 2, 3), full.toc.map { it.level })
        assertTrue(full.html.contains("id=\"l-été-2\""))
    }

    @Test fun `raw html is not passed through`() {
        val html = RichArticleMarkdown.render("<script>alert(1)</script>").html
        assertTrue(!html.contains("<script>"))
    }

    @Test fun `legacy text-only article still produces a normal body`() {
        val rendered = RichArticleMarkdown.render("# Ancien article\n\nTexte historique sans illustration.")
        assertTrue(rendered.html.contains("<h1>Ancien article</h1>"))
        assertTrue(rendered.html.contains("Texte historique sans illustration."))
        assertTrue(!rendered.html.contains("<img"))
        assertTrue(rendered.toc.isEmpty())
    }

    @Test fun `main documents are delegated but subresources remain subject to filtering`() {
        assertTrue(shouldDelegateArticleMainFrame(isForMainFrame = true))
        assertTrue(!shouldDelegateArticleMainFrame(isForMainFrame = false))
    }

    @Test fun `webview document height maps css pixels directly to dp`() {
        assertEquals(2_400f, articleDocumentHeightDp(2_400), 0f)
        assertEquals(100f, articleDocumentHeightDp(1), 0f)
    }

    @Test fun `retains the existing safe underline and color tags`() {
        val html = RichArticleMarkdown.render("<u>important</u> <color #AABBCC>colored</color>").html
        assertTrue(html.contains("<u>important</u>"))
        assertTrue(html.contains("<span style=\"color:#AABBCC\">colored</span>"))
    }
}
