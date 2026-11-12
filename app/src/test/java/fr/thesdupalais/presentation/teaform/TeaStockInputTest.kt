package fr.thesdupalais.presentation.teaform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import fr.thesdupalais.domain.quantity.parseOptionalGramsMilliGrams

class TeaStockInputTest {
    @Test fun blankMeansUnknownAndZeroRemainsKnownZero() {
        assertNull(parseOptionalGramsMilliGrams("  "))
        assertEquals(0L, parseOptionalGramsMilliGrams("0"))
    }

    @Test fun parsesCommaOrPointAndStoresGramsAsMilliGrams() {
        assertEquals(2_500L, parseOptionalGramsMilliGrams("2,5"))
        assertEquals(2_500L, parseOptionalGramsMilliGrams("2.5"))
        assertEquals(2_501L, parseOptionalGramsMilliGrams("2,501"))
    }

    @Test fun rejectsNegativeMalformedOverPreciseAndOverflowValues() {
        listOf("-1", "abc", "1,2345", "999999999999999999999999").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { parseOptionalGramsMilliGrams(input) }
        }
    }
}
