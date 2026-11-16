package fr.thesdupalais.data.database

import fr.thesdupalais.domain.semantics.MadjoSemanticResolver
import fr.thesdupalais.domain.semantics.SemanticDomain
import fr.thesdupalais.domain.semantics.SemanticConcept
import fr.thesdupalais.domain.semantics.SemanticDictionaryValidator
import fr.thesdupalais.domain.semantics.BuiltInSemanticConceptIds
import fr.thesdupalais.domain.semantics.SemanticAssociation
import org.json.JSONArray
import org.json.JSONObject

/** Versioned, standalone JSON representation. Rows (including tombstones) are intentionally exported in full. */
data class SemanticDictionaryArchive(
    val concepts: List<SemanticConceptEntity>,
    val expressions: List<SemanticExpressionEntity>,
    val relations: List<SemanticRelationEntity>,
    val associations: List<SemanticAssociationEntity> = emptyList(),
) {
    val synonymCount: Int get() = expressions.count { !it.isPrimary && it.expression.isNotBlank() }
    val inactiveConceptCount: Int get() = concepts.count { !it.active && !it.deleted }
    val tombstoneCount: Int get() = concepts.count { it.deleted }
    val relationCount: Int get() = relations.size
    val associationCount: Int get() = associations.size
}

object SemanticDictionaryArchiveJson {
    const val FORMAT = "thes-du-palais-madjo-dictionary"
    const val VERSION = 2
    const val LEGACY_VERSION = 1

    fun encode(archive: SemanticDictionaryArchive): String {
        val concepts = archive.concepts.sortedBy(SemanticConceptEntity::id).joinToString(",\n") { row ->
            "    {\"id\":${quote(row.id)},\"domain\":${quote(row.domain)},\"label\":${quote(row.label)},\"active\":${row.active},\"deleted\":${row.deleted},\"businessDimension\":${nullable(row.businessDimension)},\"businessConcept\":${nullable(row.businessConcept)},\"businessValue\":${nullable(row.businessValue)}}"
        }
        val expressions = archive.expressions.sortedWith(compareBy<SemanticExpressionEntity> { it.conceptId }.thenBy { it.id }).joinToString(",\n") { row ->
            "    {\"id\":${quote(row.id)},\"conceptId\":${quote(row.conceptId)},\"expression\":${quote(row.expression)},\"normalizedForm\":${quote(row.normalizedForm)},\"isPrimary\":${row.isPrimary},\"active\":${row.active}}"
        }
        val relations = archive.relations.sortedWith(compareBy<SemanticRelationEntity> { it.sourceConceptId }.thenBy { it.targetConceptId }.thenBy { it.type }.thenBy { it.id }).joinToString(",\n") { row ->
            "    {\"id\":${quote(row.id)},\"sourceConceptId\":${quote(row.sourceConceptId)},\"targetConceptId\":${quote(row.targetConceptId)},\"type\":${quote(row.type)},\"active\":${row.active}}"
        }
        val associations = archive.associations.sortedWith(compareBy<SemanticAssociationEntity> { it.sourceConceptId }.thenBy { it.targetConceptId }.thenBy { it.id }).joinToString(",\n") { row ->
            "    {\"id\":${quote(row.id)},\"sourceConceptId\":${quote(row.sourceConceptId)},\"targetConceptId\":${quote(row.targetConceptId)},\"weightPercent\":${row.weightPercent},\"active\":${row.active}}"
        }
        return """
            {
              "format": ${quote(FORMAT)},
              "version": $VERSION,
              "concepts": [${if (concepts.isEmpty()) "" else "\n$concepts\n  "}],
              "expressions": [${if (expressions.isEmpty()) "" else "\n$expressions\n  "}],
              "relations": [${if (relations.isEmpty()) "" else "\n$relations\n  "}],
              "associations": [${if (associations.isEmpty()) "" else "\n$associations\n  "}]
            }
        """.trimIndent() + "\n"
    }

