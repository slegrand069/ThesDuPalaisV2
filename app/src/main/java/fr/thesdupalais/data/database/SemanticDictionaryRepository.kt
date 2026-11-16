package fr.thesdupalais.data.database

import androidx.room.withTransaction
import fr.thesdupalais.data.operation.BackupOperationWriteCoordinator
import fr.thesdupalais.domain.semantics.MadjoSemanticResolver
import fr.thesdupalais.domain.semantics.SemanticConcept
import fr.thesdupalais.domain.semantics.SemanticDictionarySnapshot
import fr.thesdupalais.domain.semantics.SemanticExpression
import fr.thesdupalais.domain.semantics.SemanticRelation
import fr.thesdupalais.domain.semantics.SemanticRelationType
import fr.thesdupalais.domain.semantics.SemanticDictionaryValidator
import fr.thesdupalais.domain.semantics.SemanticDomain
import fr.thesdupalais.domain.semantics.BuiltInSemanticConceptIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Stable seed keys are independent of display labels. New bundled defaults get new IDs; insertion uses
 * IGNORE and never updates an existing row, so renamed, disabled, or otherwise customized concepts,
 * expressions, and relations survive application updates. To enrich the initial dictionary, append
 * new stable keys to [BuiltInSemanticDictionary] and increment its revision; never repurpose old keys.
 */
object BuiltInSemanticDictionary {
    const val revision = 1

    private data class ConceptSeed(
        val key: String,
        val domain: SemanticDomain,
        val label: String,
        val businessDimension: String? = null,
        val businessConcept: String? = null,
        val businessValue: String? = null,
        val expressions: List<String> = emptyList(),
    )

    private val concepts = listOf(
        ConceptSeed("green", SemanticDomain.CATEGORY, "Thé vert", "TYPE_COLOR", "category", "GREEN", listOf("vert", "verte", "thé vert", "thés verts")),
        ConceptSeed("black", SemanticDomain.CATEGORY, "Thé noir", "TYPE_COLOR", "category", "BLACK", listOf("noir", "noire", "thé noir", "thés noirs")),
        ConceptSeed("dark", SemanticDomain.CATEGORY, "Thé sombre", "TYPE_COLOR", "category", "DARK", listOf("sombre", "thé sombre", "thés sombres")),
        ConceptSeed("puerh", SemanticDomain.CATEGORY, "Pu’Erh", "TYPE_COLOR", "category", "PU_ERH", listOf("pu'erh", "pu erh", "puerh", "pu erh sombre")),
        ConceptSeed("fruity", SemanticDomain.AROMA, "Fruité", "AROMA", "aroma", "FRUITY", listOf("fruit", "fruits", "fruité", "fruitée", "fruités", "fruitées", "note fruitée")),
        ConceptSeed("berries", SemanticDomain.AROMA, "Fruits des bois", "AROMA", "aroma", "BERRIES", listOf("fruit des bois", "fruits des bois", "fruits rouges", "baies")),
        ConceptSeed("citrus", SemanticDomain.AROMA, "Agrumes", "AROMA", "aroma", "CITRUS", listOf("agrume", "agrumes", "notes d’agrumes", "zeste d’agrume")),
        ConceptSeed("floral-profile", SemanticDomain.AROMATIC_PROFILE, "Profil floral", "AROMA", "aromatic_profile", "FLORAL", listOf("profil floral", "profil fleuri")),
        ConceptSeed("asia", SemanticDomain.ORIGIN, "Asie", expressions = listOf("Asie", "asiatique", "asiatiques")),
        ConceptSeed("china", SemanticDomain.ORIGIN, "Chine", "ORIGIN", "country", "CHINA", listOf("Chine", "chinois", "chinoise")),
        ConceptSeed("japan", SemanticDomain.ORIGIN, "Japon", "ORIGIN", "country", "JAPAN", listOf("Japon", "japonais", "japonaise")),
        ConceptSeed("vietnam", SemanticDomain.ORIGIN, "Vietnam", "ORIGIN", "country", "VIETNAM", listOf("Vietnam", "vietnamien", "vietnamienne")),
        ConceptSeed("morning", SemanticDomain.MOMENT, "Matin", "MOMENT", "moment", "MORNING", listOf("le matin", "en matinée", "matinée", "pour commencer la journée")),
        ConceptSeed("top-rated", SemanticDomain.TASTE_RATING, "Mieux notés", "TASTE_RATING", "taste_rating", "top_rated", listOf("mes mieux notés", "les mieux notés", "mes meilleurs thés", "mes thés préférés")),
        ConceptSeed("available-stock", SemanticDomain.STOCK, "Disponible en stock", "STOCK", "stock", "available", listOf("en stock", "disponible", "disponibles")),
        ConceptSeed("abundant-stock", SemanticDomain.STOCK, "Stock important", "STOCK", "stock", "abundant", listOf("stock important", "il m’en reste beaucoup", "beaucoup en stock")),
        ConceptSeed("frequent-tasting", SemanticDomain.TASTING_HISTORY, "Souvent dégusté", "TASTING_HISTORY", "tasting_frequency", "frequent", listOf("souvent dégusté", "souvent dégustée", "que je bois régulièrement", "que je déguste souvent")),
        ConceptSeed("forgotten-tasting", SemanticDomain.TASTING_HISTORY, "Dégusté il y a longtemps", "TASTING_HISTORY", "tasting_recency", "oldest_first", listOf("pas bu depuis longtemps", "pas dégusté depuis longtemps", "thé oublié", "thé oublié depuis longtemps")),
    )

