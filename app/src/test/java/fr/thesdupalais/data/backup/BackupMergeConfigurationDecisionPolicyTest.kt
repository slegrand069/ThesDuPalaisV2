package fr.thesdupalais.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergeConfigurationDecisionPolicyTest {
    private val preference = ConfirmedMergeChange("Préférences métier", "configuration", "quantityPerCupMilliGrams")
    private val standardReference = ConfirmedMergeChange("Référence standard", "ref-standard", "label")
    private val customReference = ConfirmedMergeChange("Référence personnalisée", "ref-custom", "displayPriority")
    private val tea = ConfirmedMergeChange("Thé", "tea-1", "name")

    @Test fun acceptAllSelectsEveryAdmissibleConfigurationProposalButNeverConflicts() {
        val plan = plan()
        val selected = BackupMergeConfigurationDecisionPolicy.acceptAll(plan, setOf(tea))

        assertEquals(setOf(tea, preference, standardReference, customReference), selected)
        assertFalse(ConfirmedMergeChange("Référence standard", "ref-conflict", "label") in selected)
    }

    @Test fun refuseAllRemovesConfigurationSelectionsAndPreservesTeaDecisions() {
        val plan = plan()
        val accepted = BackupMergeConfigurationDecisionPolicy.acceptAll(plan, setOf(tea))

        assertEquals(setOf(tea), BackupMergeConfigurationDecisionPolicy.refuseAll(plan, accepted))
    }

    @Test fun individualToggleAfterGlobalActionChangesOnlyThatAdmissibleChoice() {
        val plan = plan()
        val accepted = BackupMergeConfigurationDecisionPolicy.acceptAll(plan, setOf(tea))

        assertEquals(setOf(tea, preference, customReference), BackupMergeConfigurationDecisionPolicy.toggle(plan, accepted, standardReference))
        assertTrue(BackupMergeConfigurationDecisionPolicy.toggle(plan, accepted, ConfirmedMergeChange("Référence standard", "ref-conflict", "label")) == accepted)
    }

    private fun plan() = BackupArchiveMergePlan(
        archiveSha256 = "a".repeat(64), localSnapshotSha256 = "b".repeat(64), selectedSets = setOf("teas", "configuration"),
        items = listOf(
            MergeItem("Préférences métier", "configuration", MergeDisposition.UPDATE_PROPOSED, listOf(preference.field)),
            MergeItem("Référence standard", "ref-standard", MergeDisposition.PRESERVED, listOf(standardReference.field)),
            MergeItem("Référence personnalisée", "ref-custom", MergeDisposition.UPDATE_PROPOSED, listOf(customReference.field)),
            MergeItem("Référence standard", "ref-conflict", MergeDisposition.CONFLICT, listOf("label")),
            MergeItem("Référence standard", "ref-added", MergeDisposition.ADD, listOf("label")),
            MergeItem("Thé", "tea-1", MergeDisposition.UPDATE_PROPOSED, listOf("name")),
        ),
        blockingConflicts = listOf("collision incompatible"), limitations = emptyList(),
    )
}
