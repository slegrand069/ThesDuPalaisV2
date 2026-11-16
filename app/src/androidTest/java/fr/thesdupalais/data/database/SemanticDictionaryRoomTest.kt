package fr.thesdupalais.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.thesdupalais.domain.semantics.SemanticConcept
import fr.thesdupalais.domain.semantics.SemanticDomain
import fr.thesdupalais.domain.semantics.SemanticRelationType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.UUID
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

    @Test fun conceptAndSynonymEditsAreAtomicAndKeepTheConceptId() = runBlocking {
        val seeder = SemanticDictionarySeeder(database)
        seeder.seed()
        val repository = SemanticDictionaryRepository(database)
        val concept = SemanticConcept("user-concept-id", SemanticDomain.AROMA, "Groseille")
        repository.saveConcept(concept, listOf("petit fruit rouge"))
        val dao = database.semanticDictionaryDao()
        assertEquals("Groseille", dao.findConcept(concept.id)?.label)
        assertEquals(2, dao.expressionsForConcept(concept.id).count { it.expression.isNotBlank() })
        repository.saveConcept(concept.copy(label = "Groseilles"), listOf("petit fruit rouge", "baie rouge"))
        assertEquals(concept.id, dao.findConcept(concept.id)?.id)
        assertEquals("Groseilles", dao.findConcept(concept.id)?.label)
        assertEquals(3, dao.expressionsForConcept(concept.id).count { it.expression.isNotBlank() })
    }

    @Test fun deletingConceptRemovesOnlyItsRelationsAndUserRows() = runBlocking {
        SemanticDictionarySeeder(database).seed()
        val repository = SemanticDictionaryRepository(database)
        val dao = database.semanticDictionaryDao()
        val custom = SemanticConcept("delete-me", SemanticDomain.AROMA, "Pomme verte")
        repository.saveConcept(custom, listOf("pomme fraîche"))
        val fruityId = BuiltInSemanticDictionary.conceptId(SemanticDomain.AROMA, "fruity")
        repository.createSpecialization(fruityId, custom.id)
        val beforeReferences = database.referenceDao().allForBackup().size
        repository.deleteConcept(custom.id)
        assertEquals(null, dao.findConcept(custom.id))
        assertEquals(0, dao.expressionsForConcept(custom.id).size)
        assertEquals(0, dao.relationsForConcept(custom.id).size)
        assertEquals("Fruité", dao.findConcept(fruityId)?.label)
        assertEquals(beforeReferences, database.referenceDao().allForBackup().size)
    }

    @Test fun seededDeletionAndRelationRemovalSurviveReseeding() = runBlocking {
        val seeder = SemanticDictionarySeeder(database)
        seeder.seed()
        val repository = SemanticDictionaryRepository(database)
        val dao = database.semanticDictionaryDao()
        val fruityId = BuiltInSemanticDictionary.conceptId(SemanticDomain.AROMA, "fruity")
        val berriesId = BuiltInSemanticDictionary.conceptId(SemanticDomain.AROMA, "berries")
        val originalRelation = requireNotNull(dao.findRelation(fruityId, berriesId, SemanticRelationType.SPECIALIZATION.name))
        repository.deleteSpecialization(originalRelation.id)
        repository.deleteConcept(berriesId)
        seeder.seed()
        assertEquals(null, dao.findConcept(berriesId))
        assertEquals(false, dao.findRelation(fruityId, berriesId, SemanticRelationType.SPECIALIZATION.name)?.active)
    }

    @Test fun indispensableIntentConceptsCannotBeDisabledOrDeleted() = runBlocking {
        SemanticDictionarySeeder(database).seed()
        val repository = SemanticDictionaryRepository(database)
        val dao = database.semanticDictionaryDao()
        val id = BuiltInSemanticDictionary.conceptId(SemanticDomain.STOCK, "abundant-stock")
        assertTrue(runCatching { repository.setConceptActive(id, false) }.isFailure)
        assertTrue(runCatching { repository.deleteConcept(id) }.isFailure)
        assertEquals(true, dao.findConcept(id)?.active)
    }

    @Test fun normalizedConflictDoesNotLeavePartialConceptRows() = runBlocking {
        val repository = SemanticDictionaryRepository(database)
        val first = SemanticConcept("first", SemanticDomain.AROMA, "Café")
        val second = SemanticConcept("second", SemanticDomain.AROMA, "Autre")
        repository.saveConcept(first, listOf("baie rouge"))
        assertTrue(runCatching { repository.saveConcept(second, listOf("CAFÉ")) }.isFailure)
        assertEquals(null, database.semanticDictionaryDao().findConcept(second.id))
        assertEquals(0, database.semanticDictionaryDao().expressionsForConcept(second.id).size)
    }

    @Test fun activationChangesArePersistedAndSynonymsResumeResolution() = runBlocking {
        val repository = SemanticDictionaryRepository(database)
        val concept = SemanticConcept("toggle-me", SemanticDomain.AROMA, "Fleuri")
        repository.saveConcept(concept, listOf("bouquet de fleurs"))
        repository.setConceptActive(concept.id, false)
        assertEquals(false, database.semanticDictionaryDao().findConcept(concept.id)?.active)
        assertTrue(database.semanticDictionaryDao().expressionsForConcept(concept.id).filter { it.expression.isNotBlank() }.none { it.active })
        repository.setConceptActive(concept.id, true)
        assertEquals(true, database.semanticDictionaryDao().findConcept(concept.id)?.active)
        assertTrue(database.semanticDictionaryDao().expressionsForConcept(concept.id).filter { it.expression.isNotBlank() }.all { it.active })
    }

    @Test fun referenceLinksMustPointToARealReferenceOfTheSelectedDomain() = runBlocking {
        val referenceId = UUID.randomUUID()
        database.referenceDao().insert(ReferenceEntity(
            referenceId, "CATEGORY", "Thé vert", "the vert", "GREEN", null, "", null, 0,
        ))
        val repository = SemanticDictionaryRepository(database)
        val valid = SemanticConcept("mapped-green", SemanticDomain.CATEGORY, "Thé vert sémantique", businessDimension = "TYPE_COLOR", businessConcept = "category", businessValue = "ref:$referenceId")
        repository.saveConcept(valid, emptyList())
        val invalid = SemanticConcept("wrong-mapping", SemanticDomain.AROMA, "Fausse liaison", businessDimension = "AROMA", businessConcept = "aroma", businessValue = "ref:$referenceId")
        assertTrue(runCatching { repository.saveConcept(invalid, emptyList()) }.isFailure)
        assertEquals(null, database.semanticDictionaryDao().findConcept(invalid.id))
        val arbitrary = SemanticConcept("arbitrary-mapping", SemanticDomain.AROMA, "Arôme inventé", businessDimension = "AROMA", businessConcept = "aroma", businessValue = "NO_SUCH_AROMA")
        assertTrue(runCatching { repository.saveConcept(arbitrary, emptyList()) }.isFailure)
        assertEquals(null, database.semanticDictionaryDao().findConcept(arbitrary.id))
    }

    @Test fun deletedBundledSynonymsStayDeletedAfterReseeding() = runBlocking {
        val seeder = SemanticDictionarySeeder(database)
        seeder.seed()
        val repository = SemanticDictionaryRepository(database)
        val id = BuiltInSemanticDictionary.conceptId(SemanticDomain.AROMA, "fruity")
        val expressions = database.semanticDictionaryDao().expressionsForConcept(id)
        val deleted = requireNotNull(expressions.firstOrNull { it.expression == "fruit" })
        val remaining = expressions.filter { !it.isPrimary && it.expression.isNotBlank() && it.id != deleted.id }.map { it.expression }
        val concept = requireNotNull(database.semanticDictionaryDao().findConcept(id))
        repository.saveConcept(SemanticConcept(concept.id, SemanticDomain.valueOf(concept.domain), concept.label,
            concept.active, concept.businessDimension, concept.businessConcept, concept.businessValue), remaining)
        seeder.seed()
        assertEquals("", database.semanticDictionaryDao().findExpression(deleted.id)?.expression)
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

    @Test fun migration7To8AddsDeletionMarkerWithoutRemovingSeededConcepts() {
        val helper = androidx.room.testing.MigrationTestHelper(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
            emptyList(),
            androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory(),
        )
        val name = "semantic-dictionary-tombstone-migration"
        helper.createDatabase(name, 7).apply {
            execSQL("INSERT INTO semantic_concepts (id, domain, label, active, businessDimension, businessConcept, businessValue) VALUES ('concept-id', 'AROMA', 'Fruité', 1, 'AROMA', 'aroma', 'FRUITY')")
            close()
        }
        val migrated = helper.runMigrationsAndValidate(name, 8, true, MIGRATION_7_8)
        migrated.query("SELECT label, deleted FROM semantic_concepts WHERE id = 'concept-id'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Fruité", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        migrated.close()
    }

    @Test fun dictionaryArchiveRoundTripPreservesCustomizationsAndDeletionMarkers() = runBlocking {
        val seeder = SemanticDictionarySeeder(database)
        seeder.seed()
        val repository = SemanticDictionaryRepository(database)
        val dao = database.semanticDictionaryDao()
        val berriesId = BuiltInSemanticDictionary.conceptId(SemanticDomain.AROMA, "berries")
        val fruityId = BuiltInSemanticDictionary.conceptId(SemanticDomain.AROMA, "fruity")
        val fruity = requireNotNull(dao.findConcept(fruityId))
        repository.saveConcept(SemanticConcept(fruityId, SemanticDomain.AROMA, "Profil fruité", true,
            fruity.businessDimension, fruity.businessConcept, fruity.businessValue), listOf("note de fruits"))
        val custom = SemanticConcept("archive-custom-concept", SemanticDomain.AROMA, "Goyave", businessDimension = "AROMA", businessConcept = "aroma", businessValue = "ref:${UUID.randomUUID()}")
        // Reference validation is exercised separately; an unlinked custom concept is portable everywhere.
        repository.saveConcept(custom.copy(businessDimension = null, businessConcept = null, businessValue = null), listOf("fruit tropical"))
        repository.setConceptActive(custom.id, false)
        repository.deleteConcept(berriesId)
        val exported = SemanticDictionaryArchiveJson.encode(repository.exportArchive())
        val beforeImport = repository.exportArchive()

        repository.saveConcept(SemanticConcept(fruityId, SemanticDomain.AROMA, "Autre libellé", true,
            fruity.businessDimension, fruity.businessConcept, fruity.businessValue), emptyList())
        repository.deleteConcept(custom.id)
        repository.replaceArchive(exported)
        seeder.seed()

        val restored = repository.exportArchive()
        assertEquals(SemanticDictionaryArchiveJson.encode(beforeImport), SemanticDictionaryArchiveJson.encode(restored))
        assertEquals(null, dao.findConcept(berriesId))
        assertEquals("", dao.expressionsForConcept(berriesId).first { it.isPrimary }.expression)
        assertEquals("Profil fruité", dao.findConcept(fruityId)?.label)
        assertEquals(false, dao.findConcept(custom.id)?.active)
        assertEquals(true, dao.allConceptRowsForBackup().first { it.id == berriesId }.deleted)
    }

    @Test fun invalidDictionaryReplacementLeavesDictionaryAndReferencesUntouched() = runBlocking {
        StandardReferenceSeeder(database).seed()
        SemanticDictionarySeeder(database).seed()
        val repository = SemanticDictionaryRepository(database)
        val category = requireNotNull(database.referenceDao().findBySemanticKey("CATEGORY", "GREEN"))
        val teaId = TeaRepository(database).create(NewTea("Thé préservé", category.id, stockMilliGrams = 12_000L))
        val teaBefore = requireNotNull(database.teaDao().findById(teaId))
        val before = SemanticDictionaryArchiveJson.encode(repository.exportArchive())
        val referenceCount = database.referenceDao().allForBackup().size
        val invalidFuture = before.replace("\"version\": 1", "\"version\": 2")
        assertTrue(runCatching { repository.replaceArchive(invalidFuture) }.isFailure)
        val unknownReference = "10000000-0000-0000-0000-000000000099"
        val invalidArchive = repository.exportArchive().copy(
            concepts = repository.exportArchive().concepts + SemanticConceptEntity("missing-ref", "AROMA", "Référence absente", true, "AROMA", "aroma", "ref:$unknownReference"),
            expressions = repository.exportArchive().expressions + SemanticExpressionEntity("missing-ref-expression", "missing-ref", "référence absente", "reference absente", true, true),
        )
        assertTrue(runCatching { repository.replaceArchive(SemanticDictionaryArchiveJson.encode(invalidArchive)) }.isFailure)
        assertEquals(before, SemanticDictionaryArchiveJson.encode(repository.exportArchive()))
        assertEquals(teaBefore, database.teaDao().findById(teaId))
        assertEquals(0, database.tastingDao().count())
        assertEquals(referenceCount, database.referenceDao().allForBackup().size)
    }
}
