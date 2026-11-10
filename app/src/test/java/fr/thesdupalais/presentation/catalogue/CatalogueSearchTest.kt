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

    @Test fun madjoAliasesRouteOnlyAtStartAsCompleteWords() {
        listOf("Madjo", "  MADJO aide", "Majdo: choix", "mAjO?", "MADO, aide-moi").forEach {
            assertEquals(it, CatalogueQueryRoute.MADJO, routeCatalogueQuery(it))
        }
        listOf("Madjolina", "thé madjo", "Madjoka", "Madoya").forEach {
            assertEquals(it, CatalogueQueryRoute.CLASSIC, routeCatalogueQuery(it))
        }
    }

    @Test fun searchIgnoresCaseAccentsAndExtraSpacesAndMatchesWordsAcrossFields() {
        assertTrue(matchesCatalogueTea(collectionTea, CatalogueCriteria(query = "  THE   JAPON   jasmin ")))
        assertTrue(matchesCatalogueTea(collectionTea, CatalogueCriteria(query = "floral uji")))
        assertTrue(matchesCatalogueTea(collectionTea, CatalogueCriteria(query = "THÉ, JAPÓN!")))
        assertFalse(matchesCatalogueTea(collectionTea, CatalogueCriteria(query = "thé absent")))
        assertEquals(CatalogueQueryRoute.CLASSIC, routeCatalogueQuery("thé madjo"))
        val madjoTea = collectionTea.copy(searchableFields = collectionTea.searchableFields + "Madjo")
        assertTrue(matchesCatalogueTea(madjoTea, CatalogueCriteria(query = "thé madjo")))
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
}
