package fr.thesdupalais.presentation.catalogue

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
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
            ThesDuPalaisTheme { CatalogueContent(CatalogueUiState.Empty) }
        }

        composeRule.onNodeWithText("Votre collection est vide").assertIsDisplayed()
        composeRule.onNodeWithText("Aucun thé n'est enregistré dans votre collection.").assertIsDisplayed()
        composeRule.onNodeWithTag("catalogue-search-disabled").assertIsNotEnabled()
        composeRule.onNodeWithTag("catalogue-add-disabled").assertIsNotEnabled()
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
        composeRule.onNodeWithTag("catalogue-search-disabled").assertIsNotEnabled()
        composeRule.onNodeWithText("Sencha test").assertIsDisplayed()
        composeRule.onNodeWithText("Thé vert").assertIsDisplayed()
        composeRule.onNodeWithText("Thé vert - Japon — Uji, Shizuoka · Inde").assertIsDisplayed()
        composeRule.onNodeWithText("★ 9/10").assertIsDisplayed()
        composeRule.onNodeWithTag("catalogue-placeholder-GREEN").assertIsDisplayed()
        composeRule.onAllNodesWithText("Notes personnelles").assertCountEquals(0)
        composeRule.onNodeWithTag("catalogue-tea-$id").assertHasClickAction().performClick()
        assertEquals(id, selectedTeaId)
    }

    @Test fun categoryWithoutIllustratedPlaceholderUsesGenericFallback() {
        val id = UUID.fromString("00000000-0000-4000-8000-000000000002")
        composeRule.setContent {
            ThesDuPalaisTheme {
                CatalogueContent(
                    CatalogueUiState.Content(
                        listOf(CatalogueTea(id, "Maté test", "Maté", "MATE", emptyList(), null, null)),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("catalogue-placeholder-MATE").assertIsDisplayed()
        composeRule.onNodeWithText("Maté").assertIsDisplayed()
    }
}
