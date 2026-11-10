package fr.thesdupalais.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.thesdupalais.domain.model.ReferenceKind
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
class ReferenceEditingRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomReferenceRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomReferenceRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun createCleansLabelAndEnforcesNormalizedUniquenessByKind() = runBlocking {
        val created = repository.create(ReferenceKind.AROMA, "  Fleur   de   thé  ")
        assertEquals("Fleur de thé", database.referenceDao().findById(created)?.label)
        assertFails("Une valeur identique") { repository.create(ReferenceKind.AROMA, "fleur de thé") }
        repository.create(ReferenceKind.BADGE, "Fleur de thé")
        Unit
    }

    @Test fun createRejectsBlankAndMoreThanOneHundredCleanedCharacters() = runBlocking {
        assertFails("Saisissez un nom") { repository.create(ReferenceKind.BADGE, "  \n  ") }
        assertFails("100 caractères") { repository.create(ReferenceKind.BADGE, "x".repeat(101)) }
        repository.create(ReferenceKind.BADGE, "x".repeat(100))
        Unit
    }

    @Test fun regionRequiresCountryAndUniquenessIsScopedToParentCountry() = runBlocking {
        assertFails("Sélectionnez un pays") { repository.create(ReferenceKind.REGION, "Nord") }
        val japan = repository.create(ReferenceKind.COUNTRY, "Japon")
        val china = repository.create(ReferenceKind.COUNTRY, "Chine")
        val japanRegion = repository.create(ReferenceKind.REGION, "Nord", japan)
        repository.create(ReferenceKind.REGION, "Nord", china)
        assertFails("identique") { repository.create(ReferenceKind.REGION, " nord ", japan) }
        assertEquals(japan, database.referenceDao().findById(japanRegion)?.parentCountryId)
        assertEquals(japan.toString(), database.referenceDao().findById(japanRegion)?.scopeKey)
    }

    @Test fun renamePreservesIdentitySemanticKeyAndTeaAssociationsAndSurvivesSeed() = runBlocking {
        StandardReferenceSeeder(database).seed()
        val standard = StandardReferenceSeeds.categories.single { it.semanticKey == "GREEN" }
        val tea = TeaEntity(
            id = UUID.randomUUID(), legacyId = null, name = "Fiche fictive de test", categoryRefId = standard.id,
            description = null, technicalInformation = null, personalNotes = null, olfactoryRating = null, tasteRating = null,
            brewTemperatureCelsius = null, infusionDurationSeconds = null, statusRefId = StandardReferenceSeeds.statuses.first().id,
            containerRefId = null, stockMilliGrams = null, archivedAtEpochMillis = null,
        )
        database.teaDao().insert(tea)
        repository.rename(standard.id, "Thé vert renommé")
        StandardReferenceSeeder(database).seed()

        val renamed = database.referenceDao().findById(standard.id)!!
        assertEquals("Thé vert renommé", renamed.label)
        assertEquals(standard.semanticKey, renamed.semanticKey)
        assertEquals(standard.id, renamed.id)
        assertEquals(1, repository.observe(ReferenceKind.CATEGORY).first().single { it.value.id == standard.id }.teaUsageCount)
    }

    @Test fun renameRejectsDuplicateAndDoesNotOverwriteOtherReferenceFields() = runBlocking {
        val country = repository.create(ReferenceKind.COUNTRY, "Japon")
        val region = repository.create(ReferenceKind.REGION, "Shizuoka", country)
        repository.create(ReferenceKind.REGION, "Uji", country)
        val before = database.referenceDao().findById(region)!!
        assertFails("identique") { repository.rename(region, " UJI ") }
        repository.rename(region, "  Shizuoka Est ")
        val after = database.referenceDao().findById(region)!!
        assertEquals("Shizuoka Est", after.label)
        assertEquals(before.id, after.id)
        assertEquals(before.semanticKey, after.semanticKey)
        assertEquals(before.parentCountryId, after.parentCountryId)
        assertEquals(before.scopeKey, after.scopeKey)
        assertNull(after.semanticKey)
        assertTrue(after.archivedAt == before.archivedAt)
    }

    private suspend fun assertFails(messagePart: String, action: suspend () -> Unit) {
        try { action(); throw AssertionError("Expected failure containing: $messagePart") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(messagePart, ignoreCase = true)) }
        catch (error: IllegalStateException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(messagePart, ignoreCase = true)) }
    }
}
