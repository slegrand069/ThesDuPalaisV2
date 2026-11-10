package fr.thesdupalais.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import fr.thesdupalais.domain.references.StandardReferenceSeeds
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceManagementDaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun managementQueryCountsEveryTeaAssociationWithoutFilteringReferenceArchiveTimestamps() = runBlocking {
        StandardReferenceSeeder(database).seed()
        val category = StandardReferenceSeeds.categories.single { it.semanticKey == "GREEN" }
        val status = StandardReferenceSeeds.statuses.first()
        val container = StandardReferenceSeeds.containers.first()
        val country = addReference(ReferenceKind.COUNTRY, "Japon")
        val region = addReference(ReferenceKind.REGION, "Shizuoka", parentCountryId = country.id)
        val aroma = addReference(ReferenceKind.AROMA, "Fleuri")
        val profile = addReference(ReferenceKind.AROMATIC_PROFILE, "Floral", archivedAt = 100L)
        val moment = StandardReferenceSeeds.moments.first()
        val badge = addReference(ReferenceKind.BADGE, "Bio")
        val archivedCountry = addReference(ReferenceKind.COUNTRY, "Pays archivé")
        val archivedRegion = addReference(ReferenceKind.REGION, "Région archivée", parentCountryId = archivedCountry.id)
        val tea = TeaEntity(
            id = UUID.randomUUID(), legacyId = null, name = "Thé fictif de test",
            categoryRefId = category.id, description = null, technicalInformation = null,
            personalNotes = null, olfactoryRating = null, tasteRating = null,
            brewTemperatureCelsius = null, infusionDurationSeconds = null,
            statusRefId = status.id, containerRefId = container.id,
            stockMilliGrams = null, archivedAtEpochMillis = null,
        )
        database.teaDao().insert(tea)
        val archivedTea = tea.copy(
            id = UUID.randomUUID(),
            name = "Thé archivé de test",
            categoryRefId = category.id,
            archivedAtEpochMillis = 1_000L,
        )
        database.teaDao().insert(archivedTea)
        database.teaDao().insertCountries(listOf(TeaCountryEntity(tea.id, country.id)))
        database.teaDao().insertRegions(listOf(TeaRegionEntity(tea.id, region.id, country.id)))
        database.teaDao().insertCountries(listOf(TeaCountryEntity(archivedTea.id, archivedCountry.id)))
        database.teaDao().insertRegions(listOf(TeaRegionEntity(archivedTea.id, archivedRegion.id, archivedCountry.id)))
        database.teaDao().insertAromas(listOf(TeaAromaEntity(tea.id, aroma.id)))
        database.teaDao().insertAromaticProfiles(listOf(TeaAromaticProfileEntity(tea.id, profile.id)))
        database.teaDao().insertMoments(listOf(TeaMomentEntity(tea.id, moment.id)))
        database.teaDao().insertBadges(listOf(TeaBadgeEntity(tea.id, badge.id)))

        val repository = RoomReferenceRepository(database)
        suspend fun usage(kind: ReferenceKind, id: UUID) =
            repository.observe(kind).first().single { it.value.id == id }

        assertEquals(2, usage(ReferenceKind.CATEGORY, category.id).teaUsageCount)
        assertEquals(2, usage(ReferenceKind.STATUS, status.id).teaUsageCount)
        assertEquals(2, usage(ReferenceKind.CONTAINER, container.id).teaUsageCount)
        assertEquals(1, usage(ReferenceKind.COUNTRY, country.id).teaUsageCount)
        assertEquals(1, usage(ReferenceKind.COUNTRY, country.id).activeTeaUsageCount)
        assertEquals(1, usage(ReferenceKind.COUNTRY, country.id).childRegionCount)
        assertEquals(1, usage(ReferenceKind.REGION, region.id).teaUsageCount)
        assertEquals(1, usage(ReferenceKind.REGION, region.id).activeTeaUsageCount)
        assertEquals(1, usage(ReferenceKind.COUNTRY, archivedCountry.id).teaUsageCount)
        assertEquals(0, usage(ReferenceKind.COUNTRY, archivedCountry.id).activeTeaUsageCount)
        assertEquals(0, usage(ReferenceKind.REGION, archivedRegion.id).activeTeaUsageCount)
        assertEquals("Japon", usage(ReferenceKind.REGION, region.id).parentCountryLabel)
        assertEquals(1, usage(ReferenceKind.AROMA, aroma.id).teaUsageCount)
        assertEquals(1, usage(ReferenceKind.AROMATIC_PROFILE, profile.id).teaUsageCount)
        assertEquals(1, usage(ReferenceKind.MOMENT, moment.id).teaUsageCount)
        assertEquals(1, usage(ReferenceKind.BADGE, badge.id).teaUsageCount)

        val profiles = repository.observe(ReferenceKind.AROMATIC_PROFILE).first()
        assertEquals(1, profiles.single { it.value.id == profile.id }.teaUsageCount)
        assertEquals(100L, profiles.single { it.value.id == profile.id }.value.archivedAt)
    }

    private suspend fun addReference(
        kind: ReferenceKind,
        label: String,
        parentCountryId: UUID? = null,
        archivedAt: Long? = null,
    ): ReferenceEntity {
        val entity = ReferenceEntity.fromDomain(
            ReferenceValue(
                id = UUID.randomUUID(),
                kind = kind,
                label = label,
                normalizedLabel = ReferenceLabelNormalizer.normalize(label),
                semanticKey = null,
                parentCountryId = parentCountryId,
                archivedAt = archivedAt,
            ),
        )
        database.referenceDao().insertIfAbsent(entity)
        return entity
    }
}
