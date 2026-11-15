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

    val snapshot: Flow<SemanticDictionarySnapshot> = combine(
        dao.observeConcepts(), dao.observeExpressions(), dao.observeRelations(),
    ) { concepts, expressions, relations ->
        SemanticDictionarySnapshot(
            concepts.mapNotNull { row -> runCatching { SemanticConcept(row.id, SemanticDomain.valueOf(row.domain), row.label, row.active, row.businessDimension, row.businessConcept, row.businessValue) }.getOrNull() },
            expressions.map { SemanticExpression(it.id, it.conceptId, it.expression, it.normalizedForm, it.isPrimary, it.active) },
            relations.mapNotNull { row -> runCatching { SemanticRelation(row.id, row.sourceConceptId, row.targetConceptId, SemanticRelationType.valueOf(row.type), row.active) }.getOrNull() },
        )
    }

    suspend fun saveConcept(concept: SemanticConcept) = writeCoordinator.withWritePermit {
        database.withTransaction {
            val row = concept.toEntity()
            if (dao.findConcept(concept.id) == null) dao.insertSeedConcepts(listOf(row)) else dao.updateConcept(row)
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
            val relations = dao.allRelations().mapNotNull { row -> runCatching { row.toDomain() }.getOrNull() }
            SemanticDictionaryValidator.validateSpecialization(source, target, relations)
            dao.insertRelation(SemanticRelationEntity(
                BuiltInSemanticDictionary.stableId("semantic-relation", "$sourceId:$targetId:SPECIALIZATION"),
                sourceId, targetId, SemanticRelationType.SPECIALIZATION.name, true,
            ))
        }
    }

    private fun SemanticConcept.toEntity() = SemanticConceptEntity(id, domain.name, label, active, businessDimension, businessConcept, businessValue)
    private fun SemanticConceptEntity.toDomain() = SemanticConcept(id, SemanticDomain.valueOf(domain), label, active, businessDimension, businessConcept, businessValue)
    private fun SemanticRelationEntity.toDomain() = SemanticRelation(id, sourceConceptId, targetConceptId, SemanticRelationType.valueOf(type), active)
}
