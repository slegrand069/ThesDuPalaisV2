package fr.thesdupalais.domain.madjo

import java.text.Normalizer
import java.util.Locale

enum class MadjoStockState { AVAILABLE, OUT_OF_STOCK, UNKNOWN }

/** Read-only snapshot of the fields M3.2 is allowed to use from one collection item. */
data class MadjoTeaProfile(
    val id: String,
    val name: String,
    val categoryId: String,
    val categoryKey: String?,
    val categoryLabel: String,
    val countries: List<MadjoReference> = emptyList(),
    val regions: List<MadjoReference> = emptyList(),
    val aromas: List<MadjoReference> = emptyList(),
    val aromaticProfiles: List<MadjoReference> = emptyList(),
    val moments: List<MadjoReference> = emptyList(),
    val tasteRating: Double? = null,
    val stockMilliGrams: Long? = null,
    val tastingCount: Int = 0,
    val lastTastedAtEpochMillis: Long? = null,
) {
    val stockState: MadjoStockState get() = when {
        stockMilliGrams == null -> MadjoStockState.UNKNOWN
        stockMilliGrams > 0L -> MadjoStockState.AVAILABLE
        stockMilliGrams == 0L -> MadjoStockState.OUT_OF_STOCK
        else -> MadjoStockState.UNKNOWN
    }
}

/** Injectable weights leave room for later administration without adding a settings screen now. */
data class MadjoRankingWeights(
    val category: Int = 20,
    val aroma: Int = 20,
    val moment: Int = 12,
    val origin: Int = 20,
    val stockPreference: Int = 12,
    val requestedRating: Int = 40,
    val defaultRatingBonus: Int = 5,
    val defaultStockBonus: Int = 4,
    val tastingHistory: Int = 100_000,
)

data class MadjoRelaxationOption(val criterion: MadjoCriterion, val matchingTeaCount: Int)

data class MadjoRankedTea(
    val tea: MadjoTeaProfile,
    val score: Int,
    val explanation: String,
    val softAssociationTraces: List<MadjoSoftAssociationTrace> = emptyList(),
)

data class MadjoRecommendationOutcome(
    val recommendations: List<MadjoRankedTea>,
    val unresolvedAmbiguities: List<MadjoAmbiguity>,
    val relaxationOptions: List<MadjoRelaxationOption>,
    val acceptedRelaxations: List<MadjoCriterion> = emptyList(),
    val collectionEmpty: Boolean = false,
) {
    val hasResults: Boolean get() = recommendations.isNotEmpty()
}

