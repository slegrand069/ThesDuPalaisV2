package fr.thesdupalais.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupExportPickerResultTest {
    @Test fun cancellationDoesNotStartAnExport() {
        var cancelled = false
        var selected: String? = null
        dispatchPickerResult<String>(null, onCancelled = { cancelled = true }, onSelected = { selected = it })
        assertTrue(cancelled)
        assertEquals(null, selected)
    }

    @Test fun selectedDestinationIsForwarded() {
        var selected: String? = null
        dispatchPickerResult("content://destination", onCancelled = {}, onSelected = { selected = it })
        assertEquals("content://destination", selected)
    }
}
