package fr.thesdupalais.data.operation

import android.content.ContentResolver
import android.net.Uri
import fr.thesdupalais.data.backup.BackupArchiveExporter
import fr.thesdupalais.data.backup.BackupArchiveValidator
import fr.thesdupalais.data.backup.BackupLocalOperationSnapshot
import fr.thesdupalais.data.backup.BackupReplacementPreviewPlan
import fr.thesdupalais.data.backup.BackupReplacementPreviewPlanner
import fr.thesdupalais.data.backup.BackupTargetStateBuilder
import fr.thesdupalais.data.backup.TargetOperation
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class BackupReplacementSession internal constructor(
    val backupId: String,
    val createdAt: String,
    val includedSets: Set<String>,
    archiveBytes: ByteArray,
    localSnapshot: BackupLocalOperationSnapshot,
) {
    internal val sessionId: String = UUID.randomUUID().toString()
    private val archiveSnapshot = archiveBytes.copyOf()
    private val localSnapshot = localSnapshot.copy(archiveBytes = localSnapshot.archiveBytes.copyOf(), photos = localSnapshot.photos.toList(), importHistory = localSnapshot.importHistory.toList())
    internal fun archiveBytesCopy() = archiveSnapshot.copyOf()
    internal fun localSnapshotCopy() = localSnapshot.copy(archiveBytes = localSnapshot.archiveBytes.copyOf())
}

class BackupReplacementSelection internal constructor(
    internal val sessionId: String,
    val plan: BackupReplacementPreviewPlan,
)

enum class BackupReplacementProgress { PREPARING, APPLYING_ROOM, FINALIZING, RECOVERING, COMPLETE }
data class BackupReplacementResult(val operationId: String, val resumedAfterInterruption: Boolean)

