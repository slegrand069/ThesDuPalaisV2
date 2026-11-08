package fr.thesdupalais.domain.references

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardReferenceSeedsTest {
    @Test
    fun `includes the approved standard categories and stable semantic identities`() {
        val categories = StandardReferenceSeeds.categories
        val byKey = categories.associateBy { it.semanticKey }

        assertEquals(
            setOf("GREEN", "BLACK", "WHITE", "YELLOW", "OOLONG", "PU_ERH", "INFUSION", "MATE", "OTHER"),
            byKey.keys,
        )
        assertEquals("Thé jaune", byKey.getValue("YELLOW").label)
        assertEquals("Pu’Erh", byKey.getValue("PU_ERH").label)
        assertEquals(byKey.getValue("PU_ERH").id, categories.single { it.semanticKey == "PU_ERH" }.id)
        assertNotEquals(byKey.getValue("PU_ERH").id, byKey.getValue("BLACK").id)
        assertTrue(categories.all { it.id.toString() == it.id.toString().lowercase() })
        assertEquals(6, StandardReferenceSeeds.statuses.size)
        assertEquals(5, StandardReferenceSeeds.containers.size)
        assertEquals(7, StandardReferenceSeeds.moments.size)
    }
}
