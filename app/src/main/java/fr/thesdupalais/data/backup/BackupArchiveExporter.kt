package fr.thesdupalais.data.backup

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.StockMovementEntity
import fr.thesdupalais.data.database.TastingEntity
import fr.thesdupalais.data.database.TeaAromaEntity
import fr.thesdupalais.data.database.TeaAromaticProfileEntity
import fr.thesdupalais.data.database.TeaBadgeEntity
import fr.thesdupalais.data.database.TeaCountryEntity
import fr.thesdupalais.data.database.TeaEntity
import fr.thesdupalais.data.database.TeaMomentEntity
import fr.thesdupalais.data.database.TeaRegionEntity
import fr.thesdupalais.data.database.TeaPhotoEntity
import fr.thesdupalais.data.database.ArticleEntity
import fr.thesdupalais.data.preferences.LocalPreferencesRepository
import fr.thesdupalais.data.preferences.UserPreferences
import java.io.IOException
import java.io.OutputStream
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

enum class BackupExportSet(val manifestName: String) {
    TEAS("teas"),
    CONFIGURATION("configuration"),
    ARTICLES("articles"),
}

data class BackupLocalOperationSnapshot(
    val archiveBytes: ByteArray,
    val photos: List<TargetPhotoRecord>,
    val importHistory: List<TargetImportRecord>,
    val articles: List<TargetArticleRecord> = emptyList(),
)

