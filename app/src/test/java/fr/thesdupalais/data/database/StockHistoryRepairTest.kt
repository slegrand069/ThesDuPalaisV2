package fr.thesdupalais.data.database

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockHistoryRepairTest {
    private val tea = UUID.fromString("20000000-0000-4000-8000-000000000001")
    private val firstMovement = UUID.fromString("20000000-0000-4000-8000-000000000002")
    private val secondMovement = UUID.fromString("20000000-0000-4000-8000-000000000003")
    private val repairId = UUID.fromString("20000000-0000-4000-8000-000000000004")

    @Test fun unknownStockRemainsUnknownAndNeedsNoMovement() {
        val plan = StockHistoryRepair.plan(tea, null, emptyList(), 100, ::nextId)
        assertTrue(plan.additions.isEmpty())
        assertNull(plan.problem)
    }

    @Test fun knownZeroWithoutHistoryGetsInventoryBaselineAtZero() {
        val plan = StockHistoryRepair.plan(tea, 0, emptyList(), 100, ::nextId)
        assertEquals("INVENTORY", plan.additions.single().kind)
        assertEquals(0L, plan.additions.single().stockAfterMilliGrams)
        assertNull(plan.additions.single().deltaMilliGrams)
    }

    @Test fun knownPositiveWithoutHistoryGetsOneReconciliationBaseline() {
        val plan = StockHistoryRepair.plan(tea, 12_500, emptyList(), 100, ::nextId)
        assertEquals(1, plan.additions.size)
        assertEquals(12_500L, plan.additions.single().stockAfterMilliGrams)
        assertEquals("Régularisation de l’état de stock préexistant", plan.additions.single().note)
    }

    @Test fun mismatchAfterAValidInventoryHistoryGetsCorrectionWithoutChangingOldRows() {
        val inventory = move("INVENTORY", null, 10_000, 10)
        val consumption = StockMovementEntity(secondMovement, tea, "CONSUMPTION", -1_000, 9_000, 20, 20, null, null)
        val plan = StockHistoryRepair.plan(tea, 8_500, listOf(inventory, consumption), 100, ::nextId)
        assertEquals(1, plan.additions.size)
        assertEquals("CORRECTION", plan.additions.single().kind)
        assertEquals(-500L, plan.additions.single().deltaMilliGrams)
        assertEquals(8_500L, plan.additions.single().stockAfterMilliGrams)
        assertEquals(firstMovement, inventory.id)
        assertEquals(secondMovement, consumption.id)
    }

    @Test fun repairIsIdempotentAndDoesNotDuplicateBaseline() {
        val first = StockHistoryRepair.plan(tea, 4_000, emptyList(), 100, ::nextId)
        val second = StockHistoryRepair.plan(tea, 4_000, first.additions, 200, ::nextId)
        assertEquals(1, first.additions.size)
        assertTrue(second.additions.isEmpty())
    }

    @Test fun movementHistoryWithoutAnInventoryBaselineIsLeftUntouchedForManualReview() {
        val existing = move("CONSUMPTION", -1_000, 9_000, 20)
        val plan = StockHistoryRepair.plan(tea, 9_000, listOf(existing), 100, ::nextId)
        assertTrue(plan.additions.isEmpty())
        assertFalse(plan.problem.isNullOrBlank())
    }

    private fun move(kind: String, delta: Long?, balance: Long, time: Long) = StockMovementEntity(
        firstMovement, tea, kind, delta, balance, time, time, null, null,
    )

    private fun nextId() = repairId
}