    fun decode(json: String): SemanticDictionaryArchive {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "Ce fichier n’est pas une sauvegarde de dictionnaire Madjo reconnue." }
        val version = root.optInt("version", -1)
        require(version == VERSION || version == LEGACY_VERSION) { "Version de dictionnaire non prise en charge : $version." }
        require(root.has("concepts") && root.has("expressions") && root.has("relations")) { "Le fichier est incomplet." }
        require(version != LEGACY_VERSION || !root.has("associations")) { "Un fichier v1 ne peut pas contenir de relations souples." }
        require(version != VERSION || root.has("associations")) { "Le fichier v2 est incomplet : associations manquantes." }
        val archive = SemanticDictionaryArchive(
            concepts = root.getJSONArray("concepts").toRows { row -> SemanticConceptEntity(
                id = row.getString("id"), domain = row.getString("domain"), label = row.getString("label"),
                active = row.getBoolean("active"), businessDimension = row.nullableString("businessDimension"),
                businessConcept = row.nullableString("businessConcept"), businessValue = row.nullableString("businessValue"),
                deleted = row.getBoolean("deleted"),
            ) },
            expressions = root.getJSONArray("expressions").toRows { row -> SemanticExpressionEntity(
                id = row.getString("id"), conceptId = row.getString("conceptId"), expression = row.getString("expression"),
                normalizedForm = row.getString("normalizedForm"), isPrimary = row.getBoolean("isPrimary"), active = row.getBoolean("active"),
            ) },
            relations = root.getJSONArray("relations").toRows { row -> SemanticRelationEntity(
                id = row.getString("id"), sourceConceptId = row.getString("sourceConceptId"), targetConceptId = row.getString("targetConceptId"),
                type = row.getString("type"), active = row.getBoolean("active"),
            ) },
            associations = if (version == LEGACY_VERSION) emptyList() else root.getJSONArray("associations").toRows { row -> SemanticAssociationEntity(
                id = row.getString("id"), sourceConceptId = row.getString("sourceConceptId"), targetConceptId = row.getString("targetConceptId"),
                weightPercent = row.getInt("weightPercent"), active = row.getBoolean("active"),
            ) },
        )
        validateStructure(archive)
        return archive
    }

    private fun validateStructure(archive: SemanticDictionaryArchive) {
        fun uniqueIds(ids: List<String>, label: String) { require(ids.distinct().size == ids.size && ids.none(String::isBlank)) { "Identifiants $label manquants ou dupliqués." } }
        uniqueIds(archive.concepts.map { it.id }, "de concepts")
        uniqueIds(archive.expressions.map { it.id }, "d’expressions")
        uniqueIds(archive.relations.map { it.id }, "de relations")
        uniqueIds(archive.associations.map { it.id }, "d’associations")
        val concepts = archive.concepts.associateBy { it.id }
        archive.concepts.forEach { row ->
            require(runCatching { SemanticDomain.valueOf(row.domain) }.isSuccess) { "Domaine inconnu pour « ${row.label} »." }
            require(row.label.isNotBlank() || row.deleted) { "Un concept actif doit avoir un libellé." }
            require(!row.deleted || !row.active) { "Un concept supprimé logiquement ne peut pas être actif." }
        }
        val expressionsByConcept = archive.expressions.groupBy { it.conceptId }
        archive.expressions.forEach { row ->
            require(row.conceptId in concepts) { "Une expression pointe vers un concept absent." }
            val concept = concepts.getValue(row.conceptId)
            require(row.expression.isNotBlank() || (row.normalizedForm.isNotBlank() && !row.active && (concept.deleted || !row.isPrimary))) {
                "Expression vide invalide pour « ${concept.label} »."
            }
            if (row.expression.isNotBlank()) require(MadjoSemanticResolver.normalize(row.expression) == row.normalizedForm) {
                "La forme normalisée d’une expression ne correspond pas à son texte."
            }
            require(!row.active || (concept.active && !concept.deleted)) { "Une expression active appartient à un concept inactif." }
        }
        archive.concepts.filterNot(SemanticConceptEntity::deleted).forEach { concept ->
            require(expressionsByConcept[concept.id].orEmpty().count(SemanticExpressionEntity::isPrimary) == 1) {
                "Le concept « ${concept.label} » doit posséder exactement un libellé principal."
            }
        }
        val expressionForms = mutableSetOf<Pair<String, String>>()
        archive.expressions.filter { it.expression.isNotBlank() }.forEach { row ->
            val domain = concepts.getValue(row.conceptId).domain
            require(expressionForms.add(domain to row.normalizedForm)) { "Conflit d’expression normalisée : « ${row.expression} »." }
        }
        archive.relations.forEach { relation ->
            require(relation.sourceConceptId in concepts && relation.targetConceptId in concepts) { "Une relation pointe vers un concept absent." }
            require(relation.sourceConceptId != relation.targetConceptId) { "Une auto-relation est interdite." }
            require(relation.type == "SPECIALIZATION") { "Type de relation non pris en charge : ${relation.type}." }
            val source = concepts.getValue(relation.sourceConceptId)
            val target = concepts.getValue(relation.targetConceptId)
            require(source.domain == target.domain && (source.businessDimension == null || target.businessDimension == null || source.businessDimension == target.businessDimension) &&
                (source.businessConcept == null || target.businessConcept == null || source.businessConcept == target.businessConcept)) {
                "Relation incohérente entre « ${source.label} » et « ${target.label} »."
            }
        }
        val associationPairs = mutableSetOf<Pair<String, String>>()
        archive.associations.forEach { association ->
            require(association.sourceConceptId in concepts && association.targetConceptId in concepts) { "Une association pointe vers un concept absent." }
            val pair = association.sourceConceptId to association.targetConceptId
            require(pair !in associationPairs) { "Une association directionnelle est dupliquée." }
            associationPairs += pair
            require(association.weightPercent in setOf(25, 50, 75)) { "Poids d’association invalide : ${association.weightPercent}." }
            val source = concepts.getValue(association.sourceConceptId)
            val target = concepts.getValue(association.targetConceptId)
            SemanticDictionaryValidator.validateAssociation(
                SemanticConcept(source.id, SemanticDomain.valueOf(source.domain), source.label, true, source.businessDimension, source.businessConcept, source.businessValue),
                SemanticConcept(target.id, SemanticDomain.valueOf(target.domain), target.label, true, target.businessDimension, target.businessConcept, target.businessValue),
                association.weightPercent,
                emptyList(),
            )
        }
        val activeEdges = archive.relations.filter(SemanticRelationEntity::active).groupBy(SemanticRelationEntity::sourceConceptId, SemanticRelationEntity::targetConceptId)
        val pairs = archive.relations.map { Triple(it.sourceConceptId, it.targetConceptId, it.type) }
        require(pairs.distinct().size == pairs.size) { "Le fichier contient des relations dupliquées." }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visiting) throw IllegalArgumentException("Le dictionnaire contient un cycle de spécialisation.")
            if (id in visited) return
            visited.add(id)
            visiting.add(id)
            activeEdges[id].orEmpty().forEach(::visit)
            visiting.remove(id)
        }
        archive.concepts.forEach { visit(it.id) }
    }

    private fun quote(value: String): String = JSONObject.quote(value)
    private fun nullable(value: String?): String = value?.let(::quote) ?: "null"
    private fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    private inline fun <T> JSONArray.toRows(mapper: (JSONObject) -> T): List<T> = (0 until length()).map { mapper(getJSONObject(it)) }
}

