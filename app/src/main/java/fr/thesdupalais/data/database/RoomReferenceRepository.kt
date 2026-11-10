package fr.thesdupalais.data.database

import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceSummary
import fr.thesdupalais.domain.model.ReferenceValue
import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import androidx.room.withTransaction
import java.util.UUID
import java.text.Normalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomReferenceRepository(private val database: AppDatabase) {
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

    suspend fun create(kind: ReferenceKind, rawLabel: String, parentCountryId: UUID? = null): UUID =
        database.withTransaction {
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
        }

    suspend fun rename(id: UUID, rawLabel: String, expectedKind: ReferenceKind? = null) = database.withTransaction {
        val label = validateLabel(rawLabel)
        val current = requireNotNull(dao.findById(id)) { "Cette référence n’existe plus." }
        require(expectedKind == null || current.kind == expectedKind.name) { "Cette valeur n’appartient pas à ce référentiel." }
        val normalized = ReferenceLabelNormalizer.normalize(label)
        check(dao.findLabelConflict(current.kind, normalized, current.scopeKey, id) == null) {
            "Une valeur identique existe déjà dans ce référentiel."
        }
        check(dao.rename(id, label, normalized) == 1) { "Le renommage n’a pas pu être enregistré." }
    }

    private fun validateLabel(raw: String): String {
        val cleaned = Normalizer.normalize(raw.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC)
        require(cleaned.isNotEmpty()) { "Saisissez un nom pour cette valeur." }
        require(cleaned.length <= 100) { "Le nom ne peut pas dépasser 100 caractères." }
        return cleaned
    }
}
