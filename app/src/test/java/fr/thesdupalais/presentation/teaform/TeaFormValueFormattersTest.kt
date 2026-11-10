package fr.thesdupalais.presentation.teaform

import org.junit.Assert.assertEquals
import org.junit.Test

class TeaFormValueFormattersTest {
    @Test fun ratingSliderStoresAndDisplaysTheSameTenth() {
        val stored = ratingFromSliderPosition(8.3f)

        assertEquals(8.3, stored, 0.0)
        assertEquals("8,3", formatRatingValue(stored))
        assertEquals("0", formatRatingValue(ratingFromSliderPosition(0f)))
        assertEquals("10", formatRatingValue(ratingFromSliderPosition(10f)))
    }

    @Test fun temperatureSliderSnapsToFiveDegreeStepsAndIncludesBounds() {
        assertEquals(60, temperatureFromSliderPosition(60f))
        assertEquals(65, temperatureFromSliderPosition(64f))
        assertEquals(65, temperatureFromSliderPosition(65f))
        assertEquals(100, temperatureFromSliderPosition(100f))
        assertEquals(60, temperatureFromSliderPosition(10f))
        assertEquals(100, temperatureFromSliderPosition(120f))
    }
}
