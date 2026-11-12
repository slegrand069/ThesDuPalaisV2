package fr.thesdupalais

import android.content.Context
import androidx.room.Room
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.MIGRATION_1_2
import fr.thesdupalais.data.database.MIGRATION_2_3
import fr.thesdupalais.data.database.MIGRATION_3_4
import fr.thesdupalais.data.database.MIGRATION_4_5
import fr.thesdupalais.data.importer.CollectionImporter
import fr.thesdupalais.data.backup.BackupArchiveExporter
import fr.thesdupalais.data.backup.BackupArchivePreviewReader
import fr.thesdupalais.data.backup.BackupArchiveMergePreviewReader
import fr.thesdupalais.data.database.RoomReferenceRepository
import fr.thesdupalais.data.database.StandardReferenceSeeder
import fr.thesdupalais.data.database.TeaRepository
import fr.thesdupalais.data.preferences.LocalPreferencesRepository
import fr.thesdupalais.data.importer.CollectionImportResult
import fr.thesdupalais.FirstRunOperations
import fr.thesdupalais.data.operation.BackupOperationRecoveryInspector
import fr.thesdupalais.data.operation.BackupOperationWriteCoordinator
import fr.thesdupalais.data.operation.DurableBackupOperationJournal
import fr.thesdupalais.data.operation.BackupRoomOperationApplier
import fr.thesdupalais.data.operation.RecoveryInspection
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppContainer(context: Context) : FirstRunOperations {
    private val recoveryStateMutable = MutableStateFlow<RecoveryInspection?>(null)
    val recoveryInspection: StateFlow<RecoveryInspection?> = recoveryStateMutable.asStateFlow()
    val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "thes-du-palais.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()

    val operationJournal = DurableBackupOperationJournal(
        root = File(context.noBackupFilesDir, "backup-operations"),
        mediaRoot = context.filesDir,
    )
    val operationWriteCoordinator = BackupOperationWriteCoordinator { inspectPendingOperation() }
    val preferences = LocalPreferencesRepository(context, operationWriteCoordinator)
    val referenceRepository = RoomReferenceRepository(database, operationWriteCoordinator)
    val teaRepository = TeaRepository(database, operationWriteCoordinator)
    val collectionImporter = CollectionImporter(database, preferences, writeCoordinator = operationWriteCoordinator)
    val backupArchiveExporter = BackupArchiveExporter(database, preferences, context.applicationContext.contentResolver)
    val backupRoomOperationApplier = BackupRoomOperationApplier(
        database = database,
        exporter = backupArchiveExporter,
        preferences = preferences,
        journal = operationJournal,
        writeCoordinator = operationWriteCoordinator,
    )
    val backupArchivePreviewReader = BackupArchivePreviewReader(context.applicationContext.contentResolver, backupArchiveExporter)
    val backupArchiveMergePreviewReader = BackupArchiveMergePreviewReader(context.applicationContext.contentResolver, backupArchiveExporter)

    override suspend fun initialize() {
        val recovery = inspectPendingOperation()
        if (recovery.blocksWrites) throw IllegalStateException(
            recovery.detail ?: "Une opération inachevée doit être récupérée avant l’initialisation.",
        )
        StandardReferenceSeeder(database, operationWriteCoordinator).seed()
    }

    /** Read-only startup check. Recovery actions are intentionally not provided by this sublot. */
    suspend fun inspectPendingOperation(): RecoveryInspection {
        val result = try {
        val pending = operationJournal.readPending()
        val marker = database.backupOperationMarkerDao().findActive()
        BackupOperationRecoveryInspector.inspect(
            journalOperationIds = pending.map { it.operationId }.toSet(),
            roomOperationId = marker?.operationId,
            journalStates = pending.associate { it.operationId to it.state },
            roomMarkerState = marker?.state,
        )
        } catch (error: Exception) {
            BackupOperationRecoveryInspector.inspect(emptySet(), null, journalReadFailed = true)
        }
        recoveryStateMutable.value = result
        return result
    }

    override suspend fun isInstallationInitialized(): Boolean = preferences.isInstallationInitialized()

    override suspend fun markInstallationInitialized() = preferences.markInstallationInitialized()

    override suspend fun importCollection(json: String): CollectionImportResult = collectionImporter.importJson(json)
}
