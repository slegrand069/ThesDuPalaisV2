package fr.thesdupalais.data.database

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TeaPhotoSelectionTest {
    private fun photo(primary: Boolean = false) = TeaPhotoDraft(UUID.randomUUID(), "tea-photos/${UUID.randomUUID()}.jpg", primary)

    @Test fun emptyAndFirstPhotoHaveExpectedPrimaryState() {
        assertTrue(TeaPhotoSelection.normalize(emptyList()).isEmpty())
        val first = photo()
        assertTrue(TeaPhotoSelection.normalize(listOf(first)).single().isPrimary)
    }

    @Test fun threePhotosPreserveOrderAndOnlyOnePrimary() {
        val original = listOf(photo(), photo(), photo())
        val result = TeaPhotoSelection.setPrimary(TeaPhotoSelection.normalize(original), original[1].id)
        assertEquals(original.map { it.id }, result.map { it.id })
        assertEquals(listOf(false, true, false), result.map { it.isPrimary })
    }

    @Test fun fourthPhotoAndMultiplePrimaryPhotosAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TeaPhotoSelection.normalize(List(4) { photo() }) }
        assertThrows(IllegalArgumentException::class.java) { TeaPhotoSelection.normalize(listOf(photo(true), photo(true))) }
    }

    @Test fun deletingSecondaryKeepsMainAndDeletingMainSelectsFirstRemaining() {
        val rows = TeaPhotoSelection.normalize(listOf(photo(), photo(), photo()))
        assertTrue(TeaPhotoSelection.remove(rows, rows[2].id).first().isPrimary)
        val afterMain = TeaPhotoSelection.remove(rows, rows.first().id)
        assertTrue(afterMain.first().isPrimary)
        assertFalse(afterMain.last().isPrimary)
    }

    @Test fun removingEveryPhotoLeavesNoPrimary() {
        val rows = TeaPhotoSelection.normalize(listOf(photo()))
        assertEquals(emptyList<TeaPhotoDraft>(), TeaPhotoSelection.remove(rows, rows.single().id))
    }
}