/** Deterministic local ranking. Constraints are applied before any preference or bonus score. */
object MadjoRecommendationEngine {
    /** Values that can genuinely separate the currently compatible collection candidates. */
    fun questionEvidence(
        teas: List<MadjoTeaProfile>,
        criteria: MadjoRecommendationCriteria,
    ): Pair<Set<MadjoQuestionId>, Map<MadjoQuestionId, Set<String>>> {
        if (criteria.ambiguities.isNotEmpty()) return emptySet<MadjoQuestionId>() to emptyMap()
        val compatible = teas.filter { tea -> criteria.absoluteConstraints.all { matches(tea, it) } }
        if (compatible.isEmpty()) return emptySet<MadjoQuestionId>() to emptyMap()
        val options = linkedMapOf<MadjoQuestionId, Set<String>>()
        options[MadjoQuestionId.TYPE_COLOR] = compatible.flatMap { tea ->
            listOf("category:${tea.categoryId}", if (isInfusion(tea)) "beverage:infusion" else "beverage:tea")
        }.toSet()
        options[MadjoQuestionId.AROMA] = compatible.flatMap { tea -> tea.aromas.map { "aroma:${it.id}" } + if (tea.aromas.isEmpty()) listOf("aroma:none") else emptyList() }.toSet()
        options[MadjoQuestionId.MOMENT] = compatible.flatMap { tea -> tea.moments.map { "moment:${it.id}" } + if (tea.moments.isEmpty()) listOf("moment:none") else emptyList() }.toSet()
        options[MadjoQuestionId.ORIGIN] = compatible.flatMap { tea -> (tea.countries + tea.regions).map { "origin:${it.id}" } + if (tea.countries.isEmpty() && tea.regions.isEmpty()) listOf("origin:none") else emptyList() }.toSet()
        val ratings = compatible.mapNotNull { it.tasteRating }
        options[MadjoQuestionId.TASTE_RATING] = buildSet {
            if (ratings.isNotEmpty()) add("rating:top_rated")
            if (ratings.any { it >= 8.0 }) add("rating:at_least_8")
            if (ratings.distinct().size > 1) add("rating:variation")
        }
        options[MadjoQuestionId.STOCK] = compatible.map { "stock:${it.stockState.toCriterionValue()}" }.toSet()
        val useful = buildSet {
            if (compatible.map { it.categoryId }.distinct().size > 1 || compatible.map(::isInfusion).distinct().size > 1) add(MadjoQuestionId.TYPE_COLOR)
            if (options.getValue(MadjoQuestionId.AROMA).size > 1) add(MadjoQuestionId.AROMA)
            if (options.getValue(MadjoQuestionId.MOMENT).size > 1) add(MadjoQuestionId.MOMENT)
            if (options.getValue(MadjoQuestionId.ORIGIN).size > 1) add(MadjoQuestionId.ORIGIN)
            if (ratings.distinct().size > 1) add(MadjoQuestionId.TASTE_RATING)
            if (options.getValue(MadjoQuestionId.STOCK).size > 1) add(MadjoQuestionId.STOCK)
        }
        return useful to options
    }

    fun recommend(
        teas: List<MadjoTeaProfile>,
        criteria: MadjoRecommendationCriteria,
        acceptedRelaxations: List<MadjoCriterion> = emptyList(),
        weights: MadjoRankingWeights = MadjoRankingWeights(),
    ): MadjoRecommendationOutcome {
        if (criteria.ambiguities.isNotEmpty()) {
            return MadjoRecommendationOutcome(emptyList(), criteria.ambiguities, emptyList(), acceptedRelaxations)
        }
        if (teas.isEmpty()) return MadjoRecommendationOutcome(emptyList(), emptyList(), emptyList(), acceptedRelaxations, collectionEmpty = true)
        val acceptedKeys = acceptedRelaxations.map(::criterionKey).toSet()
        val activeConstraints = criteria.absoluteConstraints.filterNot { criterionKey(it) in acceptedKeys }
        val eligible = teas.filter { tea -> activeConstraints.all { matches(tea, it) } }
        if (eligible.isNotEmpty()) {
            val softCriteria = criteria.preferences + acceptedRelaxations
            val historyScores = historyPreferenceScores(eligible, softCriteria, weights.tastingHistory)
            val ranked = eligible.map { tea ->
                val preferenceScore = score(tea, softCriteria, criteria, weights)
                val score = preferenceScore.score + historyScores[tea.id].orEmpty().sum()
                MadjoRankedTea(tea, score, explain(tea, criteria, preferenceScore.softTraces), preferenceScore.softTraces)
            }.sortedWith(compareByDescending<MadjoRankedTea> { it.score }
                .thenByDescending { it.tea.tasteRating ?: -1.0 }
                .thenBy { it.tea.name.lowercase(Locale.ROOT) }
                .thenBy { it.tea.id })
            return MadjoRecommendationOutcome(ranked, emptyList(), emptyList(), acceptedRelaxations)
        }

        val relaxationOptions = criteria.absoluteConstraints.filterNot { criterionKey(it) in acceptedKeys }.mapNotNull { criterion ->
            val remaining = activeConstraints.filterNot { criterionKey(it) == criterionKey(criterion) }
            val count = teas.count { tea -> remaining.all { matches(tea, it) } }
            count.takeIf { it > 0 }?.let { MadjoRelaxationOption(criterion, it) }
        }.distinctBy { criterionKey(it.criterion) }
            .sortedWith(compareBy<MadjoRelaxationOption> { it.matchingTeaCount }.thenBy { it.criterion.dimension.ordinal })
        return MadjoRecommendationOutcome(emptyList(), emptyList(), relaxationOptions, acceptedRelaxations)
    }

