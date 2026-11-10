package fr.thesdupalais.data.database

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class NewTea(
    val name: String,
    val categoryId: UUID?,
    val statusId: UUID? = null,
    val containerId: UUID? = null,
    val countryIds: List<UUID> = emptyList(),
    val regionIds: List<UUID> = emptyList(),
    val aromaIds: List<UUID> = emptyList(),
    val aromaticProfileIds: List<UUID> = emptyList(),
    val momentIds: List<UUID> = emptyList(),
    val badgeIds: List<UUID> = emptyList(),
    val tasteRating: Double? = null,
    val olfactoryRating: Double? = null,
    val personalNotes: String? = null,
    val description: String? = null,
    val technicalInformation: String? = null,
    val brewTemperatureCelsius: Int? = null,
    val infusionDurationSeconds: Int? = null,
)

class TeaRepository(private val database: AppDatabase) {
    fun observeSelectableReferences(kind: ReferenceKind): Flow<List<ReferenceValue>> =
        database.referenceDao().observeSelectableByKind(kind.name).map { references ->
            references.map(ReferenceEntity::toDomain)
        }

    suspend fun create(tea: NewTea): UUID = try {
        createInTransaction(tea)
    } catch (error: SQLiteConstraintException) {
        throw IllegalStateException(
            "Une valeur sélectionnée n’est plus compatible. La fiche n’a pas été enregistrée.",
            error,
        )
    }

    private suspend fun createInTransaction(tea: NewTea): UUID = database.withTransaction {
        val name = normalizeRequired(tea.name, "Saisissez le nom du thé.")
        val categoryId = requireNotNull(tea.categoryId) { "Choisissez une catégorie." }
        requireRating(tea.tasteRating, "La note gustative")
        requireRating(tea.olfactoryRating, "La note olfactive")
        require(tea.brewTemperatureCelsius == null || (tea.brewTemperatureCelsius in 60..100 && tea.brewTemperatureCelsius % 5 == 0)) {
            "La température doit être comprise entre 60 et 100 °C, par pas de 5 °C."
        }
        require(tea.infusionDurationSeconds == null || tea.infusionDurationSeconds > 0) {
            "La durée d’infusion doit être supérieure à zéro seconde."
        }

        val referenceDao = database.referenceDao()
        suspend fun requireReference(id: UUID, kind: ReferenceKind): ReferenceEntity {
            val reference = referenceDao.findById(id)
            require(reference?.kind == kind.name && reference.archivedAt == null) {
                "Une valeur sélectionnée n’est plus disponible dans le référentiel ${kind.name.lowercase()}."
            }
            return reference
        }

        requireReference(categoryId, ReferenceKind.CATEGORY)
        tea.statusId?.let { requireReference(it, ReferenceKind.STATUS) }
        tea.containerId?.let { requireReference(it, ReferenceKind.CONTAINER) }

        val countries = tea.countryIds.distinct().map { requireReference(it, ReferenceKind.COUNTRY) }
        val regions = tea.regionIds.distinct().map { requireReference(it, ReferenceKind.REGION) }
        regions.forEach { region ->
            val parentId = requireNotNull(region.parentCountryId) { "Une région doit être rattachée à un pays." }
            requireReference(parentId, ReferenceKind.COUNTRY)
        }
        val countryIds = (countries.map { it.id } + regions.mapNotNull { it.parentCountryId }).distinct()

        val aromas = tea.aromaIds.distinct().map { requireReference(it, ReferenceKind.AROMA) }
        val profiles = tea.aromaticProfileIds.distinct().map { requireReference(it, ReferenceKind.AROMATIC_PROFILE) }
        val moments = tea.momentIds.distinct().map { requireReference(it, ReferenceKind.MOMENT) }
        val badges = tea.badgeIds.distinct().map { requireReference(it, ReferenceKind.BADGE) }

        val id = UUID.randomUUID()
        database.teaDao().insert(
            TeaEntity(
                id = id,
                legacyId = null,
                name = name,
                categoryRefId = categoryId,
                description = normalizeOptional(tea.description),
                technicalInformation = normalizeOptional(tea.technicalInformation),
                personalNotes = normalizeOptional(tea.personalNotes),
                olfactoryRating = tea.olfactoryRating,
                tasteRating = tea.tasteRating,
                brewTemperatureCelsius = tea.brewTemperatureCelsius,
                infusionDurationSeconds = tea.infusionDurationSeconds,
                statusRefId = tea.statusId,
                containerRefId = tea.containerId,
                stockMilliGrams = null,
                archivedAtEpochMillis = null,
            ),
        )
        val teaDao = database.teaDao()
        if (countryIds.isNotEmpty()) teaDao.insertCountries(countryIds.map { TeaCountryEntity(id, it) })
        if (regions.isNotEmpty()) teaDao.insertRegions(regions.map { TeaRegionEntity(id, it.id, requireNotNull(it.parentCountryId)) })
        if (aromas.isNotEmpty()) teaDao.insertAromas(aromas.map { TeaAromaEntity(id, it.id) })
        if (profiles.isNotEmpty()) teaDao.insertAromaticProfiles(profiles.map { TeaAromaticProfileEntity(id, it.id) })
        if (moments.isNotEmpty()) teaDao.insertMoments(moments.map { TeaMomentEntity(id, it.id) })
        if (badges.isNotEmpty()) teaDao.insertBadges(badges.map { TeaBadgeEntity(id, it.id) })
        id
    }

    private fun normalizeRequired(value: String, message: String): String {
        val normalized = Normalizer.normalize(value.trim().replace(WHITESPACE, " "), Normalizer.Form.NFC)
        require(normalized.isNotEmpty()) { message }
        return normalized
    }

    private fun normalizeOptional(value: String?): String? = value
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun requireRating(value: Double?, label: String) {
        require(value == null || (value.isFinite() && value in 0.0..10.0)) {
            "$label doit être comprise entre 0 et 10."
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
