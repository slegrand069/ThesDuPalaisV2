package fr.thesdupalais.data.operation

import androidx.room.withTransaction
import fr.thesdupalais.data.backup.BackupArchiveExporter
import fr.thesdupalais.data.backup.BackupArchiveValidator
import fr.thesdupalais.data.backup.BackupTargetFingerprints
import fr.thesdupalais.data.backup.BackupTargetState
import fr.thesdupalais.data.backup.BackupTargetStateBuilder
import fr.thesdupalais.data.backup.TargetOperation
import fr.thesdupalais.data.backup.TargetPhotoRecord
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.BackupOperationMarkerEntity
import fr.thesdupalais.data.database.ImportedDatasetEntity
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
import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class RoomApplyFaultPoint { AFTER_TARGET_ROWS_BEFORE_MARKER, AFTER_ROOM_COMMIT_BEFORE_JOURNAL }
fun interface RoomApplyFaultInjector { fun hit(point: RoomApplyFaultPoint) }

data class RoomApplyResult(val operationId: String, val alreadyCommitted: Boolean, val awaitingDataStoreAndMedia: Boolean = true)
data class ConfirmedRoomOperation(
    val operationType: TargetOperation,
    val selectedSets: Set<String>,
    val fingerprints: BackupTargetFingerprints,
)

