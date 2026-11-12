package fr.thesdupalais.domain.quantity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TeaQuantitiesTest {
    @Test fun parsesCupCountAndCalculatesTotalMilliGrams() {
        assertEquals(2, parseCupCount(" 2 "))
        assertEquals(15_000L, totalConsumedMilliGrams(7_500L, 2))
    }

    @Test fun cupCountMustBePositiveInteger() {
        listOf("", "0", "-1", "1,5", "abc").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { parseCupCount(input) }
        }
    }

    @Test fun totalQuantityRejectsInvalidInputsAndOverflow() {
        assertThrows(IllegalArgumentException::class.java) { totalConsumedMilliGrams(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { totalConsumedMilliGrams(1_000, 0) }
        assertThrows(IllegalArgumentException::class.java) { totalConsumedMilliGrams(Long.MAX_VALUE, 2) }
    }
}
