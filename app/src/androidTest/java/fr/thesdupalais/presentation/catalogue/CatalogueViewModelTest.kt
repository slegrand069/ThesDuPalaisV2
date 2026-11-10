package fr.thesdupalais.presentation.catalogue

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.NewTea
import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.StandardReferenceSeeder
import fr.thesdupalais.data.database.TeaEditableField
import fr.thesdupalais.data.database.TeaRepository
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import fr.thesdupalais.domain.references.StandardReferenceSeeds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogueViewModelTest {
    private lateinit var database: AppDatabase

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        StandardReferenceSeeder(database).seed()
        insertReference(ReferenceKind.COUNTRY, "Pays associé")
        insertReference(ReferenceKind.COUNTRY, "Pays sans thé")
        Unit
    }

    @After fun tearDown() = database.close()

    private suspend fun insertReference(kind: ReferenceKind, label: String): ReferenceEntity {
        val reference = ReferenceEntity.fromDomain(
            ReferenceValue(
                id = java.util.UUID.randomUUID(), kind = kind, label = label,
                normalizedLabel = ReferenceLabelNormalizer.normalize(label),
            ),
        )
        database.referenceDao().insert(
            reference,
        )
        return reference
    }

    @Test fun filtersReactivelyToRoomUpdatesAndKeepQueryState() = runBlocking {
        val category = requireNotNull(database.referenceDao().findBySemanticKey("CATEGORY", "GREEN"))
        val countries = database.referenceDao().observeByKind(ReferenceKind.COUNTRY.name).first()
        val usedCountry = countries.first()
        val unusedCountry = countries[1]
        val status = database.referenceDao().observeByKind(ReferenceKind.STATUS.name).first().first()
        val profile = insertReference(ReferenceKind.AROMATIC_PROFILE, "Profil fictif")
        val badge = insertReference(ReferenceKind.BADGE, "Badge fictif")
        val container = insertReference(ReferenceKind.CONTAINER, "Contenant fictif")
        val moment = insertReference(ReferenceKind.MOMENT, "Moment fictif")
        val repository = TeaRepository(database)
        val teaId = repository.create(NewTea(
            name = "Sencha du matin", categoryId = category.id, countryIds = listOf(usedCountry.id), statusId = status.id,
            aromaticProfileIds = listOf(profile.id), badgeIds = listOf(badge.id), containerId = container.id, momentIds = listOf(moment.id),
        ))
        val viewModel = CatalogueViewModel(database.teaDao())

        val initial = withTimeout(5_000) { viewModel.state.first { it is CatalogueUiState.Content } } as CatalogueUiState.Content
        assertEquals(listOf("Sencha du matin"), initial.teas.map { it.name })
        assertTrue(initial.options.categories.any { it.id == category.id })
        assertEquals(listOf(usedCountry.id), initial.options.countries.map { it.id })
        assertEquals(listOf(status.id), initial.options.statuses.map { it.id })
        assertEquals(listOf(profile.id), initial.options.aromaticProfiles.map { it.id })
        assertEquals(listOf(badge.id), initial.options.badges.map { it.id })
        assertEquals(listOf(container.id), initial.options.containers.map { it.id })
        assertEquals(listOf(moment.id), initial.options.moments.map { it.id })
        assertTrue(initial.options.countries.none { it.id == unusedCountry.id })

        viewModel.setQuery("sencha")
        val searched = withTimeout(5_000) { viewModel.state.first { it is CatalogueUiState.Content && it.criteria.query == "sencha" } } as CatalogueUiState.Content
        assertEquals(teaId, searched.teas.single().id)

        viewModel.setCountries(setOf(usedCountry.id))
        withTimeout(5_000) { viewModel.state.first { it is CatalogueUiState.Content && it.criteria.countryIds == setOf(usedCountry.id) } }
        repository.update(
            teaId,
            NewTea(name = "Sencha du matin", categoryId = category.id, countryIds = listOf(unusedCountry.id), statusId = status.id),
            setOf(TeaEditableField.COUNTRIES),
        )
        val optionsUpdated = withTimeout(5_000) {
            viewModel.state.first { it is CatalogueUiState.Content && it.options.countries.singleOrNull()?.id == unusedCountry.id }
        } as CatalogueUiState.Content
        assertEquals(setOf(usedCountry.id), optionsUpdated.criteria.countryIds)
        assertTrue(optionsUpdated.noResults)

        viewModel.setCountries(emptySet())
        database.teaDao().updateEditableFields(
            teaId, "Sencha du matin modifié", category.id, null, null, null, null, null,
            null, null, null, null,
        )
        viewModel.setQuery("")
        val updated = withTimeout(5_000) {
            viewModel.state.first { it is CatalogueUiState.Content && it.teas.any { tea -> tea.name == "Sencha du matin modifié" } }
        } as CatalogueUiState.Content
        assertEquals("", updated.criteria.query)
        assertEquals("Sencha du matin modifié", updated.teas.single().name)
    }
}
