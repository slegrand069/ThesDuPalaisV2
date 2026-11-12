package fr.thesdupalais.data.operation

import android.content.ContentResolver
import android.net.Uri
import fr.thesdupalais.data.backup.BackupArchiveExporter
import fr.thesdupalais.data.backup.BackupArchiveMergePlan
import fr.thesdupalais.data.backup.BackupArchiveMergePlanner
import fr.thesdupalais.data.backup.BackupArchiveValidator
import fr.thesdupalais.data.backup.BackupExportSet
import fr.thesdupalais.data.backup.BackupLocalOperationSnapshot
import fr.thesdupalais.data.backup.BackupTargetRequest
import fr.thesdupalais.data.backup.BackupTargetResult
import fr.thesdupalais.data.backup.BackupTargetStateBuilder
import fr.thesdupalais.data.backup.ConfirmedMergeChange
import fr.thesdupalais.data.backup.TargetOperation
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class BackupMergeLabels(
    val businessLabels: Map<String, String>,
    val teaNames: Map<String, String>,
    val historyTeaNames: Map<String, String>,
)

class BackupMergeSession internal constructor(
    val backupId: String,
    val createdAt: String,
    val includedSets: Set<String>,
    val unsupportedSets: Set<String>,
    val labels: BackupMergeLabels,
    archiveBytes: ByteArray,
    localSnapshot: BackupLocalOperationSnapshot,
) {
    internal val sessionId = UUID.randomUUID().toString()
    private val archive = archiveBytes.copyOf()
    private val local = localSnapshot.copy(
        archiveBytes = localSnapshot.archiveBytes.copyOf(),
        photos = localSnapshot.photos.toList(),
        importHistory = localSnapshot.importHistory.toList(),
    )
    internal fun archiveCopy() = archive.copyOf()
    internal fun localCopy() = local.copy(archiveBytes = local.archiveBytes.copyOf())
}

class BackupMergeSelection internal constructor(
    internal val sessionId: String,
    val selectedSets: Set<String>,
    val mergePlan: BackupArchiveMergePlan,
    val confirmedChanges: Set<ConfirmedMergeChange>,
    val target: BackupTargetResult,
) {
    val applicable: Boolean get() = mergePlan.canApplyAfterConfirmation && target.applicable
}

enum class BackupMergeProgress { PREPARING, APPLYING_ROOM, FINALIZING, RECOVERING, COMPLETE }
data class BackupMergeResult(val operationId: String, val resumedAfterInterruption: Boolean)

