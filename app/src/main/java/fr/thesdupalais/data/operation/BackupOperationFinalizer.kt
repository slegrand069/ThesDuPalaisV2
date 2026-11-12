package fr.thesdupalais.data.operation

import androidx.room.withTransaction
import fr.thesdupalais.data.backup.BackupArchiveExporter
import fr.thesdupalais.data.backup.BackupArchiveValidator
import fr.thesdupalais.data.backup.BackupTargetState
import fr.thesdupalais.data.backup.BackupTargetStateBuilder
import fr.thesdupalais.data.backup.TargetPhotoRecord
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.preferences.LocalPreferencesRepository
import fr.thesdupalais.data.preferences.UserPreferences
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import org.json.JSONObject

enum class FinalizationFaultPoint { BEFORE_DATASTORE_EDIT, AFTER_DATASTORE_EDIT_BEFORE_FLAG, AFTER_MEDIA_COPY_BEFORE_SOURCE_DELETE, AFTER_MEDIA_BEFORE_FLAG, AFTER_COMPLETE_BEFORE_MARKER_CLEAR }
fun interface FinalizationFaultInjector { fun hit(point: FinalizationFaultPoint) }

/** Forward-only completion after the Room marker. No user-facing restore/fusion entry point exists yet. */
class BackupOperationFinalizer(
    private val database: AppDatabase,
    private val exporter: BackupArchiveExporter,
    private val preferences: LocalPreferencesRepository,
    private val journal: DurableBackupOperationJournal,
    private val writeCoordinator: BackupOperationWriteCoordinator,
    private val faultInjector: FinalizationFaultInjector = FinalizationFaultInjector { },
) {
    suspend fun finalize(operationId: String) {
        val saved = journal.readAll().singleOrNull { it.operationId == operationId }
            ?: throw IOException("Journal d’opération introuvable.")
        if (saved.state == BackupOperationState.COMPLETE && database.backupOperationMarkerDao().findActive() == null) {
            if (!journal.hasQuarantineArtifacts(operationId) && !journal.hasPayloadArtifacts(operationId)) return
            writeCoordinator.withRecoveryPermit(operationId) {
                journal.cleanupQuarantine(operationId)
                journal.cleanupPayloadArtifacts(operationId)
            }
            return
        }
        writeCoordinator.withRecoveryPermit(operationId) {
            try {
                finalizeUnderPermit(operationId)
            } catch (error: Throwable) {
                runCatching { journal.recordError(operationId, error) }
                throw error
            }
        }
    }

    private suspend fun finalizeUnderPermit(operationId: String) {
        var entry = journal.readAll().singleOrNull { it.operationId == operationId }
            ?: throw IOException("Journal d’opération introuvable.")
        require(entry.state !in setOf(BackupOperationState.PREPARING, BackupOperationState.PREPARED, BackupOperationState.ABORTED)) {
            "La finalisation vers l’avant exige une validation Room prouvée."
        }
        journal.verifyArtifacts(entry)
        val marker = database.backupOperationMarkerDao().findActive()
            ?: throw IOException("Marqueur Room absent : la validation transactionnelle n’est pas démontrée.")
        require(marker.operationId == operationId && marker.archiveSha256 == entry.archiveSha256 && marker.targetFingerprint == entry.targetFingerprint &&
            marker.state in setOf(BackupOperationState.ROOM_COMMITTED.name, BackupOperationState.FINALIZING.name)) {
            "Le marqueur Room ne correspond pas au journal préparé. Récupération bloquée."
        }
        if (entry.state == BackupOperationState.ROOM_APPLYING) entry = journal.transition(operationId, BackupOperationState.ROOM_COMMITTED)
        if (entry.state == BackupOperationState.ROOM_COMMITTED || entry.state == BackupOperationState.RECOVERY_REQUIRED) {
            entry = journal.transition(operationId, BackupOperationState.FINALIZING)
        }
        require(entry.state in setOf(BackupOperationState.FINALIZING, BackupOperationState.COMPLETE)) { "État de journal non finalisable : ${entry.state}." }
        val target = journal.readTargetState(entry)
        val expectedPrefs = readPreferences(target)
        val currentPrefs = preferences.preferences.first()
        if ("configuration" in entry.selectedSets) {
            if (!entry.dataStoreFinalized) {
                faultInjector.hit(FinalizationFaultPoint.BEFORE_DATASTORE_EDIT)
                if (currentPrefs != expectedPrefs) preferences.setPreferencesForRecovery(operationId, expectedPrefs)
                faultInjector.hit(FinalizationFaultPoint.AFTER_DATASTORE_EDIT_BEFORE_FLAG)
                journal.markDataStoreFinalized(operationId)
            } else require(currentPrefs == expectedPrefs) { "DataStore diverge de l’état cible après sa finalisation; récupération bloquée." }
        } else {
            require(currentPrefs == expectedPrefs) { "Les préférences locales ont changé depuis la préparation; récupération bloquée." }
            if (!entry.dataStoreFinalized) journal.markDataStoreFinalized(operationId)
        }
        require(preferences.preferences.first() == expectedPrefs) { "Vérification DataStore de l’état cible échouée." }

        entry.mediaArtifacts.forEach { artifact ->
            entry = journal.readAll().single { it.operationId == operationId }
            if (artifact.photoId !in entry.finalizedMediaPhotoIds) {
                finalizeMedia(entry, target, artifact.photoId)
                faultInjector.hit(FinalizationFaultPoint.AFTER_MEDIA_BEFORE_FLAG)
                journal.markMediaFinalized(operationId, artifact.photoId)
            } else verifyMediaResult(entry, target, artifact.photoId)
        }
        verifyTargetRoom(target)
        require(preferences.preferences.first() == expectedPrefs) { "Vérification finale DataStore échouée." }
        entry = journal.readAll().single { it.operationId == operationId }
        require(entry.dataStoreFinalized && entry.finalizedMediaPhotoIds.containsAll(entry.mediaArtifacts.map { it.photoId })) {
            "La finalisation ne couvre pas toutes les données et tous les médias préparés."
        }
        if (entry.state == BackupOperationState.FINALIZING) journal.transition(operationId, BackupOperationState.COMPLETE)
        faultInjector.hit(FinalizationFaultPoint.AFTER_COMPLETE_BEFORE_MARKER_CLEAR)
        database.withTransaction {
            val currentMarker = database.backupOperationMarkerDao().findActive()
            require(currentMarker?.operationId == operationId && currentMarker.targetFingerprint == entry.targetFingerprint) {
                "Le marqueur Room a changé avant le nettoyage final."
            }
            database.backupOperationMarkerDao().clear()
        }
        // Quarantine is disposable only after target Room and DataStore were verified.
        journal.cleanupQuarantine(operationId)
        journal.cleanupPayloadArtifacts(operationId)
    }

    private suspend fun verifyTargetRoom(target: BackupTargetState) {
        val current = exporter.captureCurrentOperationSnapshot()
        val currentEntries = BackupArchiveValidator.validate(current.archiveBytes).entries
        val targetConfiguration = JSONObject(target.payloads["data/configuration.json"]
            ?: throw IOException("Configuration cible absente pendant la vérification Room."))
        target.payloads.forEach { (path, json) ->
            val actual = currentEntries[path] ?: throw IOException("Ensemble Room absent après application : $path.")
            val expected = JSONObject(json).let { payload ->
                if (path == "data/teas.json") {
                    // Older prepared merge targets could carry the entire configuration catalog
                    // in teas.json. Keep the persisted target and its fingerprint untouched, but
                    // compare its exact tea dependency closure against Room for forward recovery.
                    try { BackupTargetStateBuilder.normalizeTeaDependencyReferences(payload, targetConfiguration) }
                    catch (error: Exception) { throw IOException("Dépendances de références de l’état cible invalides : ${error.message ?: "erreur"}.", error) }
                } else payload
            }
            if (BackupTargetStateBuilder.canonicalJson(expected) !=
                BackupTargetStateBuilder.canonicalJson(JSONObject(String(actual, Charsets.UTF_8)))) {
                throw IOException("Les données Room ne correspondent pas à l’état cible : $path.")
            }
        }
        val expectedPhotos = target.photosToKeep.sortedWith(photoOrder)
        val actualPhotos = current.photos.sortedWith(photoOrder)
        require(actualPhotos == expectedPhotos) { "Les relations photo Room ne correspondent pas à l’état cible." }
        target.photosToKeep.forEach { photo ->
            require(journal.privateMediaFile(photo.relativePath).isFile) { "Photo conservée absente du stockage privé : ${photo.relativePath}." }
        }
        val importRows = current.importHistory.associateBy { it.datasetId }
        require(target.supersededImportDatasetIds.all { importRows[it]?.isSuperseded == true }) {
            "Les marqueurs des jeux d’import remplacés ne correspondent pas à l’état cible."
        }
    }

    private fun finalizeMedia(entry: DurableOperationEntry, target: BackupTargetState, photoId: String) {
        val artifact = entry.mediaArtifacts.singleOrNull { it.photoId == photoId } ?: throw IOException("Métadonnée de photo absente.")
        val staged = journal.stagedMediaFile(entry, photoId)
        val destination = journal.quarantineFile(entry.operationId, photoId)
        if (!destination.isFile) {
            val parent = destination.parentFile ?: throw IOException("Dossier de quarantaine absent.")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Création de la quarantaine impossible.")
            val part = File(parent, destination.name + ".part")
            copyAndSync(staged, part)
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        verifyFile(destination, artifact.sizeBytes, artifact.sha256)
        faultInjector.hit(FinalizationFaultPoint.AFTER_MEDIA_COPY_BEFORE_SOURCE_DELETE)
        val source = journal.privateMediaFile(artifact.sourceRelativePath)
        val samePathKept = target.photosToKeep.any { it.relativePath == artifact.sourceRelativePath }
        if (!samePathKept && source.exists()) {
            verifyFile(source, artifact.sizeBytes, artifact.sha256)
            if (!source.delete()) throw IOException("Suppression de la photo remplacée impossible : ${artifact.sourceRelativePath}.")
        }
        verifyMediaResult(entry, target, photoId)
    }

    private fun verifyMediaResult(entry: DurableOperationEntry, target: BackupTargetState, photoId: String) {
        val artifact = entry.mediaArtifacts.singleOrNull { it.photoId == photoId } ?: throw IOException("Métadonnée de photo absente.")
        verifyFile(journal.quarantineFile(entry.operationId, photoId), artifact.sizeBytes, artifact.sha256)
        val source = journal.privateMediaFile(artifact.sourceRelativePath)
        if (target.photosToKeep.none { it.relativePath == artifact.sourceRelativePath }) require(!source.exists()) {
            "La photo retirée est toujours présente après finalisation : ${artifact.sourceRelativePath}."
        }
    }

    private fun readPreferences(target: BackupTargetState): UserPreferences {
        val config = JSONObject(target.payloads["data/configuration.json"] ?: throw IOException("Configuration cible absente."))
        val prefs = config.getJSONObject("preferences")
        return UserPreferences(prefs.getLong("quantityPerCupMilliGrams"), prefs.getLong("lowStockThresholdMilliGrams"))
    }

    private fun copyAndSync(source: File, destination: File) {
        FileInputStream(source).use { input -> FileOutputStream(destination).use { output -> input.copyTo(output); output.fd.sync() } }
    }

    private fun verifyFile(file: File, size: Long, hash: String) {
        if (!file.isFile || file.length() != size || sha256(file) != hash) throw IOException("Taille ou empreinte média incorrecte : ${file.name}.")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input -> val buffer = ByteArray(16 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val photoOrder = compareBy<TargetPhotoRecord> { it.id }.thenBy { it.teaId }.thenBy { it.position }.thenBy { it.relativePath }
    }
}
