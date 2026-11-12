package fr.thesdupalais.presentation.teaform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TeaStockInputTest {
    @Test fun blankMeansUnknownAndZeroRemainsKnownZero() {
        assertNull(parseStockInput("  "))
        assertEquals(0L, parseStockInput("0"))
    }

    @Test fun parsesCommaOrPointAndStoresGramsAsMilliGrams() {
        assertEquals(2_500L, parseStockInput("2,5"))
        assertEquals(2_500L, parseStockInput("2.5"))
        assertEquals(2_501L, parseStockInput("2,501"))
    }

    @Test fun rejectsNegativeMalformedOverPreciseAndOverflowValues() {
        listOf("-1", "abc", "1,2345", "999999999999999999999999").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { parseStockInput(input) }
        }
    }
}