/** Owns immutable preview inputs and applies only the exact decisions confirmed for them. */
class BackupMergeWorkflow(
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

    suspend fun read(uri: Uri): BackupMergeSession = withContext(Dispatchers.IO) {
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
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
        createSession(bytes)
    }

    internal suspend fun previewBytes(bytes: ByteArray): BackupMergeSession = withContext(Dispatchers.IO) {
        require(bytes.size.toLong() <= BackupArchiveValidator.MAX_ARCHIVE_BYTES) { "L’archive dépasse la limite compressée de 50 Mio." }
        createSession(bytes.copyOf())
    }

    private suspend fun createSession(bytes: ByteArray): BackupMergeSession {
        val archive = BackupArchiveValidator.validate(bytes)
        val local = exporter.captureCurrentOperationSnapshot()
        BackupArchiveValidator.validate(local.archiveBytes)
        val supported = archive.includedSets.intersect(setOf("teas", "configuration"))
        return BackupMergeSession(
            backupId = archive.backupId,
            createdAt = archive.createdAt,
            includedSets = archive.includedSets,
            unsupportedSets = archive.includedSets - supported,
            labels = readLabels(archive, BackupArchiveValidator.validate(local.archiveBytes)),
            archiveBytes = bytes,
            localSnapshot = local,
        )
    }

    suspend fun preview(
        session: BackupMergeSession,
        selectedSets: Set<String>,
        confirmedChanges: Set<ConfirmedMergeChange>,
    ): BackupMergeSelection = withContext(Dispatchers.Default) {
        val archive = session.archiveCopy()
        val local = session.localCopy()
        val plan = BackupArchiveMergePlanner.plan(archive, local.archiveBytes, selectedSets)
        val result = buildTarget(archive, local, selectedSets, confirmedChanges, plan)
        BackupMergeSelection(session.sessionId, selectedSets.toSortedSet(), plan, confirmedChanges.toSet(), result)
    }

    suspend fun apply(
        session: BackupMergeSession,
        selection: BackupMergeSelection,
        confirmed: Boolean,
        onProgress: suspend (BackupMergeProgress) -> Unit = {},
    ): BackupMergeResult {
        require(confirmed) { "La confirmation explicite de la fusion est obligatoire." }
        require(selection.sessionId == session.sessionId) { "La prévisualisation ne correspond plus à cette archive." }
        require(selection.selectedSets.isNotEmpty() && selection.selectedSets.all { it in setOf("teas", "configuration") }) {
            "Sélectionnez au moins un ensemble pris en charge (thés ou configuration)."
        }
        require(selection.mergePlan.selectedSets == selection.selectedSets && selection.mergePlan.canApplyAfterConfirmation) {
            "La fusion est bloquée par un conflit d’identité, d’historique ou de cohérence. Aucun choix ne peut lever ce blocage."
        }
        require(selection.applicable) {
            "État cible invalide : ${selection.target.blockers.joinToString(" ").ifBlank { "vérification impossible" }}"
        }
        val previewState = requireNotNull(selection.target.state)
        val targetErrors = BackupTargetStateBuilder.validatePreparedState(previewState)
        require(targetErrors.isEmpty()) { "État cible invalide : ${targetErrors.joinToString(" ")}" }
        val archiveBytes = session.archiveCopy()
        require(selection.mergePlan.archiveSha256 == selection.target.fingerprints.archiveSha256) {
            "L’empreinte de l’archive ne correspond pas au plan prévisualisé."
        }
        synchronized(consumedSessions) {
            check(consumedSessions.add(session.sessionId)) { "Cette prévisualisation a déjà été utilisée. Sélectionnez à nouveau l’archive pour recommencer." }
        }

        var operationId: String? = null
        return try {
            withContext(Dispatchers.IO) {
                onProgress(BackupMergeProgress.PREPARING)
                writeCoordinator.withOperationPermit {
                    val fresh = exporter.captureCurrentOperationSnapshot()
                    val freshFingerprint = BackupTargetStateBuilder.fingerprintLocalSnapshot(fresh.archiveBytes, fresh.photos, fresh.importHistory)
                    require(freshFingerprint == selection.target.fingerprints.localSnapshotSha256) {
                        "La collection a changé depuis la prévisualisation. Rechargez l’archive et vérifiez à nouveau les décisions."
                    }
                    val freshPlan = BackupArchiveMergePlanner.plan(archiveBytes, fresh.archiveBytes, selection.selectedSets)
                    require(freshPlan == selection.mergePlan) { "Le plan de fusion ne correspond plus à l’archive et à la collection confirmées." }
                    val freshTarget = buildTarget(archiveBytes, fresh, selection.selectedSets, selection.confirmedChanges, selection.mergePlan)
                    require(freshTarget.applicable && freshTarget.fingerprints == selection.target.fingerprints && freshTarget.state == previewState) {
                        "L’état cible a changé depuis sa prévisualisation. Recommencez l’analyse avant de fusionner."
                    }

                    val id = UUID.randomUUID().toString()
                    operationId = id
                    journal.prepare(id, TargetOperation.MERGE, selection.selectedSets, archiveBytes, previewState, selection.target.fingerprints)
                    onProgress(BackupMergeProgress.APPLYING_ROOM)
                    roomApplier.apply(id, ConfirmedRoomOperation(TargetOperation.MERGE, selection.selectedSets, selection.target.fingerprints))
                    onProgress(BackupMergeProgress.FINALIZING)
                    finalizer.finalize(id)
                    val completed = journal.readAll().singleOrNull { it.operationId == id }
                    check(completed?.state == BackupOperationState.COMPLETE && hasNoActiveMarker()) {
                        "La fusion n’a pas atteint son état final vérifié."
                    }
                    BackupMergeResult(id, resumedAfterInterruption = false)
                }
            }.also { onProgress(BackupMergeProgress.COMPLETE) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { recoverOwnedOperation(operationId, onProgress) }
            throw cancelled
        } catch (error: Exception) {
            if (operationId == null) throw error
            val recovered = recoverOwnedOperation(operationId, onProgress)
            val entry = runCatching { journal.readAll().singleOrNull { it.operationId == operationId } }.getOrNull()
            if (entry?.state == BackupOperationState.COMPLETE && !recovered.blocksWrites && hasNoActiveMarker()) {
                onProgress(BackupMergeProgress.COMPLETE)
                BackupMergeResult(operationId, resumedAfterInterruption = true)
            } else {
                throw IOException("Fusion interrompue. ${recovered.detail ?: error.message ?: "La fusion n’a pas pu être terminée."}", error)
            }
        }
    }

    private fun buildTarget(
        archive: ByteArray,
        local: BackupLocalOperationSnapshot,
        selectedSets: Set<String>,
        confirmedChanges: Set<ConfirmedMergeChange>,
        plan: BackupArchiveMergePlan,
    ): BackupTargetResult = BackupTargetStateBuilder.build(
        BackupTargetRequest(
            archiveBytes = archive,
            localSnapshotBytes = local.archiveBytes,
            operation = TargetOperation.MERGE,
            selectedSets = selectedSets,
            confirmedChanges = confirmedChanges,
            photos = local.photos,
            importHistory = local.importHistory,
            planner = plan,
        ),
    )

    private suspend fun recoverOwnedOperation(operationId: String?, onProgress: suspend (BackupMergeProgress) -> Unit): RecoveryInspection {
        if (operationId == null) return RecoveryInspection(RecoveryFinding.NONE)
        val entry = runCatching { journal.readAll().singleOrNull { it.operationId == operationId } }.getOrNull()
            ?: return RecoveryInspection(RecoveryFinding.NONE)
        if (entry.state == BackupOperationState.COMPLETE && hasNoActiveMarker()) return RecoveryInspection(RecoveryFinding.NONE)
        onProgress(BackupMergeProgress.RECOVERING)
        return recoverPendingOperation()
    }

    private fun readLabels(
        archive: BackupArchiveValidator.ValidatedArchive,
        local: BackupArchiveValidator.ValidatedArchive,
    ): BackupMergeLabels {
        val refs = mutableMapOf<String, String>()
        val teaNames = mutableMapOf<String, String>()
        val history = mutableMapOf<String, String>()
        listOf(archive, local).forEach { validated ->
            validated.entries["data/configuration.json"]?.let { bytes ->
                val array = JSONObject(String(bytes, StandardCharsets.UTF_8)).optJSONArray("references")
                if (array != null) for (index in 0 until array.length()) {
                    val ref = array.getJSONObject(index)
                    refs[ref.optString("id")] = ref.optString("label", ref.optString("kind", "Référence"))
                }
            }
            validated.entries["data/teas.json"]?.let { bytes ->
                val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
                val teas = root.optJSONArray("teas")
                if (teas != null) for (index in 0 until teas.length()) {
                    val tea = teas.getJSONObject(index)
                    teaNames[tea.optString("id")] = tea.optString("name", "Thé sans nom")
                }
                listOf("tastings", "stockMovements").forEach { field ->
                    val rows = root.optJSONArray(field)
                    if (rows != null) for (index in 0 until rows.length()) {
                        val row = rows.getJSONObject(index)
                        teaNames[row.optString("teaId")]?.let { history[row.optString("id")] = it }
                    }
                }
            }
        }
        return BackupMergeLabels(refs, teaNames, history)
    }
}
