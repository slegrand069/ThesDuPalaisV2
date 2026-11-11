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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupArchiveExporterDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        StandardReferenceSeeder(database).seed()
    }

    @After fun tearDown() = database.close()

    @Test fun archiveCreationDoesNotModifyRoomOrPreferences() = runBlocking {
        val referencesBefore = database.referenceDao().allForBackup()
        val teasBefore = database.teaDao().allForBackup()
        val preferences = LocalPreferencesRepository(context)
        val preferencesBefore = preferences.preferences.first()
        val exporter = BackupArchiveExporter(database, preferences, context.contentResolver)

        val archive = exporter.createArchive(setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))

        BackupArchiveValidator.validate(archive)
        assertEquals(referencesBefore, database.referenceDao().allForBackup())
        assertEquals(teasBefore, database.teaDao().allForBackup())
        assertEquals(preferencesBefore, preferences.preferences.first())
    }

    @Test fun previewAgainstLocalSnapshotDoesNotModifyRoomOrPreferences() = runBlocking {
        val referencesBefore = database.referenceDao().allForBackup()
        val teasBefore = database.teaDao().allForBackup()
        val preferences = LocalPreferencesRepository(context)
        val preferencesBefore = preferences.preferences.first()
        val exporter = BackupArchiveExporter(database, preferences, context.contentResolver)
        val localBytes = exporter.createArchive(setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val input = BackupArchiveValidator.validate(localBytes)
        val local = BackupArchiveValidator.validate(localBytes)

        val preview = BackupArchivePreviewAnalyzer.analyze(localBytes, input, local, setOf("teas", "configuration"))

        assertEquals(setOf("teas", "configuration"), preview.selectedSets)
        assertEquals(referencesBefore, database.referenceDao().allForBackup())
        assertEquals(teasBefore, database.teaDao().allForBackup())
        assertEquals(preferencesBefore, preferences.preferences.first())
    }
}
