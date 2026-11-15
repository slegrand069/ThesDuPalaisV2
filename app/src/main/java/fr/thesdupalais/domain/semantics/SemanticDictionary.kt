package fr.thesdupalais.domain.semantics

import java.text.Normalizer
import java.util.Locale

enum class SemanticDomain { AROMA, AROMATIC_PROFILE, CATEGORY, ORIGIN, MOMENT, TASTE_RATING, STOCK, TASTING_HISTORY }
enum class SemanticMatchType { PRIMARY_LABEL, SYNONYM }
enum class SemanticRelationType { SPECIALIZATION }

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

data class SemanticDictionarySnapshot(
    val concepts: List<SemanticConcept> = emptyList(),
    val expressions: List<SemanticExpression> = emptyList(),
    val relations: List<SemanticRelation> = emptyList(),
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
