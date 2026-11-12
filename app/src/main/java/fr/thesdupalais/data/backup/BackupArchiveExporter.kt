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
import fr.thesdupalais.data.preferences.LocalPreferencesRepository
import fr.thesdupalais.data.preferences.UserPreferences
import java.io.IOException
import java.io.OutputStream
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
)

/** Read-only snapshot writer. Articles remain intentionally unavailable until their model exists. */
class BackupArchiveExporter(
    private val database: AppDatabase,
    private val preferences: LocalPreferencesRepository,
    private val contentResolver: ContentResolver,
    private val applicationVersion: String = "1.0",
    private val now: () -> Instant = Instant::now,
) {
    suspend fun exportTo(uri: Uri, selectedSets: Set<BackupExportSet>) {
        if (selectedSets.isEmpty()) throw IllegalArgumentException("Sélectionnez au moins un ensemble à exporter.")
        if (BackupExportSet.ARTICLES in selectedSets) throw UnsupportedOperationException("L’export des articles sera disponible lorsque leur modèle sera implémenté.")
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
        if (BackupExportSet.ARTICLES in selectedSets) throw UnsupportedOperationException("L’export des articles sera disponible lorsque leur modèle sera implémenté.")
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
            TargetPhotoRecord(it.id.toString(), it.teaId.toString(), it.relativePath, it.position)
        }
        val importHistory = database.importedDatasetDao().allForBackup().map {
            TargetImportRecord(it.datasetId.toString(), it.contentSha256, it.schemaVersion, it.isSuperseded)
        }
        return BackupLocalOperationSnapshot(archive, photos, importHistory)
    }

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
        )
    }

    internal data class Snapshot(
        val references: List<ReferenceEntity>, val teas: List<TeaEntity>,
        val countries: List<TeaCountryEntity>, val regions: List<TeaRegionEntity>,
        val aromas: List<TeaAromaEntity>, val profiles: List<TeaAromaticProfileEntity>,
        val moments: List<TeaMomentEntity>, val badges: List<TeaBadgeEntity>,
        val tastings: List<TastingEntity>, val movements: List<StockMovementEntity>,
    )

    internal object BackupArchiveWriter {
        fun write(snapshot: Snapshot, sets: Set<BackupExportSet>, prefs: UserPreferences, version: String, backupId: UUID, createdAt: Instant): ByteArray {
            val payloads = linkedMapOf<String, Pair<String, ByteArray>>()
            if (BackupExportSet.TEAS in sets) payloads["data/teas.json"] = "application/json" to teasJson(snapshot).toByteArray(Charsets.UTF_8)
            if (BackupExportSet.CONFIGURATION in sets) payloads["data/configuration.json"] = "application/json" to configurationJson(snapshot.references, prefs).toByteArray(Charsets.UTF_8)
            if (BackupExportSet.ARTICLES in sets) payloads["articles/index.json"] = "application/json" to "{\"articles\":[]}".toByteArray()
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
            return JSONObject().put("references", refs).put("teas", teas).put("tastings", tastings).put("stockMovements", movements).toString()
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
}

internal fun writeArchiveBytes(output: OutputStream, bytes: ByteArray) {
    output.use { stream -> stream.write(bytes); stream.flush() }
}
