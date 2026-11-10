package fr.thesdupalais.presentation.settings

import java.math.BigDecimal
import java.math.RoundingMode

internal fun parseGramsToMilli(raw: String, allowZero: Boolean): Long {
    val value = raw.trim().replace(',', '.')
    require(value.isNotEmpty()) { "Saisissez une valeur en grammes." }
    require(GRAMS_PATTERN.matches(value)) {
        "Saisissez un nombre valide, avec au plus trois décimales."
    }
    val grams = try {
        BigDecimal(value)
    } catch (_: NumberFormatException) {
        throw IllegalArgumentException("Saisissez un nombre valide, avec au plus trois décimales.")
    }
    require(grams.stripTrailingZeros().scale().coerceAtLeast(0) <= 3) {
        "Saisissez un nombre valide, avec au plus trois décimales."
    }
    val milliGrams = try {
        grams.movePointRight(3).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("La valeur saisie est trop élevée.")
    }
    require(if (allowZero) milliGrams >= 0 else milliGrams > 0) {
        if (allowZero) "Le seuil de stock ne peut pas être négatif."
        else "Le grammage par tasse doit être supérieur à 0 g."
    }
    return milliGrams
}

internal fun formatMilliGramsAsGrams(value: Long): String =
    "${BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString().replace('.', ',')} g"

internal fun formatMilliGramsForInput(value: Long): String =
    BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString().replace('.', ',')

private val GRAMS_PATTERN = Regex("[+-]?(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)")