/** Read-only snapshot writer for collection data and offline knowledge articles. */
class BackupArchiveExporter(
    private val database: AppDatabase,
    private val preferences: LocalPreferencesRepository,
    private val contentResolver: ContentResolver,
    private val applicationVersion: String = "1.0",
    private val now: () -> Instant = Instant::now,
    private val filesDir: File? = null,
) {
    suspend fun exportTo(uri: Uri, selectedSets: Set<BackupExportSet>) {
        if (selectedSets.isEmpty()) throw IllegalArgumentException("Sélectionnez au moins un ensemble à exporter.")
        val bytes = createArchive(selectedSets)
        BackupArchiveValidator.validate(bytes)
        val output = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Le fichier de destination n’a pas pu être ouvert.")
        writeArchiveBytes(output, bytes)
        val written = contentResolver.openInputStream(uri) ?: throw IOException("Le fichier exporté n’a pas pu être relu pour vérification.")
        val readBack = written.use { stream ->
            val buffer = ByteArray(8192)
            val collected = java.io.ByteArrayOutputStream()
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (collected.size().toLong() + count > BackupArchiveValidator.MAX_ARCHIVE_BYTES) throw IOException("Le fichier relu dépasse la limite autorisée.")
                collected.write(buffer, 0, count)
            }
            collected.toByteArray()
        }
        BackupArchiveValidator.validate(readBack)
    }

    suspend fun createArchive(selectedSets: Set<BackupExportSet>): ByteArray {
        if (selectedSets.isEmpty()) throw IllegalArgumentException("Sélectionnez au moins un ensemble à exporter.")
        val snapshot = database.withTransaction { readSnapshot(selectedSets) }
        val preferenceSnapshot = if (BackupExportSet.CONFIGURATION in selectedSets) preferences.preferences.first() else UserPreferences()
        return BackupArchiveWriter.write(snapshot, selectedSets, preferenceSnapshot, applicationVersion, UUID.randomUUID(), now())
    }

    /** Captures all inputs used by the confirmed 4D.2 local-state fingerprint. */
    suspend fun captureCurrentOperationSnapshot(): BackupLocalOperationSnapshot {
        val preferenceSnapshot = preferences.preferences.first()
        return database.withTransaction { captureCurrentOperationSnapshotWithinTransaction(preferenceSnapshot) }
    }

    /** Caller must already hold this database's transaction and coordinate DataStore writers. */
    internal suspend fun captureCurrentOperationSnapshotWithinTransaction(preferenceSnapshot: UserPreferences): BackupLocalOperationSnapshot {
        val selected = setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION)
        val snapshot = readSnapshot(selected)
        val archive = BackupArchiveWriter.write(snapshot, selected, preferenceSnapshot, applicationVersion, UUID.randomUUID(), now())
        val photos = database.teaPhotoDao().allForBackup().map {
            TargetPhotoRecord(it.id.toString(), it.teaId.toString(), it.relativePath, it.position, it.isPrimary)
        }
        val importHistory = database.importedDatasetDao().allForBackup().map {
            TargetImportRecord(it.datasetId.toString(), it.contentSha256, it.schemaVersion, it.isSuperseded)
        }
        val articles = readLocalArticleRecords(database.articleDao().allForBackup())
        return BackupLocalOperationSnapshot(archive, photos, importHistory, articles)
    }

    private fun readLocalArticleRecords(rows: List<ArticleEntity>): List<TargetArticleRecord> {
        val root = filesDir?.canonicalFile ?: throw IOException("Le stockage privé des images d’articles n’est pas configuré.")
        val reference = Regex("!\\[([^\\]]*)]\\(<?images/([0-9a-fA-F-]{36}\\.jpg)>?(?:\\s+\"[^\"]*\")?\\)")
        return rows.map { article ->
            val images = reference.findAll(article.markdown).map { match ->
                val filename = match.groupValues[2]
                val imageId = filename.removeSuffix(".jpg")
                val relative = "article-images/${article.id}/$filename"
                val file = File(root, relative).canonicalFile
                val safe = file.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)
                val bytes = if (safe && file.isFile && file.length() <= BackupArchiveValidator.MAX_IMAGE_BYTES) runCatching { FileInputStream(file).use { it.readBytes() } }.getOrNull() else null
                TargetArticleImage(imageId, article.id.toString(), relative, sizeBytes = bytes?.size?.toLong() ?: 0L,
                    sha256 = bytes?.let(::sha256Bytes) ?: "0".repeat(64))
            }.distinctBy { it.id }.toList()
            TargetArticleRecord(article.id.toString(), article.title, article.category, article.markdown, images)
        }
    }

    private fun sha256Bytes(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun readSnapshot(selectedSets: Set<BackupExportSet>): Snapshot {
        val allReferences = database.referenceDao().allForBackup()
        val teas = if (BackupExportSet.TEAS in selectedSets) database.teaDao().allForBackup() else emptyList()
        val references = if (BackupExportSet.CONFIGURATION in selectedSets) allReferences else {
            val ids = buildSet {
                teas.forEach { tea -> add(tea.categoryRefId); tea.statusRefId?.let(::add); tea.containerRefId?.let(::add) }
                database.teaDao().allCountriesForBackup().filter { row -> teas.any { it.id == row.teaId } }.forEach { add(it.countryRefId) }
                database.teaDao().allRegionsForBackup().filter { row -> teas.any { it.id == row.teaId } }.forEach { add(it.regionRefId); add(it.countryRefId) }
                database.teaDao().allAromasForBackup().filter { row -> teas.any { it.id == row.teaId } }.forEach { add(it.aromaRefId) }
                database.teaDao().allAromaticProfilesForBackup().filter { row -> teas.any { it.id == row.teaId } }.forEach { add(it.profileRefId) }
                database.teaDao().allMomentsForBackup().filter { row -> teas.any { it.id == row.teaId } }.forEach { add(it.momentRefId) }
                database.teaDao().allBadgesForBackup().filter { row -> teas.any { it.id == row.teaId } }.forEach { add(it.badgeRefId) }
            }
            allReferences.filter { it.id in ids }
        }
        val selectedTeaIds = teas.map { it.id }.toSet()
        val photoRows = if (BackupExportSet.TEAS in selectedSets) database.teaPhotoDao().allForBackup().filter { it.teaId in selectedTeaIds } else emptyList()
        val photoBytes = photoRows.associate { row ->
            val root = filesDir?.canonicalFile ?: throw IOException("Le stockage privé des photos n’est pas configuré.")
            if (!row.relativePath.startsWith("tea-photos/") || row.relativePath.contains("..") || '\\' in row.relativePath) throw IOException("Référence photo locale invalide.")
            val file = File(root, row.relativePath).canonicalFile
            if (!file.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator) || !file.isFile || file.length() > BackupArchiveValidator.MAX_IMAGE_BYTES) {
                throw IOException("Fichier photo absent, hors du stockage privé ou trop volumineux : ${row.relativePath}.")
            }
            row.relativePath to FileInputStream(file).use { it.readBytes() }
        }
        val articles = if (BackupExportSet.ARTICLES in selectedSets) database.articleDao().allForBackup() else emptyList()
        val articleImageBytes = linkedMapOf<String, ByteArray>()
        articles.forEach { article ->
            ARTICLE_IMAGE_REFERENCE.findAll(article.markdown).forEach { match ->
                val relative = match.groupValues[2]
                if (relative.startsWith('/') || relative.contains('\\') || relative.split('/').any { it == ".." || it == "." || it.isEmpty() })
                    throw IOException("Référence d’image d’article invalide : $relative")
                if (!relative.startsWith("images/")) throw IOException("Les images d’article doivent référencer images/<nom>.jpg : $relative")
                val imageName = relative.removePrefix("images/")
                if (!Regex("^[A-Za-z0-9._-]{1,100}\\.(jpg|jpeg)$", RegexOption.IGNORE_CASE).matches(imageName)) throw IOException("Nom d’image d’article non pris en charge : $relative")
                val root = filesDir?.canonicalFile ?: throw IOException("Le stockage privé des images d’articles n’est pas configuré.")
                val file = File(root, "article-images/${article.id}/$imageName").canonicalFile
                if (!file.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator) || !file.isFile || file.length() > BackupArchiveValidator.MAX_IMAGE_BYTES)
                    throw IOException("Image d’article absente ou invalide : ${article.id}/$imageName")
                articleImageBytes["${article.id}/$imageName"] = FileInputStream(file).use { it.readBytes() }
            }
        }
        return Snapshot(
            references = references,
            teas = teas,
            countries = if (BackupExportSet.TEAS in selectedSets) database.teaDao().allCountriesForBackup() else emptyList(),
            regions = if (BackupExportSet.TEAS in selectedSets) database.teaDao().allRegionsForBackup() else emptyList(),
            aromas = if (BackupExportSet.TEAS in selectedSets) database.teaDao().allAromasForBackup() else emptyList(),
            profiles = if (BackupExportSet.TEAS in selectedSets) database.teaDao().allAromaticProfilesForBackup() else emptyList(),
            moments = if (BackupExportSet.TEAS in selectedSets) database.teaDao().allMomentsForBackup() else emptyList(),
            badges = if (BackupExportSet.TEAS in selectedSets) database.teaDao().allBadgesForBackup() else emptyList(),
            tastings = if (BackupExportSet.TEAS in selectedSets) database.tastingDao().allForBackup() else emptyList(),
            movements = if (BackupExportSet.TEAS in selectedSets) database.stockMovementDao().allForBackup() else emptyList(),
            photos = photoRows,
            photoBytes = photoBytes,
            articles = articles,
            articleImageBytes = articleImageBytes,
        )
    }

    internal data class Snapshot(
        val references: List<ReferenceEntity>, val teas: List<TeaEntity>,
        val countries: List<TeaCountryEntity>, val regions: List<TeaRegionEntity>,
        val aromas: List<TeaAromaEntity>, val profiles: List<TeaAromaticProfileEntity>,
        val moments: List<TeaMomentEntity>, val badges: List<TeaBadgeEntity>,
        val tastings: List<TastingEntity>, val movements: List<StockMovementEntity>,
        val photos: List<TeaPhotoEntity> = emptyList(),
        val photoBytes: Map<String, ByteArray> = emptyMap(),
        val articles: List<ArticleEntity> = emptyList(),
        val articleImageBytes: Map<String, ByteArray> = emptyMap(),
    )

    internal object BackupArchiveWriter {
        fun write(snapshot: Snapshot, sets: Set<BackupExportSet>, prefs: UserPreferences, version: String, backupId: UUID, createdAt: Instant): ByteArray {
            val payloads = linkedMapOf<String, Pair<String, ByteArray>>()
            if (BackupExportSet.TEAS in sets) payloads["data/teas.json"] = "application/json" to teasJson(snapshot).toByteArray(Charsets.UTF_8)
            if (BackupExportSet.TEAS in sets) snapshot.photos.forEach { photo ->
                val source = snapshot.photoBytes[photo.relativePath] ?: throw IllegalStateException("Fichier photo manquant pour ${photo.id}.")
                payloads["photos/${photo.id}.jpg"] = "image/jpeg" to source
            }
            if (BackupExportSet.CONFIGURATION in sets) payloads["data/configuration.json"] = "application/json" to configurationJson(snapshot.references, prefs).toByteArray(Charsets.UTF_8)
            if (BackupExportSet.ARTICLES in sets) {
                val indexRows = JSONArray()
                snapshot.articles.forEach { article ->
                    val markdownPath = "articles/content/${article.id}.md"
                    val sourceMarkdown = article.markdown
                    val illustrations = JSONArray()
                    val seenIllustrationPaths = mutableSetOf<String>()
                    ARTICLE_IMAGE_REFERENCE.findAll(sourceMarkdown).forEach { match ->
                        val name = match.groupValues[2].removePrefix("images/")
                        val archivePath = "articles/media/${article.id}/$name"
                        if (!seenIllustrationPaths.add(archivePath)) return@forEach
                        val bytes = snapshot.articleImageBytes["${article.id}/$name"] ?: throw IllegalStateException("Image d’article absente : ${article.id}/$name")
                        payloads[archivePath] = "image/jpeg" to bytes
                        val alt = match.groupValues[1].ifBlank { null }
                        illustrations.put(JSONObject().put("path", archivePath).putNullable("alt", alt))
                    }
                    val markdown = ARTICLE_IMAGE_REFERENCE.replace(sourceMarkdown) { match ->
                        val imageName = match.groupValues[2].removePrefix("images/")
                        match.value.replace(match.groupValues[2], "../media/${article.id}/$imageName")
                    }
                    payloads[markdownPath] = "text/markdown; charset=utf-8" to markdown.toByteArray(Charsets.UTF_8)
                    indexRows.put(JSONObject().put("id", article.id.toString()).put("title", article.title).put("section", article.category)
                        .put("markdownPath", markdownPath).put("illustrations", illustrations))
                }
                payloads["articles/index.json"] = "application/json" to JSONObject().put("articles", indexRows).toString().toByteArray(Charsets.UTF_8)
            }
            val entries = JSONArray()
            payloads.forEach { (path, file) ->
                entries.put(JSONObject()
                    .put("path", path)
                    .put("mediaType", file.first)
                    .put("sizeBytes", file.second.size)
                    .put("sha256", sha256(file.second)))
            }
            val manifest = JSONObject()
                .put("format", BackupArchiveValidator.FORMAT)
                .put("schemaVersion", BackupArchiveValidator.SCHEMA_VERSION)
                .put("backupId", backupId.toString())
                .put("createdAt", createdAt.toString())
                .put("producer", JSONObject().put("application", "ThésDuPalais").put("version", version))
                .put("includedSets", JSONArray(sets.map { it.manifestName }.sorted()))
                .put("entries", entries)
            val output = java.io.ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                put(zip, "manifest.json", manifest.toString().toByteArray(Charsets.UTF_8))
                payloads.forEach { (path, file) -> put(zip, path, file.second) }
            }
            return output.toByteArray()
        }

        private fun put(zip: ZipOutputStream, path: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(path).apply { time = 0L })
            zip.write(bytes)
            zip.closeEntry()
        }

        private fun teasJson(s: Snapshot): String {
            val teaIds = s.teas.map { it.id }.toSet()
            val countryRows = s.countries.filter { it.teaId in teaIds }.groupBy { it.teaId }
            val regionRows = s.regions.filter { it.teaId in teaIds }.groupBy { it.teaId }
            val aromaRows = s.aromas.filter { it.teaId in teaIds }.groupBy { it.teaId }
            val profileRows = s.profiles.filter { it.teaId in teaIds }.groupBy { it.teaId }
            val momentRows = s.moments.filter { it.teaId in teaIds }.groupBy { it.teaId }
            val badgeRows = s.badges.filter { it.teaId in teaIds }.groupBy { it.teaId }
            val usedRefs = s.references.associateBy { it.id }
            val teas = JSONArray()
            s.teas.sortedBy { it.id.toString() }.forEach { tea ->
                val regions = regionRows[tea.id].orEmpty()
                val countries = (countryRows[tea.id].orEmpty().map { it.countryRefId } + regions.map { it.countryRefId }).distinct()
                teas.put(JSONObject()
                    .put("id", tea.id.toString()).putNullable("legacyId", tea.legacyId).put("name", tea.name)
                    .put("categoryId", tea.categoryRefId.toString()).putNullable("description", tea.description)
                    .putNullable("technicalInformation", tea.technicalInformation).putNullable("personalNotes", tea.personalNotes)
                    .putNullable("olfactoryRating", tea.olfactoryRating).putNullable("tasteRating", tea.tasteRating)
                    .putNullable("brewTemperatureCelsius", tea.brewTemperatureCelsius).putNullable("infusionDurationSeconds", tea.infusionDurationSeconds)
                    .putNullable("statusId", tea.statusRefId?.toString()).putNullable("containerId", tea.containerRefId?.toString())
                    .put("countryIds", jsonIds(countries)).put("regionIds", jsonIds(regions.map { it.regionRefId }))
                    .put("aromaIds", jsonIds(aromaRows[tea.id].orEmpty().map { it.aromaRefId }))
                    .put("aromaticProfileIds", jsonIds(profileRows[tea.id].orEmpty().map { it.profileRefId }))
                    .put("momentIds", jsonIds(momentRows[tea.id].orEmpty().map { it.momentRefId }))
                    .put("badgeIds", jsonIds(badgeRows[tea.id].orEmpty().map { it.badgeRefId }))
                    .putNullable("stockMilliGrams", tea.stockMilliGrams).putNullable("archivedAt", tea.archivedAtEpochMillis?.let(::date)))
            }
            val tastings = JSONArray()
            s.tastings.filter { it.teaId in teaIds }.sortedBy { it.id.toString() }.forEach { t ->
                tastings.put(JSONObject().put("id", t.id.toString()).put("teaId", t.teaId.toString())
                    .put("occurredAt", date(t.occurredAtEpochMillis)).put("recordedAt", date(t.recordedAtEpochMillis))
                    .putNullable("consumedMilliGrams", t.consumedMilliGrams))
            }
            val movements = JSONArray()
            s.movements.filter { it.teaId in teaIds }.sortedBy { it.id.toString() }.forEach { m ->
                movements.put(JSONObject().put("id", m.id.toString()).put("teaId", m.teaId.toString()).put("kind", m.kind)
                    .putNullable("deltaMilliGrams", m.deltaMilliGrams).put("stockAfterMilliGrams", m.stockAfterMilliGrams)
                    .put("occurredAt", date(m.occurredAtEpochMillis)).put("recordedAt", date(m.recordedAtEpochMillis))
                    .putNullable("tastingId", m.tastingId?.toString()).putNullable("note", m.note))
            }
            // Include exactly the closure of references used by these teas, including region parents.
            val required = buildSet {
                s.teas.forEach { add(it.categoryRefId); it.statusRefId?.let(::add); it.containerRefId?.let(::add) }
                s.countries.filter { it.teaId in teaIds }.forEach { add(it.countryRefId) }
                s.regions.filter { it.teaId in teaIds }.forEach { add(it.regionRefId); add(it.countryRefId) }
                s.aromas.filter { it.teaId in teaIds }.forEach { add(it.aromaRefId) }
                s.profiles.filter { it.teaId in teaIds }.forEach { add(it.profileRefId) }
                s.moments.filter { it.teaId in teaIds }.forEach { add(it.momentRefId) }
                s.badges.filter { it.teaId in teaIds }.forEach { add(it.badgeRefId) }
            }
            val refsById = s.references.associateBy { it.id }
            if (!refsById.keys.containsAll(required)) throw IllegalStateException("Une référence utilisée par un thé est absente de la base.")
            val refs = JSONArray()
            s.references.filter { it.id in required }.sortedWith(compareBy<ReferenceEntity> { it.kind }.thenBy { it.id.toString() }).forEach { ref ->
                refs.put(referenceJson(ref))
            }
            val photos = JSONArray()
            s.photos.sortedWith(compareBy<TeaPhotoEntity> { it.teaId }.thenBy { it.position }.thenBy { it.id }).forEach { photo ->
                photos.put(JSONObject().put("id", photo.id.toString()).put("teaId", photo.teaId.toString())
                    .put("path", "photos/${photo.id}.jpg").put("position", photo.position).put("isPrimary", photo.isPrimary))
            }
            return JSONObject().put("references", refs).put("teas", teas).put("tastings", tastings).put("stockMovements", movements).put("photos", photos).toString()
        }

        private fun configurationJson(refs: List<ReferenceEntity>, p: UserPreferences): String {
            val references = JSONArray()
            refs.sortedWith(compareBy<ReferenceEntity> { it.kind }.thenBy { it.id.toString() }).forEach { references.put(referenceJson(it)) }
            return JSONObject().put("references", references).put("preferences", JSONObject()
                .put("quantityPerCupMilliGrams", p.gramsPerCupMilli)
                .put("lowStockThresholdMilliGrams", p.lowStockThresholdMilli)).toString()
        }

        private fun referenceJson(ref: ReferenceEntity) = JSONObject()
            .put("id", ref.id.toString()).put("kind", ref.kind).putNullable("semanticKey", ref.semanticKey)
            .put("label", ref.label).putNullable("parentCountryId", ref.parentCountryId?.toString())
            .putNullable("archivedAt", ref.archivedAt?.let(::date)).put("displayPriority", ref.displayPriority)

        private fun jsonIds(ids: List<UUID>) = JSONArray(ids.map(UUID::toString))
        private fun date(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).toString()
        private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        private fun JSONObject.putNullable(key: String, value: Any?) = put(key, value ?: JSONObject.NULL)
    }

    companion object {
        /** Local article image references use portable images/<filename>.jpg paths in Markdown. */
        internal val ARTICLE_IMAGE_REFERENCE = Regex("!\\[([^\\]]*)]\\(<?([^\\s)>]+)>?(?:\\s+\"[^\"]*\")?\\)")
    }
}

internal fun writeArchiveBytes(output: OutputStream, bytes: ByteArray) {
    output.use { stream -> stream.write(bytes); stream.flush() }
}