    /** Preference scores rank only teas with actual tasting events; never-tasted teas are not "forgotten". */
    private fun historyPreferenceScores(
        teas: List<MadjoTeaProfile>,
        preferences: List<MadjoCriterion>,
        maxScore: Int,
    ): Map<String, List<Int>> = buildMap {
        preferences.filter { it.dimension == MadjoDimension.TASTING_HISTORY }.forEach { criterion ->
            val ordered = when (criterion.concept) {
                "tasting_frequency" -> teas.filter { it.tastingCount > 0 }
                    .sortedWith(compareByDescending<MadjoTeaProfile> { it.tastingCount }.thenBy { it.id })
                "tasting_recency" -> teas.filter { it.lastTastedAtEpochMillis != null }
                    .sortedWith(compareBy<MadjoTeaProfile> { it.lastTastedAtEpochMillis }.thenBy { it.id })
                else -> emptyList()
            }
            ordered.forEachIndexed { index, tea ->
                val score = maxScore + (ordered.size - index) * 1_000
                put(tea.id, get(tea.id).orEmpty() + score)
            }
        }
    }

    fun criterionKey(criterion: MadjoCriterion): String = listOf(
        criterion.dimension.name, criterion.concept, criterion.referenceId.orEmpty(), criterion.value,
    ).joinToString("|")

    private data class PreferenceScore(val score: Int, val softTraces: List<MadjoSoftAssociationTrace>)

    private fun score(
        tea: MadjoTeaProfile,
        preferences: List<MadjoCriterion>,
        allCriteria: MadjoRecommendationCriteria,
        weights: MadjoRankingWeights,
    ): PreferenceScore {
        var total = 0
        val bestSoftByFeature = linkedMapOf<String, Pair<Int, MadjoSoftAssociationTrace>>()
        preferences.forEach { criterion ->
            if (criterion.concept == "taste_rating" && criterion.value == "top_rated") {
                total += ((tea.tasteRating ?: 0.0).coerceIn(0.0, 10.0) * weights.requestedRating).toInt() / 10
            } else if (matches(tea, criterion)) {
                total += dimensionPreferenceWeight(criterion.dimension, weights)
            } else if (criterion.kind == MadjoCriterionKind.PREFERENCE && criterion.softAssociations.isNotEmpty()) {
                val sourceLabel = criterion.displayValue ?: criterion.softAssociationSourceId.orEmpty()
                val sourceId = criterion.softAssociationSourceId.orEmpty()
                val candidates = criterion.softAssociations.mapNotNull { alternative ->
                    val weight = alternative.associationWeightPercent ?: return@mapNotNull null
                    val associationId = alternative.associationId ?: return@mapNotNull null
                    val actual = softAssociationFeature(tea, alternative) ?: return@mapNotNull null
                    val dimension = alternativeDimension(alternative) ?: return@mapNotNull null
                    val base = dimensionPreferenceWeight(dimension, weights)
                    val points = (base * weight / 100).coerceAtMost(base - 1).coerceAtLeast(0)
                    if (points == 0) return@mapNotNull null
                    val trace = MadjoSoftAssociationTrace(
                        associationId = associationId,
                        sourceConceptId = sourceId,
                        sourceConceptLabel = sourceLabel,
                        targetConceptId = alternative.conceptId,
                        targetConceptLabel = alternative.label,
                        weightPercent = weight,
                        actualCharacteristic = actual.second,
                        contribution = points,
                    )
                    Triple(actual.first, points, trace)
                }.groupBy { it.first }.mapNotNull { (_, sameFeature) -> sameFeature.maxWithOrNull(compareBy<Triple<String, Int, MadjoSoftAssociationTrace>> { it.second }.thenBy { it.third.associationId }) }
                val cap = dimensionPreferenceWeight(criterion.dimension, weights) - 1
                var remaining = cap.coerceAtLeast(0)
                candidates.sortedWith(compareByDescending<Triple<String, Int, MadjoSoftAssociationTrace>> { it.second }.thenBy { it.first }).forEach { candidate ->
                    val awarded = candidate.second.coerceAtMost(remaining)
                    if (awarded > 0) {
                        val trace = candidate.third.copy(contribution = awarded)
                        val prior = bestSoftByFeature[candidate.first]
                        if (prior == null || awarded > prior.first || (awarded == prior.first && trace.associationId < prior.second.associationId)) {
                            bestSoftByFeature[candidate.first] = awarded to trace
                        }
                        remaining -= awarded
                    }
                }
            }
        }
        if (allCriteria.criteria.none { it.dimension == MadjoDimension.TASTE_RATING } && tea.tasteRating != null) {
            total += ((tea.tasteRating.coerceIn(0.0, 10.0) * weights.defaultRatingBonus) / 10.0).toInt()
        }
        if (allCriteria.criteria.none { it.dimension == MadjoDimension.STOCK } && tea.stockState == MadjoStockState.AVAILABLE) {
            total += weights.defaultStockBonus
        }
        val traces = bestSoftByFeature.values.map { it.second }.sortedWith(compareBy<MadjoSoftAssociationTrace> { it.sourceConceptLabel }.thenBy { it.targetConceptLabel }.thenBy { it.associationId })
        return PreferenceScore(total + traces.sumOf(MadjoSoftAssociationTrace::contribution), traces)
    }

