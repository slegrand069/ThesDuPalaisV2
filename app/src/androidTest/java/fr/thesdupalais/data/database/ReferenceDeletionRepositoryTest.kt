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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceDeletionRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomReferenceRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomReferenceRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun unusedCustomReferenceIsDeletedAndDoesNotReturnAfterSeed() = runBlocking {
        val customId = repository.create(ReferenceKind.AROMA, "Arôme fictif supprimable")
        val survivorId = repository.create(ReferenceKind.AROMA, "Arôme fictif conservé")

        assertEquals(ReferenceDeletionStatus.Eligible, repository.checkDeletion(customId))
        assertEquals(ReferenceDeletionStatus.Deleted, repository.deleteIfUnused(customId))
        StandardReferenceSeeder(database).seed()

        assertEquals(null, database.referenceDao().findById(customId))
        assertNotNull(database.referenceDao().findById(survivorId))
        assertFalse(repository.observe(ReferenceKind.AROMA).first().any { it.value.id == customId })
    }

    @Test fun everyReferenceFamilyIsProtectedWhenUsedByAnArchivedTea() = runBlocking {
        val fallbackCategory = repository.create(ReferenceKind.CATEGORY, "Catégorie fictive de contrôle")
        val fallbackCountry = repository.create(ReferenceKind.COUNTRY, "Pays fictif de contrôle")

        ReferenceKind.entries.forEachIndexed { index, kind ->
            val target = repository.create(kind, "Référence fictive $index", if (kind == ReferenceKind.REGION) fallbackCountry else null)
            val teaId = UUID.randomUUID()
            val status = repository.create(ReferenceKind.STATUS, "Statut fictif $index")
            val tea = TeaEntity(
                id = teaId, legacyId = null, name = "Thé archivé fictif $index",
                categoryRefId = if (kind == ReferenceKind.CATEGORY) target else fallbackCategory,
                description = null, technicalInformation = null, personalNotes = null, olfactoryRating = null, tasteRating = null,
                brewTemperatureCelsius = null, infusionDurationSeconds = null,
                statusRefId = if (kind == ReferenceKind.STATUS) target else status,
                containerRefId = if (kind == ReferenceKind.CONTAINER) target else null,
                stockMilliGrams = null, archivedAtEpochMillis = 1L,
            )
            database.teaDao().insert(tea)
            when (kind) {
                ReferenceKind.COUNTRY -> database.teaDao().insertCountries(listOf(TeaCountryEntity(teaId, target)))
                ReferenceKind.REGION -> {
                    database.teaDao().insertCountries(listOf(TeaCountryEntity(teaId, fallbackCountry)))
                    database.teaDao().insertRegions(listOf(TeaRegionEntity(teaId, target, fallbackCountry)))
                }
                ReferenceKind.AROMA -> database.teaDao().insertAromas(listOf(TeaAromaEntity(teaId, target)))
                ReferenceKind.AROMATIC_PROFILE -> database.teaDao().insertAromaticProfiles(listOf(TeaAromaticProfileEntity(teaId, target)))
                ReferenceKind.MOMENT -> database.teaDao().insertMoments(listOf(TeaMomentEntity(teaId, target)))
                ReferenceKind.BADGE -> database.teaDao().insertBadges(listOf(TeaBadgeEntity(teaId, target)))
                ReferenceKind.CATEGORY, ReferenceKind.STATUS, ReferenceKind.CONTAINER -> Unit
            }

            assertEquals("$kind must count archived tea usage", ReferenceDeletionStatus.InUse(1), repository.checkDeletion(target))
            assertEquals("$kind must block deletion", ReferenceDeletionStatus.InUse(1), repository.deleteIfUnused(target))
            assertNotNull(database.referenceDao().findById(target))
        }
    }

    @Test fun countryWithChildRegionIsProtectedAndCanBeDeletedAfterChildRemoval() = runBlocking {
        val countryId = repository.create(ReferenceKind.COUNTRY, "Pays parent fictif")
        val regionId = repository.create(ReferenceKind.REGION, "Région enfant fictive", countryId)

        assertEquals(ReferenceDeletionStatus.HasRegions(1), repository.checkDeletion(countryId))
        assertEquals(ReferenceDeletionStatus.HasRegions(1), repository.deleteIfUnused(countryId))
        assertNotNull(database.referenceDao().findById(regionId))
        assertNotNull(database.referenceDao().findById(countryId))

        assertEquals(ReferenceDeletionStatus.Deleted, repository.deleteIfUnused(regionId))
        assertEquals(ReferenceDeletionStatus.Deleted, repository.deleteIfUnused(countryId))
    }

    @Test fun usageAddedAfterConfirmationPreviewBlocksTransactionalDelete() = runBlocking {
        val categoryId = repository.create(ReferenceKind.CATEGORY, "Catégorie ajoutée après aperçu")
        assertEquals(ReferenceDeletionStatus.Eligible, repository.checkDeletion(categoryId))
        val statusId = repository.create(ReferenceKind.STATUS, "Statut contrôle concurrence")
        val teaId = UUID.randomUUID()
        database.teaDao().insert(
            TeaEntity(
                id = teaId, legacyId = null, name = "Thé ajouté après aperçu", categoryRefId = categoryId,
                description = null, technicalInformation = null, personalNotes = null, olfactoryRating = null, tasteRating = null,
                brewTemperatureCelsius = null, infusionDurationSeconds = null, statusRefId = statusId, containerRefId = null,
                stockMilliGrams = null, archivedAtEpochMillis = null,
            ),
        )

        assertEquals(ReferenceDeletionStatus.InUse(1), repository.deleteIfUnused(categoryId))
        assertNotNull(database.referenceDao().findById(categoryId))
        assertNotNull(database.teaDao().findById(teaId))
    }

    @Test fun onlyExactSeedManagedKindAndSemanticKeyPairsAreProtected() = runBlocking {
        StandardReferenceSeeder(database).seed()
        StandardReferenceSeeds.all.forEach { standard ->
            assertEquals(
                "${standard.kind}/${standard.semanticKey}",
                ReferenceDeletionStatus.Standard,
                repository.deleteIfUnused(standard.id),
            )
            assertNotNull(database.referenceDao().findById(standard.id))
        }

        val customWithKey = ReferenceEntity.fromDomain(
            ReferenceValue(
                id = UUID.randomUUID(), kind = ReferenceKind.AROMA, label = "Arôme importé fictif",
                normalizedLabel = ReferenceLabelNormalizer.normalize("Arôme importé fictif"), semanticKey = "CUSTOM_FIXTURE_KEY",
            ),
        )
        database.referenceDao().insert(customWithKey)
        assertEquals(ReferenceDeletionStatus.Deleted, repository.deleteIfUnused(customWithKey.id))
        assertEquals(null, database.referenceDao().findById(customWithKey.id))
    }

    @Test fun restrictiveForeignKeyRemainsTheFinalGuardAndDoesNotCascade() = runBlocking {
        val categoryId = repository.create(ReferenceKind.CATEGORY, "Catégorie contrôle FK")
        val statusId = repository.create(ReferenceKind.STATUS, "Statut contrôle FK")
        val teaId = UUID.randomUUID()
        database.teaDao().insert(
            TeaEntity(
                id = teaId, legacyId = null, name = "Thé fictif FK", categoryRefId = categoryId,
                description = null, technicalInformation = null, personalNotes = null, olfactoryRating = null, tasteRating = null,
                brewTemperatureCelsius = null, infusionDurationSeconds = null, statusRefId = statusId, containerRefId = null,
                stockMilliGrams = null, archivedAtEpochMillis = null,
            ),
        )

        val rawDeleteFailed = runCatching { database.referenceDao().deleteById(categoryId) }.isFailure

        assertTrue(rawDeleteFailed)
        assertNotNull(database.teaDao().findById(teaId))
        assertNotNull(database.referenceDao().findById(categoryId))
        assertEquals(ReferenceDeletionStatus.InUse(1), repository.deleteIfUnused(categoryId))
        assertEquals(1, database.teaDao().count())
    }
}
