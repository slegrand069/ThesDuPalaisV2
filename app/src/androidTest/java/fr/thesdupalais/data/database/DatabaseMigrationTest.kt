package fr.thesdupalais.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.thesdupalais.domain.references.StandardReferenceSeeds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val factory = FrameworkSQLiteOpenHelperFactory()
    private var oldDatabase: SupportSQLiteOpenHelper? = null
    private var migratedDatabase: SupportSQLiteOpenHelper? = null

    @Before
    fun removePreviousTestDatabase() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun closeAndRemoveTestDatabase() {
        oldDatabase?.close()
        migratedDatabase?.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migrationPreservesExistingReferencesAndChangesColorKindToCategory() {
        val legacyCategories = listOf(
            LegacyCategory("GREEN", "Vert", "vert", 12345L),
            LegacyCategory("BLACK", "Noir", "noir", null),
            LegacyCategory("WHITE", "Blanc", "blanc", null),
            LegacyCategory("YELLOW", "Jaune", "jaune", null),
            LegacyCategory("OOLONG", "Oolong", "oolong", null),
            LegacyCategory("PU_ERH", "Pu'Erh", "pu'erh", null),
        )
        oldDatabase = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE reference_values (
                                id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, label TEXT NOT NULL,
                                normalizedLabel TEXT NOT NULL, semanticKey TEXT, parentCountryId TEXT,
                                scopeKey TEXT NOT NULL, archivedAt INTEGER)""",
                        )
                        db.execSQL("CREATE UNIQUE INDEX index_reference_values_kind_normalizedLabel_scopeKey ON reference_values (kind, normalizedLabel, scopeKey)")
                        db.execSQL("CREATE UNIQUE INDEX index_reference_values_kind_semanticKey ON reference_values (kind, semanticKey)")
                        db.execSQL("CREATE INDEX index_reference_values_parentCountryId ON reference_values (parentCountryId)")
                        legacyCategories.forEach { category ->
                            val id = StandardReferenceSeeds.categories
                                .single { it.semanticKey == category.semanticKey }.id.toString()
                            db.execSQL(
                                """INSERT INTO reference_values
                                    (id, kind, label, normalizedLabel, semanticKey, parentCountryId, scopeKey, archivedAt)
                                    VALUES (?, 'COLOR', ?, ?, ?, NULL, '', ?)""",
                                arrayOf<Any?>(id, category.label, category.normalizedLabel, category.semanticKey, category.archivedAt),
                            )
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("Unexpected upgrade while creating schema v1")
                })
                .build(),
        )
        oldDatabase!!.writableDatabase.close()

        migratedDatabase = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) =
                        error("Expected existing schema v1")

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        db.beginTransaction()
                        try {
                            MIGRATION_1_2.migrate(db)
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    }
                })
                .build(),
        )
        val migrated = migratedDatabase!!.writableDatabase
        migrated.query(
            "SELECT id, kind, label, normalizedLabel, semanticKey, parentCountryId, scopeKey, archivedAt, displayPriority " +
                "FROM reference_values WHERE kind = 'CATEGORY'",
        ).use { cursor ->
            assertEquals(legacyCategories.size, cursor.count)
        }
        legacyCategories.forEach { expected ->
            migrated.query(
                "SELECT id, kind, label, normalizedLabel, semanticKey, parentCountryId, scopeKey, archivedAt, displayPriority " +
                    "FROM reference_values WHERE kind = 'CATEGORY' AND semanticKey = ?",
                arrayOf(expected.semanticKey),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    StandardReferenceSeeds.categories.single { it.semanticKey == expected.semanticKey }.id.toString(),
                    cursor.getString(0),
                )
                assertEquals("CATEGORY", cursor.getString(1))
                assertEquals(expected.label, cursor.getString(2))
                assertEquals(expected.normalizedLabel, cursor.getString(3))
                assertEquals(expected.semanticKey, cursor.getString(4))
                assertTrue(cursor.isNull(5))
                assertEquals("", cursor.getString(6))
                if (expected.archivedAt == null) assertTrue(cursor.isNull(7))
                else assertEquals(expected.archivedAt, cursor.getLong(7))
                assertEquals(0, cursor.getInt(8))
            }
        }
        migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test
    fun migrationV2ToV3AddsTransactionalDatasetLedger() {
        oldDatabase = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE version_two_marker (id INTEGER NOT NULL PRIMARY KEY)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("Unexpected upgrade while creating schema v2")
                })
                .build(),
        )
        oldDatabase!!.writableDatabase
        oldDatabase!!.close()

        migratedDatabase = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("Expected existing schema v2")

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        db.beginTransaction()
                        try {
                            MIGRATION_2_3.migrate(db)
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    }
                })
                .build(),
        )
        migratedDatabase!!.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'imported_datasets'",
        ).use { cursor -> assertTrue(cursor.moveToFirst()) }
    }

    private companion object {
        const val TEST_DATABASE = "migration-test"
    }

    private data class LegacyCategory(
        val semanticKey: String,
        val label: String,
        val normalizedLabel: String,
        val archivedAt: Long?,
    )
}
