package fr.thesdupalais.domain.semantics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SemanticAssociationTest {
    private fun concept(id: String, label: String, value: String, active: Boolean = true) = SemanticConcept(
        id, SemanticDomain.AROMA, label, active, "AROMA", "aroma", value,
    )

    @Test fun acceptsSupportedWeightsAndDefaultsToMedium() {
        assertEquals(50, SemanticAssociationStrength.MEDIUM.weightPercent)
        listOf(25, 50, 75).forEach { SemanticAssociationStrength.fromWeight(it) }
        assertThrows(IllegalArgumentException::class.java) { SemanticAssociationStrength.fromWeight(40) }
    }

    @Test fun rejectsSelfDuplicateInactiveAndCrossDomainAssociations() {
        val source = concept("a", "Gourmand", "FRUITY")
        val target = concept("b", "Vanillé", "WOODY")
        val association = SemanticAssociation("id", "a", "b", 50)
        assertThrows(IllegalArgumentException::class.java) {
            SemanticDictionaryValidator.validateAssociation(source, source, 50, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SemanticDictionaryValidator.validateAssociation(source, target, 50, listOf(association))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SemanticDictionaryValidator.validateAssociation(source, target.copy(active = false), 50, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SemanticDictionaryValidator.validateAssociation(source.copy(domain = SemanticDomain.CATEGORY), target, 50, emptyList())
        }
    }

    @Test fun directionIsExplicitAndNoAssociationGraphExpansionIsProvided() {
        val association = SemanticAssociation("id", "general", "specific", 75)
        assertEquals("general", association.sourceConceptId)
        assertEquals("specific", association.targetConceptId)
        val reverse = SemanticAssociation("reverse", "specific", "general", 25)
        assertEquals("specific", reverse.sourceConceptId)
        assertEquals("general", reverse.targetConceptId)
    }
}
