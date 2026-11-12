package fr.thesdupalais.data.importer

import androidx.room.withTransaction
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.ImportedDatasetEntity
import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.StandardReferenceSeeder
import fr.thesdupalais.data.database.StockMovementEntity
import fr.thesdupalais.data.database.TastingEntity
import fr.thesdupalais.data.database.TeaAromaEntity
import fr.thesdupalais.data.database.TeaAromaticProfileEntity
import fr.thesdupalais.data.database.TeaBadgeEntity
import fr.thesdupalais.data.database.TeaCountryEntity
import fr.thesdupalais.data.database.TeaEntity
import fr.thesdupalais.data.database.TeaMomentEntity
import fr.thesdupalais.data.database.TeaRegionEntity
import fr.thesdupalais.data.preferences.UserPreferences
import fr.thesdupalais.data.preferences.UserPreferencesStore
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import fr.thesdupalais.data.operation.BackupOperationWriteCoordinator

class CollectionImporter(
    private val database: AppDatabase,
    private val preferencesStore: UserPreferencesStore,
    private val writeCoordinator: BackupOperationWriteCoordinator = BackupOperationWriteCoordinator(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun importJson(json: String): CollectionImportResult = writeCoordinator.withWritePermit {
        importJsonUnderPermit(json)
    }

    private suspend fun importJsonUnderPermit(json: String): CollectionImportResult {
        val document = CollectionJsonParser.parse(json)
        val contentHash = sha256(document)

        val result = try {
            database.withTransaction {
                val importedDao = database.importedDatasetDao()
                val priorImport = importedDao.findByDatasetId(document.datasetId)
                if (priorImport != null) {
                    if (priorImport.schemaVersion != document.schemaVersion || priorImport.contentSha256 != contentHash) {
                        throw CollectionImportException(
                            CollectionImportException.Code.DATASET_ALREADY_IMPORTED,
                            "$.datasetId",
                            "Cet identifiant de jeu de données a déjà été importé avec une version ou un contenu différent.",
                        )
                    }
                    return@withTransaction CollectionImportResult.AlreadyImported(
                        datasetId = document.datasetId,
                        schemaVersion = document.schemaVersion,
                        teaCount = priorImport.teaCount,
                    )
                }
                if (importedDao.findAny() != null ||
                    database.teaDao().count() > 0 ||
                    database.tastingDao().count() > 0 ||
                    database.stockMovementDao().count() > 0
                ) {
                    throw CollectionImportException(
                        CollectionImportException.Code.COLLECTION_NOT_EMPTY,
                        "$",
                        "Cette base contient déjà une collection. La fusion n'est pas prise en charge.",
                    )
                }

                StandardReferenceSeeder(database).seedWithinTransaction()
                val resolvedReferences = importReferences(document.references)
                importTeas(document, resolvedReferences)
                importTastings(document)
                importStockMovements(document)

                importedDao.insert(
                    ImportedDatasetEntity(
                        datasetId = document.datasetId,
                        schemaVersion = document.schemaVersion,
                        contentSha256 = contentHash,
                        sourceCreatedAtEpochMillis = document.createdAtEpochMillis,
                        importedAtEpochMillis = nowEpochMillis(),
                        referenceCount = document.references.size,
                        teaCount = document.teas.size,
                        tastingCount = document.tastings.size,
                        stockMovementCount = document.stockMovements.size,
                        gramsPerCupMilli = document.preferences.gramsPerCupMilli,
                        lowStockThresholdMilli = document.preferences.lowStockThresholdMilli,
                    ),
                )
                CollectionImportResult.Imported(
                    datasetId = document.datasetId,
                    schemaVersion = document.schemaVersion,
                    teaCount = document.teas.size,
                )
            }
        } catch (error: CollectionImportException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw CollectionImportException(
                CollectionImportException.Code.DATABASE_FAILURE,
                "$",
                "L'import a échoué. La transaction a été annulée et aucune donnée de collection n'a été conservée.",
                error,
            )
        }

        try {
            preferencesStore.setPreferences(document.preferences)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw CollectionImportException(
                CollectionImportException.Code.PREFERENCES_FAILURE,
                "$.preferences",
                "La collection est importée, mais les préférences locales n'ont pas été mises à jour. Relance le même fichier pour réessayer sans créer de doublons.",
                error,
            )
        }
        return result
    }

    private suspend fun importReferences(input: List<CollectionReference>): Map<UUID, UUID> {
        val dao = database.referenceDao()
        val resolvedIds = linkedMapOf<UUID, UUID>()
        val ordered = input.sortedWith(
            compareBy<CollectionReference> { if (it.kind == ReferenceKind.COUNTRY) 0 else if (it.kind == ReferenceKind.REGION) 2 else 1 }
                .thenBy { it.id.toString() },
        )

        ordered.forEach { reference ->
            val path = "$.references.${reference.kind.name.lowercase()}[${reference.id}]"
            val resolvedParentId = reference.parentCountryId?.let { sourceParentId ->
                resolvedIds[sourceParentId]
                    ?: importFailure("$path.parentCountryId", CollectionImportException.Code.MISSING_REFERENCE, "Le pays parent n'a pas été résolu.")
            }
            val byId = dao.findById(reference.id)
            val bySemanticKey = reference.semanticKey?.let { dao.findBySemanticKey(reference.kind.name, it) }
            if (byId != null && bySemanticKey != null && byId.id != bySemanticKey.id) {
                importFailure(path, CollectionImportException.Code.INVALID_RELATION, "L'UUID et la clé sémantique désignent deux référentiels existants différents.")
            }
            val existing = bySemanticKey ?: byId
            if (existing != null) {
                if (existing.kind != reference.kind.name) {
                    importFailure(path, CollectionImportException.Code.INVALID_RELATION, "L'UUID existe déjà avec le type ${existing.kind}.")
                }
                if (reference.semanticKey != null && existing.semanticKey != null && reference.semanticKey != existing.semanticKey) {
                    importFailure(path, CollectionImportException.Code.INVALID_RELATION, "L'UUID existe déjà avec une autre clé sémantique.")
                }
                if (reference.semanticKey != null && existing.semanticKey == null) {
                    dao.assignSemanticKeyIfMissing(existing.id, reference.semanticKey)
                }
                if (existing.parentCountryId != resolvedParentId) {
                    importFailure(path, CollectionImportException.Code.INVALID_RELATION, "Le référentiel existe déjà avec un pays parent différent.")
                }
                resolvedIds[reference.id] = existing.id
                return@forEach
            }

            val normalizedLabel = ReferenceLabelNormalizer.normalize(reference.label)
            val labelCollision = dao.findByNormalizedLabel(
                kind = reference.kind.name,
                normalizedLabel = normalizedLabel,
                scopeKey = resolvedParentId?.toString().orEmpty(),
            )
            if (labelCollision != null) {
                importFailure(
                    path,
                    CollectionImportException.Code.INVALID_RELATION,
                    "Le libellé correspond à un référentiel existant sans identité sémantique concordante; l'import ne résout pas les référentiels par le seul libellé.",
                )
            }

            val entity = ReferenceEntity.fromDomain(
                ReferenceValue(
                    id = reference.id,
                    kind = reference.kind,
                    label = reference.label,
                    normalizedLabel = normalizedLabel,
                    semanticKey = reference.semanticKey,
                    parentCountryId = resolvedParentId,
                    archivedAt = reference.archivedAtEpochMillis,
                    displayPriority = reference.displayPriority,
                ),
            )
            if (dao.insertIfAbsent(entity) == -1L) {
                importFailure(path, CollectionImportException.Code.INVALID_RELATION, "Conflit de référentiel détecté pendant l'import.")
            }
            resolvedIds[reference.id] = reference.id
        }
        if (resolvedIds.size != input.size) {
            importFailure("$.references", CollectionImportException.Code.MISSING_REFERENCE, "Tous les référentiels n'ont pas été résolus.")
        }
        return resolvedIds
    }

    private suspend fun importTeas(document: CollectionDocument, referenceIds: Map<UUID, UUID>) {
        val referencesById = document.references.associateBy { it.id }
        document.teas.forEach { tea ->
            val path = "$.teas[${tea.id}]"
            database.teaDao().insert(
                TeaEntity(
                    id = tea.id,
                    legacyId = tea.legacyId,
                    name = tea.name,
                    categoryRefId = referenceIds.required(tea.categoryId, "$path.categoryId"),
                    description = tea.description,
                    technicalInformation = tea.technicalInformation,
                    personalNotes = tea.personalNotes,
                    olfactoryRating = tea.olfactoryRating,
                    tasteRating = tea.tasteRating,
                    brewTemperatureCelsius = tea.brewTemperatureCelsius,
                    infusionDurationSeconds = tea.infusionDurationSeconds,
                    statusRefId = tea.statusId?.let { referenceIds.required(it, "$path.statusId") },
                    containerRefId = tea.containerId?.let { referenceIds.required(it, "$path.containerId") },
                    stockMilliGrams = tea.stockMilliGrams,
                    archivedAtEpochMillis = tea.archivedAtEpochMillis,
                ),
            )

            val effectiveCountrySourceIds = (tea.countryIds + tea.regionIds.mapNotNull { referencesById[it]?.parentCountryId }).distinct()
            val countryRows = effectiveCountrySourceIds.map { sourceId ->
                TeaCountryEntity(tea.id, referenceIds.required(sourceId, "$path.countryIds"))
            }
            if (countryRows.isNotEmpty()) database.teaDao().insertCountries(countryRows)

            val regionRows = tea.regionIds.map { sourceRegionId ->
                val region = referencesById.getValue(sourceRegionId)
                TeaRegionEntity(
                    teaId = tea.id,
                    regionRefId = referenceIds.required(sourceRegionId, "$path.regionIds"),
                    countryRefId = referenceIds.required(requireNotNull(region.parentCountryId), "$path.regionIds"),
                )
            }
            if (regionRows.isNotEmpty()) database.teaDao().insertRegions(regionRows)

            tea.aromaIds.takeIf { it.isNotEmpty() }?.let { ids ->
                database.teaDao().insertAromas(ids.map { TeaAromaEntity(tea.id, referenceIds.required(it, "$path.aromaIds")) })
            }
            tea.aromaticProfileIds.takeIf { it.isNotEmpty() }?.let { ids ->
                database.teaDao().insertAromaticProfiles(ids.map { TeaAromaticProfileEntity(tea.id, referenceIds.required(it, "$path.aromaticProfileIds")) })
            }
            tea.momentIds.takeIf { it.isNotEmpty() }?.let { ids ->
                database.teaDao().insertMoments(ids.map { TeaMomentEntity(tea.id, referenceIds.required(it, "$path.momentIds")) })
            }
            tea.badgeIds.takeIf { it.isNotEmpty() }?.let { ids ->
                database.teaDao().insertBadges(ids.map { TeaBadgeEntity(tea.id, referenceIds.required(it, "$path.badgeIds")) })
            }
        }
    }

    private suspend fun importTastings(document: CollectionDocument) {
        document.tastings.forEach { tasting ->
            database.tastingDao().insert(
                TastingEntity(
                    id = tasting.id,
                    teaId = tasting.teaId,
                    occurredAtEpochMillis = tasting.occurredAtEpochMillis,
                    recordedAtEpochMillis = tasting.recordedAtEpochMillis,
                    consumedMilliGrams = tasting.consumedMilliGrams,
                ),
            )
        }
    }

    private suspend fun importStockMovements(document: CollectionDocument) {
        document.stockMovements.forEach { movement ->
            database.stockMovementDao().insert(
                StockMovementEntity(
                    id = movement.id,
                    teaId = movement.teaId,
                    kind = movement.kind.name,
                    deltaMilliGrams = movement.deltaMilliGrams,
                    stockAfterMilliGrams = movement.stockAfterMilliGrams,
                    occurredAtEpochMillis = movement.occurredAtEpochMillis,
                    recordedAtEpochMillis = movement.recordedAtEpochMillis,
                    tastingId = movement.tastingId,
                    note = movement.note,
                ),
            )
        }
    }

    private fun Map<UUID, UUID>.required(id: UUID, path: String): UUID =
        get(id) ?: importFailure(path, CollectionImportException.Code.MISSING_REFERENCE, "Référence $id non résolue.")

    private fun sha256(document: CollectionDocument): String {
        val canonical = CanonicalWriter()
        canonical.field("format", COLLECTION_FORMAT)
        canonical.field("schemaVersion", document.schemaVersion.toString())
        canonical.field("datasetId", document.datasetId.toString())
        canonical.field("createdAtEpochMillis", document.createdAtEpochMillis.toString())
        canonical.field("quantityPerCupMilliGrams", document.preferences.gramsPerCupMilli.toString())
        canonical.field("lowStockThresholdMilliGrams", document.preferences.lowStockThresholdMilli.toString())
        canonical.field("references.count", document.references.size.toString())
        document.references.sortedWith(compareBy<CollectionReference> { it.kind.name }.thenBy { it.id.toString() }).forEach { reference ->
            canonical.field("reference.kind", reference.kind.name)
            canonical.field("reference.id", reference.id.toString())
            canonical.field("reference.semanticKey", reference.semanticKey)
            canonical.field("reference.label", reference.label)
            canonical.field("reference.parentCountryId", reference.parentCountryId?.toString())
            canonical.field("reference.archivedAt", reference.archivedAtEpochMillis?.toString())
            canonical.field("reference.displayPriority", reference.displayPriority.toString())
        }
        canonical.field("teas.count", document.teas.size.toString())
        document.teas.sortedBy { it.id.toString() }.forEach { tea ->
            canonical.field("tea.id", tea.id.toString())
            canonical.field("tea.legacyId", tea.legacyId?.toString())
            canonical.field("tea.name", tea.name)
            canonical.field("tea.categoryId", tea.categoryId.toString())
            canonical.field("tea.description", tea.description)
            canonical.field("tea.technicalInformation", tea.technicalInformation)
            canonical.field("tea.personalNotes", tea.personalNotes)
            canonical.field("tea.olfactoryRating", tea.olfactoryRating?.toString())
            canonical.field("tea.tasteRating", tea.tasteRating?.toString())
            canonical.field("tea.brewTemperatureCelsius", tea.brewTemperatureCelsius?.toString())
            canonical.field("tea.infusionDurationSeconds", tea.infusionDurationSeconds?.toString())
            canonical.field("tea.statusId", tea.statusId?.toString())
            canonical.field("tea.containerId", tea.containerId?.toString())
            canonical.field("tea.countryIds.count", tea.countryIds.size.toString())
            tea.countryIds.map(UUID::toString).sorted().forEach { canonical.field("tea.countryId", it) }
            canonical.field("tea.regionIds.count", tea.regionIds.size.toString())
            tea.regionIds.map(UUID::toString).sorted().forEach { canonical.field("tea.regionId", it) }
            canonical.field("tea.aromaIds.count", tea.aromaIds.size.toString())
            tea.aromaIds.map(UUID::toString).sorted().forEach { canonical.field("tea.aromaId", it) }
            canonical.field("tea.aromaticProfileIds.count", tea.aromaticProfileIds.size.toString())
            tea.aromaticProfileIds.map(UUID::toString).sorted().forEach { canonical.field("tea.aromaticProfileId", it) }
            canonical.field("tea.momentIds.count", tea.momentIds.size.toString())
            tea.momentIds.map(UUID::toString).sorted().forEach { canonical.field("tea.momentId", it) }
            canonical.field("tea.badgeIds.count", tea.badgeIds.size.toString())
            tea.badgeIds.map(UUID::toString).sorted().forEach { canonical.field("tea.badgeId", it) }
            canonical.field("tea.stockMilliGrams", tea.stockMilliGrams?.toString())
            canonical.field("tea.archivedAt", tea.archivedAtEpochMillis?.toString())
        }
        canonical.field("tastings.count", document.tastings.size.toString())
        document.tastings.sortedBy { it.id.toString() }.forEach { tasting ->
            canonical.field("tasting.id", tasting.id.toString())
            canonical.field("tasting.teaId", tasting.teaId.toString())
            canonical.field("tasting.occurredAt", tasting.occurredAtEpochMillis.toString())
            canonical.field("tasting.recordedAt", tasting.recordedAtEpochMillis.toString())
            canonical.field("tasting.consumedMilliGrams", tasting.consumedMilliGrams?.toString())
        }
        canonical.field("stockMovements.count", document.stockMovements.size.toString())
        document.stockMovements.sortedBy { it.id.toString() }.forEach { movement ->
            canonical.field("movement.id", movement.id.toString())
            canonical.field("movement.teaId", movement.teaId.toString())
            canonical.field("movement.kind", movement.kind.name)
            canonical.field("movement.deltaMilliGrams", movement.deltaMilliGrams?.toString())
            canonical.field("movement.stockAfterMilliGrams", movement.stockAfterMilliGrams.toString())
            canonical.field("movement.occurredAt", movement.occurredAtEpochMillis.toString())
            canonical.field("movement.recordedAt", movement.recordedAtEpochMillis.toString())
            canonical.field("movement.tastingId", movement.tastingId?.toString())
            canonical.field("movement.note", movement.note)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private class CanonicalWriter {
        private val output = java.io.ByteArrayOutputStream()

        fun field(name: String, value: String?) {
            writeString(name)
            writeString(value)
        }

        fun toByteArray(): ByteArray = output.toByteArray()

        private fun writeString(value: String?) {
            val bytes = value?.toByteArray(StandardCharsets.UTF_8)
            output.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
            if (bytes != null) output.write(bytes)
        }
    }

    private fun importFailure(path: String, code: CollectionImportException.Code, message: String): Nothing =
        throw CollectionImportException(code, path, message)
}

sealed interface CollectionImportResult {
    val datasetId: UUID
    val schemaVersion: Int
    val teaCount: Int

    data class Imported(
        override val datasetId: UUID,
        override val schemaVersion: Int,
        override val teaCount: Int,
    ) : CollectionImportResult

    data class AlreadyImported(
        override val datasetId: UUID,
        override val schemaVersion: Int,
        override val teaCount: Int,
    ) : CollectionImportResult
}
