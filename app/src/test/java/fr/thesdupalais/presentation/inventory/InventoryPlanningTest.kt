package fr.thesdupalais.presentation.inventory

import fr.thesdupalais.data.database.TeaEntity
import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.TeaWithCatalogData
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryPlanningTest {
    private fun tea(id: UUID = UUID.randomUUID(), name: String = "Thé", stock: Long? = null, container: UUID? = null) =
        TeaWithCatalogData(
            tea = TeaEntity(id, null, name, UUID.randomUUID(), null, null, null, null, null, null, null, null, container, stock, null),
            category = ReferenceEntity(UUID.randomUUID(), "CATEGORY", "Thé", "the", null, null, "", null, 0), status = null, container = null, countries = emptyList(), regions = emptyList(), aromas = emptyList(),
            aromaticProfiles = emptyList(), moments = emptyList(), badges = emptyList(),
        )

    @Test fun searchIgnoresCaseAndAccents() {
        assertEquals("floral", normalizeInventorySearch("  FLORÁL "))
        assertTrue(InventoryUiState(teas = listOf(tea(name = "Thé floral")), query = "FLORAL").visibleTeas.isNotEmpty())
    }

    @Test fun blankStockIsNoChangeForUnknownStock() {
        val row = tea()
        assertTrue(buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft()))).isEmpty())
    }

    @Test fun blankStockDoesNotTurnUnknownIntoZero() {
        val row = tea(stock = null)
        assertTrue(buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(stockInput = "")))).isEmpty())
    }

    @Test fun explicitZeroIsARealStockChange() {
        val row = tea(stock = null)
        val result = buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(stockInput = "0"))))
        assertEquals(1, result.size); assertEquals(0L, result.single().stockMilliGrams); assertTrue(result.single().changeStock)
    }

    @Test fun absoluteStockIsParsedInMilliGrams() {
        val row = tea(stock = 12_000)
        val result = buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(stockInput = "3,125"))))
        assertEquals(3_125L, result.single().stockMilliGrams)
    }

    @Test fun unchangedStockIsOmitted() {
        val row = tea(stock = 5_000)
        assertTrue(buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(stockInput = "5")))).isEmpty())
    }

    @Test fun invalidStockRejectsWholePlan() {
        val one = tea(); val two = tea()
        assertThrows(IllegalArgumentException::class.java) {
            buildInventoryChanges(InventoryUiState(teas = listOf(one, two), drafts = mapOf(one.tea.id to InventoryDraft(stockInput = "4"), two.tea.id to InventoryDraft(stockInput = "-2"))))
        }
    }

    @Test fun filteredOutDraftStillIncludedInPlan() {
        val hidden = tea(name = "Oolong"); val shown = tea(name = "Vert")
        val state = InventoryUiState(teas = listOf(hidden, shown), query = "Vert", drafts = mapOf(hidden.tea.id to InventoryDraft(stockInput = "8")))
        assertEquals(1, state.visibleTeas.size)
        assertEquals(hidden.tea.id, buildInventoryChanges(state).single().teaId)
    }

    @Test fun containerClearIsRepresentedSeparatelyFromNoChange() {
        val row = tea(container = UUID.randomUUID())
        val change = buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(containerId = null, containerChanged = true)))).single()
        assertTrue(change.changeContainer); assertNull(change.containerId)
    }

    @Test fun containerResetToOriginalIsNoOp() {
        val container = UUID.randomUUID(); val row = tea(container = container)
        assertTrue(buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(containerId = container, containerChanged = true)))).isEmpty())
    }

    @Test fun nameMatchesThroughAccents() {
        val row = tea(name = "Thé de Noël")
        assertEquals(1, InventoryUiState(teas = listOf(row), query = "noel").visibleTeas.size)
    }

    @Test fun untouchedDraftDoesNotProduceWrite() {
        val row = tea(stock = 2_000)
        assertFalse(InventoryUiState(teas = listOf(row)).hasChanges)
        assertTrue(buildInventoryChanges(InventoryUiState(teas = listOf(row))).isEmpty())
    }

    @Test fun explicitZeroDiffersFromUnknown() {
        val row = tea(stock = null)
        val result = buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(stockInput = "0"))))
        assertEquals(0L, result.single().stockMilliGrams)
        assertTrue(result.single().changeStock)
    }

    @Test fun fractionalGramsAreConvertedExactly() {
        val row = tea()
        val result = buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(stockInput = "49,999"))))
        assertEquals(49_999L, result.single().stockMilliGrams)
    }

    @Test fun fiftyGramsRemainFiftyThousandMilliGrams() {
        val row = tea()
        val result = buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(stockInput = "50"))))
        assertEquals(50_000L, result.single().stockMilliGrams)
    }

    @Test fun excessivePrecisionIsRejected() {
        val row = tea()
        assertThrows(IllegalArgumentException::class.java) {
            buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(stockInput = "1,0001"))))
        }
    }

    @Test fun negativeStockIsRejected() {
        val row = tea()
        assertThrows(IllegalArgumentException::class.java) {
            buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(stockInput = "-0,1"))))
        }
    }

    @Test fun staleDraftDoesNotGetSilentlyDropped() {
        val row = tea()
        assertThrows(IllegalStateException::class.java) {
            buildInventoryChanges(InventoryUiState(teas = emptyList(), drafts = mapOf(row.tea.id to InventoryDraft(stockInput = "1"))))
        }
    }

    @Test fun changingContainerAndStockProducesOneTeaChange() {
        val row = tea(stock = 1_000)
        val selected = UUID.randomUUID()
        val result = buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(selected, true, "2"))))
        assertEquals(1, result.size)
        assertTrue(result.single().changeContainer)
        assertTrue(result.single().changeStock)
        assertEquals(selected, result.single().containerId)
        assertEquals(2_000L, result.single().stockMilliGrams)
    }

    @Test fun draftsSurviveQueryChangesBecauseQueryDoesNotRewriteDraftMap() {
        val row = tea(name = "Pu Erh")
        val draft = InventoryDraft(stockInput = "12")
        val state = InventoryUiState(teas = listOf(row), query = "inexistant", drafts = mapOf(row.tea.id to draft))
        assertTrue(state.visibleTeas.isEmpty())
        assertEquals(draft, state.drafts[row.tea.id])
        assertEquals(row.tea.id, buildInventoryChanges(state).single().teaId)
    }

    @Test fun emptySearchShowsAllActiveRows() {
        val rows = listOf(tea(name = "Thé A"), tea(name = "Thé B"))
        assertEquals(2, InventoryUiState(teas = rows, query = "  ").visibleTeas.size)
    }

    @Test fun stockOnlyEditLeavesContainerUnchanged() {
        val original = UUID.randomUUID()
        val row = tea(stock = 1_000, container = original)
        val result = buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(stockInput = "2")))).single()
        assertFalse(result.changeContainer)
        assertEquals(original, result.containerId)
    }

    @Test fun knownStocksArePrefilledInGrams() {
        assertEquals("46", inventoryStockInput(46_000, null))
        assertEquals("0", inventoryStockInput(0, null))
        assertEquals("65,8", inventoryStockInput(65_800, null))
    }

    @Test fun unknownStockStartsBlank() {
        assertEquals("", inventoryStockInput(null, null))
    }

    @Test fun clearingKnownStockIsRejectedInsteadOfErasingIt() {
        val row = tea(stock = 46_000)
        assertThrows(IllegalArgumentException::class.java) {
            buildInventoryChanges(InventoryUiState(teas = listOf(row), drafts = mapOf(row.tea.id to InventoryDraft(stockInput = ""))))
        }
    }
}
