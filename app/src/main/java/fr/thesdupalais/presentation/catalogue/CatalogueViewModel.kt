package fr.thesdupalais.presentation.catalogue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.TeaDao
import fr.thesdupalais.data.database.TeaWithCatalogData
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class CatalogueTea(
    val id: UUID,
    val name: String,
    val category: String,
    val categoryKey: String?,
    val origins: List<String>,
    val personalNotes: String?,
    val tasteRating: Double?,
)

sealed interface CatalogueUiState {
    data object Loading : CatalogueUiState
    data object Empty : CatalogueUiState
    data class Content(val teas: List<CatalogueTea>) : CatalogueUiState
}

class CatalogueViewModel(teaDao: TeaDao) : ViewModel() {
    val state = teaDao.observeActiveCatalog()
        .map { rows -> rows.map(TeaWithCatalogData::toCatalogueTea) }
        .map { teas -> if (teas.isEmpty()) CatalogueUiState.Empty else CatalogueUiState.Content(teas) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), CatalogueUiState.Loading)
}

class CatalogueViewModelFactory(private val teaDao: TeaDao) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(CatalogueViewModel::class.java.isAssignableFrom(modelClass))
        return CatalogueViewModel(teaDao) as T
    }
}

internal fun TeaWithCatalogData.toCatalogueTea(): CatalogueTea = CatalogueTea(
    id = tea.id,
    name = tea.name,
    category = category.label,
    categoryKey = category.semanticKey,
    origins = formatOrigins(countries, regions),
    personalNotes = tea.personalNotes?.takeIf(String::isNotBlank),
    tasteRating = tea.tasteRating,
)

internal fun formatOrigins(countries: List<ReferenceEntity>, regions: List<ReferenceEntity>): List<String> {
    val countriesById = countries.associateBy { it.id }
    val regionsByCountry = regions.groupBy { it.parentCountryId }
    val countryIdsWithRegion = regions.mapNotNull { it.parentCountryId }.toSet()

    val groupedOrigins = regionsByCountry.entries
        .sortedWith(compareBy({ it.key?.let(countriesById::get)?.displayPriority ?: Int.MAX_VALUE }, { it.key?.let(countriesById::get)?.label.orEmpty() }))
        .map { (countryId, countryRegions) ->
            val regionLabels = countryRegions.sortedWith(compareBy<ReferenceEntity> { it.displayPriority }.thenBy { it.label })
                .joinToString(", ") { it.label }
            val country = countryId?.let(countriesById::get)
            if (country == null) regionLabels else "${country.label} — $regionLabels"
        }

    val countryOnlyOrigins = countries
        .filterNot { it.id in countryIdsWithRegion }
        .sortedWith(compareBy<ReferenceEntity> { it.displayPriority }.thenBy { it.label })
        .map { it.label }

    return groupedOrigins + countryOnlyOrigins
}
