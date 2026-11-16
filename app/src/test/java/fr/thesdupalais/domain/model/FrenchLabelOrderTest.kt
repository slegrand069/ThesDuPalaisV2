package fr.thesdupalais.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FrenchLabelOrderTest {
    private data class Choice(val id: String, val label: String)

    @Test fun sortsFrenchLabelsIgnoringCaseAndAccentsWithStableTieBreak() {
        val values = listOf(Choice("2", "Épicé"), Choice("1", "epice"), Choice("4", "Fruité"), Choice("3", "amer"))
        assertEquals(listOf("amer", "epice", "Épicé", "Fruité"), values.sortedWith(frenchLabelComparator(Choice::label, Choice::id)).map(Choice::label))
        assertEquals(listOf("1", "2"), values.filter { it.label.contains("pic", ignoreCase = true) }.sortedWith(frenchLabelComparator(Choice::label, Choice::id)).map(Choice::id))
    }
}
