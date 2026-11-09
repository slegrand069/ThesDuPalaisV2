package fr.thesdupalais.presentation.detail

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatTastingDateTime(
    occurredAtEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.FRANCE,
): String = DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", locale)
    .withZone(zoneId)
    .format(Instant.ofEpochMilli(occurredAtEpochMillis))

internal fun formatConsumedQuantity(milliGrams: Long): String =
    BigDecimal.valueOf(milliGrams, 3).stripTrailingZeros().toPlainString().replace('.', ',') + " g"

internal fun formatDetailRating(rating: Double): String =
    BigDecimal.valueOf(rating).stripTrailingZeros().toPlainString().replace('.', ',')

internal fun formatInfusionDuration(seconds: Int): String = when {
    seconds < 60 -> "$seconds s"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${seconds % 60}"
}
