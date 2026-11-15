package fr.thesdupalais.presentation.catalogue

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueSearchTest {
    private val green = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val black = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val japan = UUID.fromString("00000000-0000-4000-8000-000000000003")
    private val china = UUID.fromString("00000000-0000-4000-8000-000000000004")
    private val profile = UUID.fromString("00000000-0000-4000-8000-000000000006")
    private val badge = UUID.fromString("00000000-0000-4000-8000-000000000007")
    private val container = UUID.fromString("00000000-0000-4000-8000-000000000008")
    private val moment = UUID.fromString("00000000-0000-4000-8000-000000000009")
    private val collectionTea = CatalogueTea(
        id = UUID.fromString("00000000-0000-4000-8000-000000000005"), name = "Thé du matin", category = "Thé vert",
        categoryKey = "GREEN", categoryId = green, origins = listOf("Japon — Uji"), countryIds = setOf(japan),
        personalNotes = null, tasteRating = null, statusId = null,
        searchableFields = listOf("Thé du matin", "Thé vert", "Japon", "Uji", "Jasmin", "Floral"),
    )

    @Test fun madjoAliasesRouteAnywhereWithCaseAccentPunctuationAndSmallTypos() {
        listOf(
            "Madjo, conseille-moi un thé floral",
            "Dis-moi Majdo, que boire ce soir ?",
            "Pour ce soir, Madjo, tu proposes quoi ?",
            "  MADJO aide", "Majdo: choix", "mAjO?", "MADO, aide-moi", "Madjó, aide-moi", "Madjoo, aide-moi",
        ).forEach {
            assertEquals(it, CatalogueQueryRoute.MADJO, routeCatalogueQuery(it))
        }
    }

    @Test fun madjoDetectionDoesNotMatchOrdinaryWordsContainingSimilarLetters() {
        listOf("Madjolina", "Madjoka", "Madoya", "thé majordome", "un thé floral").forEach {
            assertEquals(it, CatalogueQueryRoute.CLASSIC, routeCatalogueQuery(it))
        }
    }

    @Test fun madjoRequestTextIsPreservedVerbatimInCriteria() {
        val request = "Pour ce soir, MADJO, tu proposes quoi ?"
        val criteria = CatalogueCriteria(query = request)
        assertEquals(request, criteria.query)
        assertEquals(CatalogueQueryRoute.MADJO, routeCatalogueQuery(criteria.query))
    }

    @Test fun newMadjoSessionUsesTheExactNewRequestAndStartsWithFreshCriteria() {
        val japanRef = fr.thesdupalais.domain.madjo.MadjoReference("japan", "JAPAN", "Japon")
        val vocabulary = fr.thesdupalais.domain.madjo.MadjoVocabulary(countries = listOf(japanRef))
        val previous = startMadjoSession("Madjo, un thé vert du Japon", vocabulary)
        val nextRequest = "  MADJO, un thé noir  "
        val next = startMadjoSession(nextRequest, vocabulary)
        assertTrue(previous.criteria.criteria.any { it.concept == "country" })
        assertEquals(nextRequest, next.initialRequest)
        assertFalse(next.criteria.criteria.any { it.concept == "country" })
        assertTrue(next.answers.isEmpty())
        assertTrue(next.messages.none { it.text.contains("Japon") })
    }

    @Test fun searchIgnoresCaseAccentsAndExtraSpacesAndMatchesWordsAcrossFields() {
        assertTrue(matchesCatalogueTea(collectionTea, CatalogueCriteria(query = "  THE   JAPON   jasmin ")))
        assertTrue(matchesCatalogueTea(collectionTea, CatalogueCriteria(query = "floral uji")))
        assertTrue(matchesCatalogueTea(collectionTea, CatalogueCriteria(query = "THÉ, JAPÓN!")))
        assertFalse(matchesCatalogueTea(collectionTea, CatalogueCriteria(query = "thé absent")))
        assertEquals(CatalogueQueryRoute.CLASSIC, routeCatalogueQuery("thé matin"))
        assertFalse(matchesCatalogueTea(collectionTea, CatalogueCriteria(query = "thé madjo")))
    }

    @Test fun filtersUseOrWithinEachGroupAndAndBetweenGroups() {
        val criteria = CatalogueCriteria(categoryIds = setOf(green, black), countryIds = setOf(japan, china))
        assertTrue(matchesCatalogueTea(collectionTea, criteria))
        assertFalse(matchesCatalogueTea(collectionTea, criteria.copy(countryIds = setOf(china))))
        assertFalse(matchesCatalogueTea(collectionTea, criteria.copy(categoryIds = setOf(black))))
    }

    @Test fun statusFilterCanExplicitlySelectUnspecifiedValue() {
        assertTrue(matchesCatalogueTea(collectionTea, CatalogueCriteria(includeUnspecifiedStatus = true)))
        assertFalse(matchesCatalogueTea(collectionTea, CatalogueCriteria(statusIds = setOf(green))))
        assertTrue(matchesCatalogueTea(collectionTea, CatalogueCriteria(statusIds = setOf(black), includeUnspecifiedStatus = true)))
    }

    @Test fun addedReferenceFiltersCombineAsOrWithinAndAndAcrossFamilies() {
        val tea = collectionTea.copy(
            aromaticProfileIds = setOf(profile), badgeIds = setOf(badge), containerId = container,
            momentIds = setOf(moment),
        )
        val criteria = CatalogueCriteria(
            countryIds = setOf(japan, china), aromaticProfileIds = setOf(profile, green),
            badgeIds = setOf(badge), containerIds = setOf(container), momentIds = setOf(moment),
            query = "jasmin",
        )
        assertTrue(matchesCatalogueTea(tea, criteria))
        assertFalse(matchesCatalogueTea(tea, criteria.copy(momentIds = setOf(china))))
        assertFalse(matchesCatalogueTea(tea, criteria.copy(query = "absent")))
    }

    @Test fun tasteRatingFiltersUseExactUnroundedNullableValues() {
        assertTrue(matchesTasteRating(10.0, TasteRatingFilter.EXACTLY_10))
        assertFalse(matchesTasteRating(9.999, TasteRatingFilter.EXACTLY_10))
        assertTrue(matchesTasteRating(8.999, TasteRatingFilter.AT_LEAST_8))
        assertFalse(matchesTasteRating(8.999, TasteRatingFilter.AT_LEAST_9))
        assertTrue(matchesTasteRating(6.0, TasteRatingFilter.AT_LEAST_6))
        assertFalse(matchesTasteRating(null, TasteRatingFilter.AT_LEAST_6))
        assertTrue(matchesTasteRating(null, TasteRatingFilter.UNSPECIFIED))
        assertFalse(matchesTasteRating(0.0, TasteRatingFilter.UNSPECIFIED))
        assertTrue(matchesTasteRating(null, TasteRatingFilter.ALL))
    }

    @Test fun stockFiltersKeepUnknownDistinctAndRespectTheFiftyGramBoundary() {
        assertTrue(matchesStock(null, StockFilter.UNSPECIFIED))
        assertFalse(matchesStock(0L, StockFilter.UNSPECIFIED))
        assertTrue(matchesStock(0L, StockFilter.EMPTY))
        assertFalse(matchesStock(1L, StockFilter.EMPTY))
        assertTrue(matchesStock(49_999L, StockFilter.LOW))
        assertFalse(matchesStock(50_000L, StockFilter.LOW))
        assertTrue(matchesStock(50_000L, StockFilter.IMPORTANT))
        assertFalse(matchesStock(null, StockFilter.IMPORTANT))
        assertTrue(matchesCatalogueTea(collectionTea.copy(stockMilliGrams = 0L), CatalogueCriteria(stock = StockFilter.EMPTY)))
        assertFalse(matchesCatalogueTea(collectionTea.copy(stockMilliGrams = null), CatalogueCriteria(stock = StockFilter.EMPTY)))
    }

    @Test fun defaultSortSeparatesEmptyStockThenRanksKnownRatingsAndUsesStableTies() {
        fun tea(name: String, stock: Long?, rating: Double?, id: String) = collectionTea.copy(
            id = UUID.fromString("00000000-0000-4000-8000-${id.padStart(12, '0')}"), name = name,
            stockMilliGrams = stock, tasteRating = rating,
        )
        val teas = listOf(
            tea("Vide sans note", 0L, null, "5"), tea("Sans stock note", null, 8.0, "4"),
            tea("Disponible B", 1L, 8.0, "3"), tea("Disponible A", 50_000L, 8.0, "2"),
            tea("Disponible sans note", 0L, 8.0, "1"),
        )
        assertEquals(listOf("Disponible A", "Disponible B", "Sans stock note", "Disponible sans note", "Vide sans note"), sortCatalogueDefault(teas).map { it.name })
        assertEquals(sortCatalogueDefault(teas), sortCatalogueDefault(teas))
    }

    @Test fun defaultSortUsesIdentifierWhenNamesAndRatingsAreIdentical() {
        val first = collectionTea.copy(id = UUID.fromString("00000000-0000-4000-8000-000000000001"), name = "Même nom", tasteRating = 9.0, stockMilliGrams = null)
        val second = collectionTea.copy(id = UUID.fromString("00000000-0000-4000-8000-000000000002"), name = "Même nom", tasteRating = 9.0, stockMilliGrams = 10_000L)
        assertEquals(listOf(first.id, second.id), sortCatalogueDefault(listOf(second, first)).map { it.id })
    }
}
