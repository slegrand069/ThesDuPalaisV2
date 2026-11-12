package fr.thesdupalais.domain.quantity

import java.math.BigDecimal

/** Parses a user-entered gram quantity into the database's milli-gram unit. */
fun parseOptionalGramsMilliGrams(input: String): Long? {
    val text = input.trim()
    if (text.isEmpty()) return null
    require(text.matches(Regex("(?:\\d+(?:[,.]\\d{0,3})?|[,.]\\d{1,3})"))) {
        "Saisissez un nombre de grammes avec au plus trois décimales."
    }
    val grams = BigDecimal(text.replace(',', '.'))
    require(grams.signum() >= 0) { "La quantité ne peut pas être négative." }
    return try {
        grams.movePointRight(3).longValueExact()
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("La quantité est trop élevée.", error)
    }
}

fun formatGrams(milliGrams: Long): String =
    BigDecimal.valueOf(milliGrams, 3).stripTrailingZeros().toPlainString().replace('.', ',')

fun parseCupCount(input: String): Int {
    val cups = input.trim().toIntOrNull()
    require(cups != null && cups > 0) { "Le nombre de tasses doit être un entier supérieur à zéro." }
    return cups
}

fun totalConsumedMilliGrams(gramsPerCupMilli: Long, cupCount: Int): Long {
    require(gramsPerCupMilli > 0) { "Le grammage par tasse doit être supérieur à zéro." }
    require(cupCount > 0) { "Le nombre de tasses doit être supérieur à zéro." }
    return try {
        Math.multiplyExact(gramsPerCupMilli, cupCount.toLong())
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("La quantité totale est trop élevée.", error)
    }
}
