package fr.thesdupalais.data.database

import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceSummary
import fr.thesdupalais.domain.model.ReferenceValue
import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import fr.thesdupalais.domain.references.StandardReferenceSeeds
import androidx.room.withTransaction
import android.database.sqlite.SQLiteConstraintException
import java.util.UUID
import java.text.Normalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import fr.thesdupalais.data.operation.BackupOperationWriteCoordinator

class RoomReferenceRepository(
    private val database: AppDatabase,
    private val writeCoordinator: BackupOperationWriteCoordinator = BackupOperationWriteCoordinator(),
) {
    private val dao get() = database.referenceDao()

    fun observe(kind: ReferenceKind): Flow<List<ReferenceSummary>> =
        dao.observeForManagement(kind.name).map { values ->
            values.map { item ->
                ReferenceSummary(
                    value = item.reference.toDomain(),
                    teaUsageCount = item.teaUsageCount,
                    activeTeaUsageCount = item.activeTeaUsageCount,
                    childRegionCount = item.childRegionCount,
                    parentCountryLabel = item.parentCountryLabel,
                )
            }
        }

    fun observeCountries(): Flow<List<ReferenceValue>> = dao.observeByKind(ReferenceKind.COUNTRY.name)
        .map { refs -> refs.map { it.toDomain() } }

    suspend fun find(id: UUID): ReferenceValue? = dao.findById(id)?.toDomain()

    suspend fun checkDeletion(id: UUID): ReferenceDeletionStatus = database.withTransaction {
        val reference = dao.findById(id) ?: return@withTransaction ReferenceDeletionStatus.Missing
        if (isSeedManaged(reference)) return@withTransaction ReferenceDeletionStatus.Standard
        val teaCount = dao.countTeaUsage(reference.kind, id)
        val regionCount = if (reference.kind == ReferenceKind.COUNTRY.name) dao.countChildRegions(id) else 0
        when {
            teaCount > 0 -> ReferenceDeletionStatus.InUse(teaCount, regionCount)
            regionCount > 0 -> ReferenceDeletionStatus.HasRegions(regionCount)
            else -> ReferenceDeletionStatus.Eligible
        }
    }

    suspend fun deleteIfUnused(id: UUID): ReferenceDeletionStatus = writeCoordinator.withWritePermit { database.withTransaction {
        val reference = dao.findById(id) ?: return@withTransaction ReferenceDeletionStatus.Missing
        if (isSeedManaged(reference)) return@withTransaction ReferenceDeletionStatus.Standard
        val status = deletionStatus(reference)
        if (status != ReferenceDeletionStatus.Eligible) return@withTransaction status
        try {
            if (dao.deleteById(id) == 1) ReferenceDeletionStatus.Deleted else ReferenceDeletionStatus.Missing
        } catch (_: SQLiteConstraintException) {
            val latest = deletionStatus(reference)
            if (latest == ReferenceDeletionStatus.Eligible) ReferenceDeletionStatus.RestrictedByDependency else latest
        }
    } }

    private suspend fun deletionStatus(reference: ReferenceEntity): ReferenceDeletionStatus {
        val teaCount = dao.countTeaUsage(reference.kind, reference.id)
        val regionCount = if (reference.kind == ReferenceKind.COUNTRY.name) dao.countChildRegions(reference.id) else 0
        return when {
            teaCount > 0 -> ReferenceDeletionStatus.InUse(teaCount, regionCount)
            regionCount > 0 -> ReferenceDeletionStatus.HasRegions(regionCount)
            else -> ReferenceDeletionStatus.Eligible
        }
    }

    private fun isSeedManaged(reference: ReferenceEntity): Boolean = StandardReferenceSeeds.all.any {
        it.kind.name == reference.kind && it.semanticKey != null && it.semanticKey == reference.semanticKey
    }

    suspend fun create(kind: ReferenceKind, rawLabel: String, parentCountryId: UUID? = null): UUID =
        writeCoordinator.withWritePermit { database.withTransaction {
            val label = validateLabel(rawLabel)
            val parent = if (kind == ReferenceKind.REGION) {
                requireNotNull(parentCountryId) { "Sélectionnez un pays pour cette région." }
                    .also { id ->
                        val country = dao.findById(id)
                        require(country?.kind == ReferenceKind.COUNTRY.name) { "Le pays sélectionné n’existe plus." }
                    }
            } else {
                require(parentCountryId == null) { "Seules les régions peuvent avoir un pays parent." }
                null
            }
            val normalized = ReferenceLabelNormalizer.normalize(label)
            val scope = parent?.toString().orEmpty()
            check(dao.findByNormalizedLabel(kind.name, normalized, scope) == null) {
                "Une valeur identique existe déjà dans ce référentiel."
            }
            val id = UUID.randomUUID()
            dao.insert(ReferenceEntity(id, kind.name, label, normalized, null, parent, scope, null, 0))
            id
        } }

    suspend fun rename(id: UUID, rawLabel: String, expectedKind: ReferenceKind? = null) = writeCoordinator.withWritePermit { database.withTransaction {
        val label = validateLabel(rawLabel)
        val current = requireNotNull(dao.findById(id)) { "Cette référence n’existe plus." }
        require(expectedKind == null || current.kind == expectedKind.name) { "Cette valeur n’appartient pas à ce référentiel." }
        val normalized = ReferenceLabelNormalizer.normalize(label)
        check(dao.findLabelConflict(current.kind, normalized, current.scopeKey, id) == null) {
            "Une valeur identique existe déjà dans ce référentiel."
        }
        check(dao.rename(id, label, normalized) == 1) { "Le renommage n’a pas pu être enregistré." }
    } }

    private fun validateLabel(raw: String): String {
        val cleaned = Normalizer.normalize(raw.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC)
        require(cleaned.isNotEmpty()) { "Saisissez un nom pour cette valeur." }
        require(cleaned.length <= 100) { "Le nom ne peut pas dépasser 100 caractères." }
        return cleaned
    }
}

sealed interface ReferenceDeletionStatus {
    data object Eligible : ReferenceDeletionStatus
    data object Deleted : ReferenceDeletionStatus
    data object Standard : ReferenceDeletionStatus
    data object Missing : ReferenceDeletionStatus
    data object RestrictedByDependency : ReferenceDeletionStatus
    data class InUse(val teaCount: Int, val regionCount: Int = 0) : ReferenceDeletionStatus
    data class HasRegions(val regionCount: Int) : ReferenceDeletionStatus
}