    private val keys = concepts.associate { it.key to conceptId(it.domain, it.key) }

    val conceptRows: List<SemanticConceptEntity> = concepts.map { seed ->
        SemanticConceptEntity(keys.getValue(seed.key), seed.domain.name, seed.label, true, seed.businessDimension, seed.businessConcept, seed.businessValue)
    }
    val expressionRows: List<SemanticExpressionEntity> = concepts.flatMap { seed ->
        val forms = (listOf(seed.label) + seed.expressions).distinctBy(MadjoSemanticResolver::normalize)
        forms.mapIndexed { index, form ->
            val id = stableId("semantic-expression", "${seed.key}:${MadjoSemanticResolver.normalize(form)}")
            SemanticExpressionEntity(id, keys.getValue(seed.key), form, MadjoSemanticResolver.normalize(form), index == 0, true)
        }
    }
    val relationRows: List<SemanticRelationEntity> = listOf(
        relation("dark", "puerh"),
        relation("fruity", "berries"),
        relation("fruity", "citrus"),
        relation("asia", "china"),
        relation("asia", "japan"),
        relation("asia", "vietnam"),
    )

    val protectedConceptIds: Set<String> = setOf("top-rated", "available-stock", "abundant-stock", "frequent-tasting", "forgotten-tasting")
        .mapNotNull { key -> concepts.firstOrNull { it.key == key }?.let { keys.getValue(key) } }.toSet()
    val seededConceptIds: Set<String> = conceptRows.map(SemanticConceptEntity::id).toSet()
    val seededExpressionIds: Set<String> = expressionRows.map(SemanticExpressionEntity::id).toSet()
    val seededRelationIds: Set<String> = relationRows.map(SemanticRelationEntity::id).toSet()

    fun stableId(namespace: String, key: String): String = UUID.nameUUIDFromBytes("$namespace.$key".toByteArray(StandardCharsets.UTF_8)).toString()
    fun conceptId(domain: SemanticDomain, key: String): String = stableId("semantic-concept-${domain.name.lowercase()}", key)
    private fun relation(source: String, target: String) = SemanticRelationEntity(
        stableId("semantic-relation", "$source:$target:SPECIALIZATION"),
        keys.getValue(source), keys.getValue(target), SemanticRelationType.SPECIALIZATION.name, true,
    )
}

class SemanticDictionarySeeder(
    private val database: AppDatabase,
    private val writeCoordinator: BackupOperationWriteCoordinator = BackupOperationWriteCoordinator(),
) {
    suspend fun seed() = writeCoordinator.withWritePermit {
        database.withTransaction {
            val dao = database.semanticDictionaryDao()
            dao.insertSeedConcepts(BuiltInSemanticDictionary.conceptRows)
            dao.insertSeedExpressions(BuiltInSemanticDictionary.expressionRows)
            dao.insertSeedRelations(BuiltInSemanticDictionary.relationRows)
        }
    }
}

