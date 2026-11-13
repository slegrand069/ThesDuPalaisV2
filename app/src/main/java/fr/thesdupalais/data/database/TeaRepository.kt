package fr.thesdupalais.data.database

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import fr.thesdupalais.data.operation.BackupOperationWriteCoordinator

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
    val stockMilliGrams: Long? = null,
)

data class TeaEditData(
    val teaId: UUID,
    val values: NewTea,
    val references: Map<ReferenceKind, List<ReferenceValue>>,
)

enum class TeaEditableField {
    NAME, CATEGORY, STATUS, CONTAINER, COUNTRIES, REGIONS, AROMAS, PROFILES, MOMENTS, BADGES,
    TASTE_RATING, OLFACTORY_RATING, PERSONAL_NOTES, DESCRIPTION, TECHNICAL_INFORMATION,
    BREW_TEMPERATURE, INFUSION_DURATION, STOCK,
}

class TeaRepository(
    private val database: AppDatabase,
    private val writeCoordinator: BackupOperationWriteCoordinator = BackupOperationWriteCoordinator(),
) {
    /** Records one tasting and updates a known stock atomically. Unknown stock remains unknown. */
    suspend fun recordConsumedTasting(teaId: UUID, consumedMilliGrams: Long): UUID =
        recordTasting(teaId, System.currentTimeMillis(), consumedMilliGrams)

    /** Records a tasting at its occurrence time while preserving the actual recording time. */
    suspend fun recordTasting(teaId: UUID, occurredAtEpochMillis: Long, consumedMilliGrams: Long): UUID =
        writeCoordinator.withWritePermit {
            require(consumedMilliGrams > 0) { "La quantité consommée doit être supérieure à zéro." }
            val recordedAt = System.currentTimeMillis()
            database.withTransaction {
                val tea = database.teaDao().findActiveById(teaId)
                    ?: throw IllegalStateException("Ce thé n’est plus disponible dans la collection.")
                val tastingId = UUID.randomUUID()
                tea.stockMilliGrams?.let { stock ->
                    val remaining = (stock - consumedMilliGrams).coerceAtLeast(0L)
                    check(database.teaDao().updateStock(teaId, remaining) == 1) {
                        "Le stock du thé n’a pas pu être mis à jour."
                    }
                }
                database.tastingDao().insert(
                    TastingEntity(
                        id = tastingId,
                        teaId = teaId,
                        occurredAtEpochMillis = occurredAtEpochMillis,
                        recordedAtEpochMillis = recordedAt,
                        consumedMilliGrams = consumedMilliGrams,
                    ),
                )
                tastingId
            }
        }

    /** Deletes one tasting and returns its known consumed amount to a known current stock. */
    suspend fun deleteTasting(teaId: UUID, tastingId: UUID): Boolean =
        writeCoordinator.withWritePermit {
            database.withTransaction {
                val tasting = database.tastingDao().findForTea(tastingId, teaId) ?: return@withTransaction false
                val consumed = tasting.consumedMilliGrams?.takeIf { it > 0 }
                if (consumed != null) {
                    val tea = database.teaDao().findById(teaId)
                        ?: throw IllegalStateException("La fiche du thé n’est plus disponible.")
                    val stock = tea.stockMilliGrams
                    if (stock != null) {
                        val restored = try {
                            Math.addExact(stock, consumed)
                        } catch (_: ArithmeticException) {
                            Long.MAX_VALUE
                        }
                        check(database.teaDao().updateStock(teaId, restored) == 1) {
                            "Le stock du thé n’a pas pu être mis à jour."
                        }
                    }
                }
                check(database.tastingDao().deleteForTea(tastingId, teaId) == 1) {
                    "La dégustation n’a pas pu être supprimée."
                }
                true
            }
        }

    fun observeSelectableReferences(kind: ReferenceKind): Flow<List<ReferenceValue>> =
        database.referenceDao().observeSelectableByKind(kind.name).map { references ->
            references.map(ReferenceEntity::toDomain)
        }

    suspend fun loadForEdit(teaId: UUID): TeaEditData? {
        val detail = database.teaDao().findActiveDetailForEdit(teaId) ?: return null
        val tea = detail.tea
        val references = mapOf(
            ReferenceKind.CATEGORY to listOf(detail.category),
            ReferenceKind.STATUS to listOfNotNull(detail.status),
            ReferenceKind.CONTAINER to listOfNotNull(detail.container),
            ReferenceKind.COUNTRY to detail.countries,
            ReferenceKind.REGION to detail.regions,
            ReferenceKind.AROMA to detail.aromas,
            ReferenceKind.AROMATIC_PROFILE to detail.aromaticProfiles,
            ReferenceKind.MOMENT to detail.moments,
            ReferenceKind.BADGE to detail.badges,
        ).mapValues { (_, values) -> values.map(ReferenceEntity::toDomain) }
        return TeaEditData(
            teaId = tea.id,
            values = NewTea(
                name = tea.name,
                categoryId = tea.categoryRefId,
                statusId = tea.statusRefId,
                containerId = tea.containerRefId,
                countryIds = detail.countries.map { it.id },
                regionIds = detail.regions.map { it.id },
                aromaIds = detail.aromas.map { it.id },
                aromaticProfileIds = detail.aromaticProfiles.map { it.id },
                momentIds = detail.moments.map { it.id },
                badgeIds = detail.badges.map { it.id },
                tasteRating = tea.tasteRating,
                olfactoryRating = tea.olfactoryRating,
                personalNotes = tea.personalNotes,
                description = tea.description,
                technicalInformation = tea.technicalInformation,
                brewTemperatureCelsius = tea.brewTemperatureCelsius,
                infusionDurationSeconds = tea.infusionDurationSeconds,
                stockMilliGrams = tea.stockMilliGrams,
            ),
            references = references,
        )
    }

    suspend fun create(tea: NewTea): UUID = writeCoordinator.withWritePermit {
        try {
            createInTransaction(tea)
        } catch (error: SQLiteConstraintException) {
            throw IllegalStateException(
                "Une valeur sélectionnée n’est plus compatible. La fiche n’a pas été enregistrée.",
                error,
            )
        }
    }

    suspend fun update(
        teaId: UUID,
        tea: NewTea,
        changedFields: Set<TeaEditableField>,
    ): Boolean = writeCoordinator.withWritePermit {
      try {
        database.withTransaction {
            val dao = database.teaDao()
            val old = dao.findActiveDetailForEdit(teaId)
                ?: throw IllegalStateException("Ce thé n’est plus disponible pour modification.")
            val oldTea = old.tea
            val oldIds = mapOf(
                ReferenceKind.CATEGORY to setOf(oldTea.categoryRefId),
                ReferenceKind.STATUS to setOfNotNull(oldTea.statusRefId),
                ReferenceKind.CONTAINER to setOfNotNull(oldTea.containerRefId),
                ReferenceKind.COUNTRY to old.countries.map { it.id }.toSet(),
                ReferenceKind.REGION to old.regions.map { it.id }.toSet(),
                ReferenceKind.AROMA to old.aromas.map { it.id }.toSet(),
                ReferenceKind.AROMATIC_PROFILE to old.aromaticProfiles.map { it.id }.toSet(),
                ReferenceKind.MOMENT to old.moments.map { it.id }.toSet(),
                ReferenceKind.BADGE to old.badges.map { it.id }.toSet(),
            )

            val name = if (TeaEditableField.NAME in changedFields) normalizeRequired(tea.name, "Saisissez le nom du thé.") else oldTea.name
            val categoryId = if (TeaEditableField.CATEGORY in changedFields) requireNotNull(tea.categoryId) { "Choisissez une catégorie." } else oldTea.categoryRefId
            val statusId = if (TeaEditableField.STATUS in changedFields) tea.statusId else oldTea.statusRefId
            val containerId = if (TeaEditableField.CONTAINER in changedFields) tea.containerId else oldTea.containerRefId
            val tasteRating = if (TeaEditableField.TASTE_RATING in changedFields) tea.tasteRating else oldTea.tasteRating
            val olfactoryRating = if (TeaEditableField.OLFACTORY_RATING in changedFields) tea.olfactoryRating else oldTea.olfactoryRating
            val personalNotes = if (TeaEditableField.PERSONAL_NOTES in changedFields) normalizeOptional(tea.personalNotes) else oldTea.personalNotes
            val description = if (TeaEditableField.DESCRIPTION in changedFields) normalizeOptional(tea.description) else oldTea.description
            val technicalInformation = if (TeaEditableField.TECHNICAL_INFORMATION in changedFields) normalizeOptional(tea.technicalInformation) else oldTea.technicalInformation
            val temperature = if (TeaEditableField.BREW_TEMPERATURE in changedFields) tea.brewTemperatureCelsius else oldTea.brewTemperatureCelsius
            val duration = if (TeaEditableField.INFUSION_DURATION in changedFields) tea.infusionDurationSeconds else oldTea.infusionDurationSeconds
            val stock = if (TeaEditableField.STOCK in changedFields) tea.stockMilliGrams else oldTea.stockMilliGrams

            requireRating(tasteRating, "La note gustative")
            requireRating(olfactoryRating, "La note olfactive")
            if (TeaEditableField.BREW_TEMPERATURE in changedFields && temperature != oldTea.brewTemperatureCelsius) {
                require(temperature == null || (temperature in 60..100 && temperature % 5 == 0)) {
                    "La température doit être comprise entre 60 et 100 °C, par pas de 5 °C."
                }
            }
            require(duration == null || duration > 0) { "La durée d’infusion doit être supérieure à zéro seconde." }
            require(stock == null || stock >= 0) { "Le stock ne peut pas être négatif." }

            suspend fun requireReference(id: UUID, kind: ReferenceKind): ReferenceEntity {
                val reference = database.referenceDao().findById(id)
                require(reference?.kind == kind.name && (reference.archivedAt == null || id in oldIds.getValue(kind))) {
                    "Une valeur sélectionnée n’est plus disponible dans le référentiel ${kind.name.lowercase()}."
                }
                return reference
            }

            requireReference(categoryId, ReferenceKind.CATEGORY)
            statusId?.let { requireReference(it, ReferenceKind.STATUS) }
            containerId?.let { requireReference(it, ReferenceKind.CONTAINER) }

            val countryIds = if (TeaEditableField.COUNTRIES in changedFields) tea.countryIds.distinct().toMutableSet()
                else oldIds.getValue(ReferenceKind.COUNTRY).toMutableSet()
            val regionIds = if (TeaEditableField.REGIONS in changedFields) tea.regionIds.distinct()
                else oldIds.getValue(ReferenceKind.REGION).toList()
            val regions = regionIds.map { requireReference(it, ReferenceKind.REGION) }
            regions.forEach { region ->
                val parentId = requireNotNull(region.parentCountryId) { "Une région doit être rattachée à un pays." }
                requireReference(parentId, ReferenceKind.COUNTRY)
                countryIds += parentId
            }
            val countries = countryIds.map { requireReference(it, ReferenceKind.COUNTRY) }
            val countrySet = countries.map { it.id }.toSet()

            suspend fun selectedIds(kind: ReferenceKind, selected: List<UUID>, changed: TeaEditableField): Set<UUID> {
                val ids = if (changed in changedFields) selected.distinct() else oldIds.getValue(kind).toList()
                ids.forEach { requireReference(it, kind) }
                return ids.toSet()
            }
            val aromaIds = selectedIds(ReferenceKind.AROMA, tea.aromaIds, TeaEditableField.AROMAS)
            val profileIds = selectedIds(ReferenceKind.AROMATIC_PROFILE, tea.aromaticProfileIds, TeaEditableField.PROFILES)
            val momentIds = selectedIds(ReferenceKind.MOMENT, tea.momentIds, TeaEditableField.MOMENTS)
            val badgeIds = selectedIds(ReferenceKind.BADGE, tea.badgeIds, TeaEditableField.BADGES)
            val regionSet = regions.map { it.id }.toSet()

            val scalarChanged = name != oldTea.name || categoryId != oldTea.categoryRefId || statusId != oldTea.statusRefId ||
                containerId != oldTea.containerRefId || description != oldTea.description || technicalInformation != oldTea.technicalInformation ||
                personalNotes != oldTea.personalNotes || tasteRating != oldTea.tasteRating || olfactoryRating != oldTea.olfactoryRating ||
                temperature != oldTea.brewTemperatureCelsius || duration != oldTea.infusionDurationSeconds ||
                stock != oldTea.stockMilliGrams
            val associationsChanged = countrySet != oldIds.getValue(ReferenceKind.COUNTRY) ||
                regionSet != oldIds.getValue(ReferenceKind.REGION) || aromaIds != oldIds.getValue(ReferenceKind.AROMA) ||
                profileIds != oldIds.getValue(ReferenceKind.AROMATIC_PROFILE) || momentIds != oldIds.getValue(ReferenceKind.MOMENT) ||
                badgeIds != oldIds.getValue(ReferenceKind.BADGE)
            if (!scalarChanged && !associationsChanged) return@withTransaction false

            if (scalarChanged) {
                check(dao.updateEditableFields(
                    teaId = teaId,
                    name = name,
                    categoryRefId = categoryId,
                    description = description,
                    technicalInformation = technicalInformation,
                    personalNotes = personalNotes,
                    olfactoryRating = olfactoryRating,
                    tasteRating = tasteRating,
                    brewTemperatureCelsius = temperature,
                    infusionDurationSeconds = duration,
                    statusRefId = statusId,
                    containerRefId = containerId,
                    stockMilliGrams = stock,
                ) == 1) { "Ce thé n’est plus disponible pour modification." }
            }
            if (countrySet != oldIds.getValue(ReferenceKind.COUNTRY) || regionSet != oldIds.getValue(ReferenceKind.REGION)) {
                dao.deleteRegionsForTea(teaId)
                dao.deleteCountriesForTea(teaId)
                if (countrySet.isNotEmpty()) dao.insertCountries(countrySet.map { TeaCountryEntity(teaId, it) })
                if (regionSet.isNotEmpty()) dao.insertRegions(regions.map { TeaRegionEntity(teaId, it.id, requireNotNull(it.parentCountryId)) })
            }
            if (aromaIds != oldIds.getValue(ReferenceKind.AROMA)) {
                dao.deleteAromasForTea(teaId)
                if (aromaIds.isNotEmpty()) dao.insertAromas(aromaIds.map { TeaAromaEntity(teaId, it) })
            }
            if (profileIds != oldIds.getValue(ReferenceKind.AROMATIC_PROFILE)) {
                dao.deleteAromaticProfilesForTea(teaId)
                if (profileIds.isNotEmpty()) dao.insertAromaticProfiles(profileIds.map { TeaAromaticProfileEntity(teaId, it) })
            }
            if (momentIds != oldIds.getValue(ReferenceKind.MOMENT)) {
                dao.deleteMomentsForTea(teaId)
                if (momentIds.isNotEmpty()) dao.insertMoments(momentIds.map { TeaMomentEntity(teaId, it) })
            }
            if (badgeIds != oldIds.getValue(ReferenceKind.BADGE)) {
                dao.deleteBadgesForTea(teaId)
                if (badgeIds.isNotEmpty()) dao.insertBadges(badgeIds.map { TeaBadgeEntity(teaId, it) })
            }
            true
        }
      } catch (error: SQLiteConstraintException) {
          throw IllegalStateException("Une valeur sélectionnée n’est plus compatible. La fiche n’a pas été modifiée.", error)
      }
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
        require(tea.stockMilliGrams == null || tea.stockMilliGrams >= 0) { "Le stock ne peut pas être négatif." }

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
                stockMilliGrams = tea.stockMilliGrams,
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
