package fr.thesdupalais.domain.semantics

import java.text.Normalizer
import java.util.Locale

enum class SemanticDomain { AROMA, AROMATIC_PROFILE, CATEGORY, ORIGIN, MOMENT, TASTE_RATING, STOCK, TASTING_HISTORY }
enum class SemanticMatchType { PRIMARY_LABEL, SYNONYM }
enum class SemanticRelationType { SPECIALIZATION }
enum class SemanticAssociationStrength(val weightPercent: Int, val label: String) {
    LOW(25, "Faible"), MEDIUM(50, "Moyenne"), HIGH(75, "Forte");

    companion object {
        fun fromWeight(weightPercent: Int): SemanticAssociationStrength = entries.firstOrNull { it.weightPercent == weightPercent }
            ?: throw IllegalArgumentException("Le poids doit être Faible (25 %), Moyen (50 %) ou Fort (75 %).")
    }
}

data class SemanticConcept(
    val id: String,
    val domain: SemanticDomain,
    val label: String,
    val active: Boolean = true,
    val businessDimension: String? = null,
    val businessConcept: String? = null,
    val businessValue: String? = null,
)

data class SemanticExpression(
    val id: String,
    val conceptId: String,
    val expression: String,
    val normalizedForm: String,
    val isPrimary: Boolean,
    val active: Boolean = true,
)

data class SemanticRelation(
    val id: String,
    val sourceConceptId: String,
    val targetConceptId: String,
    val type: SemanticRelationType,
    val active: Boolean = true,
)

data class SemanticAssociation(
    val id: String,
    val sourceConceptId: String,
    val targetConceptId: String,
    val weightPercent: Int = SemanticAssociationStrength.MEDIUM.weightPercent,
    val active: Boolean = true,
)

data class SemanticDictionarySnapshot(
    val concepts: List<SemanticConcept> = emptyList(),
    val expressions: List<SemanticExpression> = emptyList(),
    val relations: List<SemanticRelation> = emptyList(),
    val associations: List<SemanticAssociation> = emptyList(),
)

data class SemanticMatch(
    val concept: SemanticConcept,
    val matchedExpression: String,
    val normalizedExpression: String,
    val matchType: SemanticMatchType,
    val relationType: SemanticRelationType? = null,
)

data class SemanticAmbiguity(
    val domain: SemanticDomain,
    val phrase: String,
    val concepts: List<SemanticConcept>,
)

data class SemanticResolution(
    val matches: List<SemanticMatch>,
    val ambiguities: List<SemanticAmbiguity>,
)

/** Local and deterministic phrase resolver. It returns concepts and provenance, never rewritten text. */
object MadjoSemanticResolver {
    private data class Hit(val start: Int, val end: Int, val expression: SemanticExpression, val concept: SemanticConcept)

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun resolve(text: String, dictionary: SemanticDictionarySnapshot): SemanticResolution {
        val normalizedText = normalize(text)
        if (normalizedText.isBlank()) return SemanticResolution(emptyList(), emptyList())
        val concepts = dictionary.concepts.filter(SemanticConcept::active).associateBy(SemanticConcept::id)
        val hits = dictionary.expressions.asSequence()
            .filter(SemanticExpression::active)
            .mapNotNull { expression ->
                val concept = concepts[expression.conceptId] ?: return@mapNotNull null
                val normalized = expression.normalizedForm.ifBlank { normalize(expression.expression) }
                if (normalized.isBlank()) return@mapNotNull null
                val pattern = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(normalized)}(?![\\p{L}\\p{N}])")
                pattern.findAll(normalizedText).map { Hit(it.range.first, it.range.last + 1, expression, concept) }.toList()
            }
            .flatten()
            .groupBy { it.concept.domain }

