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
            TeaRepository(first).create(NewTea(name = "Persistent tea", categoryId = category.id))
        } finally {
            first.close()
        }
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        try {
            val saved = requireNotNull(reopened.teaDao().findById(teaId))
            assertEquals("Persistent tea", saved.name)
            assertNull(saved.stockMilliGrams)
            assertEquals(0, reopened.tastingDao().count())
        } finally {
            reopened.close()
            context.deleteDatabase(databaseName)
        }
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
