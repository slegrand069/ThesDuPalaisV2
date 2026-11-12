package fr.thesdupalais.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import fr.thesdupalais.domain.references.StandardReferenceSeeds
import fr.thesdupalais.data.operation.BackupOperationPendingException
import fr.thesdupalais.data.operation.BackupOperationWriteCoordinator
import fr.thesdupalais.data.operation.RecoveryFinding
import fr.thesdupalais.data.operation.RecoveryInspection
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeaRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: TeaRepository

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = TeaRepository(database)
        StandardReferenceSeeder(database).seed()
    }

    @After fun tearDown() = database.close()

    @Test fun createsMinimalTeaWithUnknownOptionalValuesAndNoGeneratedEvents() = runBlocking {
        val category = standard(ReferenceKind.CATEGORY, "GREEN")
        val id = repository.create(NewTea(name = "  Sencha   du matin  ", categoryId = category.id))

        val saved = requireNotNull(database.teaDao().findById(id))
        assertEquals("Sencha du matin", saved.name)
        assertNull(saved.legacyId)
        assertEquals(category.id, saved.categoryRefId)
        assertNull(saved.statusRefId)
        assertNull(saved.containerRefId)
        assertNull(saved.tasteRating)
        assertNull(saved.olfactoryRating)
        assertNull(saved.brewTemperatureCelsius)
        assertNull(saved.infusionDurationSeconds)
        assertNull(saved.stockMilliGrams)
        assertNull(saved.archivedAtEpochMillis)
        assertEquals(0, database.tastingDao().count())
        assertEquals(0, database.stockMovementDao().count())
        assertTrue(database.teaPhotoDao().observeForTea(id).first().isEmpty())
    }

    @Test fun createsAllSelectedAssociationsAndAddsRegionParentCountries() = runBlocking {
        val category = standard(ReferenceKind.CATEGORY, "OOLONG")
        val status = standard(ReferenceKind.STATUS)
        val container = standard(ReferenceKind.CONTAINER)
        val japan = reference(ReferenceKind.COUNTRY, "Japon")
        val china = reference(ReferenceKind.COUNTRY, "Chine")
        val shizuoka = reference(ReferenceKind.REGION, "Shizuoka", japan.id)
        val yunnan = reference(ReferenceKind.REGION, "Yunnan", china.id)
        val aroma1 = reference(ReferenceKind.AROMA, "Floral")
        val aroma2 = reference(ReferenceKind.AROMA, "Végétal")
        val profile = reference(ReferenceKind.AROMATIC_PROFILE, "Frais")
        val moment = reference(ReferenceKind.MOMENT, "Moment personnalisé")
        val badge = reference(ReferenceKind.BADGE, "Grand cru")

        val id = repository.create(
            NewTea(
                name = "Oolong multi-origine",
                categoryId = category.id,
                statusId = status.id,
                containerId = container.id,
                countryIds = listOf(japan.id),
                regionIds = listOf(shizuoka.id, yunnan.id),
                aromaIds = listOf(aroma1.id, aroma2.id),
                aromaticProfileIds = listOf(profile.id),
                momentIds = listOf(moment.id),
                badgeIds = listOf(badge.id),
                tasteRating = 0.0,
                olfactoryRating = 8.5,
                personalNotes = "  notes  ",
                description = "Description",
                technicalInformation = "Infusion courte",
                brewTemperatureCelsius = 90,
                infusionDurationSeconds = 45,
            ),
        )
        val detail = requireNotNull(database.teaDao().observeActiveDetail(id).first())

        assertEquals(setOf(japan.id, china.id), detail.countries.map { it.id }.toSet())
        assertEquals(setOf(shizuoka.id, yunnan.id), detail.regions.map { it.id }.toSet())
        assertEquals(setOf(aroma1.id, aroma2.id), detail.aromas.map { it.id }.toSet())
        assertEquals(setOf(profile.id), detail.aromaticProfiles.map { it.id }.toSet())
        assertEquals(setOf(moment.id), detail.moments.map { it.id }.toSet())
        assertEquals(setOf(badge.id), detail.badges.map { it.id }.toSet())
        assertEquals(status.id, detail.status?.id)
        assertEquals(container.id, detail.container?.id)
        assertEquals(0.0, detail.tea.tasteRating!!, 0.0)
        assertEquals(8.5, detail.tea.olfactoryRating!!, 0.0)
        assertEquals("notes", detail.tea.personalNotes)
        assertEquals(90, detail.tea.brewTemperatureCelsius)
        assertEquals(45, detail.tea.infusionDurationSeconds)
        assertEquals(0, database.tastingDao().count())
        assertEquals(0, database.stockMovementDao().count())
    }

    @Test fun createsAndUpdatesKnownStockWithoutWritingStockHistory() = runBlocking {
        val category = standard(ReferenceKind.CATEGORY, "GREEN")
        val teaId = repository.create(NewTea("Thé avec stock", category.id, stockMilliGrams = 12_500L))
        assertEquals(12_500L, database.teaDao().findById(teaId)?.stockMilliGrams)

        repository.update(
            teaId,
            NewTea("Thé avec stock", category.id, stockMilliGrams = 0L),
            setOf(TeaEditableField.STOCK),
        )
        assertEquals(0L, database.teaDao().findById(teaId)?.stockMilliGrams)

        repository.update(
            teaId,
            NewTea("Thé avec stock", category.id, stockMilliGrams = null),
            setOf(TeaEditableField.STOCK),
        )
        assertNull(database.teaDao().findById(teaId)?.stockMilliGrams)
        assertEquals(0, database.stockMovementDao().count())
    }

    @Test fun recordsOneTastingAndClampsKnownStockAtZeroWithoutStockMovements() = runBlocking {
        val category = standard(ReferenceKind.CATEGORY, "GREEN")
        val teaId = repository.create(NewTea("Stock faible", category.id, stockMilliGrams = 2_000L))

        val firstTastingId = repository.recordConsumedTasting(teaId, 5_000L)
        assertEquals(0L, database.teaDao().findById(teaId)?.stockMilliGrams)
        val tastings = database.tastingDao().observeForTea(teaId).first()
        assertEquals(1, tastings.size)
        assertEquals(firstTastingId, tastings.single().id)
        assertEquals(5_000L, tastings.single().consumedMilliGrams)

        repository.recordConsumedTasting(teaId, 1_000L)
        assertEquals(0L, database.teaDao().findById(teaId)?.stockMilliGrams)
        assertEquals(2, database.tastingDao().observeForTea(teaId).first().size)
        assertEquals(0, database.stockMovementDao().count())
    }

    @Test fun recordsTastingWithoutChangingUnknownStock() = runBlocking {
        val category = standard(ReferenceKind.CATEGORY, "GREEN")
        val teaId = repository.create(NewTea("Stock inconnu", category.id))

        repository.recordConsumedTasting(teaId, 7_000L)

        assertNull(database.teaDao().findById(teaId)?.stockMilliGrams)
        assertEquals(1, database.tastingDao().observeForTea(teaId).first().size)
        assertEquals(0, database.stockMovementDao().count())
    }

    @Test fun recordTastingRespectsBlockedWriteCoordinator() = runBlocking {
        val category = standard(ReferenceKind.CATEGORY, "GREEN")
        val teaId = repository.create(NewTea("Verrouillé", category.id, stockMilliGrams = 10_000L))
        val blockedRepository = TeaRepository(
            database,
            BackupOperationWriteCoordinator {
                RecoveryInspection(RecoveryFinding.RECOVERY_FAILED, detail = "Écriture bloquée pour test.")
            },
        )

        val failure = runCatching { blockedRepository.recordConsumedTasting(teaId, 7_000L) }.exceptionOrNull()

        assertTrue(failure is BackupOperationPendingException)
        assertEquals(10_000L, database.teaDao().findById(teaId)?.stockMilliGrams)
        assertEquals(0, database.tastingDao().count())
    }

    @Test fun rejectsWrongReferenceTypeAndInvalidNumbersBeforeWriting() = runBlocking {
        val category = standard(ReferenceKind.CATEGORY, "GREEN")
        val aroma = reference(ReferenceKind.AROMA, "Aroma")
        val wrongKind = runCatching { repository.create(NewTea("Bad category", aroma.id)) }.exceptionOrNull()
        assertNotNull(wrongKind)
        assertEquals(0, database.teaDao().count())

        val badRating = runCatching { repository.create(NewTea("Bad rating", category.id, tasteRating = 10.1)) }.exceptionOrNull()
        assertNotNull(badRating)
        val badTemperature = runCatching { repository.create(NewTea("Bad temperature", category.id, brewTemperatureCelsius = 101)) }.exceptionOrNull()
        assertNotNull(badTemperature)
        val offStepTemperature = runCatching { repository.create(NewTea("Off-step temperature", category.id, brewTemperatureCelsius = 62)) }.exceptionOrNull()
        assertNotNull(offStepTemperature)
        val belowRangeTemperature = runCatching { repository.create(NewTea("Low temperature", category.id, brewTemperatureCelsius = 55)) }.exceptionOrNull()
        assertNotNull(belowRangeTemperature)
        val boundaryTemperatureId = repository.create(NewTea("Boundary temperature", category.id, brewTemperatureCelsius = 60))
        assertEquals(60, database.teaDao().findById(boundaryTemperatureId)?.brewTemperatureCelsius)
        val stepTemperatureId = repository.create(NewTea("Stepped temperature", category.id, brewTemperatureCelsius = 65))
        assertEquals(65, database.teaDao().findById(stepTemperatureId)?.brewTemperatureCelsius)
        val badDuration = runCatching { repository.create(NewTea("Bad duration", category.id, infusionDurationSeconds = 0)) }.exceptionOrNull()
        assertNotNull(badDuration)
        assertEquals(2, database.teaDao().count())
    }

    @Test fun rejectsArchivedReferenceAndRollsBackFailedAssociationInsert() = runBlocking {
        val category = reference(ReferenceKind.CATEGORY, "Catégorie inactive", archivedAt = 42L)
        assertTrue(repository.observeSelectableReferences(ReferenceKind.CATEGORY).first().none { it.id == category.id })
        assertTrue(runCatching { repository.create(NewTea("Archived", category.id)) }.isFailure)
        assertEquals(0, database.teaDao().count())

        val activeCategory = standard(ReferenceKind.CATEGORY, "GREEN")
        val aroma = reference(ReferenceKind.AROMA, "Aroma")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_tea_aroma_insert BEFORE INSERT ON tea_aromas BEGIN SELECT RAISE(ABORT, 'forced test failure'); END",
        )
        assertTrue(runCatching { repository.create(NewTea("Atomic", activeCategory.id, aromaIds = listOf(aroma.id))) }.isFailure)
        assertEquals(0, database.teaDao().count())
    }

    @Test fun createdTeaPersistsWhenRoomDatabaseIsClosedAndReopened() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tea-create-${UUID.randomUUID()}.db"
        val first = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        val teaId = try {
            StandardReferenceSeeder(first).seed()
            val category = requireNotNull(first.referenceDao().findBySemanticKey("CATEGORY", "GREEN"))
            val repository = TeaRepository(first)
            val id = repository.create(NewTea(name = "Persistent tea", categoryId = category.id))
            val loaded = requireNotNull(repository.loadForEdit(id))
            repository.update(id, loaded.values.copy(name = "Persistent edited tea"), setOf(TeaEditableField.NAME))
            id
        } finally {
            first.close()
        }
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        try {
            val saved = requireNotNull(reopened.teaDao().findById(teaId))
            assertEquals("Persistent edited tea", saved.name)
            assertNull(saved.stockMilliGrams)
            assertEquals(0, reopened.tastingDao().count())
        } finally {
            reopened.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun nameEditPreservesImportedTemperatureIdentityStockHistoryPhotosAndArchivedReferences() = runBlocking {
        val category = standard(ReferenceKind.CATEGORY, "GREEN")
        val status = standard(ReferenceKind.STATUS)
        val country = reference(ReferenceKind.COUNTRY, "Pays conservé")
        val region = reference(ReferenceKind.REGION, "Région conservée", country.id)
        val archivedAroma = reference(ReferenceKind.AROMA, "Arôme archivé", archivedAt = 123L)
        val teaId = UUID.randomUUID()
        val legacyId = 2L
        database.teaDao().insert(
            TeaEntity(
                id = teaId, legacyId = legacyId, name = "Nom importé", categoryRefId = category.id,
                description = "Description originale", technicalInformation = "Informations importées",
                personalNotes = "Notes importées", olfactoryRating = 7.25, tasteRating = 8.35,
                brewTemperatureCelsius = 82, infusionDurationSeconds = 125, statusRefId = status.id,
                containerRefId = null, stockMilliGrams = 42_000L, archivedAtEpochMillis = null,
            ),
        )
        database.teaDao().insertCountries(listOf(TeaCountryEntity(teaId, country.id)))
        database.teaDao().insertRegions(listOf(TeaRegionEntity(teaId, region.id, country.id)))
        database.teaDao().insertAromas(listOf(TeaAromaEntity(teaId, archivedAroma.id)))
        val tastingId = UUID.randomUUID()
        database.tastingDao().insert(TastingEntity(tastingId, teaId, 100L, 200L, 7_000L))
        val movementId = UUID.randomUUID()
        database.stockMovementDao().insert(
            StockMovementEntity(movementId, teaId, "CONSUMPTION", -7_000L, 42_000L, 100L, 200L, tastingId, "Historique conservé"),
        )
        val photoId = UUID.randomUUID()
        database.teaPhotoDao().insert(TeaPhotoEntity(photoId, teaId, "photos/importee.jpg", 0))

        val loaded = requireNotNull(repository.loadForEdit(teaId))
        assertEquals(82, loaded.values.brewTemperatureCelsius)
        assertEquals("Arôme archivé", loaded.references.getValue(ReferenceKind.AROMA).single().label)
        val updatedValues = loaded.values.copy(name = "Nom modifié")
        assertTrue(repository.update(teaId, updatedValues, setOf(TeaEditableField.NAME)))

        val saved = requireNotNull(database.teaDao().findById(teaId))
        assertEquals("Nom modifié", saved.name)
        assertEquals(teaId, saved.id)
        assertEquals(legacyId, saved.legacyId)
        assertEquals(82, saved.brewTemperatureCelsius)
        assertEquals(42_000L, saved.stockMilliGrams)
        assertEquals("Description originale", saved.description)
        assertEquals("Notes importées", saved.personalNotes)
        assertEquals(8.35, saved.tasteRating!!, 0.0)
        assertEquals(1, database.tastingDao().count())
        assertEquals(movementId, database.stockMovementDao().observeForTea(teaId).first().single().id)
        assertEquals(photoId, database.teaPhotoDao().observeForTea(teaId).first().single().id)
        assertEquals(setOf(country.id), database.teaDao().observeActiveDetail(teaId).first()!!.countries.map { it.id }.toSet())
        assertEquals(setOf(region.id), database.teaDao().observeActiveDetail(teaId).first()!!.regions.map { it.id }.toSet())
        assertEquals(setOf(archivedAroma.id), database.teaDao().observeActiveDetail(teaId).first()!!.aromas.map { it.id }.toSet())
    }

    @Test fun unchangedEditSkipsAllWrites() = runBlocking {
        val category = standard(ReferenceKind.CATEGORY, "GREEN")
        val teaId = repository.create(NewTea("Sans changement", category.id))
        val loaded = requireNotNull(repository.loadForEdit(teaId))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_tea_updates BEFORE UPDATE ON teas BEGIN SELECT RAISE(ABORT, 'unexpected write'); END",
        )

        assertEquals(false, repository.update(teaId, loaded.values, emptySet()))
        assertEquals("Sans changement", database.teaDao().findById(teaId)?.name)
    }

    @Test fun changedAssociationsAreAtomicAndNullableFieldsCanBeCleared() = runBlocking {
        val category = standard(ReferenceKind.CATEGORY, "GREEN")
        val oldAroma = reference(ReferenceKind.AROMA, "Ancien arôme")
        val newAroma = reference(ReferenceKind.AROMA, "Nouvel arôme")
        val teaId = repository.create(NewTea("Fiche", category.id, aromaIds = listOf(oldAroma.id), description = "À effacer", personalNotes = "À effacer", tasteRating = 8.4, brewTemperatureCelsius = 80))
        val loaded = requireNotNull(repository.loadForEdit(teaId))
        val update = loaded.values.copy(
            name = "Nom à annuler par rollback", aromaIds = listOf(newAroma.id), description = null,
            personalNotes = null, tasteRating = null, brewTemperatureCelsius = null,
        )
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_tea_aroma_update BEFORE INSERT ON tea_aromas BEGIN SELECT RAISE(ABORT, 'forced update failure'); END",
        )
        val fields = setOf(
            TeaEditableField.NAME, TeaEditableField.AROMAS, TeaEditableField.DESCRIPTION,
            TeaEditableField.PERSONAL_NOTES, TeaEditableField.TASTE_RATING, TeaEditableField.BREW_TEMPERATURE,
        )
        assertTrue(runCatching { repository.update(teaId, update, fields) }.isFailure)
        val rolledBack = requireNotNull(database.teaDao().findById(teaId))
        assertEquals("Fiche", rolledBack.name)
        assertEquals("À effacer", rolledBack.description)
        assertEquals("À effacer", rolledBack.personalNotes)
        assertEquals(8.4, rolledBack.tasteRating!!, 0.0)
        assertEquals(80, rolledBack.brewTemperatureCelsius)
        assertEquals(setOf(oldAroma.id), database.teaDao().observeActiveDetail(teaId).first()!!.aromas.map { it.id }.toSet())
    }

    @Test fun editSynchronizesMultipleAssociationsAndMaintainsRegionCountryIntegrity() = runBlocking {
        val category = standard(ReferenceKind.CATEGORY, "GREEN")
        val japan = reference(ReferenceKind.COUNTRY, "Japon")
        val india = reference(ReferenceKind.COUNTRY, "Inde")
        val shizuoka = reference(ReferenceKind.REGION, "Shizuoka", japan.id)
        val assam = reference(ReferenceKind.REGION, "Assam", india.id)
        val oldAroma = reference(ReferenceKind.AROMA, "Floral")
        val newAroma = reference(ReferenceKind.AROMA, "Végétal")
        val profile = reference(ReferenceKind.AROMATIC_PROFILE, "Frais")
        val moment = reference(ReferenceKind.MOMENT, "Matin personnalisé")
        val badge = reference(ReferenceKind.BADGE, "Grand cru personnalisé")
        val teaId = repository.create(
            NewTea("Thé multi-associations", category.id, countryIds = listOf(japan.id), regionIds = listOf(shizuoka.id), aromaIds = listOf(oldAroma.id)),
        )
        val loaded = requireNotNull(repository.loadForEdit(teaId))
        val changed = loaded.values.copy(
            countryIds = listOf(india.id), regionIds = listOf(assam.id), aromaIds = listOf(newAroma.id),
            aromaticProfileIds = listOf(profile.id), momentIds = listOf(moment.id), badgeIds = listOf(badge.id),
        )
        val fields = setOf(
            TeaEditableField.COUNTRIES, TeaEditableField.REGIONS, TeaEditableField.AROMAS,
            TeaEditableField.PROFILES, TeaEditableField.MOMENTS, TeaEditableField.BADGES,
        )
        assertTrue(repository.update(teaId, changed, fields))
        val detail = requireNotNull(database.teaDao().observeActiveDetail(teaId).first())
        assertEquals(setOf(india.id), detail.countries.map { it.id }.toSet())
        assertEquals(setOf(assam.id), detail.regions.map { it.id }.toSet())
        assertEquals(setOf(newAroma.id), detail.aromas.map { it.id }.toSet())
        assertEquals(setOf(profile.id), detail.aromaticProfiles.map { it.id }.toSet())
        assertEquals(setOf(moment.id), detail.moments.map { it.id }.toSet())
        assertEquals(setOf(badge.id), detail.badges.map { it.id }.toSet())
        assertEquals(india.id, database.openHelper.writableDatabase.query(
            "SELECT countryRefId FROM tea_regions WHERE teaId = '${teaId}'",
        ).use { cursor -> assertTrue(cursor.moveToFirst()); UUID.fromString(cursor.getString(0)) })
    }

    @Test fun temperatureCanBeClearedOrChangedOnlyToCurrentSliderValues() = runBlocking {
        val category = standard(ReferenceKind.CATEGORY, "GREEN")
        val teaId = UUID.randomUUID()
        database.teaDao().insert(
            TeaEntity(
                id = teaId, legacyId = null, name = "Température historique", categoryRefId = category.id,
                description = null, technicalInformation = null, personalNotes = null, olfactoryRating = null,
                tasteRating = null, brewTemperatureCelsius = 82, infusionDurationSeconds = null,
                statusRefId = null, containerRefId = null, stockMilliGrams = null, archivedAtEpochMillis = null,
            ),
        )
        val loaded = requireNotNull(repository.loadForEdit(teaId))
        assertTrue(repository.update(teaId, loaded.values.copy(brewTemperatureCelsius = null), setOf(TeaEditableField.BREW_TEMPERATURE)))
        assertNull(database.teaDao().findById(teaId)?.brewTemperatureCelsius)

        val current = requireNotNull(repository.loadForEdit(teaId))
        assertTrue(repository.update(teaId, current.values.copy(brewTemperatureCelsius = 95), setOf(TeaEditableField.BREW_TEMPERATURE)))
        assertEquals(95, database.teaDao().findById(teaId)?.brewTemperatureCelsius)
        val invalid = requireNotNull(repository.loadForEdit(teaId))
        assertTrue(runCatching {
            repository.update(teaId, invalid.values.copy(brewTemperatureCelsius = 92), setOf(TeaEditableField.BREW_TEMPERATURE))
        }.isFailure)
        assertEquals(95, database.teaDao().findById(teaId)?.brewTemperatureCelsius)
    }

    private suspend fun standard(kind: ReferenceKind, semanticKey: String? = null): ReferenceEntity =
        if (semanticKey != null) requireNotNull(database.referenceDao().findBySemanticKey(kind.name, semanticKey))
        else requireNotNull(database.referenceDao().observeByKind(kind.name).first().firstOrNull())

    private suspend fun reference(
        kind: ReferenceKind,
        label: String,
        parentCountryId: UUID? = null,
        archivedAt: Long? = null,
    ): ReferenceEntity {
        val value = ReferenceValue(
            id = UUID.randomUUID(),
            kind = kind,
            label = label,
            normalizedLabel = ReferenceLabelNormalizer.normalize(label),
            semanticKey = null,
            parentCountryId = parentCountryId,
            archivedAt = archivedAt,
            displayPriority = 0,
        )
        val entity = ReferenceEntity.fromDomain(value)
        database.referenceDao().insert(entity)
        return entity
    }
}
