package fr.thesdupalais.domain.madjo

import fr.thesdupalais.data.database.BuiltInSemanticDictionary
import fr.thesdupalais.domain.semantics.MadjoSemanticResolver
import fr.thesdupalais.domain.semantics.SemanticAmbiguity
import fr.thesdupalais.domain.semantics.SemanticConcept
import fr.thesdupalais.domain.semantics.SemanticDomain
import fr.thesdupalais.domain.semantics.SemanticExpression
import fr.thesdupalais.domain.semantics.SemanticDictionarySnapshot
import fr.thesdupalais.domain.semantics.SemanticRelation
import fr.thesdupalais.domain.semantics.SemanticRelationType
import fr.thesdupalais.domain.semantics.SemanticDictionaryValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticDictionaryTest {
    private val dictionary = SemanticDictionarySnapshot(
        concepts = BuiltInSemanticDictionary.conceptRows.map {
            SemanticConcept(it.id, SemanticDomain.valueOf(it.domain), it.label, it.active, it.businessDimension, it.businessConcept, it.businessValue)
        },
        expressions = BuiltInSemanticDictionary.expressionRows.map {
            SemanticExpression(it.id, it.conceptId, it.expression, it.normalizedForm, it.isPrimary, it.active)
        },
        relations = BuiltInSemanticDictionary.relationRows.map {
            SemanticRelation(it.id, it.sourceConceptId, it.targetConceptId, SemanticRelationType.valueOf(it.type), it.active)
        },
    )

    @Test fun normalizationHandlesCaseAccentsWhitespaceAndPunctuation() {
        assertEquals("the vert fruits des bois", MadjoSemanticResolver.normalize("  THÉ—VERT,  Fruits des bois! "))
        assertEquals("stock important", MadjoSemanticResolver.normalize("Stock   important"))
    }

    @Test fun synonymsAndComposedPhrasesResolveWithoutSubstringFalsePositives() {
        assertEquals("Fruité", MadjoSemanticResolver.resolve("FRUITS", dictionary).matches.first { it.relationType == null }.concept.label)
        assertEquals("Fruits des bois", MadjoSemanticResolver.resolve("thé aux fruits des bois", dictionary).matches.first { it.relationType == null }.concept.label)
        assertTrue(MadjoSemanticResolver.resolve("fructose", dictionary).matches.isEmpty())
    }

    @Test fun vocabularyCoversSeveralDomainsAndBusinessIntentions() {
        val result = MadjoSemanticResolver.resolve("un thé vert en matinée, stock important, souvent dégusté", dictionary)
        assertTrue(result.matches.any { it.concept.domain == SemanticDomain.CATEGORY })
        assertTrue(result.matches.any { it.concept.domain == SemanticDomain.MOMENT })
        assertTrue(result.matches.any { it.concept.domain == SemanticDomain.STOCK && it.concept.businessValue == "abundant" })
        assertTrue(result.matches.any { it.concept.domain == SemanticDomain.TASTING_HISTORY && it.concept.businessValue == "frequent" })
    }

    @Test fun specializationIsDirectedAndExpandsExactlyOneLevel() {
        val fruityId = BuiltInSemanticDictionary.conceptId(SemanticDomain.AROMA, "fruity")
        val berriesId = BuiltInSemanticDictionary.conceptId(SemanticDomain.AROMA, "berries")
        val citrusId = BuiltInSemanticDictionary.conceptId(SemanticDomain.AROMA, "citrus")
        val requestGeneral = MadjoSemanticResolver.resolve("fruité", dictionary).matches
        assertTrue(requestGeneral.any { it.concept.id == berriesId && it.relationType == SemanticRelationType.SPECIALIZATION })
        assertTrue(requestGeneral.any { it.concept.id == citrusId && it.relationType == SemanticRelationType.SPECIALIZATION })
        val requestSpecific = MadjoSemanticResolver.resolve("fruits des bois", dictionary).matches
        assertTrue(requestSpecific.any { it.concept.id == berriesId })
        assertFalse(requestSpecific.any { it.concept.id == fruityId || it.concept.id == citrusId })

        val leaf = SemanticConcept("leaf", SemanticDomain.AROMA, "Mûre", true, "AROMA", "aroma", "BLACKBERRY")
        val twoHop = dictionary.copy(
            concepts = dictionary.concepts + leaf,
            relations = dictionary.relations + SemanticRelation("berries-leaf", berriesId, leaf.id, SemanticRelationType.SPECIALIZATION),
        )
        assertFalse(MadjoSemanticResolver.resolve("fruité", twoHop).matches.any { it.concept.id == leaf.id })
    }

    @Test fun samePhraseCanSurfaceAnExplicitAmbiguity() {
        val one = SemanticConcept("one", SemanticDomain.MOMENT, "Matin")
        val two = SemanticConcept("two", SemanticDomain.MOMENT, "Matinée")
        val ambiguous = SemanticDictionarySnapshot(
            concepts = listOf(one, two),
            expressions = listOf(
                SemanticExpression("one-e", one.id, "moment doux", "moment doux", false),
                SemanticExpression("two-e", two.id, "moment doux", "moment doux", false),
            ),
        )
        val result = MadjoSemanticResolver.resolve("moment doux", ambiguous)
        assertTrue(result.matches.isEmpty())
        assertEquals(listOf(one.id, two.id), result.ambiguities.single().concepts.map { it.id })
    }

    @Test fun validatorRejectsCyclesAndCrossDomainSpecialization() {
        val general = SemanticConcept("general", SemanticDomain.AROMA, "Général")
        val specific = SemanticConcept("specific", SemanticDomain.AROMA, "Spécifique")
        val otherDomain = SemanticConcept("other", SemanticDomain.CATEGORY, "Autre")
        val edge = SemanticRelation("edge", general.id, specific.id, SemanticRelationType.SPECIALIZATION)
        SemanticDictionaryValidator.validateSpecialization(general, specific, emptyList())
        assertTrue(runCatching { SemanticDictionaryValidator.validateSpecialization(specific, general, listOf(edge)) }.isFailure)
        assertTrue(runCatching { SemanticDictionaryValidator.validateSpecialization(general, otherDomain, emptyList()) }.isFailure)
    }

    @Test fun parserCombinesColorAromaOriginAndM41HistoryThroughRealReferences() {
        val categoryGreen = MadjoReference("green", "GREEN", "Thé vert")
        val aromaBerries = MadjoReference("berries", "BERRIES", "Fruits des bois")
        val countryVietnam = MadjoReference("vietnam", "VIETNAM", "Vietnam")
        val enriched = MadjoVocabulary(
            categories = listOf(categoryGreen), categoryOptions = listOf(categoryGreen),
            aromas = listOf(aromaBerries), countries = listOf(countryVietnam),
            availableStockCount = 1, semanticDictionary = dictionary,
        )
        val state = MadjoConversationEngine.start("Madjo, je voudrais un thé vert aux fruits du Vietnam, souvent dégusté", enriched)
        val criteria = state.criteria.criteria
        assertTrue(criteria.any { it.dimension == MadjoDimension.TYPE_COLOR && it.value == "GREEN" })
        assertTrue(criteria.any { it.dimension == MadjoDimension.AROMA && it.semanticExpansions.any { expansion -> expansion.conceptId == BuiltInSemanticDictionary.conceptId(SemanticDomain.AROMA, "berries") } })
        assertTrue(criteria.any { it.dimension == MadjoDimension.ORIGIN && it.referenceId == countryVietnam.id })
        assertTrue(criteria.any { it.dimension == MadjoDimension.TASTING_HISTORY && it.concept == "tasting_frequency" })
        assertTrue(criteria.none { it.dimension == MadjoDimension.AROMA && it.value == "FRUITY" } || criteria.any { it.semanticExpansions.isNotEmpty() })
    }

    @Test fun explicitSemanticConstraintsRemainStrictAcrossCombinedDimensions() {
        val green = MadjoReference("green", "GREEN", "Thé vert")
        val berries = MadjoReference("berries", "BERRIES", "Fruits des bois")
        val citrus = MadjoReference("citrus", "CITRUS", "Agrumes")
        val vietnam = MadjoReference("vietnam", "VIETNAM", "Vietnam")
        val enriched = MadjoVocabulary(categories = listOf(green), aromas = listOf(berries, citrus), countries = listOf(vietnam), semanticDictionary = dictionary)
        val criteria = MadjoConversationEngine.start("Madjo, un thé vert fruité du Vietnam", enriched).criteria
        assertTrue(criteria.absoluteConstraints.any { it.dimension == MadjoDimension.TYPE_COLOR })
        assertTrue(criteria.absoluteConstraints.any { it.dimension == MadjoDimension.AROMA })
        assertTrue(criteria.absoluteConstraints.any { it.dimension == MadjoDimension.ORIGIN })
        val correct = MadjoTeaProfile("correct", "Vert Vietnam baies", green.id, "GREEN", green.label,
            countries = listOf(vietnam), aromas = listOf(berries))
        val incorrect = MadjoTeaProfile("wrong", "Vert Vietnam agrumes", green.id, "GREEN", green.label,
            countries = listOf(vietnam), aromas = listOf(citrus))
        assertEquals(listOf("correct"), MadjoRecommendationEngine.recommend(listOf(correct, incorrect), criteria).recommendations.map { it.tea.id })
    }

    @Test fun forgottenIntentExcludesTeasNeverTasted() {
        val criteria = MadjoConversationEngine.start("Madjo, propose un thé que je n’ai pas dégusté depuis longtemps", MadjoVocabulary(semanticDictionary = dictionary)).criteria
        assertTrue(criteria.criteria.any { it.dimension == MadjoDimension.TASTING_HISTORY && it.concept == "tasting_recency" })
        val never = MadjoTeaProfile("never", "Jamais bu", "c", "GREEN", "Thé vert")
        val old = MadjoTeaProfile("old", "Ancien favori", "c", "GREEN", "Thé vert", tastingCount = 1, lastTastedAtEpochMillis = 1L)
        assertEquals(listOf("old"), MadjoRecommendationEngine.recommend(listOf(never, old), criteria).recommendations.map { it.tea.id })
    }

    @Test fun stockAndHistoryIntentsUseExistingEngineFields() {
        val tea = MadjoTeaProfile(
            id = "tea-1", name = "Vietnam fruits des bois", categoryId = "green", categoryKey = "GREEN", categoryLabel = "Thé vert",
            countries = listOf(MadjoReference("vietnam", "VIETNAM", "Vietnam")),
            aromas = listOf(MadjoReference("berries", "BERRIES", "Fruits des bois")), stockMilliGrams = 55_000L,
            tastingCount = 8, lastTastedAtEpochMillis = 10L,
        )
        val enriched = MadjoVocabulary(categories = listOf(MadjoReference("green", "GREEN", "Thé vert")), aromas = listOf(MadjoReference("berries", "BERRIES", "Fruits des bois")), countries = listOf(MadjoReference("vietnam", "VIETNAM", "Vietnam")), availableStockCount = 1, semanticDictionary = dictionary)
        val state = MadjoConversationEngine.start("Madjo, un thé stock important que je bois régulièrement", enriched)
        val outcome = MadjoRecommendationEngine.recommend(listOf(tea), state.criteria)
        assertEquals(listOf("tea-1"), outcome.recommendations.map { it.tea.id })
    }

    @Test fun specializationExplanationNamesOnlyTheActualMatchingAroma() {
        val berries = MadjoReference("berries", "BERRIES", "Fruits des bois")
        val enriched = MadjoVocabulary(aromas = listOf(berries), semanticDictionary = dictionary)
        val state = MadjoConversationEngine.start("Madjo, un thé fruité", enriched)
        val tea = MadjoTeaProfile(
            id = "berry-tea", name = "Thé aux fruits des bois", categoryId = "green", categoryKey = "GREEN", categoryLabel = "Thé vert",
            aromas = listOf(berries),
        )
        val recommendation = MadjoRecommendationEngine.recommend(listOf(tea), state.criteria).recommendations.single()
        assertTrue(recommendation.explanation.contains("Correspond à votre recherche fruité grâce à son arôme Fruits des bois."))
    }
}
