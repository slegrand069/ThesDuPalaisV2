package fr.thesdupalais.presentation.navigation

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.StandardReferenceSeeder
import fr.thesdupalais.data.database.TeaRepository
import fr.thesdupalais.data.database.TeaEntity
import fr.thesdupalais.domain.references.StandardReferenceSeeds
import fr.thesdupalais.presentation.catalogue.CatalogueScreen
import fr.thesdupalais.presentation.catalogue.CatalogueViewModel
import fr.thesdupalais.presentation.catalogue.CatalogueViewModelFactory
import fr.thesdupalais.presentation.detail.TeaDetailScreen
import fr.thesdupalais.presentation.detail.TeaDetailViewModel
import fr.thesdupalais.presentation.detail.TeaDetailViewModelFactory
import fr.thesdupalais.presentation.theme.ThesDuPalaisTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeaCreationNavigationTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        kotlinx.coroutines.runBlocking { StandardReferenceSeeder(database).seed() }
    }

    @After fun tearDown() = database.close()

    @Test fun catalogueFabOpensCreateAndSuccessfulSaveOpensNewTeaDetailRoute() {
        val greenCategoryId = StandardReferenceSeeds.categories.single { it.semanticKey == "GREEN" }.id
        composeRule.setContent {
            ThesDuPalaisTheme {
                val navController = rememberNavController()
                val teaRepository = remember { TeaRepository(database) }
                NavHost(navController, startDestination = "catalogue") {
                    composable("catalogue") {
                        val vm: CatalogueViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                            factory = CatalogueViewModelFactory(database.teaDao()),
                        )
                        CatalogueScreen(
                            viewModel = vm,
                            onTeaSelected = {},
                            onAddTea = { navController.navigate(TEA_CREATE_ROUTE) },
                        )
                    }
                    teaCreationDestination(navController, teaRepository)
                    composable("tea/{teaId}") { entry ->
                        Text(
                            "Détail ${entry.arguments?.getString("teaId")}",
                            modifier = Modifier.testTag("tea-created-detail"),
                        )
                    }
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("catalogue-add").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("catalogue-add").performClick()
        composeRule.onNodeWithTag("tea-create-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("tea-form-name").performTextInput("Navigation tea")
        composeRule.onNodeWithTag("tea-form-category-$greenCategoryId").performClick()
        composeRule.onNodeWithTag("tea-form-save").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("tea-created-detail").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("tea-created-detail").assertIsDisplayed()
        org.junit.Assert.assertEquals(1, kotlinx.coroutines.runBlocking { database.teaDao().count() })
    }

    @Test fun detailOpensEditAndReturnsToTheSameUpdatedTea() {
        val categoryId = StandardReferenceSeeds.categories.single { it.semanticKey == "GREEN" }.id
        val teaId = java.util.UUID.randomUUID()
        kotlinx.coroutines.runBlocking {
            database.teaDao().insert(
                TeaEntity(
                    id = teaId, legacyId = 26L, name = "Before edit", categoryRefId = categoryId,
                    description = null, technicalInformation = null, personalNotes = null,
                    olfactoryRating = null, tasteRating = null, brewTemperatureCelsius = 82,
                    infusionDurationSeconds = null, statusRefId = null, containerRefId = null,
                    stockMilliGrams = null, archivedAtEpochMillis = null,
                ),
            )
        }
        composeRule.setContent {
            ThesDuPalaisTheme {
                val navController = rememberNavController()
                val teaRepository = remember { TeaRepository(database) }
                NavHost(navController, startDestination = "tea/$teaId") {
                    composable("tea/{teaId}") { entry ->
                        val id = java.util.UUID.fromString(requireNotNull(entry.arguments?.getString("teaId")))
                        val vm: TeaDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                            key = "detail-$id",
                            factory = TeaDetailViewModelFactory(id, database.teaDao(), database.tastingDao()),
                        )
                        TeaDetailScreen(vm, onBack = {}, onEdit = { navController.navigate("tea/$it/edit") })
                    }
                    teaEditingDestination(navController, teaRepository)
                }
            }
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("tea-detail-edit").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("tea-detail-edit").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("tea-form-save").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("tea-form-category-$categoryId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("tea-form-temperature-value").assertTextEquals("82 °C · valeur historique")
        composeRule.onNodeWithTag("tea-form-name").performTextClearance()
        composeRule.onNodeWithTag("tea-form-name").performTextInput("After edit")
        composeRule.onNodeWithTag("tea-form-save").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("tea-detail-edit").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("tea-form-save").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("tea-detail-edit").assertIsDisplayed()
        composeRule.onNodeWithText("After edit").assertIsDisplayed()
        kotlinx.coroutines.runBlocking {
            val saved = requireNotNull(database.teaDao().findById(teaId))
            org.junit.Assert.assertEquals(teaId, saved.id)
            org.junit.Assert.assertEquals(26L, saved.legacyId)
            org.junit.Assert.assertEquals(82, saved.brewTemperatureCelsius)
        }
    }
}
