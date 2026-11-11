package fr.thesdupalais.presentation.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import fr.thesdupalais.data.backup.BackupArchiveMergePlan
import fr.thesdupalais.data.backup.BackupArchiveMergePreviewOptions
import fr.thesdupalais.data.backup.MergeDisposition
import fr.thesdupalais.data.backup.MergeItem
import fr.thesdupalais.presentation.theme.ThesDuPalaisTheme
import org.junit.Rule
import org.junit.Test

class BackupMergePreviewSectionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun selectingSetThroughCheckboxRecalculatesMatchingPlan() {
        val options = options(
            plan(setOf("teas"), listOf(MergeItem("Thé", "tea-1", MergeDisposition.ADD))),
            plan(setOf("configuration"), listOf(MergeItem("Préférences métier", "configuration", MergeDisposition.IDENTICAL))),
            plan(setOf("teas", "configuration"), listOf(MergeItem("Conflit", "ref-1", MergeDisposition.CONFLICT, details = listOf("Collision bloquante.")))),
        )
        composeRule.setContent { ThesDuPalaisTheme { MergePlanChooser(options, setOf("teas", "configuration")) } }

        composeRule.onNodeWithTag("merge-preview-blocked").assertIsDisplayed()
        composeRule.onNodeWithText("Collision bloquante.").assertIsDisplayed()
        composeRule.onNodeWithTag("merge-preview-select-configuration").performTouchInput { click() }
        composeRule.onAllNodesWithTag("merge-preview-blocked").assertCountEquals(0)
        composeRule.onNodeWithText("Plan sélectionné : Thés, associations, dégustations, mouvements et références nécessaires").assertIsDisplayed()
        composeRule.onNodeWithText("Ajouts (1)").assertIsDisplayed()
        composeRule.onNodeWithTag("merge-preview-confirmable").assertIsDisplayed()
    }

    @Test fun changesAreHumanReadableIdenticalsCollapsedAndTechnicalDataHiddenByDefault() {
        val proposed = MergeItem(
            "Préférences métier", "configuration", MergeDisposition.UPDATE_PROPOSED,
            changedFields = listOf("quantityPerCupMilliGrams"),
            details = listOf("quantityPerCupMilliGrams : 7000 → 8000 (confirmation requise)."),
        )
        val preview = options(plan(setOf("configuration"), listOf(
            MergeItem("Thé", "tea-identical", MergeDisposition.IDENTICAL),
            proposed,
            MergeItem("Référence", "ref-1", MergeDisposition.CONFLICT, details = listOf("Collision de type incompatible.")),
        )))
        composeRule.setContent { ThesDuPalaisTheme { MergePlanChooser(preview, setOf("configuration")) } }
        composeRule.onNodeWithText("Modifications proposées · confirmation requise (1)").assertIsDisplayed()
        composeRule.onNodeWithText("Quantité par tasse").assertIsDisplayed()
        composeRule.onNodeWithText("Avant : 7000").assertIsDisplayed()
        composeRule.onNodeWithText("Après : 8000 (confirmation requise)").assertIsDisplayed()
        composeRule.onNodeWithText("Éléments identiques : 1").assertIsDisplayed()
        composeRule.onAllNodesWithTag("merge-item-tea-identical").assertCountEquals(0)
        composeRule.onAllNodesWithTag("merge-preview-archive-hash").assertCountEquals(0)
        composeRule.onNodeWithTag("merge-preview-technical-toggle").performClick()
        composeRule.onNodeWithTag("merge-preview-archive-hash").assertIsDisplayed()
        composeRule.onNodeWithTag("merge-preview-unsupported-articles").assertIsDisplayed()
    }

    @Test fun conflictsAppearBeforeChangesAndIdenticalsHaveNoDetailCard() {
        val preview = options(plan(setOf("teas"), listOf(
            MergeItem("Thé", "tea-1", MergeDisposition.UPDATE_PROPOSED, details = listOf("name : Local → Archive.")),
            MergeItem("Thé", "tea-2", MergeDisposition.IDENTICAL),
            MergeItem("Référence", "ref-1", MergeDisposition.CONFLICT, details = listOf("Collision bloquante.")),
        )))
        composeRule.setContent { ThesDuPalaisTheme { MergePlanChooser(preview, setOf("teas")) } }
        composeRule.onNodeWithTag("merge-item-ref-1").assertIsDisplayed()
        composeRule.onAllNodesWithTag("merge-item-tea-2").assertCountEquals(0)
        composeRule.onNodeWithText("Éléments identiques : 1").assertIsDisplayed()
        composeRule.onNodeWithText("Conflits bloquants (1)").assertIsDisplayed()
        composeRule.onNodeWithText("Modifications proposées · confirmation requise (1)").assertIsDisplayed()
    }

    private fun options(vararg plans: BackupArchiveMergePlan) = BackupArchiveMergePreviewOptions(
        backupId = "123e4567-e89b-12d3-a456-426614174000",
        createdAt = "2026-01-01T00:00:00Z",
        includedSets = setOf("teas", "configuration", "articles"),
        plans = plans.toList(),
        unsupportedSets = setOf("articles"),
    )

    private fun plan(sets: Set<String>, items: List<MergeItem>) = BackupArchiveMergePlan(
        archiveSha256 = "a".repeat(64), localSnapshotSha256 = "b".repeat(64), selectedSets = sets,
        items = items, blockingConflicts = items.filter { it.disposition == MergeDisposition.CONFLICT }.flatMap { it.details },
        limitations = listOf("Prévisualisation seule."),
    )
}
