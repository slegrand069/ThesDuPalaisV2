package fr.thesdupalais.presentation.teaform

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

/** Convertit les positions du slider en dixièmes exacts, sans conserver l'imprécision du Float. */
internal fun ratingFromSliderPosition(position: Float): Double =
    (position.coerceIn(0f, 10f) * 10f).roundToInt() / 10.0

internal fun formatRatingValue(value: Double): String =
    BigDecimal.valueOf(value)
        .setScale(1, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
        .replace('.', ',')

internal fun temperatureFromSliderPosition(position: Float): Int {
    val stepsFromMinimum = ((position.coerceIn(60f, 100f) - 60f) / 5f).roundToInt()
    return (60 + stepsFromMinimum * 5).coerceIn(60, 100)
}
