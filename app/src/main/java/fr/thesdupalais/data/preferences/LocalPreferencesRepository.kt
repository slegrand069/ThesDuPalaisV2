package fr.thesdupalais.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class UserPreferences(
    val gramsPerCupMilli: Long = DEFAULT_GRAMS_PER_CUP_MILLI,
    val lowStockThresholdMilli: Long = DEFAULT_LOW_STOCK_THRESHOLD_MILLI,
) {
    companion object {
        const val DEFAULT_GRAMS_PER_CUP_MILLI = 7_000L
        const val DEFAULT_LOW_STOCK_THRESHOLD_MILLI = 25_000L
    }
}

interface UserPreferencesStore {
    suspend fun setPreferences(preferences: UserPreferences)
}

class LocalPreferencesRepository(context: Context) : UserPreferencesStore {
    private val dataStore = context.applicationContext.settingsDataStore

    val preferences: Flow<UserPreferences> = dataStore.data.map { values ->
        UserPreferences(
            gramsPerCupMilli = values[GRAMS_PER_CUP_MILLI] ?: UserPreferences.DEFAULT_GRAMS_PER_CUP_MILLI,
            lowStockThresholdMilli = values[LOW_STOCK_THRESHOLD_MILLI]
                ?: UserPreferences.DEFAULT_LOW_STOCK_THRESHOLD_MILLI,
        )
    }

    val installationInitialized: Flow<Boolean> = dataStore.data.map { it[INSTALLATION_INITIALIZED] ?: false }

    suspend fun isInstallationInitialized(): Boolean = installationInitialized.first()

    suspend fun markInstallationInitialized() {
        dataStore.edit { it[INSTALLATION_INITIALIZED] = true }
    }

    suspend fun setGramsPerCupMilli(value: Long) {
        require(value > 0) { "Le grammage par tasse doit être positif." }
        dataStore.edit { it[GRAMS_PER_CUP_MILLI] = value }
    }

    suspend fun setLowStockThresholdMilli(value: Long) {
        require(value >= 0) { "Le seuil de stock faible ne peut pas être négatif." }
        dataStore.edit { it[LOW_STOCK_THRESHOLD_MILLI] = value }
    }

    override suspend fun setPreferences(preferences: UserPreferences) {
        require(preferences.gramsPerCupMilli > 0) { "Le grammage par tasse doit être positif." }
        require(preferences.lowStockThresholdMilli >= 0) { "Le seuil de stock faible ne peut pas être négatif." }
        dataStore.edit {
            it[GRAMS_PER_CUP_MILLI] = preferences.gramsPerCupMilli
            it[LOW_STOCK_THRESHOLD_MILLI] = preferences.lowStockThresholdMilli
        }
    }

    private companion object {
        val GRAMS_PER_CUP_MILLI = longPreferencesKey("grams_per_cup_milli")
        val LOW_STOCK_THRESHOLD_MILLI = longPreferencesKey("low_stock_threshold_milli")
        val INSTALLATION_INITIALIZED = booleanPreferencesKey("installation_initialized")
    }
}
