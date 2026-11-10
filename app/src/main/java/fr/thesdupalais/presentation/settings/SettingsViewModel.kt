package fr.thesdupalais.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.preferences.LocalPreferencesRepository
import fr.thesdupalais.data.preferences.UserPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SettingsViewModel(private val repository: LocalPreferencesRepository) : ViewModel() {
    val preferences = repository.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = UserPreferences(),
    )
    val preferencesLoaded = repository.preferences
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = false,
        )

    suspend fun updateGramsPerCupMilli(value: Long) = repository.setGramsPerCupMilli(value)

    suspend fun updateLowStockThresholdMilli(value: Long) = repository.setLowStockThresholdMilli(value)
}

class SettingsViewModelFactory(
    private val repository: LocalPreferencesRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(SettingsViewModel::class.java.isAssignableFrom(modelClass))
        return SettingsViewModel(repository) as T
    }
}
