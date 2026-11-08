package fr.thesdupalais.domain.references

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardReferenceSeedsTest {
    @Test
    fun `includes the approved standard colors and stable semantic identities`() {
        val colors = StandardReferenceSeeds.colors
        val byKey = colors.associateBy { it.semanticKey }

        assertEquals(
            setOf("GREEN", "BLACK", "WHITE", "YELLOW", "OOLONG", "PU_ERH"),
            byKey.keys,
        )
        assertEquals("Jaune", byKey.getValue("YELLOW").label)
        assertEquals("Pu'Erh", byKey.getValue("PU_ERH").label)
        assertEquals(byKey.getValue("PU_ERH").id, colors.single { it.semanticKey == "PU_ERH" }.id)
        assertNotEquals(byKey.getValue("PU_ERH").id, byKey.getValue("BLACK").id)
        assertTrue(colors.all { it.id.toString() == it.id.toString().lowercase() })
    }
}