        val matches = mutableListOf<SemanticMatch>()
        val ambiguities = mutableListOf<SemanticAmbiguity>()
        SemanticDomain.entries.forEach { domain ->
            val accepted = mutableListOf<Hit>()
            val domainHits = hits[domain].orEmpty().sortedWith(compareBy<Hit> { it.start }.thenByDescending { it.end - it.start })
            var index = 0
            while (index < domainHits.size) {
                val first = domainHits[index]
                val sameStart = domainHits.drop(index).takeWhile { it.start == first.start }
                val longestLength = sameStart.maxOf { it.end - it.start }
                val longest = sameStart.filter { it.end - it.start == longestLength }
                val byConcept = longest.distinctBy { it.concept.id }
                if (byConcept.size > 1) {
                    ambiguities += SemanticAmbiguity(domain, longest.first().expression.expression, byConcept.map(Hit::concept))
                } else if (accepted.none { overlaps(it, longest.first()) }) {
                    accepted += longest.first()
                }
                index += sameStart.size
            }

            val direct = accepted.distinctBy { it.concept.id to it.expression.normalizedForm }
            direct.forEach { hit ->
                matches += hit.toSemanticMatch()
                // Exactly one specialization edge is considered; grandchildren are intentionally ignored.
                dictionary.relations.asSequence()
                    .filter { it.active && it.type == SemanticRelationType.SPECIALIZATION && it.sourceConceptId == hit.concept.id }
                    .mapNotNull { relation -> concepts[relation.targetConceptId]?.takeIf { it.domain == domain }?.let { relation to it } }
                    .forEach { (relation, child) ->
                        val childExpression = dictionary.expressions.firstOrNull { it.active && it.conceptId == child.id && it.isPrimary }
                        matches += SemanticMatch(
                            concept = child,
                            matchedExpression = hit.expression.expression,
                            normalizedExpression = hit.expression.normalizedForm,
                            matchType = hit.expression.matchType(),
                            relationType = relation.type,
                        ).let { if (childExpression == null) it else it.copy(concept = child) }
                    }
            }
        }
        return SemanticResolution(matches.distinctBy { Triple(it.concept.id, it.normalizedExpression, it.relationType) }, ambiguities)
    }

    private fun overlaps(left: Hit, right: Hit): Boolean = left.start < right.end && right.start < left.end

    private fun Hit.toSemanticMatch() = SemanticMatch(
        concept = concept,
        matchedExpression = expression.expression,
        normalizedExpression = expression.normalizedForm.ifBlank { normalize(expression.expression) },
        matchType = expression.matchType(),
    )

    private fun SemanticExpression.matchType() = if (isPrimary) SemanticMatchType.PRIMARY_LABEL else SemanticMatchType.SYNONYM
}

/** Validation is separate from resolving: it may inspect a graph, while resolution never traverses it. */
object SemanticDictionaryValidator {
    private val internalIntentDomains = setOf(SemanticDomain.TASTE_RATING, SemanticDomain.STOCK, SemanticDomain.TASTING_HISTORY)

    fun validateConceptMapping(concept: SemanticConcept) {
        val parts = listOf(concept.businessDimension, concept.businessConcept, concept.businessValue)
        require(parts.all { it == null } || parts.all { !it.isNullOrBlank() }) {
            "La liaison métier doit être complète ou vide."
        }
        if (parts.all { it == null }) return
        require(concept.domain !in internalIntentDomains) {
            "Les liaisons internes de ce domaine sont réservées aux intentions Madjo."
        }
        val valid = when (concept.domain) {
            SemanticDomain.AROMA -> concept.businessDimension == "AROMA" && concept.businessConcept == "aroma"
            SemanticDomain.AROMATIC_PROFILE -> concept.businessDimension == "AROMA" && concept.businessConcept == "aromatic_profile"
            SemanticDomain.CATEGORY -> concept.businessDimension == "TYPE_COLOR" && concept.businessConcept == "category"
            SemanticDomain.ORIGIN -> concept.businessDimension == "ORIGIN" && concept.businessConcept in setOf("country", "region")
            SemanticDomain.MOMENT -> concept.businessDimension == "MOMENT" && concept.businessConcept == "moment"
            SemanticDomain.TASTE_RATING, SemanticDomain.STOCK, SemanticDomain.TASTING_HISTORY -> false
        }
        require(valid) { "Cette liaison métier n’est pas compatible avec le domaine choisi." }
    }

    fun isReferenceKindAllowed(domain: SemanticDomain, referenceKind: String): Boolean = when (domain) {
        SemanticDomain.AROMA -> referenceKind == "AROMA"
        SemanticDomain.AROMATIC_PROFILE -> referenceKind == "AROMATIC_PROFILE"
        SemanticDomain.CATEGORY -> referenceKind == "CATEGORY"
        SemanticDomain.ORIGIN -> referenceKind == "COUNTRY" || referenceKind == "REGION"
        SemanticDomain.MOMENT -> referenceKind == "MOMENT"
        SemanticDomain.TASTE_RATING, SemanticDomain.STOCK, SemanticDomain.TASTING_HISTORY -> false
    }

