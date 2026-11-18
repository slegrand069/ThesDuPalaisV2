package fr.thesdupalais.presentation.statistics

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

enum class StatisticsPeriod(val label: String) {
    DAYS_30("30 jours"),
    DAYS_90("90 jours"),
    YEAR("1 an"),
    ALL("Tout"),
}

data class StatisticsReference(val id: UUID, val label: String, val semanticKey: String?)

data class StatisticsTea(
    val id: UUID,
    val name: String,
    val active: Boolean,
    val category: StatisticsReference,
    val stockMilliGrams: Long?,
    val countries: List<StatisticsReference>,
    val badges: List<StatisticsReference>,
    val tastingDatesEpochMillis: List<Long>,
    /** Preserved for traceability only; the manual buy-back intention never affects quantitative advice. */
    val statusSemanticKey: String? = null,
)

data class StatisticsBucket(
    val id: String,
    val label: String,
    val value: Long,
    val colorKey: String? = null,
)

data class PurchaseSuggestion(
    val teaId: UUID,
    val name: String,
    val stockMilliGrams: Long,
    val tastingCount: Int,
)

data class MonthlyTastingCount(val month: YearMonth, val count: Int, val segments: List<MonthlyTastingSegment> = emptyList()) {
    val label: String get() = month.month.getDisplayName(TextStyle.SHORT, Locale.FRANCE)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.FRANCE) else it.toString() }
}
data class MonthlyTastingSegment(val colorKey: String, val label: String, val count: Int)

/** Proportional bar height using a small headroom above the largest displayed interval. */
internal fun monthlyBarHeightFraction(count: Int, maximum: Int): Float =
    if (count <= 0 || maximum <= 0) 0f else count.toFloat() / (maximum.toFloat() * 1.08f)

internal fun compactMonthlyLegendLabel(label: String): String = label
    .replace(Regex("(?i)(?<![\\p{L}\\p{N}])thé(?![\\p{L}])\\s*"), "")
    .replace(Regex("\\s*\\+\\s*"), " + ")
    .trim()
    .ifBlank { label }

data class StatisticsSnapshot(
    val period: StatisticsPeriod,
    val activeReferenceCount: Int,
    val knownStockMilliGrams: Long,
    val unknownStockReferenceCount: Int,
    val lowStockCount: Int,
    val tastingEventCount: Int,
    val tastingsByColor: List<StatisticsBucket>,
    val tastingsByCountry: List<StatisticsBucket>,
    val stockByColor: List<StatisticsBucket>,
    val stockByCountry: List<StatisticsBucket>,
    val stockByBadge: List<StatisticsBucket>,
    val purchaseSuggestions: List<PurchaseSuggestion>,
    val topFive: List<StatisticsBucket>,
    val monthlyTastings: List<MonthlyTastingCount>,
)

fun observeStatisticsSnapshots(
    teas: Flow<List<StatisticsTea>>,
    period: Flow<StatisticsPeriod>,
    clock: Clock = Clock.systemDefaultZone(),
    zone: ZoneId = clock.zone,
): Flow<StatisticsSnapshot> = combine(teas, period) { rows, selected ->
    buildStatisticsSnapshot(rows, selected, clock, zone)
}

