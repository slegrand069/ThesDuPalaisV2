package fr.thesdupalais.presentation.teaform

import fr.thesdupalais.data.database.TeaEditableField
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class TeaFormDraftTest {
    @Test fun unchangedDraftHasNoChangesAndPreservesHistoricalTemperature() {
        val initial = draft(temperature = 82)

        assertEquals(emptySet<TeaEditableField>(), initial.changedFieldsSince(initial))
        assertEquals(setOf(TeaEditableField.NAME), initial.copy(name = "Renommé").changedFieldsSince(initial))
        assertEquals(
            setOf(TeaEditableField.BREW_TEMPERATURE),
            initial.copy(brewTemperatureCelsius = 85).changedFieldsSince(initial),
        )
    }

    @Test fun explicitlyClearingNullableTextIsDetectedAsAnEdit() {
        val initial = draft(personalNotes = "Texte existant")

        assertEquals(
            setOf(TeaEditableField.PERSONAL_NOTES),
            initial.copy(personalNotes = "").changedFieldsSince(initial),
        )
    }

    @Test fun changingOrClearingStockIsDetectedAsAnEdit() {
        val initial = draft(stockInput = "12,5")

        assertEquals(setOf(TeaEditableField.STOCK), initial.copy(stockInput = "0").changedFieldsSince(initial))
        assertEquals(setOf(TeaEditableField.STOCK), initial.copy(stockInput = "").changedFieldsSince(initial))
    }

    private fun draft(temperature: Int? = null, personalNotes: String = "", stockInput: String = "") = TeaFormDraft(
        name = "Thé fictif",
        categoryId = UUID.fromString("10000000-0000-4000-8000-000000000001"),
        statusId = null,
        containerId = null,
        countryIds = emptySet(),
        regionIds = emptySet(),
        aromaIds = emptySet(),
        profileIds = emptySet(),
        momentIds = emptySet(),
        badgeIds = emptySet(),
        tasteRating = null,
        olfactoryRating = null,
        personalNotes = personalNotes,
        description = "",
        technicalInformation = "",
        brewTemperatureCelsius = temperature,
        infusionDurationInput = "",
        stockInput = stockInput,
    )
}
