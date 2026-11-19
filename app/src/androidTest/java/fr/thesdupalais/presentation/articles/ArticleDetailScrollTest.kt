package fr.thesdupalais.presentation.articles

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import fr.thesdupalais.data.database.ArticleEntity
import fr.thesdupalais.data.database.ArticleImageStore
import fr.thesdupalais.presentation.theme.ThesDuPalaisTheme
import java.util.UUID
import org.junit.Rule
import org.junit.Test

/** Regression coverage for bounded Compose measurement around the WebView article reader. */
class ArticleDetailScrollTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun opensOlderTextOnlyArticleInsideSingleVerticalScroll() {
        composeRule.setContent {
            ThesDuPalaisTheme {
                ArticleDetailScreen(
                    ArticleDetailState.Content(article(markdown = "# Ancien article\n\n" + "Texte. ".repeat(300))),
                    imageStore(),
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithTag("article-detail-title").assertIsDisplayed()
        composeRule.onNodeWithTag("article-markdown-webview").assertIsDisplayed()
    }

    @Test fun opensNewIllustratedArticleWithTocAndWideTable() {
        val markdown = """
            ## Première section
            Texte illustré.

            ![Feuilles](images/123e4567-e89b-12d3-a456-426614174000.jpg)

            ## Deuxième section
            | Thé | Température | Durée |
            |---|---:|---:|
            | Sencha | 75 °C | 2 min |

            ## Troisième section
            Fin.
        """.trimIndent()
        composeRule.setContent {
            ThesDuPalaisTheme {
                ArticleDetailScreen(
                    ArticleDetailState.Content(article(markdown = markdown)),
                    imageStore(),
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithTag("article-detail-title").assertIsDisplayed()
        composeRule.onNodeWithTag("article-markdown-webview").assertIsDisplayed()
        composeRule.onNodeWithText("Sommaire").assertIsDisplayed()
    }

    private fun article(markdown: String) = ArticleEntity(
        UUID.fromString("123e4567-e89b-12d3-a456-426614174001"), "Article de test", "Savoirs", markdown,
    )

    private fun imageStore() = ArticleImageStore(ApplicationProvider.getApplicationContext<Context>().filesDir)
}
