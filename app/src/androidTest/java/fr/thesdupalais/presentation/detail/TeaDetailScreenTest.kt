package fr.thesdupalais.presentation.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import fr.thesdupalais.presentation.theme.ThesDuPalaisTheme
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TeaDetailScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun showsRealDetailFieldsAndHistoricalTastingsWithoutWriteActions() {
        val occurredAt = ZonedDateTime.of(2026, 11, 1, 14, 30, 0, 0, ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
        var backPressed = false
        var editedTeaId: UUID? = null
        composeRule.setContent {
            ThesDuPalaisTheme {
                TeaDetailContent(
                    TeaDetailUiState.Content(
                        tea = detail().copy(stockMilliGrams = 12_500L),
                        tastings = listOf(TeaTasting(UUID.randomUUID(), occurredAt, 1_250L)),
                    ),
                    onBack = { backPressed = true },
                    onEdit = { editedTeaId = it },
                )
            }
        }

        composeRule.onNodeWithText("Thé vert", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Statut : Dans ma collection", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Contenant : Boîte", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("tea-detail-stock").assertIsDisplayed()
        assertTextExists("12,5 g")
        assertTextExists("8,5 / 10")
        assertTextExists("9 / 10")
        assertTextExists("Notes personnelles")
        assertTextExists("Description de test")
        assertTextExists("Informations techniques de test")
        assertTextExists("Profils aromatiques")
        assertTextExists("Végétal")
        assertTextExists(formatTastingDateTime(occurredAt))
        assertTextExists("Quantité consommée · 1,25 g")
        composeRule.onNodeWithTag("tea-detail-edit").assertIsEnabled().performClick()
        org.junit.Assert.assertEquals(detail().id, editedTeaId)
        composeRule.onAllNodesWithText("STOCK & CONTENANT").assertCountEquals(0)
        composeRule.onAllNodesWithText("GALERIE PHOTOS").assertCountEquals(0)
        composeRule.onAllNodesWithText("En déguster un").assertCountEquals(0)
        composeRule.onAllNodesWithText("En stock").assertCountEquals(0)

        composeRule.onNodeWithTag("tea-detail-back").performClick()
        assertTrue(backPressed)
    }

    @Test fun hidesUnsetOptionalSectionsAndShowsEmptyTastingHistory() {
        composeRule.setContent {
            ThesDuPalaisTheme {
                TeaDetailContent(
                    TeaDetailUiState.Content(
                        tea = detail().copy(
                            personalStatus = null,
                            container = null,
                            description = null,
                            technicalInformation = null,
                            tasteRating = null,
                            olfactoryRating = null,
                            brewTemperatureCelsius = null,
                            infusionDurationSeconds = null,
                            personalNotes = null,
                            aromas = emptyList(),
                            aromaticProfiles = emptyList(),
                            moments = emptyList(),
                            badges = emptyList(),
                        ),
                        tastings = emptyList(),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Aucune dégustation enregistrée.").assertIsDisplayed()
        assertTextExists("Non renseigné")
        composeRule.onAllNodesWithTag("tea-detail-additional-information").assertCountEquals(0)
        composeRule.onAllNodesWithTag("tea-detail-preparation").assertCountEquals(0)
        composeRule.onAllNodesWithTag("tea-detail-aromas-moments").assertCountEquals(0)
        composeRule.onAllNodesWithText("Contenant : Boîte").assertCountEquals(0)
        composeRule.onAllNodesWithText("Quantité consommée").assertCountEquals(0)
    }

    @Test fun showsUnavailableForAbsentOrArchivedTeaState() {
        composeRule.setContent { ThesDuPalaisTheme { TeaDetailContent(TeaDetailUiState.Unavailable, onBack = {}) } }

        composeRule.onNodeWithText("Ce thé n’est pas disponible dans le catalogue.").assertIsDisplayed()
        composeRule.onNodeWithTag("tea-detail-edit").assertIsNotEnabled()
    }

    @Test fun distinguishesKnownZeroStockFromUnknownStock() {
        composeRule.setContent {
            ThesDuPalaisTheme {
                TeaDetailContent(
                    TeaDetailUiState.Content(detail().copy(stockMilliGrams = 0L), emptyList()),
                    onBack = {},
                )
            }
        }

        assertTextExists("0 g")
        composeRule.onAllNodesWithText("Non renseigné").assertCountEquals(0)
    }

    @Test fun drinkActionUsesEnteredDoseAndCupCountForOneTasting() {
        var recordedQuantity: Long? = null
        composeRule.setContent {
            ThesDuPalaisTheme {
                TeaDetailContent(
                    TeaDetailUiState.Content(detail(), emptyList()),
                    onBack = {},
                    defaultGramsPerCupMilli = 7_000L,
                    onRecordTasting = { recordedQuantity = it },
                )
            }
        }

        composeRule.onNodeWithTag("tea-detail-drink").performClick()
        composeRule.onNodeWithTag("tea-drink-grams-input").performTextClearance()
        composeRule.onNodeWithTag("tea-drink-grams-input").performTextInput("2,5")
        composeRule.onNodeWithTag("tea-drink-cups-input").performTextClearance()
        composeRule.onNodeWithTag("tea-drink-cups-input").performTextInput("2")
        composeRule.onNodeWithTag("tea-drink-confirm").performClick()
        composeRule.waitUntil(3_000) { recordedQuantity != null }

        org.junit.Assert.assertEquals(5_000L, recordedQuantity)
        composeRule.onAllNodesWithTag("tea-drink-dialog").assertCountEquals(0)
    }

    private fun detail() = TeaDetail(
        id = UUID.fromString("00000000-0000-4000-8000-000000000003"),
        name = "Sencha fictif",
        category = "Thé vert",
        origins = listOf("Japon — Shizuoka, Uji"),
        personalStatus = "Dans ma collection",
        container = "Boîte",
        description = "Description de test",
        technicalInformation = "Informations techniques de test",
        tasteRating = 8.5,
        olfactoryRating = 9.0,
        brewTemperatureCelsius = 75,
        infusionDurationSeconds = 150,
        personalNotes = "Note personnelle de test",
        aromas = listOf("Herbe coupée"),
        aromaticProfiles = listOf("Végétal"),
        moments = listOf("Matin"),
        badges = listOf("Bio"),
    )

    private fun assertTextExists(text: String) = composeRule.onAllNodesWithText(text).assertCountEquals(1)
}
