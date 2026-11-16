package fr.thesdupalais.domain.model

import java.text.Collator
import java.util.Locale

/** French alphabetical ordering for user-facing choices, with a stable ID tie-breaker. */
fun <T> frenchLabelComparator(label: (T) -> String, stableId: (T) -> String): Comparator<T> {
    val collator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.PRIMARY }
    return Comparator { left, right ->
        collator.compare(label(left), label(right)).takeIf { it != 0 }
            ?: label(left).compareTo(label(right), ignoreCase = true).takeIf { it != 0 }
            ?: stableId(left).compareTo(stableId(right))
    }
}