    private fun dimensionPreferenceWeight(dimension: MadjoDimension, weights: MadjoRankingWeights): Int = when (dimension) {
        MadjoDimension.TYPE_COLOR -> weights.category
        MadjoDimension.AROMA -> weights.aroma
        MadjoDimension.MOMENT -> weights.moment
        MadjoDimension.ORIGIN -> weights.origin
        MadjoDimension.TASTE_RATING -> weights.requestedRating
        MadjoDimension.STOCK -> weights.stockPreference
        MadjoDimension.TASTING_HISTORY -> 0
    }

    private fun alternativeDimension(alternative: MadjoSemanticAlternative): MadjoDimension? = when (alternative.businessDimension) {
        "TYPE_COLOR" -> MadjoDimension.TYPE_COLOR
        "AROMA" -> MadjoDimension.AROMA
        "ORIGIN" -> MadjoDimension.ORIGIN
        "MOMENT" -> MadjoDimension.MOMENT
        else -> null
    }

    private fun softAssociationFeature(tea: MadjoTeaProfile, alternative: MadjoSemanticAlternative): Pair<String, String>? {
        if (alternative.relation != MadjoRelation.SEMANTIC_ASSOCIATION) return null
        return when (alternative.businessDimension) {
            "TYPE_COLOR" -> if ((alternative.referenceId != null && tea.categoryId == alternative.referenceId) ||
                alternative.businessValues.any { valueMatches(it, tea.categoryKey, tea.categoryLabel) }) "CATEGORY:${tea.categoryId}" to tea.categoryLabel else null
            "AROMA" -> {
                val refs = if (alternative.businessConcept == "aromatic_profile") tea.aromaticProfiles else tea.aromas
                refs.firstOrNull { ref ->
                    (alternative.referenceId != null && alternative.referenceId == ref.id) || alternative.businessValues.any { valueMatches(it, ref.semanticKey, ref.label) }
                }?.let { "${alternative.businessConcept}:${it.id}" to it.label }
            }
            "ORIGIN" -> {
                val refs = if (alternative.businessConcept == "country") tea.countries else tea.regions
                refs.firstOrNull { ref ->
                    (alternative.referenceId != null && alternative.referenceId == ref.id) || alternative.businessValues.any { valueMatches(it, ref.semanticKey, ref.label) }
                }?.let { "${alternative.businessConcept}:${it.id}" to it.label }
            }
            "MOMENT" -> tea.moments.firstOrNull { ref ->
                (alternative.referenceId != null && alternative.referenceId == ref.id) || alternative.businessValues.any { valueMatches(it, ref.semanticKey, ref.label) }
            }?.let { "MOMENT:${it.id}" to it.label }
            else -> null
        }
    }

