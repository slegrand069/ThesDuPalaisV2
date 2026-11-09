package fr.thesdupalais.presentation.navigation

import android.os.Build
import android.view.WindowInsets
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import fr.thesdupalais.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NavigationSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomNavigationOpensTheKnowledgeAndSettingsDestinations() {
        openCatalogueIfFirstRun()
        composeRule.onNodeWithTag("bottom-tab-savoirs").performClick()
        composeRule.onNodeWithTag("screen-title-savoirs").assertIsDisplayed()

        composeRule.onNodeWithTag("bottom-tab-parametres").performClick()
        composeRule.onNodeWithTag("screen-title-paramètres").assertIsDisplayed()
    }

    @Test
    fun bottomBarStaysAboveTheSystemNavigationInset() {
        openCatalogueIfFirstRun()
        composeRule.onNodeWithTag("main-bottom-bar").assertIsDisplayed()

        val rootWindowInsets = composeRule.activity.window.decorView.rootWindowInsets
        val navigationInsetPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
        } else {
            0
        }
        val density = composeRule.activity.resources.displayMetrics.density
        val rootBottom = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val bottomBarBottom = composeRule.onNodeWithTag("main-bottom-bar").fetchSemanticsNode().boundsInRoot.bottom
        val insetDp = navigationInsetPx / density

        assertTrue(
            "Bottom bar should end above the system navigation area (inset=${insetDp}dp, barBottom=$bottomBarBottom, rootBottom=$rootBottom)",
            navigationInsetPx == 0 || bottomBarBottom <= rootBottom - insetDp + 1f,
        )
    }

    private fun openCatalogueIfFirstRun() {
        if (composeRule.onAllNodesWithTag("first-run-empty").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("first-run-empty").performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("first-run-open-catalogue").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("first-run-open-catalogue").performClick()
        }
    }
}
