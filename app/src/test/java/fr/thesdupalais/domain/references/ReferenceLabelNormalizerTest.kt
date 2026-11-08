package fr.thesdupalais.domain.references

import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceLabelNormalizerTest {
    @Test
    fun `ignores case and collapses repeated whitespace`() {
        assertEquals(
            "pu'erh noir",
            ReferenceLabelNormalizer.normalize("  Pu'Erh   Noir  "),
        )
    }

    @Test
    fun `normalizes unicode to canonical composition`() {
        assertEquals(
            ReferenceLabelNormalizer.normalize("Thé vert"),
            ReferenceLabelNormalizer.normalize("The\u0301 vert"),
        )
    }
}
