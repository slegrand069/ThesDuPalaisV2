package fr.thesdupalais.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticDictionaryRoomTest {
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun seedingIsIdempotentAndKeepsUserCustomization() = runBlocking {
        val seeder = SemanticDictionarySeeder(database)
        seeder.seed()
        val dao = database.semanticDictionaryDao()
        val seeded = dao.allConcepts()
        val original = requireNotNull(dao.findConcept(BuiltInSemanticDictionary.conceptId(fr.thesdupalais.domain.semantics.SemanticDomain.AROMA, "fruity")))
        dao.updateConcept(original.copy(label = "Libellé personnalisé"))
        seeder.seed()
        assertEquals(seeded.size, dao.allConcepts().size)
        assertEquals("Libellé personnalisé", dao.findConcept(original.id)?.label)
        assertEquals(BuiltInSemanticDictionary.expressionRows.size, dao.allExpressions().size)
        assertEquals(BuiltInSemanticDictionary.relationRows.size, dao.allRelations().size)
    }

    @Test fun migration6To7AddsDictionaryAndPreservesExistingTeaRows() {
        val helper = androidx.room.testing.MigrationTestHelper(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
            emptyList(),
            androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory(),
        )
        val name = "semantic-dictionary-migration"
        helper.createDatabase(name, 6).apply {
            execSQL("INSERT INTO reference_values (id, kind, label, normalizedLabel, semanticKey, parentCountryId, scopeKey, archivedAt, displayPriority) VALUES ('00000000-0000-4000-8000-000000000002', 'CATEGORY', 'Vert', 'vert', 'GREEN', NULL, '', NULL, 0)")
            execSQL("INSERT INTO teas (id, legacyId, name, categoryRefId, description, technicalInformation, personalNotes, olfactoryRating, tasteRating, brewTemperatureCelsius, infusionDurationSeconds, statusRefId, containerRefId, stockMilliGrams, archivedAtEpochMillis) VALUES ('00000000-0000-4000-8000-000000000001', NULL, 'Thé témoin', '00000000-0000-4000-8000-000000000002', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)")
            close()
        }
        val migrated = helper.runMigrationsAndValidate(name, 7, true, MIGRATION_6_7)
        migrated.query("SELECT name FROM teas WHERE id = '00000000-0000-4000-8000-000000000001'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Thé témoin", cursor.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM semantic_concepts").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }
}
