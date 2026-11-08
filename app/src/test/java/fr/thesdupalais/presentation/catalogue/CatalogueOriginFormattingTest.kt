package fr.thesdupalais.presentation.catalogue

import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogueOriginFormattingTest {
    @Test
    fun groupsEveryRegionUnderItsCountryAndKeepsCountriesWithoutRegions() {
        val japan = reference(ReferenceKind.COUNTRY, "Japon", priority = 1)
        val india = reference(ReferenceKind.COUNTRY, "Inde", priority = 2)
        val uji = reference(ReferenceKind.REGION, "Uji", parent = japan.id, priority = 2)
        val shizuoka = reference(ReferenceKind.REGION, "Shizuoka", parent = japan.id, priority = 1)

        assertEquals(
            listOf("Japon — Shizuoka, Uji", "Inde"),
            formatOrigins(listOf(india, japan), listOf(uji, shizuoka)),
        )
    }

    @Test
    fun returnsNoOriginWhenNoCountryOrRegionIsAssociated() {
        assertEquals(emptyList<String>(), formatOrigins(emptyList(), emptyList()))
    }

    private fun reference(
        kind: ReferenceKind,
        label: String,
        parent: UUID? = null,
        priority: Int = 0,
    ): ReferenceEntity {
        val value = ReferenceValue(
            id = UUID.randomUUID(),
            kind = kind,
            label = label,
            normalizedLabel = ReferenceLabelNormalizer.normalize(label),
            semanticKey = null,
            parentCountryId = parent,
            archivedAt = null,
            displayPriority = priority,
        )
        return ReferenceEntity.fromDomain(value)
    }
}
