package fr.thesdupalais

import android.content.Context
import androidx.room.Room
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.MIGRATION_1_2
import fr.thesdupalais.data.database.MIGRATION_2_3
import fr.thesdupalais.data.database.MIGRATION_3_4
import fr.thesdupalais.data.database.MIGRATION_4_5
import fr.thesdupalais.data.database.MIGRATION_5_6
import fr.thesdupalais.data.importer.CollectionImporter
import fr.thesdupalais.data.backup.BackupArchiveExporter
import fr.thesdupalais.data.backup.BackupArchivePreviewReader
import fr.thesdupalais.data.backup.BackupArchiveMergePreviewReader
import fr.thesdupalais.data.database.RoomReferenceRepository
import fr.thesdupalais.data.database.StandardReferenceSeeder
import fr.thesdupalais.data.database.TeaRepository
import fr.thesdupalais.data.database.ArticleRepository
import fr.thesdupalais.data.importer.ArticleJsonImporter
import fr.thesdupalais.data.preferences.LocalPreferencesRepository
import fr.thesdupalais.data.importer.CollectionImportResult
import fr.thesdupalais.FirstRunOperations
import fr.thesdupalais.data.operation.BackupOperationRecoveryInspector
import fr.thesdupalais.data.operation.BackupOperationWriteCoordinator
import fr.thesdupalais.data.operation.DurableBackupOperationJournal
import fr.thesdupalais.data.operation.BackupRoomOperationApplier
import fr.thesdupalais.data.operation.BackupOperationFinalizer
import fr.thesdupalais.data.operation.BackupOperationState
import fr.thesdupalais.data.operation.RecoveryFinding
import fr.thesdupalais.data.operation.RecoveryInspection
import fr.thesdupalais.data.operation.BackupReplacementWorkflow
import fr.thesdupalais.data.operation.BackupMergeWorkflow
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppContainer(context: Context) : FirstRunOperations {
    private val recoveryMutex = Mutex()
    private val recoveryStateMutable = MutableStateFlow<RecoveryInspection?>(null)
    val recoveryInspection: StateFlow<RecoveryInspection?> = recoveryStateMutable.asStateFlow()
    val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "thes-du-palais.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()

    val operationJournal = DurableBackupOperationJournal(
        root = File(context.noBackupFilesDir, "backup-operations"),
        mediaRoot = context.filesDir,
    )
    val operationWriteCoordinator = BackupOperationWriteCoordinator { inspectPendingOperation() }
    val preferences = LocalPreferencesRepository(context, operationWriteCoordinator)
    val referenceRepository = RoomReferenceRepository(database, operationWriteCoordinator)
    val teaRepository = TeaRepository(database, operationWriteCoordinator)
    val articleRepository = ArticleRepository(database, operationWriteCoordinator)
    val articleJsonImporter = ArticleJsonImporter(articleRepository)
    val collectionImporter = CollectionImporter(database, preferences, writeCoordinator = operationWriteCoordinator)
    val backupArchiveExporter = BackupArchiveExporter(database, preferences, context.applicationContext.contentResolver)
    val backupRoomOperationApplier = BackupRoomOperationApplier(
        database = database,
        exporter = backupArchiveExporter,
        preferences = preferences,
        journal = operationJournal,
        writeCoordinator = operationWriteCoordinator,
    )
    private val backupOperationFinalizer = BackupOperationFinalizer(
        database, backupArchiveExporter, preferences, operationJournal, operationWriteCoordinator,
    )
    val backupArchivePreviewReader = BackupArchivePreviewReader(context.applicationContext.contentResolver, backupArchiveExporter)
    val backupArchiveMergePreviewReader = BackupArchiveMergePreviewReader(context.applicationContext.contentResolver, backupArchiveExporter)
    val backupReplacementWorkflow = BackupReplacementWorkflow(
        contentResolver = context.applicationContext.contentResolver,
        exporter = backupArchiveExporter,
        writeCoordinator = operationWriteCoordinator,
        journal = operationJournal,
        roomApplier = backupRoomOperationApplier,
        finalizer = backupOperationFinalizer,
        hasNoActiveMarker = { database.backupOperationMarkerDao().findActive() == null },
        recoverPendingOperation = { recoverPendingOperation() },
    )
    val backupMergeWorkflow = BackupMergeWorkflow(
        contentResolver = context.applicationContext.contentResolver,
        exporter = backupArchiveExporter,
        writeCoordinator = operationWriteCoordinator,
        journal = operationJournal,
        roomApplier = backupRoomOperationApplier,
        finalizer = backupOperationFinalizer,
        hasNoActiveMarker = { database.backupOperationMarkerDao().findActive() == null },
        recoverPendingOperation = { recoverPendingOperation() },
    )

    override suspend fun initialize() {
        val recovery = recoverPendingOperation()
        if (recovery.blocksWrites) throw IllegalStateException(
            recovery.detail ?: "Une opération inachevée doit être récupérée avant l’initialisation.",
        )
        StandardReferenceSeeder(database, operationWriteCoordinator).seed()
    }

    /** Performs one bounded recovery pass. It never loops; an incomplete pass remains write-blocking. */
    suspend fun recoverPendingOperation(): RecoveryInspection = recoveryMutex.withLock {
        try {
            val all = operationJournal.readAll()
            val marker = database.backupOperationMarkerDao().findActive()
            val pending = all.filterNot { it.state in setOf(BackupOperationState.COMPLETE, BackupOperationState.ABORTED) }
            if (pending.size > 1) throw IllegalStateException("Plusieurs opérations inachevées sont présentes; récupération bloquée.")
            if (marker != null) {
                val entry = all.singleOrNull { it.operationId == marker.operationId }
                    ?: throw IllegalStateException("Marqueur Room sans journal durable correspondant.")
                backupOperationFinalizer.finalize(entry.operationId)
            } else {
                val entry = pending.singleOrNull()
                if (entry != null) {
                    operationWriteCoordinator.withRecoveryPermit(entry.operationId) {
                        // Re-read both durable authorities after obtaining the exclusive permit;
                        // an operation may have advanced while this startup check waited.
                        val freshMarker = database.backupOperationMarkerDao().findActive()
                        val freshEntry = operationJournal.readAll().singleOrNull { it.operationId == entry.operationId }
                            ?: throw IllegalStateException("Journal disparu pendant la récupération.")
                        if (freshMarker != null) {
                            if (freshMarker.operationId != entry.operationId) throw IllegalStateException("Un autre marqueur Room est apparu; récupération bloquée.")
                            backupOperationFinalizer.finalize(entry.operationId)
                        } else if (freshEntry.state in setOf(BackupOperationState.COMPLETE, BackupOperationState.ABORTED)) {
                            operationJournal.cleanupQuarantine(entry.operationId)
                            operationJournal.cleanupPayloadArtifacts(entry.operationId)
                        } else {
                            if (freshEntry.state !in setOf(BackupOperationState.PREPARING, BackupOperationState.PREPARED, BackupOperationState.ROOM_APPLYING)) {
                                throw IllegalStateException("Le journal affirme un commit Room sans marqueur; récupération bloquée.")
                            }
                            operationJournal.cleanupPayloadArtifacts(entry.operationId)
                            operationJournal.transition(entry.operationId, BackupOperationState.ABORTED, "Interruption avant validation Room; aucune donnée métier modifiée.")
                        }
                    }
                }
                all.filter { it.state in setOf(BackupOperationState.COMPLETE, BackupOperationState.ABORTED) &&
                    (operationJournal.hasQuarantineArtifacts(it.operationId) || operationJournal.hasPayloadArtifacts(it.operationId)) }.forEach { terminal ->
                    operationWriteCoordinator.withRecoveryPermit(terminal.operationId) {
                        operationJournal.cleanupQuarantine(terminal.operationId)
                        operationJournal.cleanupPayloadArtifacts(terminal.operationId)
                    }
                }
            }
            inspectPendingOperation()
        } catch (error: Exception) {
            val current = runCatching { inspectPendingOperation() }.getOrNull()
            if (current != null && !current.blocksWrites) current
            else RecoveryInspection(RecoveryFinding.RECOVERY_FAILED, detail = "Récupération interrompue : ${error.message ?: error.javaClass.simpleName}. Les écritures restent bloquées.")
                .also { recoveryStateMutable.value = it }
        }
    }

    /** Read-only status probe used by ordinary writers and diagnostics. */
    suspend fun inspectPendingOperation(): RecoveryInspection {
        val result = try {
        val all = operationJournal.readAll()
        val marker = database.backupOperationMarkerDao().findActive()
        val pending = if (marker != null) all else all.filterNot { it.state in setOf(BackupOperationState.COMPLETE, BackupOperationState.ABORTED) }
        val inspected = BackupOperationRecoveryInspector.inspect(
            journalOperationIds = pending.map { it.operationId }.toSet(),
            roomOperationId = marker?.operationId,
            journalStates = pending.associate { it.operationId to it.state },
            roomMarkerState = marker?.state,
        )
        if (inspected.finding == RecoveryFinding.NONE && marker == null) {
            val terminal = all.firstOrNull { it.state in setOf(BackupOperationState.COMPLETE, BackupOperationState.ABORTED) &&
                (operationJournal.hasQuarantineArtifacts(it.operationId) || operationJournal.hasPayloadArtifacts(it.operationId)) }
            if (terminal != null) RecoveryInspection(RecoveryFinding.TERMINAL_CLEANUP_REQUIRED, terminal.operationId, "Nettoyage durable d’une opération terminée requis; écritures suspendues.") else inspected
        } else inspected
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
