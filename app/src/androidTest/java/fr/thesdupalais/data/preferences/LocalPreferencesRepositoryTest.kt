package fr.thesdupalais.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPreferencesRepositoryTest {
    @Test fun defaultsAndUpdatesPersistAcrossDataStoreRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "preferences-test-${UUID.randomUUID()}.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val firstStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val firstRepository = LocalPreferencesRepository(firstStore)
            assertEquals(UserPreferences(7_000L, 25_000L), firstRepository.preferences.first())

            firstRepository.setGramsPerCupMilli(8_250L)
            firstRepository.setLowStockThresholdMilli(0L)
            assertEquals(UserPreferences(8_250L, 0L), firstRepository.preferences.first())

            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reopened = LocalPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
            )
            assertEquals(UserPreferences(8_250L, 0L), reopened.preferences.first())
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test fun settersRetainExistingRangeValidation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.cacheDir, "preferences-test-${UUID.randomUUID()}.preferences_pb")
        try {
            val repository = LocalPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
            )
            val cupError = runCatching { repository.setGramsPerCupMilli(0L) }.exceptionOrNull()
            val thresholdError = runCatching { repository.setLowStockThresholdMilli(-1L) }.exceptionOrNull()
            assertTrue(cupError is IllegalArgumentException)
            assertTrue(thresholdError is IllegalArgumentException)
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}
