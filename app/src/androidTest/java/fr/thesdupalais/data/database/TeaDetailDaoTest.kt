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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeaDetailDaoTest {
    private lateinit var database: AppDatabase

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun closeDatabase() = database.close()

    @Test fun detailObservesTeaAssociationsAndDescendingHistoricalTastings() = runBlocking {
        StandardReferenceSeeder(database).seed()
        val category = StandardReferenceSeeds.categories.single { it.semanticKey == "GREEN" }
        val status = StandardReferenceSeeds.statuses.first()
        val container = StandardReferenceSeeds.containers.first()
        val country = addReference(ReferenceKind.COUNTRY, "Japon")
        val region = addReference(ReferenceKind.REGION, "Shizuoka", country.id)
        val aroma = addReference(ReferenceKind.AROMA, "Herbe coupée")
        val profile = addReference(ReferenceKind.AROMATIC_PROFILE, "Végétal")
        val moment = addReference(ReferenceKind.MOMENT, "Matin")
        val badge = addReference(ReferenceKind.BADGE, "Bio")
        val tea = TeaEntity(
            id = UUID.randomUUID(), legacyId = null, name = "Thé test détail", categoryRefId = category.id,
            description = "Description fictive de test", technicalInformation = "Information fictive de test",
            personalNotes = "Note personnelle fictive", olfactoryRating = 9.0, tasteRating = 8.5,
            brewTemperatureCelsius = 75, infusionDurationSeconds = 150, statusRefId = status.id,
            containerRefId = container.id, stockMilliGrams = null, archivedAtEpochMillis = null,
        )
        database.teaDao().insert(tea)
        database.teaDao().insertCountries(listOf(TeaCountryEntity(tea.id, country.id)))
        database.teaDao().insertRegions(listOf(TeaRegionEntity(tea.id, region.id, country.id)))
        database.teaDao().insertAromas(listOf(TeaAromaEntity(tea.id, aroma.id)))
        database.teaDao().insertAromaticProfiles(listOf(TeaAromaticProfileEntity(tea.id, profile.id)))
        database.teaDao().insertMoments(listOf(TeaMomentEntity(tea.id, moment.id)))
        database.teaDao().insertBadges(listOf(TeaBadgeEntity(tea.id, badge.id)))
        database.tastingDao().insert(tasting(tea.id, occurredAt = 200L))
        database.tastingDao().insert(tasting(tea.id, occurredAt = 100L))

        val details = database.teaDao().observeActiveDetail(tea.id).first()!!
        val tastings = database.tastingDao().observeForTea(tea.id).first()

        assertEquals("Thé vert", details.category.label)
        assertEquals("Dans ma collection", details.status?.label)
        assertEquals("Boîte", details.container?.label)
        assertEquals(listOf("Japon"), details.countries.map { it.label })
        assertEquals(listOf("Shizuoka"), details.regions.map { it.label })
        assertEquals(listOf("Herbe coupée"), details.aromas.map { it.label })
        assertEquals(listOf("Végétal"), details.aromaticProfiles.map { it.label })
        assertEquals(listOf("Matin"), details.moments.map { it.label })
        assertEquals(listOf("Bio"), details.badges.map { it.label })
        assertEquals(8.5, details.tea.tasteRating!!, 0.0)
        assertEquals(9.0, details.tea.olfactoryRating!!, 0.0)
        assertEquals(listOf(200L, 100L), tastings.map { it.occurredAtEpochMillis })
        assertTrue(tastings.all { it.consumedMilliGrams == 1_000L })
        assertEquals(0, database.stockMovementDao().count())
    }

    @Test fun missingAndArchivedTeasAreUnavailable() = runBlocking {
        StandardReferenceSeeder(database).seed()
        val category = StandardReferenceSeeds.categories.first()
        val archived = TeaEntity(
            UUID.randomUUID(), null, "Thé archivé fictif", category.id, null, null, null,
            null, null, null, null, null, null, null, 10L,
        )
        database.teaDao().insert(archived)

        assertNull(database.teaDao().observeActiveDetail(archived.id).first())
        assertNull(database.teaDao().observeActiveDetail(UUID.randomUUID()).first())
    }

    private suspend fun addReference(kind: ReferenceKind, label: String, parent: UUID? = null): ReferenceEntity {
        val value = ReferenceValue(
            id = UUID.randomUUID(),
            kind = kind,
            label = label,
            normalizedLabel = ReferenceLabelNormalizer.normalize(label),
            semanticKey = null,
            parentCountryId = parent,
            archivedAt = null,
        )
        val entity = ReferenceEntity.fromDomain(value)
        database.referenceDao().insertIfAbsent(entity)
        return entity
    }

    private fun tasting(teaId: UUID, occurredAt: Long) = TastingEntity(
        id = UUID.randomUUID(), teaId = teaId, occurredAtEpochMillis = occurredAt,
        recordedAtEpochMillis = occurredAt, consumedMilliGrams = 1_000L,
    )
}