/** Deterministic aggregation over persisted rows; the clock and local zone are injectable for tests. */
fun buildStatisticsSnapshot(
    teas: List<StatisticsTea>,
    period: StatisticsPeriod,
    clock: Clock = Clock.systemDefaultZone(),
    zone: ZoneId = clock.zone,
): StatisticsSnapshot {
    val now = Instant.now(clock)
    val today = LocalDate.now(clock.withZone(zone))
    val endExclusive = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val startInclusive = when (period) {
        StatisticsPeriod.DAYS_30 -> today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
        StatisticsPeriod.DAYS_90 -> today.minusDays(89).atStartOfDay(zone).toInstant().toEpochMilli()
        StatisticsPeriod.YEAR -> today.minusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
        StatisticsPeriod.ALL -> Long.MIN_VALUE
    }
    fun isInPeriod(epochMillis: Long) = epochMillis >= startInclusive && epochMillis < endExclusive && epochMillis <= now.toEpochMilli()

    val active = teas.filter(StatisticsTea::active)
    val knownStocks = active.mapNotNull(StatisticsTea::stockMilliGrams)
    val tastingCounts = teas.associate { tea -> tea.id to tea.tastingDatesEpochMillis.count(::isInPeriod) }
    val eventCount = tastingCounts.values.sum()

    val tastingsByColor = teas.flatMap { tea ->
        tea.tastingDatesEpochMillis.filter(::isInPeriod).map { tea.category }
    }.groupingBy(StatisticsReference::id).eachCount().mapNotNull { (id, count) ->
        val reference = teas.firstOrNull { it.category.id == id }?.category ?: return@mapNotNull null
        StatisticsBucket(id.toString(), reference.label, count.toLong(), categoryColorKey(reference))
    }.sortedWith(bucketValueComparator())

    val stockByColor = groupStock(active, { listOf(it.category) }, ::categoryColorKey)
    val stockByCountry = groupStock(active, references = { tea -> listOf(originBucket(tea.countries)) })
    val stockByBadge = groupStock(active, references = { tea -> tea.badges.ifEmpty { listOf(NO_BADGE) } })

    val monthStart = YearMonth.from(today).minusMonths(5)
    val months = (0L..5L).map { monthStart.plusMonths(it) }
    val monthly = months.map { month ->
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val count = if (to <= startInclusive || from >= endExclusive) 0 else teas.sumOf { tea ->
            tea.tastingDatesEpochMillis.count { epoch -> isInPeriod(epoch) && epoch >= from && epoch < to }
        }
        val segments = teas.flatMap { tea ->
            tea.tastingDatesEpochMillis.filter { epoch -> isInPeriod(epoch) && epoch >= from && epoch < to }
                .map { categoryColorKey(tea.category) }
        }.groupingBy { it }.eachCount().entries.sortedBy { it.key }
            .map { MonthlyTastingSegment(it.key, teas.firstOrNull { tea -> categoryColorKey(tea.category) == it.key }?.category?.label ?: it.key, it.value) }
        MonthlyTastingCount(month, count, segments)
    }

    val suggestions = active.mapNotNull { tea ->
        val stock = tea.stockMilliGrams ?: return@mapNotNull null
        if (stock >= LOW_STOCK_LIMIT_MILLI_GRAMS || tea.statusSemanticKey.equals("AVOID", ignoreCase = true)) return@mapNotNull null
        PurchaseSuggestion(tea.id, tea.name, stock, tastingCounts[tea.id] ?: 0)
    }.sortedWith(compareBy<PurchaseSuggestion> { it.stockMilliGrams }
        .then(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .thenBy { it.teaId.toString() })

    val topFive = teas.mapNotNull { tea ->
        val count = tastingCounts[tea.id] ?: 0
        if (count == 0) null else StatisticsBucket(tea.id.toString(), tea.name, count.toLong())
    }.sortedWith(compareByDescending<StatisticsBucket> { it.value }
        .then(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }).thenBy { it.id }).take(5)

    return StatisticsSnapshot(
        period = period,
        activeReferenceCount = active.size,
        knownStockMilliGrams = knownStocks.sum(),
        unknownStockReferenceCount = active.count { it.stockMilliGrams == null },
        lowStockCount = active.count { it.stockMilliGrams != null && it.stockMilliGrams < LOW_STOCK_LIMIT_MILLI_GRAMS },
        tastingEventCount = eventCount,
        tastingsByColor = tastingsByColor,
        tastingsByCountry = teas.flatMap { tea ->
            val bucket = originBucket(tea.countries)
            tea.tastingDatesEpochMillis.filter(::isInPeriod).map { bucket }
        }.groupingBy { it.id }.eachCount().mapNotNull { (id, count) ->
            val reference = teas.asSequence().map { originBucket(it.countries) }.firstOrNull { it.id == id } ?: return@mapNotNull null
            StatisticsBucket(id.toString(), reference.label, count.toLong(), reference.semanticKey)
        }.sortedWith(bucketValueComparator()),
        stockByColor = stockByColor,
        stockByCountry = stockByCountry,
        stockByBadge = stockByBadge,
        purchaseSuggestions = suggestions,
        topFive = topFive,
        monthlyTastings = monthly,
    )
}