    fun validateAssociation(source: SemanticConcept, target: SemanticConcept, weightPercent: Int, existing: List<SemanticAssociation>, editingId: String? = null) {
        require(source.id != target.id) { "Un concept ne peut pas être associé à lui-même." }
        require(source.active && target.active) { "Les deux concepts doivent être actifs." }
        require(target.businessDimension != null && target.businessConcept != null && target.businessValue != null) {
            "Le concept associé doit désigner une caractéristique réelle de ThésDuPalais."
        }
        require(associationDimension(source.domain) != null && associationDimension(source.domain) == associationDimension(target.domain)) {
            "Ces domaines ne sont pas compatibles pour une association souple."
        }
        SemanticAssociationStrength.fromWeight(weightPercent)
        require(existing.none { it.id != editingId && it.sourceConceptId == source.id && it.targetConceptId == target.id }) {
            "Cette association existe déjà dans cette direction."
        }
    }

    fun associationDimension(domain: SemanticDomain): String? = when (domain) {
        SemanticDomain.AROMA, SemanticDomain.AROMATIC_PROFILE -> "AROMA"
        SemanticDomain.CATEGORY -> "TYPE_COLOR"
        SemanticDomain.ORIGIN -> "ORIGIN"
        SemanticDomain.MOMENT -> "MOMENT"
        SemanticDomain.TASTE_RATING, SemanticDomain.STOCK, SemanticDomain.TASTING_HISTORY -> null
    }

    fun normalizedExpressions(label: String, synonyms: List<String>): List<String> {
        require(label.isNotBlank()) { "Le libellé est obligatoire." }
        val forms = listOf(label.trim()) + synonyms.map(String::trim).filter(String::isNotBlank)
        val normalized = forms.map(MadjoSemanticResolver::normalize)
        require(normalized.all(String::isNotBlank)) { "Les expressions ne peuvent pas être vides." }
        require(normalized.distinct().size == normalized.size) {
            "Le libellé ou un synonyme est présent plusieurs fois après normalisation."
        }
        return normalized
    }

    fun checkSynonymConflicts(domain: SemanticDomain, forms: List<String>, dictionary: SemanticDictionarySnapshot, excludingConceptId: String? = null) {
        val normalizedForms = forms.map(MadjoSemanticResolver::normalize)
        val conceptsById = dictionary.concepts.associateBy(SemanticConcept::id)
        normalizedForms.forEach { form ->
            val conflict = dictionary.expressions.firstOrNull { expression ->
                expression.expression.isNotBlank() && MadjoSemanticResolver.normalize(expression.normalizedForm.ifBlank { expression.expression }) == form &&
                    conceptsById[expression.conceptId]?.let { it.domain == domain && it.id != excludingConceptId } == true
            }
            if (conflict != null) {
                val owner = conceptsById[conflict.conceptId]?.label ?: "un autre concept"
                throw IllegalArgumentException("« ${conflict.expression} » est déjà associé au concept « $owner ».")
            }
        }
    }

    fun isProtectedSystemConcept(concept: SemanticConcept): Boolean =
        BuiltInSemanticConceptIds.protected.contains(concept.id)

    fun validateSpecialization(source: SemanticConcept, target: SemanticConcept, existing: List<SemanticRelation>) {
        require(source.id != target.id) { "Un concept ne peut pas se spécialiser lui-même." }
        require(source.domain == target.domain) { "Une spécialisation doit rester dans le même domaine." }
        require(source.businessDimension == null || target.businessDimension == null || source.businessDimension == target.businessDimension) {
            "Une spécialisation doit conserver le même domaine métier."
        }
        require(source.businessConcept == null || target.businessConcept == null || source.businessConcept == target.businessConcept) {
            "Une spécialisation doit conserver le même type de référence métier."
        }
        val adjacency = existing.asSequence().filter { it.active && it.type == SemanticRelationType.SPECIALIZATION }
            .groupBy(SemanticRelation::sourceConceptId, SemanticRelation::targetConceptId)
        val pending = ArrayDeque<String>().apply { add(target.id) }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            require(current != source.id) { "Cette relation créerait un cycle de spécialisation." }
            if (visited.add(current)) adjacency[current].orEmpty().forEach(pending::addLast)
        }
    }
}

/** Stable IDs of intent concepts whose business meaning is wired directly into Madjo. */
object BuiltInSemanticConceptIds {
    val protected: Set<String> = setOf(
        "top-rated" to SemanticDomain.TASTE_RATING,
        "available-stock" to SemanticDomain.STOCK,
        "abundant-stock" to SemanticDomain.STOCK,
        "frequent-tasting" to SemanticDomain.TASTING_HISTORY,
        "forgotten-tasting" to SemanticDomain.TASTING_HISTORY,
    ).map { (key, domain) ->
        java.util.UUID.nameUUIDFromBytes("semantic-concept-${domain.name.lowercase()}.$key".toByteArray(Charsets.UTF_8)).toString()
    }.toSet()
}
