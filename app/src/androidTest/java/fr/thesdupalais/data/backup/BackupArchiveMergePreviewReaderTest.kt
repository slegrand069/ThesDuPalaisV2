package fr.thesdupalais.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.StandardReferenceSeeder
import fr.thesdupalais.data.preferences.LocalPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupArchiveMergePreviewReaderTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        StandardReferenceSeeder(database).seed()
    }

    @After fun tearDown() = database.close()

    @Test fun mergePreviewSnapshotDoesNotModifyRoomDataStoreOrMediaFiles() = runBlocking {
        val referencesBefore = database.referenceDao().allForBackup()
        val teasBefore = database.teaDao().allForBackup()
        val tastingsBefore = database.tastingDao().allForBackup()
        val movementsBefore = database.stockMovementDao().allForBackup()
        val preferences = LocalPreferencesRepository(context)
        val preferencesBefore = preferences.preferences.first()
        val exporter = BackupArchiveExporter(database, preferences, context.contentResolver)
        val reader = BackupArchiveMergePreviewReader(context.contentResolver, exporter)
        val incoming = exporter.createArchive(setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))

        val preview = reader.previewBytes(incoming)

        assertEquals(setOf("teas", "configuration"), preview.includedSets)
        assertEquals(3, preview.plans.size)
        preview.plans.forEach { plan ->
            assertEquals(64, plan.archiveSha256.length)
            assertEquals(64, plan.localSnapshotSha256.length)
        }
        assertNotNull(preview.plans.firstOrNull { it.selectedSets == setOf("teas", "configuration") })
        assertEquals(referencesBefore, database.referenceDao().allForBackup())
        assertEquals(teasBefore, database.teaDao().allForBackup())
        assertEquals(tastingsBefore, database.tastingDao().allForBackup())
        assertEquals(movementsBefore, database.stockMovementDao().allForBackup())
        assertEquals(preferencesBefore, preferences.preferences.first())
    }
}