private fun groupStock(
    teas: List<StatisticsTea>,
    references: (StatisticsTea) -> List<StatisticsReference>,
    colorKey: (StatisticsReference) -> String = { it.semanticKey ?: it.id.toString() },
): List<StatisticsBucket> = teas.asSequence()
    .filter { it.stockMilliGrams != null }
    .flatMap { tea -> references(tea).map { it to requireNotNull(tea.stockMilliGrams) } }
    .groupBy({ it.first.id }, { it.second })
    .map { (id, amounts) ->
        val reference = teas.asSequence().flatMap { references(it).asSequence() }.first { it.id == id }
        StatisticsBucket(id.toString(), reference.label, amounts.sum(), colorKey(reference))
    }
    .sortedWith(bucketValueComparator())

private fun bucketValueComparator() = compareByDescending<StatisticsBucket> { it.value }
    .then(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }).thenBy { it.id }

private fun categoryColorKey(reference: StatisticsReference): String {
    val label = normalizeOrigin(reference.label)
    return when (label) {
        "the vert", "vert" -> "GREEN"
        "the noir", "noir" -> "BLACK"
        "the jaune", "jaune" -> "YELLOW"
        "oolong" -> "OOLONG"
        "the blanc", "blanc" -> "WHITE"
        "mixte", "melange" -> "MIXED"
        "puerh", "pu erh", "pu er h" -> "PU_ERH"
        "the sombre", "sombre" -> "DARK"
        "infusion", "tisane" -> "INFUSION"
        "mate" -> "MATE"
        else -> reference.semanticKey ?: reference.id.toString()
    }
}

private val UNKNOWN_ORIGIN = StatisticsReference(UUID(0L, 1L), "Non renseigné", "ORIGIN_UNKNOWN")
private val MULTIPLE_ORIGINS = StatisticsReference(UUID(0L, 3L), "Origines multiples", "ORIGIN_MULTIPLE")
private val NC_ORIGIN = StatisticsReference(UUID(0L, 4L), "NC / non communiqué", "ORIGIN_NC")
private val NO_BADGE = StatisticsReference(UUID(0L, 2L), "Sans badge", null)
private const val LOW_STOCK_LIMIT_MILLI_GRAMS = 30_000L

private fun originBucket(countries: List<StatisticsReference>): StatisticsReference {
    val distinct = countries.distinctBy { it.id }
    if (distinct.isEmpty()) return UNKNOWN_ORIGIN
    if (distinct.size > 1) return MULTIPLE_ORIGINS
    val country = distinct.single()
    val normalized = normalizeOrigin(country.label)
    if (normalized in setOf("nc", "n c", "non communique", "non communiquee", "non renseigne", "non renseignee")) return NC_ORIGIN
    val key = when (normalized) {
        "chine" -> "COUNTRY_CHINA"
        "japon" -> "COUNTRY_JAPAN"
        "inde" -> "COUNTRY_INDIA"
        "vietnam" -> "COUNTRY_VIETNAM"
        "afrique du sud" -> "COUNTRY_SOUTH_AFRICA"
        else -> "COUNTRY_${normalized.uppercase(Locale.ROOT).replace(' ', '_')}"
    }
    return country.copy(semanticKey = key)
}

private fun normalizeOrigin(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
