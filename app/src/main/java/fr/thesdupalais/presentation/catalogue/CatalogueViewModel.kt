package fr.thesdupalais.presentation.catalogue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.TeaDao
import fr.thesdupalais.data.database.TeaWithCatalogData
import fr.thesdupalais.domain.madjo.MadjoConversationEngine
import fr.thesdupalais.domain.madjo.MadjoConversationState
import fr.thesdupalais.domain.madjo.MadjoQuestionId
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class CatalogueTea(
    val id: UUID,
    val name: String,
    val category: String,
    val categoryKey: String?,
    val categoryId: UUID = UUID(0L, 0L),
    val origins: List<String>,
    val countryIds: Set<UUID> = emptySet(),
    val personalNotes: String?,
    val tasteRating: Double?,
    val statusId: UUID? = null,
    val searchableFields: List<String> = listOf(name, category) + origins,
    val aromaticProfileIds: Set<UUID> = emptySet(),
    val badgeIds: Set<UUID> = emptySet(),
    val containerId: UUID? = null,
    val momentIds: Set<UUID> = emptySet(),
)

data class CatalogueCriteria(
    val query: String = "",
    val categoryIds: Set<UUID> = emptySet(),
    val countryIds: Set<UUID> = emptySet(),
    val statusIds: Set<UUID> = emptySet(),
    val includeUnspecifiedStatus: Boolean = false,
    val aromaticProfileIds: Set<UUID> = emptySet(),
    val badgeIds: Set<UUID> = emptySet(),
    val containerIds: Set<UUID> = emptySet(),
    val momentIds: Set<UUID> = emptySet(),
    val tasteRating: TasteRatingFilter = TasteRatingFilter.ALL,
) {
    val hasFilters: Boolean get() = categoryIds.isNotEmpty() || countryIds.isNotEmpty() || statusIds.isNotEmpty() || includeUnspecifiedStatus ||
        aromaticProfileIds.isNotEmpty() || badgeIds.isNotEmpty() || containerIds.isNotEmpty() || momentIds.isNotEmpty() || tasteRating != TasteRatingFilter.ALL
    val hasAnyCriteria: Boolean get() = query.isNotBlank() || hasFilters
}

data class CatalogueFilterOptions(
    val categories: List<ReferenceEntity> = emptyList(),
    val countries: List<ReferenceEntity> = emptyList(),
    val statuses: List<ReferenceEntity> = emptyList(),
    val aromaticProfiles: List<ReferenceEntity> = emptyList(),
    val badges: List<ReferenceEntity> = emptyList(),
    val containers: List<ReferenceEntity> = emptyList(),
    val moments: List<ReferenceEntity> = emptyList(),
    val hasUnspecifiedStatus: Boolean = false,
)

sealed interface CatalogueUiState {
    data object Loading : CatalogueUiState
    data class Empty(val criteria: CatalogueCriteria = CatalogueCriteria(), val options: CatalogueFilterOptions = CatalogueFilterOptions(), val madjoRequest: Boolean = false, val madjoRequestText: String? = null, val madjoConversation: MadjoConversationState? = null) : CatalogueUiState
    data class Content(
        val teas: List<CatalogueTea>,
        val criteria: CatalogueCriteria = CatalogueCriteria(),
        val options: CatalogueFilterOptions = CatalogueFilterOptions(),
        val madjoRequest: Boolean = false,
        val noResults: Boolean = false,
        val madjoRequestText: String? = null,
        val madjoConversation: MadjoConversationState? = null,
    ) : CatalogueUiState
}

enum class TasteRatingFilter(val label: String) {
    ALL("Toutes"), EXACTLY_10("10/10"), AT_LEAST_9("9 et +"), AT_LEAST_8("8 et +"),
    AT_LEAST_7("7 et +"), AT_LEAST_6("6 et +"), UNSPECIFIED("Non renseignée"),
}

class CatalogueViewModel(teaDao: TeaDao) : ViewModel() {
    private val criteria = MutableStateFlow(CatalogueCriteria())
    private val madjoConversation = MutableStateFlow<MadjoConversationState?>(null)
    val currentCriteria get() = criteria.value

