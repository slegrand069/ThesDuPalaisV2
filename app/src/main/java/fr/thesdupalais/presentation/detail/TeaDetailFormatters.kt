package fr.thesdupalais.presentation.detail

import fr.thesdupalais.domain.quantity.formatGrams
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

internal fun formatTastingDateTime(
    occurredAtEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.FRANCE,
): String = DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", locale)
    .withZone(zoneId)
    .format(Instant.ofEpochMilli(occurredAtEpochMillis))

internal fun formatTastingDateInput(
    occurredAtEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = DateTimeFormatter.ofPattern("dd/MM/uuuu")
    .withZone(zoneId)
    .format(Instant.ofEpochMilli(occurredAtEpochMillis))

internal fun formatTastingTimeInput(
    occurredAtEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = DateTimeFormatter.ofPattern("HH:mm")
    .withZone(zoneId)
    .format(Instant.ofEpochMilli(occurredAtEpochMillis))

internal fun parseTastingDateTimeInput(
    dateInput: String,
    timeInput: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long {
    val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
    return try {
        val date = LocalDate.parse(dateInput.trim(), dateFormatter)
        val time = LocalTime.parse(timeInput.trim(), timeFormatter)
        LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli()
    } catch (error: DateTimeException) {
        throw IllegalArgumentException("Saisissez une date au format jj/mm/aaaa et une heure au format hh:mm.", error)
    }
}

internal fun formatConsumedQuantity(milliGrams: Long): String =
    formatGrams(milliGrams) + " g"

internal fun formatStockQuantity(milliGrams: Long): String =
    formatGrams(milliGrams) + " g"

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