class SemanticDictionaryRepository(
    private val database: AppDatabase,
    private val writeCoordinator: BackupOperationWriteCoordinator = BackupOperationWriteCoordinator(),
) {
    private val dao get() = database.semanticDictionaryDao()

    suspend fun exportArchive(): SemanticDictionaryArchive = SemanticDictionaryArchive(
        concepts = dao.allConceptRowsForBackup(),
        expressions = dao.allExpressions(),
        relations = dao.allRelations(),
    )

    suspend fun validateArchive(json: String): SemanticDictionaryArchive {
        val archive = SemanticDictionaryArchiveJson.decode(json)
        validateArchiveBusinessRules(archive)
        return archive
    }

    /** Replaces only semantic dictionary rows; all checks and writes share one Room transaction. */
    suspend fun replaceArchive(json: String): SemanticDictionaryArchive = writeCoordinator.withWritePermit {
        database.withTransaction {
            val archive = SemanticDictionaryArchiveJson.decode(json)
            validateArchiveBusinessRules(archive)
            dao.clearRelationsForReplacement()
            dao.clearExpressionsForReplacement()
            dao.clearConceptsForReplacement()
            for (concept in archive.concepts) dao.insertConcept(concept)
            if (archive.expressions.isNotEmpty()) dao.insertExpressions(archive.expressions)
            if (archive.relations.isNotEmpty()) dao.insertRelations(archive.relations)
            archive
        }
    }

    private suspend fun validateArchiveBusinessRules(archive: SemanticDictionaryArchive) {
        val availableReferences = database.referenceDao().allForBackup()
            .filter { it.archivedAt == null }
            .associate { it.id.toString() to it.kind }
        SemanticDictionaryArchiveRules.validateBusinessLinks(archive, availableReferences)
    }

    val snapshot: Flow<SemanticDictionarySnapshot> = combine(
        dao.observeConcepts(), dao.observeExpressions(), dao.observeRelations(),
    ) { concepts, expressions, relations ->
        SemanticDictionarySnapshot(
            concepts.mapNotNull { row -> runCatching { SemanticConcept(row.id, SemanticDomain.valueOf(row.domain), row.label, row.active, row.businessDimension, row.businessConcept, row.businessValue) }.getOrNull() },
            expressions.map { SemanticExpression(it.id, it.conceptId, it.expression, it.normalizedForm, it.isPrimary, it.active) },
            relations.mapNotNull { row -> runCatching { SemanticRelation(row.id, row.sourceConceptId, row.targetConceptId, SemanticRelationType.valueOf(row.type), row.active) }.getOrNull() },
        )
    }

    suspend fun saveConcept(concept: SemanticConcept, synonyms: List<String>) = writeCoordinator.withWritePermit {
        database.withTransaction {
            require(concept.label.isNotBlank()) { "Le libellé est obligatoire." }
            val existingRow = dao.findConcept(concept.id)
            val existing = existingRow?.toDomain()
            require(existing == null || existing.domain == concept.domain) { "Le domaine d’un concept existant ne peut pas être modifié." }
            if (existing != null && BuiltInSemanticConceptIds.protected.contains(existing.id)) {
                require(existing.businessDimension == concept.businessDimension && existing.businessConcept == concept.businessConcept && existing.businessValue == concept.businessValue) {
                    "La liaison interne de ce concept système ne peut pas être modifiée."
                }
                require(concept.active) { "Ce concept système doit rester actif." }
            } else SemanticDictionaryValidator.validateConceptMapping(concept)

            val normalizedForms = SemanticDictionaryValidator.normalizedExpressions(concept.label, synonyms)
            normalizedForms.forEach { form ->
                val conflict = dao.findExpressionConflict(concept.domain.name, form, concept.id)
                require(conflict == null) {
                    "« ${conflict?.expression} » est déjà associé au concept « ${conflict?.label} »."
                }
            }
            validateReferenceLink(concept, existing)

            val existingExpressions = existing?.let { dao.expressionsForConcept(concept.id) }.orEmpty()
            val primaryId = existingExpressions.firstOrNull(SemanticExpressionEntity::isPrimary)?.id ?: UUID.randomUUID().toString()
            val forms = listOf(concept.label.trim()) + synonyms.map(String::trim).filter(String::isNotBlank)
            val oldIdsByForm = existingExpressions.associate { it.normalizedForm to it.id }
            val expressionRows = forms.mapIndexed { index, form ->
                val normalized = normalizedForms[index]
                SemanticExpressionEntity(
                    id = if (index == 0) primaryId else oldIdsByForm[normalized] ?: UUID.randomUUID().toString(),
                    conceptId = concept.id,
                    expression = form,
                    normalizedForm = normalized,
                    isPrimary = index == 0,
                    active = concept.active,
                )
            }
            if (existing == null) dao.insertConcept(concept.toEntity()) else dao.updateConcept(concept.toEntity())
            val desiredIds = expressionRows.map(SemanticExpressionEntity::id).toSet()
            val oldById = existingExpressions.associateBy(SemanticExpressionEntity::id)
            val toInsert = expressionRows.filter { it.id !in oldById }
            for (row in expressionRows.filter { it.id in oldById }) dao.updateExpression(row)
            if (toInsert.isNotEmpty()) dao.insertExpressions(toInsert)
            existingExpressions.filter { it.expression.isNotEmpty() && it.id !in desiredIds }.forEach { row ->
                if (row.id in BuiltInSemanticDictionary.seededExpressionIds) dao.tombstoneExpression(row.id)
                else dao.deleteExpression(row.id)
            }
        }
    }

    suspend fun setConceptActive(id: String, active: Boolean) = writeCoordinator.withWritePermit {
        database.withTransaction {
            val current = requireNotNull(dao.findConcept(id)) { "Ce concept n’existe plus." }.toDomain()
            require(active || current.id !in BuiltInSemanticConceptIds.protected) { "Ce concept système ne peut pas être désactivé." }
            dao.updateConcept(current.copy(active = active).toEntity())
            dao.expressionsForConcept(id).filter { it.expression.isNotEmpty() }.forEach { expression -> dao.updateExpression(expression.copy(active = active)) }
        }
    }

    data class DeleteImpact(val label: String, val synonymCount: Int, val relationCount: Int, val protected: Boolean)

    suspend fun deleteImpact(id: String): DeleteImpact {
        val concept = requireNotNull(dao.findConcept(id)) { "Ce concept n’existe plus." }.toDomain()
        return DeleteImpact(concept.label, dao.countExpressions(id), dao.countRelations(id), concept.id in BuiltInSemanticConceptIds.protected)
    }

    suspend fun deleteConcept(id: String) = writeCoordinator.withWritePermit {
        database.withTransaction {
            val concept = requireNotNull(dao.findConcept(id)) { "Ce concept n’existe plus." }.toDomain()
            require(concept.id !in BuiltInSemanticConceptIds.protected) { "Ce concept système ne peut pas être supprimé." }
            dao.relationsForConcept(id).filter(SemanticRelationEntity::active).forEach { relation ->
                if (relation.id in BuiltInSemanticDictionary.seededRelationIds) dao.deactivateRelation(relation.id)
                else dao.deleteRelation(relation.id)
            }
            val expressions = dao.expressionsForConcept(id)
            expressions.filter { it.expression.isNotEmpty() }.forEach { expression ->
                if (expression.id in BuiltInSemanticDictionary.seededExpressionIds) dao.tombstoneExpression(expression.id)
                else dao.deleteExpression(expression.id)
            }
            if (id in BuiltInSemanticDictionary.seededConceptIds) {
                require(dao.markConceptDeleted(id) == 1) { "Ce concept n’existe plus." }
            } else {
                require(dao.deleteConcept(id) == 1) { "Ce concept n’existe plus." }
            }
        }
    }

    suspend fun saveExpression(expression: SemanticExpression) = writeCoordinator.withWritePermit {
        database.withTransaction {
            require(dao.findConcept(expression.conceptId) != null) { "Le concept de cette expression n’existe pas." }
            val normalized = MadjoSemanticResolver.normalize(expression.expression)
            require(normalized.isNotBlank()) { "L’expression ne peut pas être vide." }
            require(dao.findExpressionByForm(expression.conceptId, normalized)?.id in setOf(null, expression.id)) {
                "Cette expression existe déjà pour ce concept."
            }
            val row = SemanticExpressionEntity(expression.id, expression.conceptId, expression.expression.trim(), normalized, expression.isPrimary, expression.active)
            if (dao.findExpression(expression.id) == null) dao.insertSeedExpressions(listOf(row)) else dao.updateExpression(row)
        }
    }

    suspend fun createSpecialization(sourceId: String, targetId: String) = writeCoordinator.withWritePermit {
        database.withTransaction {
            val concepts = dao.allConcepts().mapNotNull { row -> runCatching { row.toDomain() }.getOrNull() }.associateBy(SemanticConcept::id)
            val source = requireNotNull(concepts[sourceId]) { "Le concept général est introuvable." }
            val target = requireNotNull(concepts[targetId]) { "Le concept spécialisé est introuvable." }
            require(source.active && target.active) { "Les deux concepts doivent être actifs." }
            val relations = dao.allRelations().mapNotNull { row -> runCatching { row.toDomain() }.getOrNull() }
            SemanticDictionaryValidator.validateSpecialization(source, target, relations)
            val existing = dao.findRelation(sourceId, targetId, SemanticRelationType.SPECIALIZATION.name)
            require(existing == null || !existing.active) { "Cette relation existe déjà." }
            val row = SemanticRelationEntity(
                BuiltInSemanticDictionary.stableId("semantic-relation", "$sourceId:$targetId:SPECIALIZATION"),
                sourceId, targetId, SemanticRelationType.SPECIALIZATION.name, true,
            )
            if (existing == null) dao.insertRelation(row) else dao.updateRelation(existing.copy(active = true))
        }
    }

    suspend fun deleteSpecialization(id: String) = writeCoordinator.withWritePermit {
        database.withTransaction {
            if (id in BuiltInSemanticDictionary.seededRelationIds) require(dao.deactivateRelation(id) == 1) { "Cette relation n’existe plus." }
            else require(dao.deleteRelation(id) == 1) { "Cette relation n’existe plus." }
        }
    }

    private suspend fun validateReferenceLink(concept: SemanticConcept, existing: SemanticConcept?) {
        val value = concept.businessValue ?: return
        if (concept.domain in setOf(SemanticDomain.TASTE_RATING, SemanticDomain.STOCK, SemanticDomain.TASTING_HISTORY)) return
        if (!value.startsWith("ref:", ignoreCase = true)) {
            require(concept.id in BuiltInSemanticDictionary.seededConceptIds && existing != null &&
                existing.businessDimension == concept.businessDimension && existing.businessConcept == concept.businessConcept && existing.businessValue == value) {
                "Choisissez une référence existante dans ThésDuPalais."
            }
            return
        }
        val id = runCatching { UUID.fromString(value.substringAfter(':')) }.getOrElse { throw IllegalArgumentException("La référence métier sélectionnée n’est pas valide.") }
        val reference = requireNotNull(database.referenceDao().findById(id)) { "La référence métier sélectionnée n’existe plus." }
        require(SemanticDictionaryValidator.isReferenceKindAllowed(concept.domain, reference.kind) && reference.archivedAt == null) {
            "Cette référence n’est plus disponible dans le domaine du concept."
        }
    }

    private fun SemanticConcept.toEntity() = SemanticConceptEntity(id, domain.name, label, active, businessDimension, businessConcept, businessValue)
    private fun SemanticConceptEntity.toDomain() = SemanticConcept(id, SemanticDomain.valueOf(domain), label, active, businessDimension, businessConcept, businessValue)
    private fun SemanticRelationEntity.toDomain() = SemanticRelation(id, sourceConceptId, targetConceptId, SemanticRelationType.valueOf(type), active)
}
