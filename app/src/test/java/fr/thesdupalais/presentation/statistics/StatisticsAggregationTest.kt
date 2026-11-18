package fr.thesdupalais.presentation.statistics

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsAggregationTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2025, 6, 15)
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
    private val green = ref("category-green", "Thé vert", "GREEN")
    private val black = ref("category-black", "Thé noir", "BLACK")
    private val japan = ref("country-japan", "Japon", null)
    private val china = ref("country-china", "Chine", null)
    private val grandCru = ref("badge-grand-cru", "Grand cru", null)
    private val organic = ref("badge-organic", "Bio", null)

    @Test
    fun emptyCollectionProducesOnlyZeroMetricsAndEmptyCharts() {
        val result = snapshot(emptyList())
        assertEquals(0, result.activeReferenceCount)
        assertEquals(0L, result.knownStockMilliGrams)
        assertEquals(0, result.unknownStockReferenceCount)
        assertEquals(0, result.lowStockCount)
        assertEquals(0, result.tastingEventCount)
        assertTrue(result.tastingsByColor.isEmpty())
        assertTrue(result.stockByColor.isEmpty())
        assertTrue(result.stockByCountry.isEmpty())
        assertTrue(result.stockByBadge.isEmpty())
        assertTrue(result.purchaseSuggestions.isEmpty())
        assertTrue(result.topFive.isEmpty())
        assertEquals(6, result.monthlyTastings.size)
        assertTrue(result.monthlyTastings.all { it.count == 0 })
    }

    @Test
    fun activeCollectionAndKnownStocksExcludeArchivedAndUnknownRows() {
        val rows = listOf(
            tea("active-known", stock = 1_250_000),
            tea("active-unknown", stock = null),
            tea("archived-known", stock = 900_000, active = false, tastings = listOf(on(today))),
        )
        val result = snapshot(rows)
        assertEquals(2, result.activeReferenceCount)
        assertEquals(1_250_000, result.knownStockMilliGrams)
        assertEquals(1, result.unknownStockReferenceCount)
        assertEquals(1, result.tastingEventCount)
        assertEquals("archived-known", result.topFive.single().label)
    }

    @Test
    fun lowStockUsesStrictThirtyGramLimitAndIgnoresUnknownAndManualStatus() {
        val rows = listOf(
            tea("empty", stock = 0),
            tea("under", stock = 29_999),
            tea("boundary", stock = 30_000),
            tea("unknown", stock = null),
            tea("flagged-high-stock", stock = 150_000, status = "TO_REBUY"),
            tea("flagged-low-stock", stock = 20_000, status = "TO_REBUY", tastings = listOf(on(today), on(today.minusDays(40)))),
            tea("avoid-low-stock", stock = 10_000, status = "AVOID"),
        )
        val result = snapshot(rows)
        assertEquals(4, result.lowStockCount)
        assertEquals(listOf("empty", "flagged-low-stock", "under"), result.purchaseSuggestions.map { it.name })
        assertFalse(result.purchaseSuggestions.any { it.name == "boundary" || it.name == "unknown" || it.name == "flagged-high-stock" })
        assertEquals(150_000L + 30_000L + 20_000L + 29_999L + 10_000L, result.knownStockMilliGrams)
        val frequent = result.purchaseSuggestions.first { it.name == "flagged-low-stock" }
        assertEquals(2, frequent.tastingCount)
        assertEquals(1, snapshot(rows, StatisticsPeriod.DAYS_30).purchaseSuggestions.first { it.name == "flagged-low-stock" }.tastingCount)
    }

    @Test
    fun periodUsesLocalCalendarDayBoundsAndCountsEveryEvent() {
        val rows = listOf(
            tea("at-30-day-start", tastings = listOf(on(today.minusDays(30)))),
            tea("at-29-day-start", tastings = listOf(on(today.minusDays(29)), on(today.minusDays(29)))),
            tea("today", tastings = listOf(on(today))),
            tea("future", tastings = listOf(on(today.plusDays(1)))),
        )
        assertEquals(3, snapshot(rows, StatisticsPeriod.DAYS_30).tastingEventCount)
        assertEquals(4, snapshot(rows, StatisticsPeriod.DAYS_90).tastingEventCount)
        assertEquals(4, snapshot(rows, StatisticsPeriod.YEAR).tastingEventCount)
        assertEquals(4, snapshot(rows, StatisticsPeriod.ALL).tastingEventCount)
    }

    @Test
    fun ninetyDayAndYearBoundariesAreInclusiveByLocalDate() {
        val rows = listOf(
            tea("ninety-out", tastings = listOf(on(today.minusDays(90)))),
            tea("eighty-nine-in", tastings = listOf(on(today.minusDays(89)))),
            tea("year-edge-in", tastings = listOf(on(today.minusYears(1)))),
            tea("year-out", tastings = listOf(on(today.minusYears(1).minusDays(1)))),
        )
        assertEquals(1, snapshot(rows, StatisticsPeriod.DAYS_90).tastingEventCount)
        assertEquals(3, snapshot(rows, StatisticsPeriod.YEAR).tastingEventCount)
        assertEquals(4, snapshot(rows, StatisticsPeriod.ALL).tastingEventCount)
    }

    @Test
    fun tastingCountsByPersistedTeaColorAndPeriod() {
        val rows = listOf(
            tea("green", category = green, tastings = listOf(on(today), on(today.minusDays(4)))),
            tea("black", category = black, tastings = listOf(on(today.minusDays(35)))),
        )
        val result = snapshot(rows, StatisticsPeriod.DAYS_30)
        assertEquals(2, result.tastingEventCount)
        assertEquals(listOf("Thé vert"), result.tastingsByColor.map { it.label })
        assertEquals(2L, result.tastingsByColor.single().value)
    }

    @Test
    fun stockByColorUsesActiveKnownStocksAndOrdersDescending() {
        val rows = listOf(
            tea("green-small", category = green, stock = 10_000),
            tea("green-large", category = green, stock = 35_000),
            tea("black", category = black, stock = 60_000),
            tea("archived", category = green, stock = 900_000, active = false),
            tea("unknown", category = black, stock = null),
        )
        val result = snapshot(rows)
        assertEquals(listOf("Thé noir", "Thé vert"), result.stockByColor.map { it.label })
        assertEquals(listOf(60_000L, 45_000L), result.stockByColor.map { it.value })
    }

    @Test
    fun multipleCountriesAreGroupedOnceAndMissingCountryIsExplicit() {
        val rows = listOf(
            tea("blend", stock = 40_000, countries = listOf(japan, china)),
            tea("Thé japonais sans origine", stock = 5_000),
        )
        val countryStock = snapshot(rows).stockByCountry.associate { it.label to it.value }
        assertEquals(40_000L, countryStock["Origines multiples"])
        assertEquals(5_000L, countryStock["Non renseigné"])
        assertEquals(45_000L, countryStock.values.sum())
        assertEquals(listOf(40_000L, 5_000L), snapshot(rows).stockByCountry.map { it.value })
    }

    @Test
    fun tastingsByCountryAssignsEachEventOnceAndUsesDedicatedMultipleAndNcBuckets() {
        val nc = ref("country-nc", "NC", null)
        val rows = listOf(
            tea("japan-event", countries = listOf(japan), tastings = listOf(on(today), on(today.minusDays(1)))),
            tea("china-event", countries = listOf(china), tastings = listOf(on(today))),
            tea("multi-event", countries = listOf(japan, china), tastings = listOf(on(today))),
            tea("nc-event", countries = listOf(nc), tastings = listOf(on(today))),
            tea("unknown-event", tastings = listOf(on(today))),
        )
        val result = snapshot(rows)
        assertEquals(6, result.tastingEventCount)
        val buckets = result.tastingsByCountry.associateBy { it.label }
        assertEquals(2L, buckets.getValue("Japon").value)
        assertEquals("COUNTRY_CHINA", buckets.getValue("Chine").colorKey)
        assertEquals(1L, buckets.getValue("Chine").value)
        assertEquals(1L, buckets.getValue("Origines multiples").value)
        assertEquals(1L, buckets.getValue("NC / non communiqué").value)
        assertEquals(1L, buckets.getValue("Non renseigné").value)
        assertEquals(6L, result.tastingsByCountry.sumOf { it.value })
        assertEquals("COUNTRY_JAPAN", buckets.getValue("Japon").colorKey)
        assertEquals("ORIGIN_MULTIPLE", buckets.getValue("Origines multiples").colorKey)
    }

    @Test
    fun tastingsByCountryUsesCommonPeriodAndOmitsZeroOriginCategories() {
        val rows = listOf(
            tea("recent-japan", countries = listOf(japan), tastings = listOf(on(today))),
            tea("old-china", countries = listOf(china), tastings = listOf(on(today.minusDays(50)))),
        )
        val thirtyDays = snapshot(rows, StatisticsPeriod.DAYS_30)
        assertEquals(1, thirtyDays.tastingEventCount)
        assertEquals(listOf("Japon"), thirtyDays.tastingsByCountry.map { it.label })
    }

    @Test
    fun monthlyStackSegmentsSumToEachIntervalTotal() {
        val rows = listOf(
            tea("green", category = green, tastings = listOf(on(today), on(today.minusDays(1)))),
            tea("black", category = black, tastings = listOf(on(today))),
        )
        val month = snapshot(rows).monthlyTastings.last()
        assertEquals(month.count, month.segments.sumOf { it.count })
        assertEquals(3, month.count)
        assertEquals(setOf("GREEN", "BLACK"), month.segments.map { it.colorKey }.toSet())
    }

    @Test
    fun monthlyBarsUseDynamicMaximumWithSmallHeadroomAndKeepRareSegmentsProportional() {
        val fractionForSixteen = monthlyBarHeightFraction(16, 16)
        val fractionForTwoHundred = monthlyBarHeightFraction(200, 200)
        assertEquals(fractionForSixteen, fractionForTwoHundred, 0.0001f)
        assertEquals(1f / 1.08f, fractionForSixteen, 0.0001f)
        val rareSegmentFraction = monthlyBarHeightFraction(1, 200)
        assertEquals(1f / 200f / 1.08f, rareSegmentFraction, 0.00001f)
        assertTrue(rareSegmentFraction > 0f)
        assertEquals(0f, monthlyBarHeightFraction(0, 200), 0f)
    }

    @Test
    fun compactMonthlyLegendRemovesOnlyTheRedundantTeaPrefix() {
        assertEquals("noir", compactMonthlyLegendLabel("Thé noir"))
        assertEquals("sombre", compactMonthlyLegendLabel("thé sombre"))
        assertEquals("noir + vert", compactMonthlyLegendLabel("noir +Thé vert"))
        assertEquals("vert + blanc", compactMonthlyLegendLabel("Thé vert + Thé blanc"))
        assertEquals("Pu’erh", compactMonthlyLegendLabel("Pu’erh"))
        assertEquals("Infusion", compactMonthlyLegendLabel("Infusion"))
    }

    @Test
    fun categoryPaletteKeyIsSharedAcrossTastingStockAndMonthlyCharts() {
        val mixed = ref("custom-mixed", "Mixte", null)
        val result = snapshot(listOf(tea("mixed", category = mixed, stock = 10_000, tastings = listOf(on(today)))))
        assertEquals("MIXED", result.tastingsByColor.single().colorKey)
        assertEquals("MIXED", result.stockByColor.single().colorKey)
        assertEquals("MIXED", result.monthlyTastings.last().segments.single().colorKey)
    }

    @Test
    fun multipleBadgesReceiveFullStockAndUnbadgedTeasUseFallbackBucket() {
        val rows = listOf(
            tea("multi-badge", stock = 100_000, badges = listOf(grandCru, organic)),
            tea("no-badge", stock = 25_000),
        )
        val badgeStock = snapshot(rows).stockByBadge.associate { it.label to it.value }
        assertEquals(100_000L, badgeStock["Grand cru"])
        assertEquals(100_000L, badgeStock["Bio"])
        assertEquals(25_000L, badgeStock["Sans badge"])
        assertEquals(225_000L, badgeStock.values.sum())
        assertEquals(listOf(100_000L, 100_000L, 25_000L), snapshot(rows).stockByBadge.map { it.value })
    }

    @Test
    fun topFiveSortsByEventCountThenStableNameAndId() {
        val rows = listOf(
            tea("Z tea", tastings = List(3) { on(today) }),
            tea("B tea", tastings = List(4) { on(today) }),
            tea("A tea", tastings = List(4) { on(today) }),
            tea("C tea", tastings = List(2) { on(today) }),
            tea("D tea", tastings = List(1) { on(today) }),
            tea("Never", tastings = emptyList()),
        )
        assertEquals(listOf("A tea", "B tea", "Z tea", "C tea", "D tea"), snapshot(rows).topFive.map { it.label })
        assertEquals(listOf(4L, 4L, 3L, 2L, 1L), snapshot(rows).topFive.map { it.value })
    }

    @Test
    fun sixMonthChartContainsZeroMonthsAndTotalMatchesRepresentedEvents() {
        val rows = listOf(
            tea("January and March", tastings = listOf(on(LocalDate.of(2025, 1, 2)), on(LocalDate.of(2025, 1, 9)), on(LocalDate.of(2025, 3, 8)))),
            tea("May and June", tastings = listOf(on(LocalDate.of(2025, 5, 20)), on(LocalDate.of(2025, 5, 21)), on(today))),
            tea("outside current period", tastings = listOf(on(today.minusDays(30)))),
        )
        val year = snapshot(rows, StatisticsPeriod.YEAR)
        assertEquals(listOf(2, 0, 1, 0, 3, 1), year.monthlyTastings.map { it.count })
        assertEquals(7, year.monthlyTastings.sumOf { it.count })
        val thirtyDays = snapshot(rows, StatisticsPeriod.DAYS_30)
        assertEquals(3, thirtyDays.tastingEventCount)
        assertEquals(3, thirtyDays.monthlyTastings.sumOf { it.count })
        assertEquals(0, thirtyDays.monthlyTastings.take(4).sumOf { it.count })
    }

    @Test
    fun rebuildingSnapshotReflectsPersistedChangesAndKeepsArchivedHistory() {
        val initial = listOf(tea("tea", stock = 50_000, tastings = listOf(on(today))))
        val before = snapshot(initial)
        val edited = listOf(tea("tea", stock = 20_000, tastings = listOf(on(today), on(today.minusDays(1)))))
        val after = snapshot(edited)
        assertEquals(50_000L, before.knownStockMilliGrams)
        assertEquals(1, before.tastingEventCount)
        assertEquals(20_000L, after.knownStockMilliGrams)
        assertEquals(2, after.tastingEventCount)
        val archived = snapshot(listOf(tea("tea", active = false, stock = 20_000, tastings = listOf(on(today)))))
        assertEquals(0, archived.activeReferenceCount)
        assertEquals(0L, archived.knownStockMilliGrams)
        assertEquals(1, archived.tastingEventCount)
        assertEquals("tea", archived.topFive.single().label)
    }

    @Test
    fun observationRefreshesAfterDataAndCommonPeriodChanges() = runBlocking {
        val rows = MutableStateFlow(listOf(tea("tea", tastings = listOf(on(today), on(today.minusDays(60))))))
        val period = MutableStateFlow(StatisticsPeriod.YEAR)
        val snapshots = Channel<StatisticsSnapshot>(capacity = 3)
        val collection = launch {
            observeStatisticsSnapshots(rows, period, clock, zone).take(3).collect(snapshots::send)
        }

        assertEquals(2, withTimeout(1_000) { snapshots.receive() }.tastingEventCount)
        period.value = StatisticsPeriod.DAYS_30
        assertEquals(1, withTimeout(1_000) { snapshots.receive() }.tastingEventCount)
        rows.value = listOf(tea("tea", tastings = listOf(on(today), on(today), on(today.minusDays(60)))))
        assertEquals(2, withTimeout(1_000) { snapshots.receive() }.tastingEventCount)
        collection.join()
    }

    private fun snapshot(rows: List<StatisticsTea>, period: StatisticsPeriod = StatisticsPeriod.YEAR) =
        buildStatisticsSnapshot(rows, period, clock, zone)

    private fun tea(
        name: String,
        category: StatisticsReference = green,
        active: Boolean = true,
        stock: Long? = null,
        countries: List<StatisticsReference> = emptyList(),
        badges: List<StatisticsReference> = emptyList(),
        tastings: List<Long> = emptyList(),
        status: String? = null,
    ) = StatisticsTea(
        id = UUID.nameUUIDFromBytes(name.toByteArray()),
        name = name,
        active = active,
        category = category,
        stockMilliGrams = stock,
        countries = countries,
        badges = badges,
        tastingDatesEpochMillis = tastings,
        statusSemanticKey = status,
    )

    private fun ref(id: String, label: String, key: String?) = StatisticsReference(UUID.nameUUIDFromBytes(id.toByteArray()), label, key)
    private fun on(date: LocalDate): Long = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
}
