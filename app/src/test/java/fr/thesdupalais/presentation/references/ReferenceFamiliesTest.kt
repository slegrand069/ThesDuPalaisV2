package fr.thesdupalais.presentation.references

import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceSummary
import fr.thesdupalais.domain.model.ReferenceValue
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceFamiliesTest {
    @Test
    fun exposesAllNineFamiliesWithValidatedCategoryAndProfileLabels() {
        assertEquals(ReferenceKind.entries.toSet(), referenceFamilies.map { it.kind }.toSet())
        assertEquals(9, referenceFamilies.size)
        assertEquals("Catégories", ReferenceKind.CATEGORY.displayName())
        assertTrue(referenceFamilies.any { it.kind == ReferenceKind.AROMATIC_PROFILE && it.label == "Profils aromatiques" })
    }

    @Test
    fun geographyGroupsUseActiveTeaUsageAndSortAlphabetically() {
        val references = listOf(
            reference(ReferenceKind.COUNTRY, "Japon", total = 2, active = 1),
            reference(ReferenceKind.COUNTRY, "Inde", total = 1, active = 0),
            reference(ReferenceKind.COUNTRY, "Chine", total = 1, active = 1),
            reference(ReferenceKind.COUNTRY, "Brésil", total = 0, active = 0, archivedAt = 10L),
        )

        val sections = requireNotNull(geographicReferenceSections(ReferenceKind.COUNTRY, references))

        assertEquals("Utilisés dans ma collection", sections[0].title)
        assertEquals(listOf("Chine", "Japon"), sections[0].references.map { it.value.label })
        assertEquals("Autres pays", sections[1].title)
        assertEquals(listOf("Brésil", "Inde"), sections[1].references.map { it.value.label })
        assertEquals(2, sections[0].references.last().teaUsageCount)
    }

    @Test
    fun regionsShowTheirParentCountryInTheDisplayLabel() {
        val region = reference(
            ReferenceKind.REGION,
            "Shizuoka",
            parentCountryId = UUID.randomUUID(),
            parentCountryLabel = "Japon",
        )

        assertEquals("Shizuoka (Japon)", region.displayLabel())
    }

    private fun reference(
        kind: ReferenceKind,
        label: String,
        total: Int = 0,
        active: Int = 0,
        archivedAt: Long? = null,
        parentCountryId: UUID? = null,
        parentCountryLabel: String? = null,
    ) = ReferenceSummary(
        value = ReferenceValue(
            id = UUID.randomUUID(),
            kind = kind,
            label = label,
            normalizedLabel = label.lowercase(),
            parentCountryId = parentCountryId,
            archivedAt = archivedAt,
        ),
        teaUsageCount = total,
        activeTeaUsageCount = active,
        childRegionCount = 0,
        parentCountryLabel = parentCountryLabel,
    )
}
