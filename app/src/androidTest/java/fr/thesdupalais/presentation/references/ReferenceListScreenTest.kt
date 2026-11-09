package fr.thesdupalais.presentation.references

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceSummary
import fr.thesdupalais.domain.model.ReferenceValue
import fr.thesdupalais.presentation.theme.ThesDuPalaisTheme
import java.util.UUID
import org.junit.Rule
import org.junit.Test

class ReferenceListScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun countriesShowActiveAndOtherSectionsWithoutArchiveFilter() {
        val activeCountry = reference(ReferenceKind.COUNTRY, "Japon", total = 3, active = 2)
        val archivedCountry = reference(ReferenceKind.COUNTRY, "Inde", total = 1, active = 0, archivedAt = 100L)
        composeRule.setContent {
            ThesDuPalaisTheme {
                ReferenceListContent(
                    state = ReferenceListUiState(
                        kind = ReferenceKind.COUNTRY,
                        references = listOf(activeCountry, archivedCountry),
                        isLoading = false,
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Utilisés dans ma collection").assertIsDisplayed()
        composeRule.onNodeWithText("Autres pays").assertIsDisplayed()
        composeRule.onNodeWithText("Inde").assertIsDisplayed()
        composeRule.onNodeWithText("Utilisée · 3 thés").assertIsDisplayed()
        composeRule.onAllNodesWithTag("reference-filter-active").assertCountEquals(0)
        composeRule.onAllNodesWithTag("reference-filter-archived").assertCountEquals(0)
    }

    @Test
    fun regionsShowParentCountryAndSeparateActiveFromOtherRegions() {
        val region = reference(
            ReferenceKind.REGION,
            "Shizuoka",
            total = 2,
            active = 1,
            parentCountryId = UUID.randomUUID(),
            parentCountryLabel = "Japon",
        )
        val archivedOnlyRegion = reference(
            ReferenceKind.REGION,
            "Ancienne région",
            total = 1,
            active = 0,
            parentCountryId = UUID.randomUUID(),
            parentCountryLabel = "Chine",
        )
        composeRule.setContent {
            ThesDuPalaisTheme {
                ReferenceListContent(
                    state = ReferenceListUiState(
                        kind = ReferenceKind.REGION,
                        references = listOf(region, archivedOnlyRegion),
                        isLoading = false,
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Utilisées dans ma collection").assertIsDisplayed()
        composeRule.onNodeWithText("Autres régions").assertIsDisplayed()
        composeRule.onNodeWithText("Shizuoka (Japon)").assertIsDisplayed()
        composeRule.onNodeWithText("Ancienne région (Chine)").assertIsDisplayed()
        composeRule.onNodeWithText("Utilisée · 2 thés").assertIsDisplayed()
    }

    private fun reference(
        kind: ReferenceKind,
        label: String,
        total: Int,
        active: Int,
        archivedAt: Long? = null,
        parentCountryId: UUID? = null,
        parentCountryLabel: String? = null,
    ) = ReferenceSummary(
        value = ReferenceValue(
            id = UUID.randomUUID(),
            kind = kind,
            label = label,
            normalizedLabel = label.lowercase(),
            parentCountryId = parentCountryId,
            archivedAt = archivedAt,
        ),
        teaUsageCount = total,
        activeTeaUsageCount = active,
        childRegionCount = 0,
        parentCountryLabel = parentCountryLabel,
    )
}
