package fr.thesdupalais.presentation.catalogue

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.thesdupalais.presentation.theme.ThesDuPalaisTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CatalogueScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun showsEmptyCollectionMessage() {
        composeRule.setContent {
            ThesDuPalaisTheme { CatalogueContent(CatalogueUiState.Empty()) }
        }

        composeRule.onNodeWithText("Votre collection est vide").assertIsDisplayed()
        composeRule.onNodeWithText("Aucun thé n'est enregistré dans votre collection.").assertIsDisplayed()
        composeRule.onNodeWithTag("catalogue-search").assertIsDisplayed()
        composeRule.onNodeWithTag("catalogue-add").assertIsDisplayed()
    }

    @Test fun clickingTeaCardPassesItsIdToNavigation() {
        val id = UUID.fromString("00000000-0000-4000-8000-000000000001")
        var selectedTeaId: UUID? = null
        composeRule.setContent {
            ThesDuPalaisTheme {
                CatalogueContent(
                    CatalogueUiState.Content(
                        listOf(
                            CatalogueTea(
                                id = id,
                                name = "Sencha test",
                                category = "Thé vert",
                                categoryKey = "GREEN",
                                origins = listOf("Japon — Uji, Shizuoka", "Inde"),
                                personalNotes = "Notes personnelles",
                                tasteRating = 9.0,
                            ),
                        ),
                    ),
                    onTeaSelected = { selectedTeaId = it },
                )
            }
        }

        composeRule.onNodeWithTag("catalogue-list").assertIsDisplayed()
        composeRule.onNodeWithTag("catalogue-search").assertIsDisplayed()
        composeRule.onNodeWithText("Sencha test").assertIsDisplayed()
        composeRule.onNodeWithText("Thé vert - Japon — Uji, Shizuoka · Inde").assertIsDisplayed()
        composeRule.onNodeWithText("★ 9/10").assertIsDisplayed()
        composeRule.onAllNodesWithText("Notes personnelles").assertCountEquals(0)
        composeRule.onNodeWithTag("catalogue-tea-$id").assertHasClickAction().performClick()
        assertEquals(id, selectedTeaId)
    }

    @Test fun showsMadjoUnavailableAndKeepsTheTypedRequest() {
        composeRule.setContent {
            ThesDuPalaisTheme {
                CatalogueContent(CatalogueUiState.Content(emptyList(), CatalogueCriteria(query = "  MAdJo aide"), madjoRequest = true))
            }
        }
        composeRule.onNodeWithTag("catalogue-search").assertIsDisplayed()
        composeRule.onNodeWithTag("catalogue-search").assertTextEquals("  MAdJo aide")
        composeRule.onNodeWithText("Madjo sera bientôt disponible").assertIsDisplayed()
        composeRule.onAllNodesWithText("Sencha").assertCountEquals(0)
    }

    @Test fun filterButtonOpensSelectionDialog() {
        composeRule.setContent { ThesDuPalaisTheme { CatalogueContent(CatalogueUiState.Content(emptyList())) } }
        composeRule.onNodeWithTag("catalogue-filter-button").performClick()
        composeRule.onNodeWithText("Filtres du catalogue").assertIsDisplayed()
    }

    @Test fun categoryWithoutIllustratedPlaceholderUsesGenericFallback() {
        val id = UUID.fromString("00000000-0000-4000-8000-000000000002")
        composeRule.setContent {
            ThesDuPalaisTheme {
                CatalogueContent(
                    CatalogueUiState.Content(
                        listOf(CatalogueTea(id = id, name = "Maté test", category = "Maté", categoryKey = "MATE", origins = emptyList(), personalNotes = null, tasteRating = null)),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("catalogue-tea-$id").assertIsDisplayed()
        composeRule.onNodeWithText("Maté").assertIsDisplayed()
    }
}
