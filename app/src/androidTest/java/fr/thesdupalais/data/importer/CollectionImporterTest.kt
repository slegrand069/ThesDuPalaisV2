package fr.thesdupalais.data.importer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.StandardReferenceSeeder
import fr.thesdupalais.data.preferences.UserPreferences
import fr.thesdupalais.data.preferences.UserPreferencesStore
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
class CollectionImporterTest {
    private lateinit var database: AppDatabase
    private lateinit var preferencesStore: RecordingPreferencesStore
    private lateinit var importer: CollectionImporter

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferencesStore = RecordingPreferencesStore()
        importer = CollectionImporter(database, preferencesStore) { 1_800_000_000_000L }
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun importsFictiveFixtureResolvesOldStandardLabelAndIsIdempotent() = runBlocking {
        val green = StandardReferenceSeeds.categories.single { it.semanticKey == "GREEN" }
        database.referenceDao().insertIfAbsent(
            ReferenceEntity(
                id = green.id,
                kind = "CATEGORY",
                label = "Vert",
                normalizedLabel = ReferenceLabelNormalizer.normalize("Vert"),
                semanticKey = "GREEN",
                parentCountryId = null,
                scopeKey = "",
                archivedAt = null,
                displayPriority = 0,
            ),
        )
        val json = fixture()
        val equivalentJson = " \n" + json.replace(
            "\"schemaVersion\": 1,\n  \"datasetId\": \"$DATASET_ID\"",
            "\"datasetId\": \"$DATASET_ID\",\n  \"schemaVersion\": 1",
        ) + "\n "

        val first = importer.importJson(json)
        val second = importer.importJson(equivalentJson)

        assertTrue(first is CollectionImportResult.Imported)
        assertTrue(second is CollectionImportResult.AlreadyImported)
        assertEquals(1, database.teaDao().count())
        assertEquals(1, database.tastingDao().count())
        assertEquals(0, database.stockMovementDao().count())
        assertEquals("Vert", database.referenceDao().findById(green.id)?.label)
        assertEquals(green.id, database.teaDao().findById(FIXTURE_TEA_ID)?.categoryRefId)
        assertEquals(UserPreferences(8_000L, 30_000L), preferencesStore.current)
        assertEquals(1, database.importedDatasetDao().findAny()?.teaCount)

        database.openHelper.readableDatabase.query(
            "SELECT countryRefId FROM tea_countries WHERE teaId = ?",
            arrayOf(FIXTURE_TEA_ID.toString()),
        ).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("10000000-0000-4000-8000-000000000003", cursor.getString(0))
        }
    }

    @Test
    fun importDoesNotRestoreOldLabelOfRenamedStandardReference() = runBlocking {
        StandardReferenceSeeder(database).seed()
        val green = StandardReferenceSeeds.categories.single { it.semanticKey == "GREEN" }
        val references = fr.thesdupalais.data.database.RoomReferenceRepository(database)
        references.rename(green.id, "Catégorie verte personnalisée")

        importer.importJson(fixture())

        val persisted = database.referenceDao().findById(green.id)!!
        assertEquals("Catégorie verte personnalisée", persisted.label)
        assertEquals("GREEN", persisted.semanticKey)
        assertEquals(green.id, persisted.id)
    }

    @Test
    fun invalidUuidIsRejectedBeforeWritingAnything() = runBlocking {
        val json = fixture().replace(DATASET_ID, "not-a-uuid")

        val error = expectImportError(json)

        assertEquals(CollectionImportException.Code.INVALID_UUID, error.code)
        assertEquals(0, database.teaDao().count())
        assertNull(database.importedDatasetDao().findAny())
    }

    @Test
    fun missingReferenceAndInvalidNumericValueAreRejected() = runBlocking {
        val missingReference = fixture().replace(
            "\"categoryId\": \"10000000-0000-4000-8000-000000000001\"",
            "\"categoryId\": \"99999999-0000-4000-8000-000000000099\"",
        )
        val missingError = expectImportError(missingReference)
        assertEquals(CollectionImportException.Code.MISSING_REFERENCE, missingError.code)

        val invalidRating = fixture().replace("\"olfactoryRating\": 7.5", "\"olfactoryRating\": 10.5")
        val numericError = expectImportError(invalidRating)
        assertEquals(CollectionImportException.Code.INVALID_FIELD, numericError.code)
        assertTrue(numericError.message.orEmpty().contains("entre 0 et 10"))
        assertEquals(0, database.teaDao().count())
    }

    @Test
    fun duplicateAssociationAndUnsupportedVersionAreRejected() = runBlocking {
        val duplicate = fixture().replace(
            "\"badgeIds\": [\"10000000-0000-4000-8000-000000000008\"]",
            "\"badgeIds\": [\"10000000-0000-4000-8000-000000000008\", \"10000000-0000-4000-8000-000000000008\"]",
        )
        val duplicateError = expectImportError(duplicate)
        assertEquals(CollectionImportException.Code.DUPLICATE_VALUE, duplicateError.code)

        val original = fixture()
        val teaObject = original.substringAfter("\"teas\": [\n").substringBefore("\n  ],\n  \"tastings\"")
        val duplicateTea = original.replace(
            "\n  ],\n  \"tastings\": [",
            ",\n$teaObject\n  ],\n  \"tastings\": [",
        )
        val duplicateTeaError = expectImportError(duplicateTea)
        assertEquals(CollectionImportException.Code.DUPLICATE_VALUE, duplicateTeaError.code)

        val unsupported = fixture().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        val versionError = expectImportError(unsupported)
        assertEquals(CollectionImportException.Code.UNSUPPORTED_VERSION, versionError.code)
        assertEquals(0, database.teaDao().count())
    }

    @Test
    fun numericStringsAreNotCoercedToNumbers() = runBlocking {
        val json = fixture().replace(
            "\"quantityPerCupMilliGrams\": 8000",
            "\"quantityPerCupMilliGrams\": \"8000\"",
        )

        val error = expectImportError(json)

        assertEquals(CollectionImportException.Code.INVALID_FIELD, error.code)
        assertTrue(error.message.orEmpty().contains("entier JSON"))
        assertEquals(0, database.teaDao().count())
    }

    @Test
    fun importsOnlyExplicitStockMovementsAndRejectsMovementForUnknownStock() = runBlocking {
        val withBaseline = fixture()
            .replace("\"stockMilliGrams\": null", "\"stockMilliGrams\": 7000")
            .replace(
                "\"stockMovements\": []",
                """"stockMovements": [{
                  "id": "40000000-0000-4000-8000-000000000001",
                  "teaId": "20000000-0000-4000-8000-000000000001",
                  "kind": "INVENTORY",
                  "deltaMilliGrams": null,
                  "stockAfterMilliGrams": 7000,
                  "occurredAt": "2026-01-01T09:00:00.000Z",
                  "recordedAt": "2026-01-01T09:00:00.000Z",
                  "tastingId": null,
                  "note": "Stock fictif de fixture"
                }]""",
            )
        val imported = importer.importJson(withBaseline)
        assertTrue(imported is CollectionImportResult.Imported)
        assertEquals(1, database.stockMovementDao().count())
        assertEquals(7_000L, database.teaDao().findById(FIXTURE_TEA_ID)?.stockMilliGrams)

        database.close()
        createDatabase()
        val invalidUnknownStock = withBaseline.replace("\"stockMilliGrams\": 7000", "\"stockMilliGrams\": null")
        val error = expectImportError(invalidUnknownStock)
        assertEquals(CollectionImportException.Code.INVALID_RELATION, error.code)
        assertEquals(0, database.stockMovementDao().count())
    }

    @Test
    fun differentPayloadCannotReuseAnImportedDatasetId() = runBlocking {
        val original = fixture()
        importer.importJson(original)

        val changed = original.replace("Thé de test fictif", "Autre thé fictif")
        val error = expectImportError(changed)

        assertEquals(CollectionImportException.Code.DATASET_ALREADY_IMPORTED, error.code)
        assertEquals(1, database.teaDao().count())
    }

    @Test
    fun importingIntoAnExistingCollectionPreservesItsData() = runBlocking {
        importer.importJson(fixture())
        val differentDataset = fixture().replace(DATASET_ID, "90000000-0000-4000-8000-000000000099")

        val error = expectImportError(differentDataset)

        assertEquals(CollectionImportException.Code.COLLECTION_NOT_EMPTY, error.code)
        assertEquals(1, database.teaDao().count())
        assertEquals("Thé de test fictif", database.teaDao().findById(FIXTURE_TEA_ID)?.name)
        assertEquals(1, database.tastingDao().count())
    }

    @Test
    fun databaseFailureRollsBackReferencesTeasTastingsAndDatasetRecord() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER fail_fixture_tasting BEFORE INSERT ON tastings
                BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""",
        )

        val error = expectImportError(fixture())

        assertEquals(CollectionImportException.Code.DATABASE_FAILURE, error.code)
        assertEquals(0, database.teaDao().count())
        assertEquals(0, database.tastingDao().count())
        assertEquals(0, database.referenceDao().observeByKind("CATEGORY").first().size)
        assertEquals(0, database.referenceDao().observeByKind("REGION").first().size)
        assertNull(database.importedDatasetDao().findAny())
        assertEquals(UserPreferences(), preferencesStore.current)
    }

    private suspend fun expectImportError(json: String): CollectionImportException = try {
        importer.importJson(json)
        throw AssertionError("Expected an import error")
    } catch (error: CollectionImportException) {
        error
    }

    private fun fixture(): String = InstrumentationRegistry.getInstrumentation().context.assets
        .open("fixtures/collection-valid-fictive-v1.json")
        .bufferedReader()
        .use { it.readText() }

    private class RecordingPreferencesStore : UserPreferencesStore {
        var current = UserPreferences()
            private set

        override suspend fun setPreferences(preferences: UserPreferences) {
            current = preferences
        }
    }

    private companion object {
        val FIXTURE_TEA_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000001")
        const val DATASET_ID = "90000000-0000-4000-8000-000000000001"
    }
}
