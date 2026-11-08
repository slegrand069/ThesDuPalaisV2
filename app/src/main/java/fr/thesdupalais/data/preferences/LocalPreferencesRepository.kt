package fr.thesdupalais.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

class LocalPreferencesRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val preferences: Flow<UserPreferences> = dataStore.data.map { values ->
        UserPreferences(
            gramsPerCupMilli = values[GRAMS_PER_CUP_MILLI] ?: UserPreferences.DEFAULT_GRAMS_PER_CUP_MILLI,
            lowStockThresholdMilli = values[LOW_STOCK_THRESHOLD_MILLI]
                ?: UserPreferences.DEFAULT_LOW_STOCK_THRESHOLD_MILLI,
        )
    }

    suspend fun setGramsPerCupMilli(value: Long) {
        require(value > 0) { "Le grammage par tasse doit être positif." }
        dataStore.edit { it[GRAMS_PER_CUP_MILLI] = value }
    }

    suspend fun setLowStockThresholdMilli(value: Long) {
        require(value >= 0) { "Le seuil de stock faible ne peut pas être négatif." }
        dataStore.edit { it[LOW_STOCK_THRESHOLD_MILLI] = value }
    }

    private companion object {
        val GRAMS_PER_CUP_MILLI = longPreferencesKey("grams_per_cup_milli")
        val LOW_STOCK_THRESHOLD_MILLI = longPreferencesKey("low_stock_threshold_milli")
    }
}
