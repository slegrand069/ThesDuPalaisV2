package fr.thesdupalais.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArticleMigrationTest {
    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration5To6AddsArticlesAndPreservesExistingTeaRows() {
        val name = "articles-migration"
        helper.createDatabase(name, 5).apply {
            execSQL(
                """INSERT INTO reference_values
                    (id, kind, label, normalizedLabel, semanticKey, parentCountryId, scopeKey, archivedAt, displayPriority)
                    VALUES ('00000000-0000-4000-8000-000000000002', 'CATEGORY', 'Vert', 'vert', 'GREEN', NULL, '', NULL, 0)""",
            )
            execSQL(
                """INSERT INTO teas (id, legacyId, name, categoryRefId, description, technicalInformation,
                    personalNotes, olfactoryRating, tasteRating, brewTemperatureCelsius, infusionDurationSeconds,
                    statusRefId, containerRefId, stockMilliGrams, archivedAtEpochMillis)
                    VALUES ('00000000-0000-4000-8000-000000000001', NULL, 'Thé témoin',
                    '00000000-0000-4000-8000-000000000002', NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                    NULL, NULL, NULL, NULL)""",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(name, 6, true, MIGRATION_5_6)
        migrated.query("SELECT name FROM teas WHERE id = '00000000-0000-4000-8000-000000000001'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Thé témoin", cursor.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM articles").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }
}
