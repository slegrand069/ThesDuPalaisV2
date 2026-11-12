package fr.thesdupalais.presentation.articles

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import fr.thesdupalais.data.importer.ArticleImportGlobalError
import fr.thesdupalais.data.importer.ArticleImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleMarkdownRendererTest {
    @Test fun rendersHeadingsParagraphsAndListsWithoutTheirMarkers() {
        val blocks = ArticleMarkdownRenderer.render(
            "# Titre\n\n## Sous-titre\n\n### Niveau trois\n\nParagraphe\nsur deux lignes.\n\n- Puce\n1. Étape",
        )
        assertEquals(
            listOf(
                ArticleMarkdownBlockKind.HEADING_1,
                ArticleMarkdownBlockKind.HEADING_2,
                ArticleMarkdownBlockKind.HEADING_3,
                ArticleMarkdownBlockKind.PARAGRAPH,
                ArticleMarkdownBlockKind.BULLET,
                ArticleMarkdownBlockKind.NUMBERED,
            ),
            blocks.map { it.kind },
        )
        assertEquals("Titre", blocks[0].text.text)
        assertEquals("Sous-titre", blocks[1].text.text)
        assertEquals("Niveau trois", blocks[2].text.text)
        assertEquals("Paragraphe\nsur deux lignes.", blocks[3].text.text)
        assertEquals("Puce", blocks[4].text.text)
        assertEquals("•", blocks[4].marker)
        assertEquals("Étape", blocks[5].text.text)
        assertEquals("1.", blocks[5].marker)
    }

    @Test fun combinesColorBoldItalicAndUnderlineStylesAndKeepsSpecialCharacters() {
        val rendered = ArticleMarkdownRenderer.renderInline(
            "<color #0088CC>**bleu & thé**</color> *frais* <u>cœur</u>",
        )
        assertEquals("bleu & thé frais cœur", rendered.text)
        assertTrue(rendered.spanStyles.any {
            it.start == 0 && it.end == "bleu & thé".length && it.item.color == Color(0xFF0088CC)
        })
        assertTrue(rendered.spanStyles.any {
            it.start == 0 && it.end == "bleu & thé".length && it.item.fontWeight == FontWeight.Bold
        })
        assertTrue(rendered.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(rendered.spanStyles.any { it.item.textDecoration == TextDecoration.Underline })
    }

    @Test fun malformedInlineMarkupDoesNotCrashAndInvalidColorContentStaysReadable() {
        val rendered = ArticleMarkdownRenderer.renderInline(
            "<color #GG0000>sans couleur **gras**</color> <color #00AA00>non terminé *italique*",
        )
        assertEquals("sans couleur gras non terminé italique", rendered.text)
        assertFalse(rendered.spanStyles.any { it.item.color != Color.Unspecified })
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(rendered.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test fun leavesUnsupportedHtmlAsTextAndRemovesRecognizedMarkers() {
        val rendered = ArticleMarkdownRenderer.renderInline("<script>alert(1)</script> **gras**")
        assertEquals("<script>alert(1)</script> gras", rendered.text)
    }

    @Test fun boundsDeeplyNestedInlineMarkupWithoutCrashing() {
        val source = "<u>".repeat(300) + "texte" + "</u>".repeat(300)
        assertTrue(ArticleMarkdownRenderer.renderInline(source).text.isNotEmpty())
    }

    @Test fun importOutcomeCallsInterruptedSuccessfulWritesPartial() {
        val result = ArticleImportResult(
            added = 2,
            globalError = ArticleImportGlobalError("$.articles[2]", "erreur de persistance"),
        )
        assertEquals(ArticleImportOutcome.PARTIAL, result.outcome())
        assertEquals(ArticleImportOutcome.INTERRUPTED, ArticleImportResult(globalError = result.globalError).outcome())
        assertEquals(ArticleImportOutcome.WITH_REJECTIONS, ArticleImportResult(rejections = listOf(
            fr.thesdupalais.data.importer.ArticleImportRejection(0, "$.articles[0].title", "titre manquant"),
        )).outcome())
        assertEquals(ArticleImportOutcome.COMPLETE, ArticleImportResult(added = 1).outcome())
    }
}