    fun setQuery(query: String) { criteria.value = criteria.value.copy(query = query) }
    fun submitQuery() {
        val query = criteria.value.query
        madjoConversation.value = query.takeIf(::containsMadjoInvocation)?.let(MadjoConversationEngine::start)
    }
    fun submitMadjoAnswer(questionId: MadjoQuestionId, answer: String) {
        madjoConversation.value?.let { current ->
            madjoConversation.value = MadjoConversationEngine.submitAnswer(current, questionId, answer)
        }
    }
    fun requestMadjoRecommendations() {
        madjoConversation.value?.let { current -> madjoConversation.value = MadjoConversationEngine.requestRecommendations(current) }
    }
    fun dismissMadjo() { madjoConversation.value = null }
    fun setCategories(ids: Set<UUID>) { criteria.value = criteria.value.copy(categoryIds = ids) }
    fun setCountries(ids: Set<UUID>) { criteria.value = criteria.value.copy(countryIds = ids) }
    fun setStatuses(ids: Set<UUID>, includeUnspecified: Boolean) {
        criteria.value = criteria.value.copy(statusIds = ids, includeUnspecifiedStatus = includeUnspecified)
    }
    fun setAromaticProfiles(ids: Set<UUID>) { criteria.value = criteria.value.copy(aromaticProfileIds = ids) }
    fun setBadges(ids: Set<UUID>) { criteria.value = criteria.value.copy(badgeIds = ids) }
    fun setContainers(ids: Set<UUID>) { criteria.value = criteria.value.copy(containerIds = ids) }
    fun setMoments(ids: Set<UUID>) { criteria.value = criteria.value.copy(momentIds = ids) }
    fun setTasteRating(filter: TasteRatingFilter) { criteria.value = criteria.value.copy(tasteRating = filter) }
    fun resetFilters() { criteria.value = criteria.value.copy(
        categoryIds = emptySet(), countryIds = emptySet(), statusIds = emptySet(), includeUnspecifiedStatus = false,
        aromaticProfileIds = emptySet(), badgeIds = emptySet(), containerIds = emptySet(), momentIds = emptySet(), tasteRating = TasteRatingFilter.ALL,
    ) }
    fun resetAll() { criteria.value = CatalogueCriteria(); madjoConversation.value = null }

    val state = combine(teaDao.observeActiveCatalog(), criteria, madjoConversation) { rows, current, conversation ->
        val allTeas = rows.map(TeaWithCatalogData::toCatalogueTea)
        val options = rows.toFilterOptions()
        if (allTeas.isEmpty()) return@combine CatalogueUiState.Empty(current, options, conversation != null, conversation?.initialRequest, conversation)
        if (conversation != null) {
            return@combine CatalogueUiState.Content(emptyList(), current, options, madjoRequest = true, madjoRequestText = conversation.initialRequest, madjoConversation = conversation)
        }
        val visible = allTeas.filter { tea -> matchesCatalogueTea(tea, current) }
        CatalogueUiState.Content(visible, current, options, noResults = visible.isEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), CatalogueUiState.Loading)
}

class CatalogueViewModelFactory(private val teaDao: TeaDao) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(CatalogueViewModel::class.java.isAssignableFrom(modelClass))
        return CatalogueViewModel(teaDao) as T
    }
}

enum class CatalogueQueryRoute { CLASSIC, MADJO }

internal fun routeCatalogueQuery(query: String): CatalogueQueryRoute =
    if (containsMadjoInvocation(query)) CatalogueQueryRoute.MADJO else CatalogueQueryRoute.CLASSIC

/** Matches a standalone name (or a one-edit typo) after accent and punctuation normalization. */
internal fun containsMadjoInvocation(query: String): Boolean =
    normalizeCatalogueSearch(query).split(' ').any { token ->
        token == "madjo" || token == "majdo" ||
            levenshteinDistanceAtMostOne(token, "madjo") || levenshteinDistanceAtMostOne(token, "majdo")
    }

private fun levenshteinDistanceAtMostOne(left: String, right: String): Boolean {
    if (left.isEmpty() || kotlin.math.abs(left.length - right.length) > 1) return false
    if (left.length == right.length) {
        return left.indices.count { left[it] != right[it] } <= 1
    }
    val shorter = if (left.length < right.length) left else right
    val longer = if (left.length < right.length) right else left
    var shortIndex = 0
    var longIndex = 0
    var skipped = false
    while (shortIndex < shorter.length && longIndex < longer.length) {
        if (shorter[shortIndex] == longer[longIndex]) {
            shortIndex++
            longIndex++
        } else {
            if (skipped) return false
            skipped = true
            longIndex++
        }
    }
    return true
}

