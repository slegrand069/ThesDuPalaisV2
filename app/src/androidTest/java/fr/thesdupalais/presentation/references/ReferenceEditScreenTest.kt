package fr.thesdupalais.presentation.references

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.RoomReferenceRepository
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.presentation.theme.ThesDuPalaisTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ReferenceEditScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomReferenceRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomReferenceRepository(database)
    }
    @After fun tearDown() = database.close()

    @Test fun creatingValueSavesCleanedTextAndClosesForm() {
        val vm = ReferenceEditViewModel(repository, ReferenceKind.AROMA, null)
        var saved = false
        composeRule.setContent { ThesDuPalaisTheme { ReferenceEditScreen(vm, onCancel = {}, onSaved = { saved = true }) } }
        composeRule.waitUntil(5_000) { !vm.uiState.value.isLoading }
        composeRule.onNodeWithTag("reference-label-input").performTextInput("  Fleur   blanche  ")
        composeRule.onNodeWithTag("reference-save").performClick()
        composeRule.waitUntil(5_000) { saved }
        runBlocking { assertEquals("Fleur blanche", repository.observe(ReferenceKind.AROMA).first().single().value.label) }
    }

    @Test fun invalidTextShowsValidationAndRegionRequiresCountrySelection() {
        val vm = ReferenceEditViewModel(repository, ReferenceKind.REGION, null)
        composeRule.setContent { ThesDuPalaisTheme { ReferenceEditScreen(vm, onCancel = {}, onSaved = {}) } }
        composeRule.waitUntil(5_000) { !vm.uiState.value.isLoading }
        composeRule.onNodeWithTag("reference-label-input").performTextInput("Vallée")
        composeRule.onNodeWithTag("reference-save").performClick()
        composeRule.onNodeWithText("Sélectionnez un pays pour cette région.").assertIsDisplayed()
    }

    @Test fun renamedStandardKeepsItsIdentityAndParentLocked() = runBlocking {
        val countryId = repository.create(ReferenceKind.COUNTRY, "Pays parent de test")
        val regionId = repository.create(ReferenceKind.REGION, "Région initiale", countryId)
        val vm = ReferenceEditViewModel(repository, ReferenceKind.REGION, regionId)
        composeRule.setContent { ThesDuPalaisTheme { ReferenceEditScreen(vm, onCancel = {}, onSaved = {}) } }
        composeRule.waitUntil(5_000) { !vm.uiState.value.isLoading }
        composeRule.onNodeWithTag("reference-parent-readonly").assertIsDisplayed()
        composeRule.onNodeWithTag("reference-country-picker").assertDoesNotExist()
        assertTrue(vm.uiState.value.parentCountryId == countryId)
    }
}
