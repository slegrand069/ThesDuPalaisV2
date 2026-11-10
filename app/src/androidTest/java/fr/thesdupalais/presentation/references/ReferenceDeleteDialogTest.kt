package fr.thesdupalais.presentation.references

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.thesdupalais.data.database.ReferenceDeletionStatus
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceSummary
import fr.thesdupalais.domain.model.ReferenceValue
import fr.thesdupalais.presentation.theme.ThesDuPalaisTheme
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReferenceDeleteDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun unusedCustomReferenceShowsFigmaConfirmationAndCancelDoesNotConfirm() {
        val id = UUID.randomUUID()
        var confirmed = false
        var dismissed = false
        composeRule.setContent {
            ThesDuPalaisTheme {
                ReferenceListContent(
                    state = state(id, ReferenceDeletionStatus.Eligible),
                    onBack = {}, onConfirmDelete = { confirmed = true }, onDismissDelete = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Supprimer cette valeur ?").assertIsDisplayed()
        composeRule.onNodeWithText("« Valeur fictive » n’est liée à aucun thé. La suppression sera définitive.").assertIsDisplayed()
        composeRule.onNodeWithTag("reference-delete-cancel").performClick()
        assertTrue(dismissed)
        assertTrue(!confirmed)
    }

    @Test fun usedValueAndSeedManagedValueShowClearNonDestructiveMessages() {
        val id = UUID.randomUUID()
        composeRule.setContent {
            ThesDuPalaisTheme {
                ReferenceListContent(
                    state = state(id, ReferenceDeletionStatus.InUse(2)),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Suppression impossible").assertIsDisplayed()
        composeRule.onNodeWithText("« Valeur fictive » est associée à 2 thés.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("ne peut pas être supprimée tant qu’elle est utilisée.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("reference-delete-understood").performClick()
    }

    @Test fun standardReferenceProtectionExplainsRenameRemainsAvailable() {
        val id = UUID.randomUUID()
        composeRule.setContent {
            ThesDuPalaisTheme {
                ReferenceListContent(
                    state = state(id, ReferenceDeletionStatus.Standard),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Référence standard protégée").assertIsDisplayed()
        composeRule.onNodeWithText("« Valeur fictive » est gérée par les références standard et ne peut pas être supprimée. Vous pouvez la renommer.").assertIsDisplayed()
    }

    private fun state(id: UUID, status: ReferenceDeletionStatus) = ReferenceListUiState(
        kind = ReferenceKind.AROMA,
        references = listOf(
            ReferenceSummary(
                value = ReferenceValue(id, ReferenceKind.AROMA, "Valeur fictive", "valeur fictive"),
                teaUsageCount = 0, activeTeaUsageCount = 0, childRegionCount = 0, parentCountryLabel = null,
            ),
        ),
        isLoading = false,
        deletePrompt = ReferenceDeletePrompt(id, "Valeur fictive", status),
    )
}
