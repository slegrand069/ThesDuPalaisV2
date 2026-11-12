package fr.thesdupalais.data.operation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.thesdupalais.data.backup.BackupArchiveExporter
import fr.thesdupalais.data.backup.BackupExportSet
import fr.thesdupalais.data.backup.BackupTargetFingerprints
import fr.thesdupalais.data.backup.BackupTargetState
import fr.thesdupalais.data.backup.BackupTargetStateBuilder
import fr.thesdupalais.data.backup.TargetOperation
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.TeaRepository
import fr.thesdupalais.data.database.NewTea
import fr.thesdupalais.data.preferences.LocalPreferencesRepository
import fr.thesdupalais.data.preferences.UserPreferences
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupOperationInfrastructureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun privateJournalSurvivesRecreationAndVerifiesArchiveAndTarget() {
        val root = File(context.noBackupFilesDir, "operation-test-${UUID.randomUUID()}")
        try {
            val archive = archive()
            val target = BackupTargetState(mapOf("data/configuration.json" to "{}", "data/teas.json" to "{}"), emptyList(), emptySet())
            val fingerprints = BackupTargetFingerprints(
                archiveSha256 = sha(archive),
                localSnapshotSha256 = "c".repeat(64),
                operationSha256 = "d".repeat(64),
                targetSha256 = BackupTargetStateBuilder.fingerprintTargetState(target),
            )
            val first = DurableBackupOperationJournal(root).prepare(
                "123e4567-e89b-12d3-a456-426614174000", TargetOperation.REPLACE,
                setOf("teas", "configuration"), archive, target, fingerprints,
            )
            val reopened = DurableBackupOperationJournal(root)
            val pending = reopened.readPending().single()
            assertEquals(first, pending)
            assertTrue(reopened.readArchive(pending).contentEquals(archive))
            assertEquals(target, reopened.readTargetState(pending))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun pendingOperationBlocksRoomAndDataStoreWriters() = runBlocking {
        val inspection = RecoveryInspection(RecoveryFinding.UNCOMMITTED_OPERATION_NEEDS_DECISION, "123e4567-e89b-12d3-a456-426614174000")
        val coordinator = BackupOperationWriteCoordinator { inspection }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.cacheDir, "pending-preferences-${UUID.randomUUID()}.preferences_pb")
        try {
            val teaError = runCatching { TeaRepository(database, coordinator).create(NewTea(name = "Bloqué", categoryId = null)) }.exceptionOrNull()
            assertTrue(teaError is BackupOperationPendingException)
            assertEquals(0, database.teaDao().count())

            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val preferences = LocalPreferencesRepository(store, coordinator)
            val preferenceError = runCatching { preferences.setGramsPerCupMilli(9_000L) }.exceptionOrNull()
            assertTrue(preferenceError is BackupOperationPendingException)
            assertEquals(UserPreferences(), preferences.preferences.first())
        } finally {
            scope.cancel()
            database.close()
            file.delete()
        }
    }

    private fun archive(): ByteArray = BackupArchiveExporter.BackupArchiveWriter.write(
        BackupArchiveExporter.Snapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION), UserPreferences(), "test",
        UUID.fromString("123e4567-e89b-12d3-a456-426614174001"), Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
