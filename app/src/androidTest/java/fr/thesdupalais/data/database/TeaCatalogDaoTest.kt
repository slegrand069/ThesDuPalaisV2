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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeaCatalogDaoTest {
    private lateinit var database: AppDatabase

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun closeDatabase() = database.close()

    @Test fun observesSortedActiveTeasWithCategoryAndAllOrigins() = runBlocking {
        StandardReferenceSeeder(database).seed()
        val green = StandardReferenceSeeds.categories.single { it.semanticKey == "GREEN" }
        val oolong = StandardReferenceSeeds.categories.single { it.semanticKey == "OOLONG" }
        val japan = addReference(ReferenceKind.COUNTRY, "Japon", priority = 1)
        val india = addReference(ReferenceKind.COUNTRY, "Inde", priority = 2)
        val uji = addReference(ReferenceKind.REGION, "Uji", japan.id, priority = 2)
        val shizuoka = addReference(ReferenceKind.REGION, "Shizuoka", japan.id, priority = 1)
        val oolongTea = tea("zulu Oolong", oolong.id)
        val greenTea = tea("Alpha Sencha", green.id, notes = "Notes de dégustation")
        val archivedTea = tea("Archived tea", green.id, archivedAt = 1234L)
        database.teaDao().insert(oolongTea)
        database.teaDao().insert(greenTea)
        database.teaDao().insert(archivedTea)
        database.teaDao().insertCountries(listOf(TeaCountryEntity(oolongTea.id, japan.id), TeaCountryEntity(oolongTea.id, india.id)))
        database.teaDao().insertRegions(
            listOf(
                TeaRegionEntity(oolongTea.id, uji.id, japan.id),
                TeaRegionEntity(oolongTea.id, shizuoka.id, japan.id),
            ),
        )

        val result = database.teaDao().observeActiveCatalog().first()

        assertEquals(listOf("Alpha Sencha", "zulu Oolong"), result.map { it.tea.name })
        assertEquals("Thé vert", result[0].category.label)
        assertEquals("Oolong", result[1].category.label)
        assertEquals(setOf("Inde", "Japon"), result[1].countries.map { it.label }.toSet())
        assertEquals(setOf("Uji", "Shizuoka"), result[1].regions.map { it.label }.toSet())
        assertEquals("Notes de dégustation", result[0].tea.personalNotes)
    }

    @Test fun emitsAgainWhenRoomCollectionChanges() = runBlocking {
        StandardReferenceSeeder(database).seed()
        val updates = Channel<List<TeaWithCatalogData>>(Channel.UNLIMITED)
        val collector = CoroutineScope(Dispatchers.Main).launch {
            database.teaDao().observeActiveCatalog().take(2).collect { updates.send(it) }
        }
        val initial = withTimeout(5_000) { updates.receive() }
        assertTrue(initial.isEmpty())

        val green = StandardReferenceSeeds.categories.single { it.semanticKey == "GREEN" }
        database.teaDao().insert(tea("Reactive tea", green.id))

        val updated = withTimeout(5_000) { updates.receive() }
        assertEquals(listOf("Reactive tea"), updated.map { it.tea.name })
        collector.join()
    }

    @Test fun catalogueProjectionIncludesStatusAromasAndAromaticProfilesForSearch() = runBlocking {
        StandardReferenceSeeder(database).seed()
        val category = StandardReferenceSeeds.categories.single { it.semanticKey == "GREEN" }
        val status = StandardReferenceSeeds.statuses.first()
        val country = addReference(ReferenceKind.COUNTRY, "Japon")
        val region = addReference(ReferenceKind.REGION, "Uji", country.id)
        val aroma = addReference(ReferenceKind.AROMA, "Jasmin")
        val profile = addReference(ReferenceKind.AROMATIC_PROFILE, "Floral")
        val container = addReference(ReferenceKind.CONTAINER, "Boîte personnalisée")
        val moment = addReference(ReferenceKind.MOMENT, "Après-midi personnalisé")
        val badge = addReference(ReferenceKind.BADGE, "Grand cru personnalisé")
        val tea = tea("Sencha au jasmin", category.id).copy(statusRefId = status.id, containerRefId = container.id)
        database.teaDao().insert(tea)
        database.teaDao().insertCountries(listOf(TeaCountryEntity(tea.id, country.id)))
        database.teaDao().insertRegions(listOf(TeaRegionEntity(tea.id, region.id, country.id)))
        database.teaDao().insertAromas(listOf(TeaAromaEntity(tea.id, aroma.id)))
        database.teaDao().insertAromaticProfiles(listOf(TeaAromaticProfileEntity(tea.id, profile.id)))
        database.teaDao().insertMoments(listOf(TeaMomentEntity(tea.id, moment.id)))
        database.teaDao().insertBadges(listOf(TeaBadgeEntity(tea.id, badge.id)))

        val row = database.teaDao().observeActiveCatalog().first().single()

        assertEquals(status.id, row.status?.id)
        assertEquals(container.id, row.container?.id)
        assertEquals(listOf(aroma.id), row.aromas.map { it.id })
        assertEquals(listOf(profile.id), row.aromaticProfiles.map { it.id })
        assertEquals(listOf(moment.id), row.moments.map { it.id })
        assertEquals(listOf(badge.id), row.badges.map { it.id })
        assertEquals(listOf(country.id), row.countries.map { it.id })
        assertEquals(listOf(region.id), row.regions.map { it.id })
    }

    private suspend fun addReference(
        kind: ReferenceKind,
        label: String,
        parent: UUID? = null,
        priority: Int = 0,
    ): ReferenceEntity {
        val value = ReferenceValue(
            id = UUID.randomUUID(),
            kind = kind,
            label = label,
            normalizedLabel = ReferenceLabelNormalizer.normalize(label),
            semanticKey = null,
            parentCountryId = parent,
            archivedAt = null,
            displayPriority = priority,
        )
        val entity = ReferenceEntity.fromDomain(value)
        database.referenceDao().insertIfAbsent(entity)
        return entity
    }

    private fun tea(name: String, categoryId: UUID, notes: String? = null, archivedAt: Long? = null) = TeaEntity(
        id = UUID.randomUUID(), legacyId = null, name = name, categoryRefId = categoryId,
        description = null, technicalInformation = null, personalNotes = notes,
        olfactoryRating = null, tasteRating = null, brewTemperatureCelsius = null,
        infusionDurationSeconds = null, statusRefId = null, containerRefId = null,
        stockMilliGrams = null, archivedAtEpochMillis = archivedAt,
    )
}
