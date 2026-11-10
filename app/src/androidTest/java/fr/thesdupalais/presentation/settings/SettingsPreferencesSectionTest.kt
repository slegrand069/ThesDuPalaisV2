package fr.thesdupalais.presentation.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import fr.thesdupalais.data.preferences.UserPreferences
import fr.thesdupalais.presentation.theme.ThesDuPalaisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SettingsPreferencesSectionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun cupValueCanBeEditedInGramsAndSavedAsMilligrams() {
        var saved: Long? = null
        composeRule.setContent {
            ThesDuPalaisTheme {
                SettingsPreferencesSection(UserPreferences(), onUpdateGramsPerCupMilli = { saved = it }, onUpdateLowStockThresholdMilli = {})
            }
        }

        composeRule.onNodeWithTag("settings-edit-cup").performClick()
        composeRule.onNodeWithTag("settings-value-input-cup").performTextClearance()
        composeRule.onNodeWithTag("settings-value-input-cup").performTextInput("7,5")
        composeRule.onNodeWithTag("settings-save-cup").performClick()
        composeRule.waitUntil(5_000) { saved != null }

        assertEquals(7_500L, saved)
    }

    @Test fun thresholdAllowsZeroAndCancelDoesNotSave() {
        var saved: Long? = null
        composeRule.setContent {
            ThesDuPalaisTheme {
                SettingsPreferencesSection(UserPreferences(), onUpdateGramsPerCupMilli = {}, onUpdateLowStockThresholdMilli = { saved = it })
            }
        }

        composeRule.onNodeWithTag("settings-edit-low-stock").performClick()
        composeRule.onNodeWithTag("settings-value-input-low-stock").performTextClearance()
        composeRule.onNodeWithTag("settings-value-input-low-stock").performTextInput("0")
        composeRule.onNodeWithTag("settings-cancel-low-stock").performClick()
        assertNull(saved)

        composeRule.onNodeWithTag("settings-edit-low-stock").performClick()
        composeRule.onNodeWithTag("settings-value-input-low-stock").performTextClearance()
        composeRule.onNodeWithTag("settings-value-input-low-stock").performTextInput("0")
        composeRule.onNodeWithTag("settings-save-low-stock").performClick()
        composeRule.waitUntil(5_000) { saved != null }
        assertEquals(0L, saved)
    }

    @Test fun zeroCupValueShowsValidationWithoutWriting() {
        var saved: Long? = null
        composeRule.setContent {
            ThesDuPalaisTheme {
                SettingsPreferencesSection(UserPreferences(), onUpdateGramsPerCupMilli = { saved = it }, onUpdateLowStockThresholdMilli = {})
            }
        }
        composeRule.onNodeWithTag("settings-edit-cup").performClick()
        composeRule.onNodeWithTag("settings-value-input-cup").performTextClearance()
        composeRule.onNodeWithTag("settings-value-input-cup").performTextInput("0")
        composeRule.onNodeWithTag("settings-save-cup").performClick()

        composeRule.onNodeWithText("Le grammage par tasse doit être supérieur à 0 g.").assertIsDisplayed()
        assertNull(saved)
    }
}
