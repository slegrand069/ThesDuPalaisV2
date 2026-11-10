package fr.thesdupalais.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceAmountInputTest {
    @Test fun parsesFrenchAndEnglishDecimalSeparatorsIntoMilligrams() {
        assertEquals(7_500L, parseGramsToMilli("7,5", allowZero = false))
        assertEquals(125L, parseGramsToMilli("0.125", allowZero = false))
        assertEquals(0L, parseGramsToMilli("0", allowZero = true))
    }

    @Test fun formatsMilligramsWithoutLosingPrecision() {
        assertEquals("7 g", formatMilliGramsAsGrams(7_000))
        assertEquals("7,125 g", formatMilliGramsAsGrams(7_125))
    }

    @Test fun rejectsInvalidRangesPrecisionAndOverflow() {
        assertFailsWith("supérieur") { parseGramsToMilli("0", allowZero = false) }
        assertFailsWith("négatif") { parseGramsToMilli("-0,1", allowZero = true) }
        assertFailsWith("trois décimales") { parseGramsToMilli("0,0001", allowZero = true) }
        assertFailsWith("élevée") { parseGramsToMilli("999999999999999999999999", allowZero = true) }
    }

    private fun assertFailsWith(expectedMessage: String, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue("Expected IllegalArgumentException", error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains(expectedMessage, ignoreCase = true))
    }
}