/** SAF read, exact-state planning and explicitly confirmed replacement orchestration. */
class BackupReplacementWorkflow(
    private val contentResolver: ContentResolver,
    private val exporter: BackupArchiveExporter,
    private val writeCoordinator: BackupOperationWriteCoordinator,
    private val journal: DurableBackupOperationJournal,
    private val roomApplier: BackupRoomOperationApplier,
    private val finalizer: BackupOperationFinalizer,
    private val hasNoActiveMarker: suspend () -> Boolean,
    private val recoverPendingOperation: suspend () -> RecoveryInspection,
) {
    private val consumedSessions = mutableSetOf<String>()

    suspend fun read(uri: Uri): BackupReplacementSession = withContext(Dispatchers.IO) {
        val archiveBytes = contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > BackupArchiveValidator.MAX_ARCHIVE_BYTES) {
                    throw BackupArchiveValidator.ValidationException("ZIP", "L’archive dépasse la limite compressée de 50 Mio.")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("Le fichier sélectionné n’a pas pu être lu.")
        createSession(archiveBytes)
    }

    internal suspend fun previewBytes(archiveBytes: ByteArray): BackupReplacementSession = withContext(Dispatchers.IO) {
        createSession(archiveBytes.copyOf())
    }

    private suspend fun createSession(archiveBytes: ByteArray): BackupReplacementSession {
        val validated = BackupArchiveValidator.validate(archiveBytes)
        val local = exporter.captureCurrentOperationSnapshot()
        BackupArchiveValidator.validate(local.archiveBytes)
        return BackupReplacementSession(validated.backupId, validated.createdAt, validated.includedSets, archiveBytes, local)
    }

    suspend fun preview(session: BackupReplacementSession, selectedSets: Set<String>): BackupReplacementSelection =
        withContext(Dispatchers.Default) {
            BackupReplacementSelection(
                session.sessionId,
                BackupReplacementPreviewPlanner.plan(session.archiveBytesCopy(), session.localSnapshotCopy(), selectedSets),
            )
        }

    suspend fun restore(
        session: BackupReplacementSession,
        selection: BackupReplacementSelection,
        confirmed: Boolean,
        onProgress: suspend (BackupReplacementProgress) -> Unit = {},
    ): BackupReplacementResult {
        require(confirmed) { "La confirmation explicite du remplacement est obligatoire." }
        require(selection.sessionId == session.sessionId) { "La prévisualisation ne correspond plus à cette archive." }
        val plan = selection.plan
        require(plan.selectedSets.isNotEmpty() && plan.selectedSets.all { it in setOf("teas", "configuration") }) {
            "Seuls les thés et la configuration peuvent être restaurés dans cette version."
        }
        require(plan.applicable) {
            "La restauration est bloquée : ${plan.blockers.joinToString(" ").ifBlank { "état cible invalide" }}"
        }
        val target = requireNotNull(plan.target.state)
        val targetErrors = BackupTargetStateBuilder.validatePreparedState(target)
        require(targetErrors.isEmpty()) { "État cible invalide : ${targetErrors.joinToString(" ")}" }
        val archiveBytes = session.archiveBytesCopy()
        require(plan.target.fingerprints.archiveSha256 == plan.comparison.archiveSha256) { "L’empreinte de l’archive a changé depuis la prévisualisation." }
        synchronized(consumedSessions) {
            check(consumedSessions.add(session.sessionId)) { "Cette prévisualisation a déjà été utilisée. Sélectionnez à nouveau l’archive pour recommencer." }
        }

        var operationId: String? = null
        return try {
            withContext(Dispatchers.IO) {
                onProgress(BackupReplacementProgress.PREPARING)
                writeCoordinator.withOperationPermit {
                    // The exact preview snapshot must still be current while holding the exclusive write permit.
                    val fresh = exporter.captureCurrentOperationSnapshot()
                    val freshFingerprint = BackupTargetStateBuilder.fingerprintLocalSnapshot(
                        fresh.archiveBytes, fresh.photos, fresh.importHistory,
                    )
                    require(freshFingerprint == plan.target.fingerprints.localSnapshotSha256) {
                        "La collection a changé depuis la prévisualisation. Rechargez l’archive et vérifiez à nouveau les conséquences."
                    }
                    val id = UUID.randomUUID().toString()
                    operationId = id
                    journal.prepare(
                        operationId = id,
                        operationType = TargetOperation.REPLACE,
                        selectedSets = plan.selectedSets,
                        archiveBytes = archiveBytes,
                        target = target,
                        fingerprints = plan.target.fingerprints,
                    )
                    onProgress(BackupReplacementProgress.APPLYING_ROOM)
                    roomApplier.apply(id, ConfirmedRoomOperation(TargetOperation.REPLACE, plan.selectedSets, plan.target.fingerprints))
                    onProgress(BackupReplacementProgress.FINALIZING)
                    finalizer.finalize(id)
                    val completed = journal.readAll().singleOrNull { it.operationId == id }
                    check(completed?.state == BackupOperationState.COMPLETE && hasNoActiveMarker()) {
                        "La restauration n’a pas atteint son état final vérifié."
                    }
                    BackupReplacementResult(id, resumedAfterInterruption = false)
                }
            }.also { onProgress(BackupReplacementProgress.COMPLETE) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { recoverOwnedOperation(operationId, onProgress) }
            throw cancelled
        } catch (error: Exception) {
            if (operationId == null) throw error
            val recovered = recoverOwnedOperation(operationId, onProgress)
            val entry = runCatching { journal.readAll().singleOrNull { it.operationId == operationId } }.getOrNull()
            if (entry?.state == BackupOperationState.COMPLETE && !recovered.blocksWrites && hasNoActiveMarker()) {
                onProgress(BackupReplacementProgress.COMPLETE)
                BackupReplacementResult(operationId, resumedAfterInterruption = true)
            } else {
                val detail = recovered.detail ?: error.message ?: "La restauration n’a pas pu être terminée."
                throw IOException("Restauration interrompue. $detail", error)
            }
        }
    }

    private suspend fun recoverOwnedOperation(
        operationId: String?,
        onProgress: suspend (BackupReplacementProgress) -> Unit,
    ): RecoveryInspection {
        if (operationId == null) return RecoveryInspection(RecoveryFinding.NONE)
        val entry = runCatching { journal.readAll().singleOrNull { it.operationId == operationId } }.getOrNull()
            ?: return RecoveryInspection(RecoveryFinding.NONE)
        if (entry.state == BackupOperationState.COMPLETE && hasNoActiveMarker()) return RecoveryInspection(RecoveryFinding.NONE)
        onProgress(BackupReplacementProgress.RECOVERING)
        return recoverPendingOperation()
    }
}