    private fun matches(tea: MadjoTeaProfile, criterion: MadjoCriterion): Boolean {
        val refId = criterion.referenceId
        return when (criterion.concept) {
            "category" -> criterion.semanticExpansions.any { alternative ->
                alternative.referenceId?.let { tea.categoryId == it } == true || alternative.businessValues.any { valueMatches(it, tea.categoryKey, tea.categoryLabel) }
            } || (refId?.let { tea.categoryId == it } ?: valueMatches(criterion.value, tea.categoryKey, tea.categoryLabel))
            "beverage_type" -> when (criterion.value.lowercase(Locale.ROOT)) {
                "infusion" -> normalize(tea.categoryKey.orEmpty()) == "infusion" || normalize(tea.categoryLabel) in setOf("infusion", "tisane")
                "tea" -> normalize(tea.categoryKey.orEmpty()) != "infusion" && normalize(tea.categoryLabel) !in setOf("infusion", "tisane")
                else -> false
            }
            "aroma" -> (refMatches(refId, criterion.value, tea.aromas) || criterion.semanticExpansions.any { alternative ->
                val refs = if (alternative.businessConcept == "aromatic_profile") tea.aromaticProfiles else tea.aromas
                (alternative.referenceId != null && refs.any { it.id == alternative.referenceId }) ||
                    refs.any { ref -> alternative.businessValues.any { valueMatches(it, ref.semanticKey, ref.label) } }
            })
            "moment" -> refMatches(refId, criterion.value, tea.moments) || semanticReferenceMatches(criterion, tea.moments)
            "country" -> refMatches(refId, criterion.value, tea.countries) || semanticReferenceMatches(criterion, tea.countries)
            "region" -> refMatches(refId, criterion.value, tea.regions) || semanticReferenceMatches(criterion, tea.regions)
            "taste_rating" -> ratingMatches(tea.tasteRating, criterion.value)
            "stock" -> when (criterion.value) {
                "available" -> tea.stockState == MadjoStockState.AVAILABLE
                "out_of_stock" -> tea.stockState == MadjoStockState.OUT_OF_STOCK
                "unknown" -> tea.stockState == MadjoStockState.UNKNOWN
                "abundant" -> tea.stockMilliGrams != null && tea.stockMilliGrams >= 50_000L
                else -> false
            }
            "tasting_frequency" -> tea.tastingCount > 0
            "tasting_recency" -> tea.lastTastedAtEpochMillis != null
            else -> false
        }
    }

    private fun refMatches(id: String?, value: String, refs: List<MadjoReference>): Boolean = refs.any { ref ->
        (id != null && ref.id == id) || (id == null && valueMatches(value, ref.semanticKey, ref.label))
    }

    private fun semanticReferenceMatches(criterion: MadjoCriterion, refs: List<MadjoReference>): Boolean =
        criterion.semanticExpansions.any { alternative -> refs.any { ref ->
            alternative.referenceId == ref.id || alternative.businessValues.any { valueMatches(it, ref.semanticKey, ref.label) }
        } }

    private fun valueMatches(value: String, key: String?, label: String): Boolean =
        normalize(value.replace('_', ' ')) == normalize(key.orEmpty().replace('_', ' ')) ||
            normalize(value.replace('_', ' ')) == normalize(label)

    private fun ratingMatches(rating: Double?, value: String): Boolean {
        if (rating == null) return false
        val exact = Regex("^exact:(\\d+(?:\\.\\d+)?)$").matchEntire(value)
        if (exact != null) return rating == exact.groupValues[1].toDouble()
        val minimum = Regex("^(?:at_least:|at_least_)(\\d+(?:\\.\\d+)?)$").matchEntire(value)
        if (minimum != null) return rating >= minimum.groupValues[1].toDouble()
        return when (value) {
            "top_rated" -> true
            "at_least_8" -> rating >= 8.0
            else -> false
        }
    }

