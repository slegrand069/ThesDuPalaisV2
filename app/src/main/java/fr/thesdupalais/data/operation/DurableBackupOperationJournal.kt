package fr.thesdupalais.data.operation

import fr.thesdupalais.data.backup.BackupArchiveValidator
import fr.thesdupalais.data.backup.BackupTargetState
import fr.thesdupalais.data.backup.BackupTargetStateBuilder
import fr.thesdupalais.data.backup.BackupTargetFingerprints
import fr.thesdupalais.data.backup.TargetOperation
import fr.thesdupalais.data.backup.TargetPhotoRecord
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class DurableOperationEntry(
    val operationId: String,
    val operationType: TargetOperation,
    val selectedSets: Set<String>,
    val state: BackupOperationState,
    val archiveSha256: String,
    val localSnapshotSha256: String,
    val operationSha256: String,
    val targetFingerprint: String,
    val targetBundleSha256: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastError: String?,
    val mediaArtifacts: List<DurableMediaArtifact> = emptyList(),
    val dataStoreFinalized: Boolean = false,
    val finalizedMediaPhotoIds: Set<String> = emptySet(),
)

data class DurableMediaArtifact(
    val photoId: String,
    val sourceRelativePath: String,
    val stagedFileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

enum class PreparationFaultPoint { AFTER_PREPARING_JOURNAL, AFTER_ARCHIVE_COPY, AFTER_TARGET_COPY, BEFORE_PREPARED }
fun interface PreparationFaultInjector { fun hit(point: PreparationFaultPoint) }

/**
 * Durable pre-apply artifacts, kept in app-private no-backup storage. This component only stages,
 * verifies and journals bytes; it never writes collection tables or executes recovery.
 */
class DurableBackupOperationJournal(
    private val root: File,
    private val mediaRoot: File? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val faultInjector: PreparationFaultInjector = PreparationFaultInjector { },
) {
    fun prepare(
        operationId: String,
        operationType: TargetOperation,
        selectedSets: Set<String>,
        archiveBytes: ByteArray,
        target: BackupTargetState,
        fingerprints: BackupTargetFingerprints,
    ): DurableOperationEntry {
        requireCanonicalUuid(operationId)
        require(selectedSets.isNotEmpty() && selectedSets.all { it in setOf("teas", "configuration") }) {
            "Sélection d’ensembles invalide pour une opération durable."
        }
        require(archiveBytes.size.toLong() <= BackupArchiveValidator.MAX_ARCHIVE_BYTES) { "Archive trop volumineuse pour le journal." }
        BackupArchiveValidator.validate(archiveBytes)
        require(sha256(archiveBytes) == fingerprints.archiveSha256) { "L’archive ne correspond pas au plan confirmé." }
        listOf(fingerprints.archiveSha256, fingerprints.localSnapshotSha256, fingerprints.operationSha256, fingerprints.targetSha256)
            .forEach { require(it.matches(Regex("[0-9a-f]{64}"))) { "Empreinte SHA-256 invalide." } }
        require(BackupTargetStateBuilder.fingerprintTargetState(target) == fingerprints.targetSha256) {
            "L’empreinte ne correspond pas à l’état cible fourni."
        }

        val archiveSha = fingerprints.archiveSha256
        val targetBytes = encodeTarget(target)
        require(targetBytes.size.toLong() <= MAX_TARGET_BYTES) { "État cible trop volumineux pour la préparation durable." }
        val targetSha = sha256(targetBytes)
        val operationDir = directoryFor(operationId)
        var existing: DurableOperationEntry? = null
        if (operationDir.exists()) {
            val previous = readEntry(operationDir)
            existing = previous
            require(previous.archiveSha256 == archiveSha && previous.localSnapshotSha256 == fingerprints.localSnapshotSha256 &&
                previous.operationSha256 == fingerprints.operationSha256 && previous.targetBundleSha256 == targetSha &&
                previous.targetFingerprint == fingerprints.targetSha256 && previous.selectedSets == selectedSets) {
                "L’identifiant d’opération existe déjà avec un contenu différent."
            }
            if (previous.state == BackupOperationState.PREPARED) {
                verifyArtifacts(previous)
                return previous
            }
            require(previous.state == BackupOperationState.PREPARING) { "L’identifiant appartient déjà à une opération commencée ou terminée." }
        } else {
            if (!operationDir.mkdirs()) throw IOException("Impossible de créer le dossier privé de l’opération.")
        }

        val now = nowEpochMillis()
        var entry = existing ?: DurableOperationEntry(
            operationId = operationId,
            operationType = operationType,
            selectedSets = selectedSets.toSortedSet(),
            state = BackupOperationState.PREPARING,
            archiveSha256 = archiveSha,
            localSnapshotSha256 = fingerprints.localSnapshotSha256,
            operationSha256 = fingerprints.operationSha256,
            targetFingerprint = fingerprints.targetSha256,
            targetBundleSha256 = targetSha,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            lastError = null,
        )
        if (existing == null) writeEntry(operationDir, entry)
        try {
            faultInjector.hit(PreparationFaultPoint.AFTER_PREPARING_JOURNAL)
            writeAtomically(File(operationDir, ARCHIVE_NAME), archiveBytes)
            faultInjector.hit(PreparationFaultPoint.AFTER_ARCHIVE_COPY)
            writeAtomically(File(operationDir, TARGET_NAME), targetBytes)
            faultInjector.hit(PreparationFaultPoint.AFTER_TARGET_COPY)
            val stagedMedia = if (existing?.mediaArtifacts?.isNotEmpty() == true) {
                verifyMediaArtifacts(operationDir, existing.mediaArtifacts)
                existing.mediaArtifacts
            } else stageMedia(operationDir, target.photosToQuarantine)
            entry = entry.copy(mediaArtifacts = stagedMedia)
            writeEntry(operationDir, entry)
            verifyArtifacts(entry)
            faultInjector.hit(PreparationFaultPoint.BEFORE_PREPARED)
            BackupOperationStateMachine.requireTransition(entry.state, BackupOperationState.PREPARED)
            entry = entry.copy(state = BackupOperationState.PREPARED, updatedAtEpochMillis = nowEpochMillis(), lastError = null)
            writeEntry(operationDir, entry)
            return entry
        } catch (error: Exception) {
            runCatching {
                val current = readEntry(operationDir)
                if (current.state == BackupOperationState.PREPARING) {
                    writeEntry(operationDir, current.copy(updatedAtEpochMillis = nowEpochMillis(), lastError = error.message?.take(2000) ?: error.javaClass.simpleName))
                }
            }
            throw error
        }
    }

    fun transition(operationId: String, next: BackupOperationState, error: String? = null): DurableOperationEntry {
        requireCanonicalUuid(operationId)
        val dir = directoryFor(operationId)
        val current = readEntry(dir)
        if (current.state == next && current.lastError == error) return current
        BackupOperationStateMachine.requireTransition(current.state, next)
        val updated = current.copy(state = next, updatedAtEpochMillis = nowEpochMillis(), lastError = error)
        writeEntry(dir, updated)
        return updated
    }

    fun markDataStoreFinalized(operationId: String): DurableOperationEntry = update(operationId) { it.copy(dataStoreFinalized = true, lastError = null) }

    fun markMediaFinalized(operationId: String, photoId: String): DurableOperationEntry = update(operationId) {
        it.copy(finalizedMediaPhotoIds = it.finalizedMediaPhotoIds + photoId, lastError = null)
    }

    fun recordError(operationId: String, error: Throwable): DurableOperationEntry = update(operationId) {
        it.copy(lastError = (error.message ?: error.javaClass.simpleName).take(2000))
    }

    private fun update(operationId: String, transform: (DurableOperationEntry) -> DurableOperationEntry): DurableOperationEntry {
        val dir = directoryFor(operationId)
        val current = readEntry(dir)
        val updated = transform(current).copy(updatedAtEpochMillis = nowEpochMillis())
        writeEntry(dir, updated)
        return updated
    }

    /** Resolve a validated app-private media path and refuse symlinks/path escape. */
    fun privateMediaFile(relativePath: String): File {
        validateRelativeMediaPath(relativePath)
        val base = mediaRoot?.canonicalFile ?: throw IOException("Dossier privé des médias indisponible.")
        val candidate = File(base, relativePath).canonicalFile
        val prefix = base.path.trimEnd(File.separatorChar) + File.separator
        if (!candidate.path.startsWith(prefix)) throw IOException("Chemin média hors du dossier privé.")
        return candidate
    }

    fun quarantineFile(operationId: String, photoId: String): File {
        requireCanonicalUuid(operationId); requireCanonicalUuid(photoId)
        val base = mediaRoot?.canonicalFile ?: throw IOException("Dossier privé des médias indisponible.")
        val rootDir = File(base, QUARANTINE_DIRECTORY).canonicalFile
        if (rootDir.parentFile != base) throw IOException("Dossier de quarantaine hors de l’espace privé autorisé.")
        val opDir = File(rootDir, operationId).canonicalFile
        if (opDir.parentFile != rootDir) throw IOException("Chemin de quarantaine non sûr.")
        return File(opDir, "$photoId.media").canonicalFile.also {
            if (it.parentFile != opDir) throw IOException("Chemin de quarantaine non sûr.")
        }
    }

    fun cleanupPayloadArtifacts(operationId: String) {
        val dir = directoryFor(operationId)
        listOf(ARCHIVE_NAME, TARGET_NAME, MEDIA_DIRECTORY, "$ARCHIVE_NAME.part", "$TARGET_NAME.part", "$JOURNAL_NAME.part").forEach { name ->
            val child = safeFile(dir, name)
            if (child.isDirectory) child.deleteRecursively() else if (child.exists() && !child.delete()) throw IOException("Nettoyage impossible : ${child.name}.")
        }
    }

    fun hasPayloadArtifacts(operationId: String): Boolean {
        val dir = directoryFor(operationId)
        return listOf(ARCHIVE_NAME, TARGET_NAME, MEDIA_DIRECTORY, "$ARCHIVE_NAME.part", "$TARGET_NAME.part", "$JOURNAL_NAME.part").any { safeFile(dir, it).exists() }
    }

    fun hasQuarantineArtifacts(operationId: String): Boolean {
        requireCanonicalUuid(operationId)
        val base = mediaRoot?.canonicalFile ?: throw IOException("Dossier privé des médias indisponible.")
        val rootDir = File(base, QUARANTINE_DIRECTORY).canonicalFile
        if (rootDir.parentFile != base) throw IOException("Dossier de quarantaine hors de l’espace privé autorisé.")
        val opDir = File(rootDir, operationId).canonicalFile
        if (opDir.parentFile != rootDir) throw IOException("Chemin de quarantaine non sûr.")
        return opDir.exists()
    }

    fun cleanupQuarantine(operationId: String) {
        requireCanonicalUuid(operationId)
        val base = mediaRoot?.canonicalFile ?: throw IOException("Dossier privé des médias indisponible.")
        val rootDir = File(base, QUARANTINE_DIRECTORY).canonicalFile
        if (rootDir.parentFile != base) throw IOException("Dossier de quarantaine hors de l’espace privé autorisé.")
        val opDir = File(rootDir, operationId).canonicalFile
        if (opDir.parentFile == rootDir && opDir.exists() && !opDir.deleteRecursively()) throw IOException("Nettoyage de quarantaine impossible.")
    }

    fun readAll(): List<DurableOperationEntry> {
        if (!root.exists()) return emptyList()
        if (!root.isDirectory) throw IOException("Le chemin du journal n’est pas un dossier.")
        return root.listFiles()?.sortedBy { it.name }?.map { dir ->
            if (!dir.isDirectory) throw IOException("Entrée inattendue dans le journal des opérations.")
            readEntry(dir)
        }.orEmpty()
    }

    fun readPending(): List<DurableOperationEntry> = readAll().filterNot { it.state == BackupOperationState.COMPLETE || it.state == BackupOperationState.ABORTED }

    fun verifyArtifacts(entry: DurableOperationEntry) {
        val dir = directoryFor(entry.operationId)
        val archive = safeFile(dir, ARCHIVE_NAME).takeIf(File::isFile) ?: throw IOException("Copie durable de l’archive absente.")
        val target = safeFile(dir, TARGET_NAME).takeIf(File::isFile) ?: throw IOException("Copie durable de l’état cible absente.")
        if (sha256(readBounded(archive, BackupArchiveValidator.MAX_ARCHIVE_BYTES)) != entry.archiveSha256) throw IOException("Empreinte de la copie d’archive incorrecte.")
        val targetBytes = readBounded(target, MAX_TARGET_BYTES)
        if (sha256(targetBytes) != entry.targetBundleSha256) throw IOException("Empreinte de l’état cible durable incorrecte.")
        val decodedTarget = decodeTarget(targetBytes)
        if (BackupTargetStateBuilder.fingerprintTargetState(decodedTarget) != entry.targetFingerprint) {
            throw IOException("L’état cible durable ne correspond pas à l’empreinte confirmée.")
        }
        val expectedPhotos = decodedTarget.photosToQuarantine.associateBy { it.id }
        if (expectedPhotos.size != decodedTarget.photosToQuarantine.size || expectedPhotos.keys != entry.mediaArtifacts.map { it.photoId }.toSet()) {
            throw IOException("Les fichiers médias préparés ne correspondent pas aux photos à mettre en quarantaine.")
        }
        entry.mediaArtifacts.forEach { artifact ->
            if (expectedPhotos[artifact.photoId]?.relativePath != artifact.sourceRelativePath) throw IOException("Chemin source de média incohérent.")
        }
        verifyMediaArtifacts(dir, entry.mediaArtifacts)
    }

    fun readArchive(entry: DurableOperationEntry): ByteArray {
        verifyArtifacts(entry)
        return readBounded(safeFile(directoryFor(entry.operationId), ARCHIVE_NAME), BackupArchiveValidator.MAX_ARCHIVE_BYTES)
    }

    fun readTargetBundle(entry: DurableOperationEntry): ByteArray {
        verifyArtifacts(entry)
        return readBounded(safeFile(directoryFor(entry.operationId), TARGET_NAME), MAX_TARGET_BYTES)
    }

    fun readTargetState(entry: DurableOperationEntry): BackupTargetState = decodeTarget(readTargetBundle(entry))

    fun stagedMediaFile(entry: DurableOperationEntry, photoId: String): File {
        verifyArtifacts(entry)
        val artifact = entry.mediaArtifacts.singleOrNull { it.photoId == photoId }
            ?: throw IOException("Copie temporaire de la photo absente.")
        return safeFile(File(directoryFor(entry.operationId), MEDIA_DIRECTORY), artifact.stagedFileName)
    }

    private fun writeEntry(dir: File, entry: DurableOperationEntry) {
        val media = JSONArray()
        entry.mediaArtifacts.sortedBy { it.photoId }.forEach { artifact ->
            media.put(JSONObject()
                .put("photoId", artifact.photoId)
                .put("sourceRelativePath", artifact.sourceRelativePath)
                .put("stagedFileName", artifact.stagedFileName)
                .put("sizeBytes", artifact.sizeBytes)
                .put("sha256", artifact.sha256))
        }
        val json = JSONObject()
            .put("journalVersion", JOURNAL_VERSION)
            .put("operationId", entry.operationId)
            .put("operationType", entry.operationType.name)
            .put("selectedSets", JSONArray(entry.selectedSets.sorted()))
            .put("state", entry.state.name)
            .put("archiveSha256", entry.archiveSha256)
            .put("localSnapshotSha256", entry.localSnapshotSha256)
            .put("operationSha256", entry.operationSha256)
            .put("targetFingerprint", entry.targetFingerprint)
            .put("targetBundleSha256", entry.targetBundleSha256)
            .put("createdAtEpochMillis", entry.createdAtEpochMillis)
            .put("updatedAtEpochMillis", entry.updatedAtEpochMillis)
            .put("lastError", entry.lastError ?: JSONObject.NULL)
            .put("mediaArtifacts", media)
            .put("dataStoreFinalized", entry.dataStoreFinalized)
            .put("finalizedMediaPhotoIds", JSONArray(entry.finalizedMediaPhotoIds.sorted()))
        writeAtomically(File(dir, JOURNAL_NAME), json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun readEntry(dir: File): DurableOperationEntry {
        val file = File(dir, JOURNAL_NAME)
        if (!file.isFile) throw IOException("Journal absent ou incomplet dans ${dir.name}.")
        try {
            val json = JSONObject(String(readBounded(file, MAX_JOURNAL_BYTES), StandardCharsets.UTF_8))
            val version = json.getInt("journalVersion")
            if (version !in 1..JOURNAL_VERSION) throw IOException("Version de journal non prise en charge.")
            val baseKeys = setOf("journalVersion", "operationId", "operationType", "selectedSets", "state", "archiveSha256", "localSnapshotSha256", "operationSha256", "targetFingerprint", "targetBundleSha256", "createdAtEpochMillis", "updatedAtEpochMillis", "lastError", "mediaArtifacts")
            val allowed = if (version == 1) baseKeys else baseKeys + setOf("dataStoreFinalized", "finalizedMediaPhotoIds")
            if (json.keys().asSequence().toSet() != allowed) throw IOException("Propriétés de journal manquantes ou inconnues.")
            val operationId = json.getString("operationId")
            requireCanonicalUuid(operationId)
            if (dir.name != operationId) throw IOException("Identifiant de dossier différent du journal.")
            val operationType = TargetOperation.valueOf(json.getString("operationType"))
            val selected = json.getJSONArray("selectedSets").let { array -> (0 until array.length()).map { array.getString(it) }.toSortedSet() }
            val state = BackupOperationState.valueOf(json.getString("state"))
            val archiveSha = json.getString("archiveSha256").requireHash()
            val localHash = json.getString("localSnapshotSha256").requireHash()
            val operationHash = json.getString("operationSha256").requireHash()
            val targetFingerprint = json.getString("targetFingerprint").requireHash()
            val bundleHash = json.getString("targetBundleSha256").requireHash()
            val error = if (json.isNull("lastError")) null else json.getString("lastError")
            val mediaArray = json.getJSONArray("mediaArtifacts")
            val mediaArtifacts = (0 until mediaArray.length()).map { index ->
                val row = mediaArray.getJSONObject(index)
                if (row.keys().asSequence().toSet() != setOf("photoId", "sourceRelativePath", "stagedFileName", "sizeBytes", "sha256")) throw IOException("Métadonnée média absente ou inconnue.")
                val photoId = row.getString("photoId").also(::requireCanonicalUuid)
                val sourcePath = row.getString("sourceRelativePath").also(::validateRelativeMediaPath)
                val stagedName = row.getString("stagedFileName")
                if (stagedName != "$photoId.media") throw IOException("Nom de média temporaire invalide.")
                val size = row.getLong("sizeBytes")
                if (size < 0) throw IOException("Taille de média invalide.")
                DurableMediaArtifact(photoId, sourcePath, stagedName, size, row.getString("sha256").requireHash())
            }
            if (mediaArtifacts.map { it.photoId }.distinct().size != mediaArtifacts.size) throw IOException("Identifiant de photo dupliqué dans le journal.")
            val finalized = if (json.has("finalizedMediaPhotoIds")) json.getJSONArray("finalizedMediaPhotoIds").let { a -> (0 until a.length()).map { a.getString(it).also(::requireCanonicalUuid) }.toSet() } else emptySet()
            return DurableOperationEntry(operationId, operationType, selected, state, archiveSha, localHash, operationHash, targetFingerprint, bundleHash, json.getLong("createdAtEpochMillis"), json.getLong("updatedAtEpochMillis"), error, mediaArtifacts, json.optBoolean("dataStoreFinalized", false), finalized)
        } catch (error: IOException) { throw error }
        catch (error: Exception) { throw IOException("Journal d’opération corrompu : ${error.message ?: "format invalide"}.", error) }
    }

    private fun encodeTarget(target: BackupTargetState): ByteArray {
        val payloads = JSONObject()
        target.payloads.toSortedMap().forEach { (path, json) -> payloads.put(path, JSONObject(json)) }
        val photos = JSONArray()
        target.photosToKeep.sortedWith(compareBy({ it.teaId }, { it.position }, { it.id })).forEach { photo ->
            photos.put(JSONObject().put("id", photo.id).put("teaId", photo.teaId).put("relativePath", photo.relativePath).put("position", photo.position))
        }
        val quarantine = JSONArray()
        target.photosToQuarantine.sortedWith(compareBy({ it.teaId }, { it.position }, { it.id })).forEach { photo ->
            quarantine.put(JSONObject().put("id", photo.id).put("teaId", photo.teaId).put("relativePath", photo.relativePath).put("position", photo.position))
        }
        return JSONObject()
            .put("targetFormat", TARGET_FORMAT)
            .put("payloads", payloads)
            .put("photosToKeep", photos)
            .put("photosToQuarantine", quarantine)
            .put("supersededImportDatasetIds", JSONArray(target.supersededImportDatasetIds.sorted()))
            .toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun stageMedia(operationDir: File, photos: List<TargetPhotoRecord>): List<DurableMediaArtifact> {
        if (photos.isEmpty()) return emptyList()
        val sourceRoot = mediaRoot?.canonicalFile ?: throw IOException("Le dossier privé des photos est indisponible; préparation interrompue.")
        if (!sourceRoot.isDirectory) throw IOException("Le dossier privé des photos n’existe pas.")
        val mediaDir = safeFile(operationDir, MEDIA_DIRECTORY)
        if (!mediaDir.isDirectory && !mediaDir.mkdirs()) throw IOException("Création du dossier temporaire des médias impossible.")
        return photos.sortedBy { it.id }.map { photo ->
            requireCanonicalUuid(photo.id)
            validateRelativeMediaPath(photo.relativePath)
            val source = File(sourceRoot, photo.relativePath).canonicalFile
            val rootPrefix = sourceRoot.path.trimEnd(File.separatorChar) + File.separator
            if (!source.path.startsWith(rootPrefix) || !source.isFile) throw IOException("Fichier photo absent ou hors du dossier privé : ${photo.relativePath}.")
            val fileName = "${photo.id}.media"
            val destination = safeFile(mediaDir, fileName)
            val temporary = File(mediaDir, "$fileName.part")
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            try {
                FileInputStream(source).use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            size = Math.addExact(size, count.toLong())
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: Exception) {
                temporary.delete()
                if (error is IOException) throw error
                throw IOException("Copie temporaire du média impossible.", error)
            }
            DurableMediaArtifact(photo.id, photo.relativePath, fileName, size, digest.digest().toHex())
        }
    }

    private fun verifyMediaArtifacts(operationDir: File, artifacts: List<DurableMediaArtifact>) {
        val mediaDir = safeFile(operationDir, MEDIA_DIRECTORY)
        artifacts.forEach { artifact ->
            validateRelativeMediaPath(artifact.sourceRelativePath)
            if (artifact.stagedFileName != "${artifact.photoId}.media") throw IOException("Nom de média temporaire incohérent.")
            val file = safeFile(mediaDir, artifact.stagedFileName)
            if (!file.isFile || file.length() != artifact.sizeBytes || sha256File(file) != artifact.sha256) {
                throw IOException("Empreinte ou taille d’un média temporaire incorrecte.")
            }
        }
    }

    private fun validateRelativeMediaPath(path: String) {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\') || ':' in path || '\u0000' in path || '\\' in path ||
            path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw IOException("Chemin relatif de média non sûr.")
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun decodeTarget(bytes: ByteArray): BackupTargetState {
        try {
            val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
            if (root.keys().asSequence().toSet() != setOf("targetFormat", "payloads", "photosToKeep", "photosToQuarantine", "supersededImportDatasetIds")) {
                throw IOException("Propriétés de l’état cible absentes ou inconnues.")
            }
            if (root.getString("targetFormat") != TARGET_FORMAT) throw IOException("Format d’état cible non pris en charge.")
            val payloadObject = root.getJSONObject("payloads")
            val payloads = payloadObject.keys().asSequence().associateWith { payloadObject.getJSONObject(it).toString() }.toSortedMap()
            fun readPhotos(key: String): List<TargetPhotoRecord> {
                val photosArray = root.getJSONArray(key)
                return (0 until photosArray.length()).map { index ->
                    val photo = photosArray.getJSONObject(index)
                    if (photo.keys().asSequence().toSet() != setOf("id", "teaId", "relativePath", "position")) throw IOException("Photo de l’état cible invalide.")
                    TargetPhotoRecord(photo.getString("id"), photo.getString("teaId"), photo.getString("relativePath"), photo.getInt("position"))
                }
            }
            val photos = readPhotos("photosToKeep")
            val quarantine = readPhotos("photosToQuarantine")
            val markers = root.getJSONArray("supersededImportDatasetIds")
            val superseded = (0 until markers.length()).map { markers.getString(it) }.toSet()
            if (superseded.size != markers.length()) throw IOException("Marqueur d’import répété dans l’état cible.")
            return BackupTargetState(payloads, photos, superseded, quarantine)
        } catch (error: IOException) { throw error }
        catch (error: Exception) { throw IOException("État cible durable corrompu.", error) }
    }

    private fun directoryFor(id: String) = safeFile(root, id)
    private fun safeFile(parent: File, name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+")) && name !in setOf(".", "..")) { "Nom de fichier de journal non sûr." }
        val parentCanonical = parent.canonicalFile
        val child = File(parentCanonical, name).canonicalFile
        require(child.parentFile == parentCanonical) { "Chemin de journal hors du dossier autorisé." }
        return child
    }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val parent = destination.parentFile ?: throw IOException("Dossier parent absent.")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Création du dossier temporaire impossible.")
        val temporary = File(parent, "${destination.name}.part")
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            temporary.delete()
            if (error is IOException) throw error
            throw IOException("Écriture durable impossible.", error)
        }
    }

    private fun readBounded(file: File, maxBytes: Long): ByteArray {
        if (!file.isFile || file.length() > maxBytes) throw IOException("Fichier absent ou dépassement de taille : ${file.name}.")
        return file.readBytes()
    }

    private fun requireCanonicalUuid(value: String) {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "UUID d’opération invalide." }
    }

    private fun String.requireHash(): String = also { require(matches(Regex("[0-9a-f]{64}"))) { "SHA-256 invalide dans le journal." } }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val JOURNAL_VERSION = 2
        const val TARGET_FORMAT = "thesdupalais.target-state"
        const val MAX_TARGET_BYTES = 200L * 1024 * 1024
        const val MAX_JOURNAL_BYTES = 256L * 1024
        const val JOURNAL_NAME = "operation.json"
        const val ARCHIVE_NAME = "archive.zip"
        const val TARGET_NAME = "target-state.json"
        const val MEDIA_DIRECTORY = "media"
        const val QUARANTINE_DIRECTORY = "backup-quarantine"
    }
}
