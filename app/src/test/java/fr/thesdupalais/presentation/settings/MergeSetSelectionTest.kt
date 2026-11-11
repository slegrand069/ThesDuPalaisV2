package fr.thesdupalais.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeSetSelectionTest {
    @Test fun selectingAndDeselectingSetKeepsOtherChoices() {
        val initial = setOf("teas")
        val withConfiguration = toggleMergeSet(initial, "configuration")
        assertEquals(setOf("teas", "configuration"), withConfiguration)
        assertEquals(initial, toggleMergeSet(withConfiguration, "configuration"))
    }
}
