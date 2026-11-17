package fr.thesdupalais.presentation.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.TeaDao
import fr.thesdupalais.data.database.TeaWithCatalogData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock

sealed interface StatisticsUiState {
    data object Loading : StatisticsUiState
    data class Content(val snapshot: StatisticsSnapshot) : StatisticsUiState
}

class StatisticsViewModel(
    teaDao: TeaDao,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val period = MutableStateFlow(StatisticsPeriod.YEAR)

    val state = observeStatisticsSnapshots(
        teas = teaDao.observeAllCatalogForStatistics().map { rows -> rows.map(TeaWithCatalogData::toStatisticsTea) },
        period = period,
        clock = clock,
    ).map(StatisticsUiState::Content)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), StatisticsUiState.Loading)

    fun selectPeriod(value: StatisticsPeriod) {
        period.value = value
    }
}

private fun TeaWithCatalogData.toStatisticsTea() = StatisticsTea(
    id = tea.id,
    name = tea.name,
    active = tea.archivedAtEpochMillis == null,
    category = category.toStatisticsReference(),
    stockMilliGrams = tea.stockMilliGrams,
    countries = countries.map(ReferenceEntity::toStatisticsReference),
    badges = badges.map(ReferenceEntity::toStatisticsReference),
    tastingDatesEpochMillis = tastings.map { it.occurredAtEpochMillis },
    statusSemanticKey = status?.semanticKey,
)

private fun ReferenceEntity.toStatisticsReference() = StatisticsReference(id, label, semanticKey)

class StatisticsViewModelFactory(
    private val teaDao: TeaDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(StatisticsViewModel::class.java.isAssignableFrom(modelClass))
        return StatisticsViewModel(teaDao) as T
    }
}