/** Shared semantic/business-link checks used by both import and the repository's transactional apply. */
object SemanticDictionaryArchiveRules {
    fun validateBusinessLinks(archive: SemanticDictionaryArchive, availableReferences: Map<String, String>) {
        val rows = archive.concepts.associateBy(SemanticConceptEntity::id)
        require(BuiltInSemanticDictionary.seededConceptIds.all(rows::containsKey)) {
            "Cette sauvegarde ne contient pas l’ensemble des concepts initiaux nécessaires à Madjo."
        }
        val seeds = BuiltInSemanticDictionary.conceptRows.associateBy(SemanticConceptEntity::id)
        BuiltInSemanticDictionary.protectedConceptIds.forEach { id ->
            val row = requireNotNull(rows[id]) { "Une intention système indispensable est absente." }
            val seed = seeds.getValue(id)
            require(!row.deleted && row.active && row.domain == seed.domain && row.businessDimension == seed.businessDimension &&
                row.businessConcept == seed.businessConcept && row.businessValue == seed.businessValue) {
                "Le concept système « ${seed.label} » est incomplet ou altéré."
            }
        }
        archive.concepts.forEach { row ->
            val concept = SemanticConcept(row.id, SemanticDomain.valueOf(row.domain), row.label, row.active, row.businessDimension, row.businessConcept, row.businessValue)
            val seed = seeds[row.id]
            if (row.id in BuiltInSemanticConceptIds.protected) {
                require(seed != null && concept.businessDimension == seed.businessDimension && concept.businessConcept == seed.businessConcept && concept.businessValue == seed.businessValue)
            } else SemanticDictionaryValidator.validateConceptMapping(concept)
            val value = concept.businessValue ?: return@forEach
            if (value.startsWith("ref:", ignoreCase = true)) {
                val id = runCatching { java.util.UUID.fromString(value.substringAfter(':')).toString() }
                    .getOrElse { throw IllegalArgumentException("La référence métier ${value.substringAfter(':')} n’est pas un identifiant valide.") }
                val kind = requireNotNull(availableReferences[id]) { "Référence métier introuvable ou archivée : $id." }
                require(SemanticDictionaryValidator.isReferenceKindAllowed(concept.domain, kind)) {
                    "La référence métier $id n’est pas compatible avec le domaine ${concept.domain}."
                }
            } else {
                require(seed != null && concept.businessDimension == seed.businessDimension && concept.businessConcept == seed.businessConcept && concept.businessValue == seed.businessValue) {
                    "Liaison métier interne inconnue pour « ${concept.label} »."
                }
            }
        }
    }
}
