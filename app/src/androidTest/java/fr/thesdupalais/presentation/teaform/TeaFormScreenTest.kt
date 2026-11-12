package fr.thesdupalais.presentation.teaform

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.StandardReferenceSeeder
import fr.thesdupalais.data.database.TeaRepository
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.references.StandardReferenceSeeds
import fr.thesdupalais.presentation.theme.ThesDuPalaisTheme
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeaFormScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var viewModel: TeaFormViewModel

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        StandardReferenceSeeder(database).seed()
        viewModel = TeaFormViewModel(TeaRepository(database))
    }

    @After fun tearDown() = database.close()

    @Test fun savesMinimalTeaAndPreservesUnknownValuesAsNull() {
        val savedId = AtomicReference<UUID?>(null)
        composeRule.setContent {
            ThesDuPalaisTheme {
                TeaFormScreen(viewModel, onCancel = {}, onSaved = { savedId.set(it) })
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("tea-form-category-${greenCategoryId()}")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("tea-form-name").performTextInput("Thé de test")
        composeRule.onNodeWithTag("tea-form-category-${greenCategoryId()}").performClick()
        composeRule.onNodeWithTag("tea-form-save").performClick()
        composeRule.waitUntil(5_000) { savedId.get() != null }

        val tea = runBlocking { database.teaDao().findById(requireNotNull(savedId.get())) }
        assertNotNull(tea)
        assertNull(tea?.stockMilliGrams)
        assertNull(tea?.tasteRating)
        assertNull(tea?.olfactoryRating)
        assertEquals(0, runBlocking { database.tastingDao().count() })
        assertEquals(0, runBlocking { database.stockMovementDao().count() })
    }

    @Test fun savesOptionalStockEnteredWithFrenchDecimalComma() {
        val savedId = AtomicReference<UUID?>(null)
        composeRule.setContent {
            ThesDuPalaisTheme { TeaFormScreen(viewModel, onCancel = {}, onSaved = { savedId.set(it) }) }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("tea-form-category-${greenCategoryId()}"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("tea-form-name").performTextInput("Thé avec stock")
        composeRule.onNodeWithTag("tea-form-category-${greenCategoryId()}").performClick()
        composeRule.onNodeWithTag("tea-form-stock-input").performTextInput("12,5")
        composeRule.onNodeWithTag("tea-form-save").performClick()
        composeRule.waitUntil(5_000) { savedId.get() != null }

        assertEquals(12_500L, runBlocking { database.teaDao().findById(requireNotNull(savedId.get()))?.stockMilliGrams })
        assertEquals(0, runBlocking { database.stockMovementDao().count() })
    }

    @Test fun asksBeforeAbandoningDirtyFormAndCancelDoesNotWrite() {
        var cancelled = false
        composeRule.setContent {
            ThesDuPalaisTheme {
                TeaFormScreen(viewModel, onCancel = { cancelled = true }, onSaved = {})
            }
        }
        composeRule.onNodeWithTag("tea-form-name").performTextInput("Brouillon")
        composeRule.onNodeWithTag("tea-form-cancel").performClick()
        composeRule.onNodeWithTag("tea-form-abandon-continue").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("tea-form-cancel").performClick()
        composeRule.onNodeWithTag("tea-form-abandon-confirm").assertIsDisplayed().performClick()
        composeRule.waitUntil(2_000) { cancelled }
        assertEquals(0, runBlocking { database.teaDao().count() })
    }

    @Test fun requiresNameAndCategoryAndPreventsRepeatedSave() {
        composeRule.setContent {
            ThesDuPalaisTheme { TeaFormScreen(viewModel, onCancel = {}, onSaved = {}) }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("tea-form-category-${greenCategoryId()}")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("tea-form-save").performClick()
        composeRule.onNodeWithTag("tea-form-footer").assertIsDisplayed()
        composeRule.onNodeWithTag("tea-form-error").assertIsDisplayed()
        composeRule.runOnIdle {
            viewModel.updateName("Unique tea")
            viewModel.toggleReference(ReferenceKind.CATEGORY, greenCategoryId(), true)
            viewModel.save {}
            viewModel.save {}
        }
        composeRule.waitUntil(5_000) { runBlocking { database.teaDao().count() == 1 } }
        assertEquals(1, runBlocking { database.teaDao().count() })
    }

    @Test fun editFormPrefillsImportedTeaAndUnchangedSavePerformsNoWrite() = runBlocking {
        val category = requireNotNull(database.referenceDao().findBySemanticKey("CATEGORY", "GREEN"))
        val teaId = UUID.randomUUID()
        database.teaDao().insert(
            fr.thesdupalais.data.database.TeaEntity(
                id = teaId, legacyId = 2L, name = "Imported tea", categoryRefId = category.id,
                description = "Imported description", technicalInformation = "Imported technical data",
                personalNotes = null, olfactoryRating = 7.25, tasteRating = 8.35,
                brewTemperatureCelsius = 82, infusionDurationSeconds = 130,
                statusRefId = null, containerRefId = null, stockMilliGrams = 12_500L,
                archivedAtEpochMillis = null,
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_unchanged_edit BEFORE UPDATE ON teas BEGIN SELECT RAISE(ABORT, 'unchanged edit wrote'); END",
        )
        val editViewModel = TeaFormViewModel(TeaRepository(database), teaId)
        val savedId = AtomicReference<UUID?>(null)
        composeRule.setContent {
            ThesDuPalaisTheme {
                TeaFormScreen(editViewModel, onCancel = {}, onSaved = { savedId.set(it) })
            }
        }
        composeRule.waitUntil(5_000) {
            editViewModel.uiState.value.initialDraft != null &&
                composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("tea-form-category-${greenCategoryId()}")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle { assertEquals("Imported tea", editViewModel.uiState.value.name) }
        composeRule.runOnIdle { assertEquals("12,5", editViewModel.uiState.value.stockInput) }
        composeRule.onNodeWithTag("tea-form-temperature-value").assertTextEquals("82 °C · valeur historique")
        composeRule.onNodeWithTag("tea-form-save").performClick()
        composeRule.waitUntil(2_000) { savedId.get() == teaId }

        val unchanged = requireNotNull(database.teaDao().findById(teaId))
        assertEquals("Imported tea", unchanged.name)
        assertEquals(2L, unchanged.legacyId)
        assertEquals(82, unchanged.brewTemperatureCelsius)
        assertEquals(12_500L, unchanged.stockMilliGrams)
    }

    @Test fun editCancelAndConfirmedAbandonLeaveTheStoredTeaUntouched() = runBlocking {
        val category = requireNotNull(database.referenceDao().findBySemanticKey("CATEGORY", "GREEN"))
        val teaId = TeaRepository(database).create(
            fr.thesdupalais.data.database.NewTea("Original name", category.id, personalNotes = "Original notes"),
        )
        val editViewModel = TeaFormViewModel(TeaRepository(database), teaId)
        var cancelled = false
        composeRule.setContent {
            ThesDuPalaisTheme {
                TeaFormScreen(editViewModel, onCancel = { cancelled = true }, onSaved = {})
            }
        }
        composeRule.waitUntil(5_000) { editViewModel.uiState.value.initialDraft != null }
        composeRule.onNodeWithTag("tea-form-name").performTextInput(" changed")
        composeRule.onNodeWithTag("tea-form-cancel").performClick()
        composeRule.onNodeWithTag("tea-form-abandon-continue").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("tea-form-back").performClick()
        composeRule.onNodeWithTag("tea-form-abandon-confirm").assertIsDisplayed().performClick()
        composeRule.waitUntil(2_000) { cancelled }
        val unchanged = requireNotNull(database.teaDao().findById(teaId))
        assertEquals("Original name", unchanged.name)
        assertEquals("Original notes", unchanged.personalNotes)
    }

    private fun greenCategoryId(): UUID = StandardReferenceSeeds.categories.single { it.semanticKey == "GREEN" }.id
}
