package fr.thesdupalais.presentation.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.TastingDao
import fr.thesdupalais.data.database.TastingEntity
import fr.thesdupalais.data.database.TeaDao
import fr.thesdupalais.data.database.TeaRepository
import fr.thesdupalais.data.database.TeaWithDetailData
import fr.thesdupalais.data.preferences.UserPreferences
import fr.thesdupalais.presentation.catalogue.formatOrigins
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

data class TeaDetail(
    val id: UUID,
    val name: String,
    val category: String,
    val origins: List<String>,
    val personalStatus: String?,
    val container: String?,
    val description: String?,
    val technicalInformation: String?,
    val tasteRating: Double?,
    val olfactoryRating: Double?,
    val brewTemperatureCelsius: Int?,
    val infusionDurationSeconds: Int?,
    val personalNotes: String?,
    val aromas: List<String>,
    val aromaticProfiles: List<String>,
    val moments: List<String>,
    val badges: List<String>,
    val stockMilliGrams: Long? = null,
)

data class TeaTasting(val id: UUID, val occurredAtEpochMillis: Long, val consumedMilliGrams: Long?)

sealed interface TeaDetailUiState {
    data object Loading : TeaDetailUiState
    data object Unavailable : TeaDetailUiState
    data class Content(val tea: TeaDetail, val tastings: List<TeaTasting>) : TeaDetailUiState
}

class TeaDetailViewModel(
    private val teaId: UUID,
    teaDao: TeaDao,
    tastingDao: TastingDao,
    private val teaRepository: TeaRepository,
    gramsPerCupMilli: kotlinx.coroutines.flow.Flow<Long> = flowOf(UserPreferences.DEFAULT_GRAMS_PER_CUP_MILLI),
) : ViewModel() {
    val gramsPerCupMilli = gramsPerCupMilli.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        UserPreferences.DEFAULT_GRAMS_PER_CUP_MILLI,
    )

    val state = combine(
        teaDao.observeActiveDetail(teaId),
        tastingDao.observeForTea(teaId),
    ) { detail, tastings ->
        if (detail == null) {
            TeaDetailUiState.Unavailable
        } else {
            TeaDetailUiState.Content(
                tea = detail.toTeaDetail(),
                tastings = tastings.map(TastingEntity::toTeaTasting),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), TeaDetailUiState.Loading)

    suspend fun recordConsumedTasting(consumedMilliGrams: Long) {
        teaRepository.recordConsumedTasting(teaId, consumedMilliGrams)
    }

    suspend fun recordTasting(occurredAtEpochMillis: Long, consumedMilliGrams: Long) {
        teaRepository.recordTasting(teaId, occurredAtEpochMillis, consumedMilliGrams)
    }

    suspend fun deleteTasting(tastingId: UUID): Boolean = teaRepository.deleteTasting(teaId, tastingId)
}

class TeaDetailViewModelFactory(
    private val teaId: UUID,
    private val teaDao: TeaDao,
    private val tastingDao: TastingDao,
    private val teaRepository: TeaRepository,
    private val gramsPerCupMilli: kotlinx.coroutines.flow.Flow<Long> = flowOf(UserPreferences.DEFAULT_GRAMS_PER_CUP_MILLI),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(TeaDetailViewModel::class.java.isAssignableFrom(modelClass))
        return TeaDetailViewModel(teaId, teaDao, tastingDao, teaRepository, gramsPerCupMilli) as T
    }
}

internal fun TeaWithDetailData.toTeaDetail(): TeaDetail = TeaDetail(
    id = tea.id,
    name = tea.name,
    category = category.label,
    origins = formatOrigins(countries, regions),
    personalStatus = status?.label,
    container = container?.label,
    description = tea.description?.takeIf(String::isNotBlank),
    technicalInformation = tea.technicalInformation?.takeIf(String::isNotBlank),
    tasteRating = tea.tasteRating,
    olfactoryRating = tea.olfactoryRating,
    brewTemperatureCelsius = tea.brewTemperatureCelsius,
    infusionDurationSeconds = tea.infusionDurationSeconds,
    personalNotes = tea.personalNotes?.takeIf(String::isNotBlank),
    aromas = aromas.displayLabels(),
    aromaticProfiles = aromaticProfiles.displayLabels(),
    moments = moments.displayLabels(),
    badges = badges.displayLabels(),
    stockMilliGrams = tea.stockMilliGrams,
)

internal fun TastingEntity.toTeaTasting() = TeaTasting(id, occurredAtEpochMillis, consumedMilliGrams)

private fun List<ReferenceEntity>.displayLabels(): List<String> =
    sortedWith(compareBy<ReferenceEntity> { it.displayPriority }.thenBy { it.label.lowercase() }).map { it.label }