internal fun normalizeCatalogueSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .replace(Regex("[\\p{P}\\p{S}]+"), " ")
    .lowercase(Locale.ROOT)
    .trim()
    .replace(Regex("\\s+"), " ")

internal fun matchesCatalogueTea(tea: CatalogueTea, criteria: CatalogueCriteria): Boolean {
    if (!matchesAny(criteria.categoryIds, setOf(tea.categoryId))) return false
    if (!matchesAny(criteria.countryIds, tea.countryIds)) return false
    if (!matchesAny(criteria.aromaticProfileIds, tea.aromaticProfileIds)) return false
    if (!matchesAny(criteria.badgeIds, tea.badgeIds)) return false
    if (!matchesAny(criteria.containerIds, setOfNotNull(tea.containerId))) return false
    if (!matchesAny(criteria.momentIds, tea.momentIds)) return false
    if (criteria.statusIds.isNotEmpty() || criteria.includeUnspecifiedStatus) {
        val statusMatch = tea.statusId?.let { it in criteria.statusIds } ?: criteria.includeUnspecifiedStatus
        if (!statusMatch) return false
    }
    if (!matchesTasteRating(tea.tasteRating, criteria.tasteRating)) return false
    val tokens = normalizeCatalogueSearch(criteria.query).split(' ').filter(String::isNotBlank)
    if (tokens.isEmpty()) return true
    val fields = tea.searchableFields.map(::normalizeCatalogueSearch)
    return tokens.all { token -> fields.any { field -> token in field } }
}

private fun matchesAny(selected: Set<UUID>, actual: Set<UUID>): Boolean = selected.isEmpty() || selected.any { it in actual }

internal fun matchesTasteRating(rating: Double?, filter: TasteRatingFilter): Boolean = when (filter) {
    TasteRatingFilter.ALL -> true
    TasteRatingFilter.EXACTLY_10 -> rating == 10.0
    TasteRatingFilter.AT_LEAST_9 -> rating != null && rating >= 9.0
    TasteRatingFilter.AT_LEAST_8 -> rating != null && rating >= 8.0
    TasteRatingFilter.AT_LEAST_7 -> rating != null && rating >= 7.0
    TasteRatingFilter.AT_LEAST_6 -> rating != null && rating >= 6.0
    TasteRatingFilter.UNSPECIFIED -> rating == null
}

private fun List<TeaWithCatalogData>.toFilterOptions(): CatalogueFilterOptions {
    fun sorted(values: List<ReferenceEntity>) = values.distinctBy { it.id }.sortedBy { it.label.lowercase(Locale.FRENCH) }
    val rows = this // observeActiveCatalog() already excludes archived teas; selected filters never narrow these rows.
    return CatalogueFilterOptions(
        categories = sorted(rows.map { it.category }),
        countries = sorted(rows.flatMap { it.countries }),
        statuses = sorted(rows.mapNotNull { it.status }),
        aromaticProfiles = sorted(rows.flatMap { it.aromaticProfiles }),
        badges = sorted(rows.flatMap { it.badges }),
        containers = sorted(rows.mapNotNull { it.container }),
        moments = sorted(rows.flatMap { it.moments }),
        hasUnspecifiedStatus = rows.any { it.status == null },
    )
}

internal fun TeaWithCatalogData.toCatalogueTea(): CatalogueTea = CatalogueTea(
    id = tea.id,
    name = tea.name,
    category = category.label,
    categoryKey = category.semanticKey,
    categoryId = category.id,
    origins = formatOrigins(countries, regions),
    countryIds = countries.map { it.id }.toSet(),
    personalNotes = tea.personalNotes?.takeIf(String::isNotBlank),
    tasteRating = tea.tasteRating,
    statusId = status?.id,
    searchableFields = listOf(tea.name, category.label) + countries.map { it.label } + regions.map { it.label } + aromas.map { it.label } + aromaticProfiles.map { it.label },
    aromaticProfileIds = aromaticProfiles.map { it.id }.toSet(),
    badgeIds = badges.map { it.id }.toSet(),
    containerId = container?.id,
    momentIds = moments.map { it.id }.toSet(),
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
