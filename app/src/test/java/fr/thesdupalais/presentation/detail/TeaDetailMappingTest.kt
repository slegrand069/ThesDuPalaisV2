package fr.thesdupalais.presentation.detail

import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.TeaEntity
import fr.thesdupalais.data.database.TeaWithDetailData
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeaDetailMappingTest {
    @Test fun mapsAllReferenceAssociationsAndNullableTeaFieldsWithoutSyntheticValues() {
        val countryId = UUID.randomUUID()
        val category = reference("CATEGORY", "Thé vert", semanticKey = "GREEN")
        val country = reference("COUNTRY", "Japon", id = countryId)
        val region = reference("REGION", "Shizuoka", parentCountryId = countryId)
        val tea = TeaEntity(
            id = UUID.randomUUID(), legacyId = 23, name = "Sencha fictif", categoryRefId = category.id,
            description = "Description", technicalInformation = "Détail technique", personalNotes = "Note personnelle",
            olfactoryRating = 9.0, tasteRating = 8.5, brewTemperatureCelsius = 75,
            infusionDurationSeconds = 150, statusRefId = null, containerRefId = null,
            stockMilliGrams = null, archivedAtEpochMillis = null,
        )

        val result = TeaWithDetailData(
            tea = tea,
            category = category,
            status = null,
            container = null,
            countries = listOf(country),
            regions = listOf(region),
            aromas = listOf(reference("AROMA", "Herbe coupée")),
            aromaticProfiles = listOf(reference("AROMATIC_PROFILE", "Végétal")),
            moments = listOf(reference("MOMENT", "Matin")),
            badges = listOf(reference("BADGE", "Bio")),
        ).toTeaDetail()

        assertEquals("Sencha fictif", result.name)
        assertEquals("Thé vert", result.category)
        assertEquals(listOf("Japon — Shizuoka"), result.origins)
        assertNull(result.personalStatus)
        assertNull(result.container)
        assertEquals("Note personnelle", result.personalNotes)
        assertEquals("Description", result.description)
        assertEquals("Détail technique", result.technicalInformation)
        assertEquals(8.5, result.tasteRating!!, 0.0)
        assertEquals(9.0, result.olfactoryRating!!, 0.0)
        assertEquals(listOf("Herbe coupée"), result.aromas)
        assertEquals(listOf("Végétal"), result.aromaticProfiles)
        assertEquals(listOf("Matin"), result.moments)
        assertEquals(listOf("Bio"), result.badges)
        assertNull(result.stockMilliGrams)
    }

    private fun reference(
        kind: String,
        label: String,
        id: UUID = UUID.randomUUID(),
        parentCountryId: UUID? = null,
        semanticKey: String? = null,
    ) = ReferenceEntity(
        id = id,
        kind = kind,
        label = label,
        normalizedLabel = label.lowercase(),
        semanticKey = semanticKey,
        parentCountryId = parentCountryId,
        scopeKey = parentCountryId?.toString().orEmpty(),
        archivedAt = null,
        displayPriority = 0,
    )
}
