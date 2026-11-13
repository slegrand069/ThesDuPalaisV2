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
    val moments: List<MadjoReference> = emptyList(),
    val tasteRating: Double? = null,
    val stockMilliGrams: Long? = null,
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
)

data class MadjoRelaxationOption(val criterion: MadjoCriterion, val matchingTeaCount: Int)

data class MadjoRankedTea(
    val tea: MadjoTeaProfile,
    val score: Int,
    val explanation: String,
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
            val ranked = eligible.map { tea ->
                val score = score(tea, softCriteria, criteria, weights)
                MadjoRankedTea(tea, score, explain(tea, criteria))
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

    fun criterionKey(criterion: MadjoCriterion): String = listOf(
        criterion.dimension.name, criterion.concept, criterion.referenceId.orEmpty(), criterion.value,
    ).joinToString("|")

    private fun score(
        tea: MadjoTeaProfile,
        preferences: List<MadjoCriterion>,
        allCriteria: MadjoRecommendationCriteria,
        weights: MadjoRankingWeights,
    ): Int {
        var total = 0
        preferences.forEach { criterion ->
            if (criterion.concept == "taste_rating" && criterion.value == "top_rated") {
                total += ((tea.tasteRating ?: 0.0).coerceIn(0.0, 10.0) * weights.requestedRating).toInt() / 10
            } else if (matches(tea, criterion)) {
                total += when (criterion.dimension) {
                    MadjoDimension.TYPE_COLOR -> weights.category
                    MadjoDimension.AROMA -> weights.aroma
                    MadjoDimension.MOMENT -> weights.moment
                    MadjoDimension.ORIGIN -> weights.origin
                    MadjoDimension.TASTE_RATING -> weights.requestedRating
                    MadjoDimension.STOCK -> weights.stockPreference
                }
            }
        }
        if (allCriteria.criteria.none { it.dimension == MadjoDimension.TASTE_RATING } && tea.tasteRating != null) {
            total += ((tea.tasteRating.coerceIn(0.0, 10.0) * weights.defaultRatingBonus) / 10.0).toInt()
        }
        if (allCriteria.criteria.none { it.dimension == MadjoDimension.STOCK } && tea.stockState == MadjoStockState.AVAILABLE) {
            total += weights.defaultStockBonus
        }
        return total
    }

    private fun matches(tea: MadjoTeaProfile, criterion: MadjoCriterion): Boolean {
        val refId = criterion.referenceId
        return when (criterion.concept) {
            "category" -> refId?.let { tea.categoryId == it } ?: valueMatches(criterion.value, tea.categoryKey, tea.categoryLabel)
            "beverage_type" -> when (criterion.value.lowercase(Locale.ROOT)) {
                "infusion" -> normalize(tea.categoryKey.orEmpty()) == "infusion" || normalize(tea.categoryLabel) in setOf("infusion", "tisane")
                "tea" -> normalize(tea.categoryKey.orEmpty()) != "infusion" && normalize(tea.categoryLabel) !in setOf("infusion", "tisane")
                else -> false
            }
            "aroma" -> refMatches(refId, criterion.value, tea.aromas)
            "moment" -> refMatches(refId, criterion.value, tea.moments)
            "country" -> refMatches(refId, criterion.value, tea.countries)
            "region" -> refMatches(refId, criterion.value, tea.regions)
            "taste_rating" -> ratingMatches(tea.tasteRating, criterion.value)
            "stock" -> when (criterion.value) {
                "available" -> tea.stockState == MadjoStockState.AVAILABLE
                "out_of_stock" -> tea.stockState == MadjoStockState.OUT_OF_STOCK
                "unknown" -> tea.stockState == MadjoStockState.UNKNOWN
                else -> false
            }
            else -> false
        }
    }

    private fun refMatches(id: String?, value: String, refs: List<MadjoReference>): Boolean = refs.any { ref ->
        (id != null && ref.id == id) || (id == null && valueMatches(value, ref.semanticKey, ref.label))
    }

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

    private fun explain(tea: MadjoTeaProfile, criteria: MadjoRecommendationCriteria): String {
        val matched = mutableListOf<String>()
        criteria.criteria.filter { criterion -> criterion.dimension !in setOf(MadjoDimension.TASTE_RATING, MadjoDimension.STOCK) && matches(tea, criterion) }
            .forEach { criterion ->
                when (criterion.concept) {
                    "category", "beverage_type" -> if (tea.categoryLabel !in matched) matched += tea.categoryLabel
                    "aroma" -> tea.aromas.firstOrNull { criterion.referenceId == null || it.id == criterion.referenceId }?.label?.let(matched::add)
                    "moment" -> tea.moments.firstOrNull { criterion.referenceId == null || it.id == criterion.referenceId }?.label?.let(matched::add)
                    "country" -> tea.countries.firstOrNull { criterion.referenceId == null || it.id == criterion.referenceId }?.label?.let(matched::add)
                    "region" -> tea.regions.firstOrNull { criterion.referenceId == null || it.id == criterion.referenceId }?.label?.let(matched::add)
                }
            }
        val sentences = mutableListOf<String>()
        if (matched.isNotEmpty()) sentences += "Correspond à ta demande de ${matched.distinct().joinToString(", ")}."
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