    private fun explain(tea: MadjoTeaProfile, criteria: MadjoRecommendationCriteria, softTraces: List<MadjoSoftAssociationTrace>): String {
        val matched = mutableListOf<String>()
        val semanticExplanations = mutableListOf<String>()
        criteria.criteria.filter { criterion -> criterion.dimension !in setOf(MadjoDimension.TASTE_RATING, MadjoDimension.STOCK) && matches(tea, criterion) }
            .forEach { criterion ->
                when (criterion.concept) {
                    "category", "beverage_type" -> if (tea.categoryLabel !in matched) matched += tea.categoryLabel
                    "aroma" -> {
                        val actual = (tea.aromas + tea.aromaticProfiles).firstNotNullOfOrNull { ref ->
                            val expansion = criterion.semanticExpansions.firstOrNull { alternative ->
                                alternative.referenceId == ref.id || alternative.businessValues.any { valueMatches(it, ref.semanticKey, ref.label) }
                            }
                            if (criterion.referenceId == ref.id || expansion != null) ref to expansion else null
                        }
                        actual?.first?.label?.let { label ->
                            if (actual.second?.relation == MadjoRelation.SPECIALIZATION) {
                                val requested = criterion.displayValue?.lowercase(Locale.FRENCH) ?: "profil aromatique"
                                semanticExplanations += "Correspond à votre recherche $requested grâce à son arôme $label."
                            } else matched += label
                        }
                    }
                    "moment" -> tea.moments.firstOrNull { criterion.referenceId == null || it.id == criterion.referenceId }?.label?.let(matched::add)
                    "country" -> tea.countries.firstOrNull { criterion.referenceId == null || it.id == criterion.referenceId }?.label?.let(matched::add)
                    "region" -> tea.regions.firstOrNull { criterion.referenceId == null || it.id == criterion.referenceId }?.label?.let(matched::add)
                }
            }
        val sentences = semanticExplanations.toMutableList()
        softTraces.forEach { trace ->
            val strength = when (trace.weightPercent) { 25 -> "faible"; 50 -> "moyenne"; 75 -> "forte"; else -> "modérée" }
            sentences += "Rapprochement souple ($strength) entre « ${trace.sourceConceptLabel} » et « ${trace.targetConceptLabel} », via ${trace.actualCharacteristic} : +${trace.contribution} points de pertinence."
        }
        if (matched.isNotEmpty()) sentences += "Correspond à ta demande de ${matched.distinct().joinToString(", ")}."
        criteria.criteria.filter { it.dimension == MadjoDimension.TASTING_HISTORY && matches(tea, it) }.forEach { criterion ->
            sentences += when (criterion.concept) {
                "tasting_frequency" -> "Dégusté ${tea.tastingCount} fois dans votre historique."
                "tasting_recency" -> "Parmi les thés dégustés il y a le plus longtemps."
                else -> ""
            }
        }
        val askedTopRated = criteria.criteria.any { it.concept == "taste_rating" && it.value == "top_rated" }
        if (tea.tasteRating != null && (askedTopRated || criteria.criteria.any { it.dimension == MadjoDimension.TASTE_RATING } || tea.tasteRating >= 8.0)) {
            sentences += if (askedTopRated) "Apprécié à ${formatRating(tea.tasteRating)}/10." else "Noté ${formatRating(tea.tasteRating)}/10."
        }
        sentences += when (tea.stockState) {
            MadjoStockState.AVAILABLE -> "Disponible en stock."
            MadjoStockState.OUT_OF_STOCK -> "Épuisé."
            MadjoStockState.UNKNOWN -> "Stock inconnu."
        }
        return sentences.joinToString(" ")
    }

    private fun formatRating(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    private fun isInfusion(tea: MadjoTeaProfile): Boolean = normalize(tea.categoryKey.orEmpty()) == "infusion" || normalize(tea.categoryLabel) in setOf("infusion", "tisane")

    private fun MadjoStockState.toCriterionValue(): String = when (this) {
        MadjoStockState.AVAILABLE -> "available"
        MadjoStockState.OUT_OF_STOCK -> "out_of_stock"
        MadjoStockState.UNKNOWN -> "unknown"
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
