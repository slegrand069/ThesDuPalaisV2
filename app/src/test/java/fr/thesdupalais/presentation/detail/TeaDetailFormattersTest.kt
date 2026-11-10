package fr.thesdupalais.presentation.detail

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TeaDetailFormattersTest {
    @Test fun formatsTastingInstantInFrenchLocalDateAndTime() {
        val instant = ZonedDateTime.of(2026, 11, 1, 14, 30, 0, 0, ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()

        assertEquals("1 novembre 2026 à 14:30", formatTastingDateTime(instant, ZoneId.of("Europe/Paris"), Locale.FRANCE))
    }

    @Test fun formatsKnownConsumedQuantityAndNullableRatingsWithoutRounding() {
        assertEquals("1,25 g", formatConsumedQuantity(1_250L))
        assertEquals("7 g", formatConsumedQuantity(7_000L))
        assertEquals("8,5", formatDetailRating(8.5))
        assertEquals("8,3", formatDetailRating(8.300000190734863))
        assertEquals("8,375", formatDetailRating(8.375))
    }

    @Test fun formatsInfusionDurationsInMinutesAndSeconds() {
        assertEquals("45 s", formatInfusionDuration(45))
        assertEquals("2 min", formatInfusionDuration(120))
        assertEquals("2 min 30", formatInfusionDuration(150))
    }
}