/** Applies only the already-confirmed Room target. DataStore and files remain untouched in 4D.4. */
class BackupRoomOperationApplier(
    private val database: AppDatabase,
    private val exporter: BackupArchiveExporter,
    private val preferences: LocalPreferencesRepository,
    private val journal: DurableBackupOperationJournal,
    private val writeCoordinator: BackupOperationWriteCoordinator,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val faultInjector: RoomApplyFaultInjector = RoomApplyFaultInjector { },
) {
    suspend fun apply(operationId: String, confirmation: ConfirmedRoomOperation): RoomApplyResult =
        writeCoordinator.withPreparedOperationPermit(operationId) {
            applyUnderPermit(operationId, confirmation)
        }

    private suspend fun applyUnderPermit(operationId: String, confirmation: ConfirmedRoomOperation): RoomApplyResult {
        val entry = journal.readAll().singleOrNull { it.operationId == operationId }
            ?: throw IOException("L’opération préparée est introuvable dans le journal.")
        require(entry.state in setOf(BackupOperationState.PREPARED, BackupOperationState.ROOM_APPLYING, BackupOperationState.ROOM_COMMITTED)) {
            "L’état ${entry.state} n’autorise pas l’application Room."
        }
        val confirmed = confirmation.fingerprints
        require(entry.operationType == confirmation.operationType && entry.selectedSets == confirmation.selectedSets &&
            entry.archiveSha256 == confirmed.archiveSha256 &&
            entry.localSnapshotSha256 == confirmed.localSnapshotSha256 &&
            entry.operationSha256 == confirmed.operationSha256 &&
            entry.targetFingerprint == confirmed.targetSha256) {
            "Les empreintes confirmées ne correspondent plus à l’opération préparée."
        }
        journal.verifyArtifacts(entry)
        val archiveBytes = journal.readArchive(entry)
        val validatedArchive = BackupArchiveValidator.validate(archiveBytes)
        require(validatedArchive.includedSets.containsAll(entry.selectedSets)) { "L’archive préparée ne contient pas tous les ensembles sélectionnés." }
        val target = journal.readTargetState(entry)
        val targetErrors = BackupTargetStateBuilder.validatePreparedState(target)
        require(targetErrors.isEmpty()) { "État cible préparé incohérent : ${targetErrors.joinToString(" ")}" }
        require(entry.selectedSets.isNotEmpty() && entry.selectedSets.all { it in setOf("teas", "configuration") }) {
            "L’opération contient un ensemble non pris en charge."
        }

        val markerDao = database.backupOperationMarkerDao()
        val existingMarker = markerDao.findActive()
        if (existingMarker != null) {
            require(existingMarker.operationId == operationId && existingMarker.state == BackupOperationState.ROOM_COMMITTED.name &&
                existingMarker.archiveSha256 == entry.archiveSha256 && existingMarker.targetFingerprint == entry.targetFingerprint) {
                "Un autre marqueur Room ou une empreinte incohérente empêche l’application."
            }
            require(entry.state != BackupOperationState.PREPARED) { "Marqueur Room présent avant l’état journalisé d’application." }
            if (entry.state == BackupOperationState.ROOM_APPLYING) journal.transition(operationId, BackupOperationState.ROOM_COMMITTED)
            return RoomApplyResult(operationId, alreadyCommitted = true)
        }
        require(entry.state != BackupOperationState.ROOM_COMMITTED) {
            "Le journal affirme un commit Room mais son marqueur transactionnel est absent."
        }
        if (entry.state == BackupOperationState.PREPARED) journal.transition(operationId, BackupOperationState.ROOM_APPLYING)

        val prefsSnapshot = preferences.preferences.first()
        try {
            database.withTransaction {
                val markerInTransaction = markerDao.findActive()
                require(markerInTransaction == null) { "Un marqueur Room est apparu avant l’application transactionnelle." }
                val local = exporter.captureCurrentOperationSnapshotWithinTransaction(prefsSnapshot)
                val localFingerprint = BackupTargetStateBuilder.fingerprintLocalSnapshot(local.archiveBytes, local.photos, local.importHistory)
                require(localFingerprint == entry.localSnapshotSha256) {
                    "La collection locale a changé depuis la prévisualisation ; l’opération préparée est périmée."
                }
                val expectedPhotoRecords = (target.photosToKeep + target.photosToQuarantine).sortedWith(photoOrder)
                require(local.photos.sortedWith(photoOrder) == expectedPhotoRecords) {
                    "Les métadonnées photo locales diffèrent de l’état préparé."
                }

                val rows = parseTarget(target)
                applyReferences(rows.references)
                val changedMovementIds = removeObsoleteHistory(rows.tastings, rows.movements)
                applyTeas(rows.teas)
                applyAssociations(rows)
                writeTargetHistory(rows.tastings, rows.movements, changedMovementIds)
                deleteObsoleteReferences(rows.references.map { it.id }.toSet())
                if (target.supersededImportDatasetIds.isNotEmpty()) {
                    database.importedDatasetDao().markSuperseded(target.supersededImportDatasetIds.map { UUID.fromString(it) })
                }
                faultInjector.hit(RoomApplyFaultPoint.AFTER_TARGET_ROWS_BEFORE_MARKER)
                markerDao.replace(
                    BackupOperationMarkerEntity(
                        operationId = operationId,
                        state = BackupOperationState.ROOM_COMMITTED.name,
                        archiveSha256 = entry.archiveSha256,
                        targetFingerprint = entry.targetFingerprint,
                        updatedAtEpochMillis = nowEpochMillis(),
                    ),
                )
            }
        } catch (error: Throwable) {
            // withTransaction has rolled back before this handler runs. The Room marker decides
            // whether a cancellation/error happened before or after the durable commit point.
            val markerAfterFailure = withContext(NonCancellable) { runCatching { markerDao.findActive() }.getOrNull() }
            if (markerAfterFailure?.operationId == operationId && markerAfterFailure.state == BackupOperationState.ROOM_COMMITTED.name) {
                runCatching { journal.transition(operationId, BackupOperationState.ROOM_COMMITTED) }
            } else if (markerAfterFailure == null) {
                runCatching { journal.transition(operationId, BackupOperationState.ABORTED, error.message ?: error.javaClass.simpleName) }
            }
            throw error
        }

        faultInjector.hit(RoomApplyFaultPoint.AFTER_ROOM_COMMIT_BEFORE_JOURNAL)
        journal.transition(operationId, BackupOperationState.ROOM_COMMITTED)
        return RoomApplyResult(operationId, alreadyCommitted = false)
    }

    private suspend fun applyReferences(target: List<ReferenceEntity>) {
        val dao = database.referenceDao()
        val existing = dao.allForBackup().associateBy { it.id }
        val targetById = target.associateBy { it.id }
        require(targetById.size == target.size) { "UUID de référence dupliqué dans l’état cible." }

        // Temporarily free only unique keys that are changing/retiring. Values remain invisible
        // outside the transaction and preserve foreign keys by keeping the same reference UUID.
        existing.values.forEach { old ->
            val next = targetById[old.id]
            val collidesWithTargetKey = target.any { wanted ->
                wanted.id != old.id && wanted.kind == old.kind &&
                    (wanted.semanticKey != null && wanted.semanticKey == old.semanticKey ||
                        wanted.normalizedLabel == old.normalizedLabel && wanted.scopeKey == old.scopeKey)
            }
            if (next == null || next != old || collidesWithTargetKey) {
                val temporary = "__backup_" + old.id.toString().replace("-", "")
                dao.update(old.copy(label = temporary, normalizedLabel = ReferenceLabelNormalizer.normalize(temporary), semanticKey = null))
            }
        }

        target.sortedWith(compareBy<ReferenceEntity> { if (it.kind == "COUNTRY") 0 else if (it.kind == "REGION") 2 else 1 }.thenBy { it.id.toString() })
            .forEach { next ->
                if (next.id in existing) dao.update(next) else dao.insert(next)
            }
    }

    private suspend fun removeObsoleteHistory(targetTastings: List<TastingEntity>, targetMovements: List<StockMovementEntity>): Set<UUID> {
        val tastingDao = database.tastingDao()
        val movementDao = database.stockMovementDao()
        val oldMovements = movementDao.allForBackup().associateBy { it.id }
        val newMovements = targetMovements.associateBy { it.id }
        val changedMovementIds = oldMovements.values.filter { newMovements[it.id] != it }.map { it.id }.toSet()
        changedMovementIds.forEach { movementDao.delete(it) }

        val oldTastings = tastingDao.allForBackup().associateBy { it.id }
        val newTastings = targetTastings.associateBy { it.id }
        oldTastings.values.filter { newTastings[it.id] == null }.forEach { tastingDao.delete(it.id) }
        return targetMovements.filter { oldMovements[it.id] != it }.map { it.id }.toSet()
    }

    private suspend fun writeTargetHistory(
        targetTastings: List<TastingEntity>,
        targetMovements: List<StockMovementEntity>,
        changedMovementIds: Set<UUID>,
    ) {
        val tastingDao = database.tastingDao()
        val movementDao = database.stockMovementDao()
        val oldTastings = tastingDao.allForBackup().associateBy { it.id }
        targetTastings.forEach { next ->
            val old = oldTastings[next.id]
            if (old == null) tastingDao.insert(next) else if (old != next) tastingDao.update(next)
        }
        targetMovements.filter { it.id in changedMovementIds }.forEach { movementDao.insert(it) }
    }

    private suspend fun applyTeas(target: List<TeaEntity>) {
        val dao = database.teaDao()
        val current = dao.allForBackup().associateBy { it.id }
        val targetById = target.associateBy { it.id }
        require(targetById.size == target.size) { "UUID de thé dupliqué dans l’état cible." }

        // Free unique legacy IDs before reassignments/swaps, then remove rows absent from the target.
        current.values.filter { old -> targetById[old.id]?.legacyId != old.legacyId && old.legacyId != null }
            .forEach { old -> dao.update(old.copy(legacyId = null)) }
        current.values.filter { it.id !in targetById }.forEach { dao.deleteById(it.id) }
        target.forEach { next ->
            val old = current[next.id]
            if (old == null) dao.insert(next) else if (old != next) dao.update(next)
        }
    }

    private suspend fun applyAssociations(rows: ParsedTarget) {
        val dao = database.teaDao()
        val currentCountries = dao.allCountriesForBackup().toSet()
        val currentRegions = dao.allRegionsForBackup().toSet()
        val currentAromas = dao.allAromasForBackup().toSet()
        val currentProfiles = dao.allAromaticProfilesForBackup().toSet()
        val currentMoments = dao.allMomentsForBackup().toSet()
        val currentBadges = dao.allBadgesForBackup().toSet()

        (currentRegions - rows.regions.toSet()).forEach { dao.deleteRegion(it.teaId, it.regionRefId) }
        (currentCountries - rows.countries.toSet()).forEach { dao.deleteCountry(it.teaId, it.countryRefId) }
        (currentAromas - rows.aromas.toSet()).forEach { dao.deleteAroma(it.teaId, it.aromaRefId) }
        (currentProfiles - rows.profiles.toSet()).forEach { dao.deleteAromaticProfile(it.teaId, it.profileRefId) }
        (currentMoments - rows.moments.toSet()).forEach { dao.deleteMoment(it.teaId, it.momentRefId) }
        (currentBadges - rows.badges.toSet()).forEach { dao.deleteBadge(it.teaId, it.badgeRefId) }

        (rows.countries.toSet() - currentCountries).takeIf { it.isNotEmpty() }?.let { dao.insertCountries(it.toList()) }
        (rows.regions.toSet() - currentRegions).takeIf { it.isNotEmpty() }?.let { dao.insertRegions(it.toList()) }
        (rows.aromas.toSet() - currentAromas).takeIf { it.isNotEmpty() }?.let { dao.insertAromas(it.toList()) }
        (rows.profiles.toSet() - currentProfiles).takeIf { it.isNotEmpty() }?.let { dao.insertAromaticProfiles(it.toList()) }
        (rows.moments.toSet() - currentMoments).takeIf { it.isNotEmpty() }?.let { dao.insertMoments(it.toList()) }
        (rows.badges.toSet() - currentBadges).takeIf { it.isNotEmpty() }?.let { dao.insertBadges(it.toList()) }
    }

    private suspend fun deleteObsoleteReferences(targetIds: Set<UUID>) {
        database.referenceDao().allForBackup()
            .filter { it.id !in targetIds }
            .sortedWith(compareBy<ReferenceEntity> { if (it.kind == "REGION") 0 else 1 }.thenBy { it.id.toString() })
            .forEach { ref ->
                check(database.referenceDao().deleteById(ref.id) == 1) { "La référence ${ref.id} n’a pas pu être retirée." }
            }
    }

    private data class ParsedTarget(
        val references: List<ReferenceEntity>, val teas: List<TeaEntity>, val countries: List<TeaCountryEntity>,
        val regions: List<TeaRegionEntity>, val aromas: List<TeaAromaEntity>, val profiles: List<TeaAromaticProfileEntity>,
        val moments: List<TeaMomentEntity>, val badges: List<TeaBadgeEntity>, val tastings: List<TastingEntity>,
        val movements: List<StockMovementEntity>,
    )

    private fun parseTarget(target: BackupTargetState): ParsedTarget {
        val teaPayload = JSONObject(target.payloads.getValue("data/teas.json"))
        val configPayload = JSONObject(target.payloads.getValue("data/configuration.json"))
        val references = configPayload.getJSONArray("references").objects().map { row ->
            val parent = row.uuidOrNull("parentCountryId")
            val label = row.getString("label")
            ReferenceEntity(
                id = row.uuid("id"), kind = row.getString("kind"), label = label,
                normalizedLabel = ReferenceLabelNormalizer.normalize(label), semanticKey = row.stringOrNull("semanticKey"),
                parentCountryId = parent, scopeKey = parent?.toString().orEmpty(), archivedAt = row.instantMillisOrNull("archivedAt"),
                displayPriority = row.getInt("displayPriority"),
            )
        }
        val teas = teaPayload.getJSONArray("teas").objects().map { row ->
            TeaEntity(
                id = row.uuid("id"), legacyId = row.longOrNull("legacyId"), name = row.getString("name"),
                categoryRefId = row.uuid("categoryId"), description = row.stringOrNull("description"),
                technicalInformation = row.stringOrNull("technicalInformation"), personalNotes = row.stringOrNull("personalNotes"),
                olfactoryRating = row.doubleOrNull("olfactoryRating"), tasteRating = row.doubleOrNull("tasteRating"),
                brewTemperatureCelsius = row.intOrNull("brewTemperatureCelsius"), infusionDurationSeconds = row.intOrNull("infusionDurationSeconds"),
                statusRefId = row.uuidOrNull("statusId"), containerRefId = row.uuidOrNull("containerId"),
                stockMilliGrams = row.longOrNull("stockMilliGrams"), archivedAtEpochMillis = row.instantMillisOrNull("archivedAt"),
            )
        }
        fun <T> association(key: String, factory: (UUID, UUID) -> T): List<T> = teaPayload.getJSONArray("teas").objects().flatMap { tea ->
            tea.getJSONArray(key).strings().map { factory(tea.uuid("id"), UUID.fromString(it)) }
        }
        val countries = association("countryIds", ::TeaCountryEntity)
        val regions = teaPayload.getJSONArray("teas").objects().flatMap { tea ->
            val countryIds = tea.getJSONArray("countryIds").strings().map(UUID::fromString)
            val refs = configPayload.getJSONArray("references").objects().associateBy { it.getString("id") }
            tea.getJSONArray("regionIds").strings().map { regionId ->
                val region = refs[regionId] ?: throw IOException("Région $regionId absente de la configuration cible.")
                val parent = region.uuid("parentCountryId")
                require(parent in countryIds) { "La région $regionId est associée sans son pays." }
                TeaRegionEntity(tea.uuid("id"), UUID.fromString(regionId), parent)
            }
        }
        val aromas = association("aromaIds", ::TeaAromaEntity)
        val profiles = association("aromaticProfileIds", ::TeaAromaticProfileEntity)
        val moments = association("momentIds", ::TeaMomentEntity)
        val badges = association("badgeIds", ::TeaBadgeEntity)
        val tastings = teaPayload.getJSONArray("tastings").objects().map { row ->
            TastingEntity(row.uuid("id"), row.uuid("teaId"), row.instantMillis("occurredAt"), row.instantMillis("recordedAt"), row.longOrNull("consumedMilliGrams"))
        }
        val movements = teaPayload.getJSONArray("stockMovements").objects().map { row ->
            StockMovementEntity(row.uuid("id"), row.uuid("teaId"), row.getString("kind"), row.longOrNull("deltaMilliGrams"), row.getLong("stockAfterMilliGrams"), row.instantMillis("occurredAt"), row.instantMillis("recordedAt"), row.uuidOrNull("tastingId"), row.stringOrNull("note"))
        }
        return ParsedTarget(references, teas, countries, regions, aromas, profiles, moments, badges, tastings, movements)
    }

    private fun JSONObject.uuid(key: String): UUID = UUID.fromString(getString(key)).also { require(it.toString() == getString(key)) { "UUID non canonique : $key." } }
    private fun JSONObject.uuidOrNull(key: String): UUID? = if (isNull(key)) null else uuid(key)
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key)) null else getDouble(key)
    private fun JSONObject.instantMillis(key: String): Long = Instant.parse(getString(key)).toEpochMilli()
    private fun JSONObject.instantMillisOrNull(key: String): Long? = if (isNull(key)) null else instantMillis(key)
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private companion object {
        val photoOrder = compareBy<TargetPhotoRecord> { it.id }.thenBy { it.teaId }.thenBy { it.position }.thenBy { it.relativePath }
    }
}
