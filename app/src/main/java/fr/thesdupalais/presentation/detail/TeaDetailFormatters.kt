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

internal fun formatDetailRating(rating: Double): String {
    val value = BigDecimal.valueOf(rating)
    val nearestTenth = value.setScale(1, java.math.RoundingMode.HALF_UP)
    val normalized = if (value.subtract(nearestTenth).abs() <= BigDecimal("0.000001")) nearestTenth else value
    return normalized.stripTrailingZeros().toPlainString().replace('.', ',')
}

internal fun formatInfusionDuration(seconds: Int): String = when {
    seconds < 60 -> "$seconds s"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${seconds % 60}"
}
