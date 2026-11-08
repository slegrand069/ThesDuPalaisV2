package fr.thesdupalais.presentation.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import fr.thesdupalais.MainActivity
import org.junit.Rule
import org.junit.Test

class NavigationSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomNavigationOpensTheKnowledgeAndSettingsDestinations() {
        if (composeRule.onAllNodesWithTag("first-run-empty").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("first-run-empty").performClick()
            composeRule.onNodeWithTag("first-run-open-catalogue").performClick()
        }
        composeRule.onNodeWithTag("bottom-tab-savoirs").performClick()
        composeRule.onNodeWithTag("screen-title-savoirs").assertIsDisplayed()

        composeRule.onNodeWithTag("bottom-tab-parametres").performClick()
        composeRule.onNodeWithTag("screen-title-paramètres").assertIsDisplayed()
    }
}
