package fr.thesdupalais.domain.madjo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MadjoRecommendationEngineTest {
    private val green = MadjoReference("green", "GREEN", "Thé vert")
    private val black = MadjoReference("black", "BLACK", "Thé noir")
    private val china = MadjoReference("china", "CHINA", "Chine")
    private val japan = MadjoReference("japan", "JAPAN", "Japon")
    private val floral = MadjoReference("floral", "FLORAL", "Floral")
    private val fruity = MadjoReference("fruity", "FRUITY", "Fruité")
    private val evening = MadjoReference("evening", "EVENING", "Soir")

    private val greenChinaFloral = tea("1", "Sencha floral", green, countries = listOf(china), aromas = listOf(floral), rating = 6.0, stock = 1200L)
    private val blackJapanFruity = tea("2", "Noir fruité", black, countries = listOf(japan), aromas = listOf(fruity), rating = 10.0, stock = 0L)
    private val greenChinaUnknownStock = tea("3", "Vert du soir", green, countries = listOf(china), moments = listOf(evening), rating = 8.0, stock = null)

    @Test fun hardConstraintsKeepAnIncompatibleHighRatedTeaOutOfResults() {
        val criteria = MadjoRecommendationCriteria(listOf(
            criterion(MadjoDimension.TYPE_COLOR, "category", "GREEN", green, MadjoCriterionKind.ABSOLUTE_CONSTRAINT),
        ))
        val result = MadjoRecommendationEngine.recommend(listOf(blackJapanFruity, greenChinaFloral), criteria)
        assertEquals(listOf(greenChinaFloral.id), result.recommendations.map { it.tea.id })
    }

    @Test fun softPreferenceChangesOrderWithoutExcludingOtherCandidates() {
        val criteria = MadjoRecommendationCriteria(listOf(
            criterion(MadjoDimension.AROMA, "aroma", "FLORAL", floral, MadjoCriterionKind.PREFERENCE),
        ))
        val result = MadjoRecommendationEngine.recommend(
            listOf(blackJapanFruity, greenChinaFloral), criteria,
            weights = MadjoRankingWeights(aroma = 80, defaultRatingBonus = 0, defaultStockBonus = 0),
        )
        assertEquals(greenChinaFloral.id, result.recommendations.first().tea.id)
        assertEquals(2, result.recommendations.size)
    }

    @Test fun softAssociationAddsModerateTraceButNeverSatisfiesHardConstraint() {
        val vanilla = MadjoReference("vanilla", "VANILLA", "Vanillé")
        val linked = tea("linked", "Thé vanillé", green, aromas = listOf(vanilla), rating = 5.0).copy(stockMilliGrams = 0L)
        val plain = tea("plain", "Thé neutre", green, rating = 5.0).copy(stockMilliGrams = 0L)
        val softAlternative = MadjoSemanticAlternative(
            "vanilla-concept", "Vanillé", setOf("VANILLA"), vanilla.id,
            MadjoRelation.SEMANTIC_ASSOCIATION, "AROMA", "aroma", "VANILLA", "association-1", 75,
        )
        val soft = MadjoCriterion(
            MadjoDimension.AROMA, "aroma", "GOURMAND", MadjoCriterionKind.PREFERENCE,
            MadjoCriterionSource.INITIAL_REQUEST, displayValue = "Gourmand",
            softAssociations = listOf(softAlternative), softAssociationSourceId = "gourmand-concept",
        )
        val ranked = MadjoRecommendationEngine.recommend(
            listOf(plain, linked), MadjoRecommendationCriteria(listOf(soft)),
            weights = MadjoRankingWeights(defaultRatingBonus = 0, defaultStockBonus = 0),
        ).recommendations
        assertEquals("linked", ranked.first().tea.id)
        assertEquals(15, ranked.first().softAssociationTraces.single().contribution)
        assertTrue(ranked.first().explanation.contains("Rapprochement souple"))

        val hard = soft.copy(kind = MadjoCriterionKind.ABSOLUTE_CONSTRAINT)
        val strictResults = MadjoRecommendationEngine.recommend(listOf(plain, linked), MadjoRecommendationCriteria(listOf(hard)))
        assertTrue(strictResults.recommendations.isEmpty())
    }

    @Test fun directMatchOutranksSoftAssociationAndConvergingLinksAreNotCountedTwice() {
        val vanilla = MadjoReference("vanilla", "VANILLA", "Vanillé")
        val directTea = tea("direct", "Direct", green, aromas = listOf(fruity), rating = 5.0).copy(stockMilliGrams = 0L)
        val linkedTea = tea("linked", "Linked", green, aromas = listOf(vanilla), rating = 5.0).copy(stockMilliGrams = 0L)
        fun alternative(id: String, weight: Int) = MadjoSemanticAlternative(
            "vanilla-$id", "Vanillé", setOf("VANILLA"), vanilla.id,
            MadjoRelation.SEMANTIC_ASSOCIATION, "AROMA", "aroma", "VANILLA", id, weight,
        )
        val directCriterion = MadjoCriterion(
            MadjoDimension.AROMA, "aroma", "FRUITY", MadjoCriterionKind.PREFERENCE,
            MadjoCriterionSource.INITIAL_REQUEST, displayValue = "Fruité",
        )
        val indirectCriterion = MadjoCriterion(
            MadjoDimension.AROMA, "aroma", "GOURMAND", MadjoCriterionKind.PREFERENCE,
            MadjoCriterionSource.INITIAL_REQUEST, displayValue = "Gourmand",
            softAssociations = listOf(alternative("a1", 75), alternative("a2", 75)), softAssociationSourceId = "gourmand",
        )
        val result = MadjoRecommendationEngine.recommend(
            listOf(linkedTea, directTea), MadjoRecommendationCriteria(listOf(directCriterion, indirectCriterion)),
            weights = MadjoRankingWeights(defaultRatingBonus = 0, defaultStockBonus = 0),
        ).recommendations
        assertEquals("direct", result.first().tea.id)
        assertEquals(1, result.single { it.tea.id == "linked" }.softAssociationTraces.size)
        assertEquals(15, result.single { it.tea.id == "linked" }.softAssociationTraces.single().contribution)
    }

    @Test fun categoryOriginAndAromaConstraintsCombineAndExplanationsUseRealFields() {
        val criteria = MadjoRecommendationCriteria(listOf(
            criterion(MadjoDimension.TYPE_COLOR, "category", "GREEN", green, MadjoCriterionKind.ABSOLUTE_CONSTRAINT),
            criterion(MadjoDimension.ORIGIN, "country", "CHINA", china, MadjoCriterionKind.ABSOLUTE_CONSTRAINT),
            criterion(MadjoDimension.AROMA, "aroma", "FLORAL", floral, MadjoCriterionKind.ABSOLUTE_CONSTRAINT),
        ))
        val result = MadjoRecommendationEngine.recommend(listOf(blackJapanFruity, greenChinaFloral, greenChinaUnknownStock), criteria)
        assertEquals(listOf(greenChinaFloral.id), result.recommendations.map { it.tea.id })
        assertTrue(result.recommendations.single().explanation.contains("Thé vert"))
        assertTrue(result.recommendations.single().explanation.contains("Chine"))
        assertTrue(result.recommendations.single().explanation.contains("Floral"))
    }

    @Test fun stockStatesStayDistinctAndAnInStockConstraintExcludesZeroAndUnknown() {
        val profiles = listOf(greenChinaFloral, blackJapanFruity, greenChinaUnknownStock)
        assertEquals(MadjoStockState.AVAILABLE, profiles[0].stockState)
        assertEquals(MadjoStockState.OUT_OF_STOCK, profiles[1].stockState)
        assertEquals(MadjoStockState.UNKNOWN, profiles[2].stockState)
        val criterion = MadjoCriterion(MadjoDimension.STOCK, "stock", "available", MadjoCriterionKind.ABSOLUTE_CONSTRAINT, MadjoCriterionSource.INITIAL_REQUEST)
        val result = MadjoRecommendationEngine.recommend(profiles, MadjoRecommendationCriteria(listOf(criterion)))
        assertEquals(listOf(greenChinaFloral.id), result.recommendations.map { it.tea.id })
        assertTrue(result.recommendations.single().explanation.contains("Disponible en stock"))
    }

    @Test fun stockPreferencePromotesAvailableTeaWithoutExcludingTheOthers() {
        val available = greenChinaFloral.copy(tasteRating = 7.0)
        val exhausted = blackJapanFruity.copy(tasteRating = 7.0)
        val criterion = MadjoCriterion(MadjoDimension.STOCK, "stock", "available", MadjoCriterionKind.PREFERENCE, MadjoCriterionSource.USER_ANSWER)
        val result = MadjoRecommendationEngine.recommend(listOf(exhausted, available), MadjoRecommendationCriteria(listOf(criterion)))
        assertEquals(available.id, result.recommendations.first().tea.id)
        assertEquals(2, result.recommendations.size)
    }

    @Test fun withoutStockConstraintAllStatusesRemainVisibleAndAreExplained() {
        val result = MadjoRecommendationEngine.recommend(
            listOf(greenChinaFloral, blackJapanFruity, greenChinaUnknownStock),
            MadjoRecommendationCriteria(emptyList()),
        )
        assertEquals(3, result.recommendations.size)
        assertTrue(result.recommendations.any { it.explanation.contains("Disponible en stock") })
        assertTrue(result.recommendations.any { it.explanation.contains("Épuisé") })
        assertTrue(result.recommendations.any { it.explanation.contains("Stock inconnu") })
    }

    @Test fun requestedBestTeasUseTasteRatingAsAnImportantSortFactor() {
        val criterion = MadjoCriterion(MadjoDimension.TASTE_RATING, "taste_rating", "top_rated", MadjoCriterionKind.PREFERENCE, MadjoCriterionSource.INITIAL_REQUEST)
        val result = MadjoRecommendationEngine.recommend(listOf(greenChinaFloral, blackJapanFruity), MadjoRecommendationCriteria(listOf(criterion)))
        assertEquals(blackJapanFruity.id, result.recommendations.first().tea.id)
        assertTrue(result.recommendations.first().explanation.contains("10/10"))
    }

    @Test fun minimumTasteRatingConstraintUsesOnlyNumericRatings() {
        val criterion = MadjoCriterion(MadjoDimension.TASTE_RATING, "taste_rating", "at_least:8", MadjoCriterionKind.ABSOLUTE_CONSTRAINT, MadjoCriterionSource.INITIAL_REQUEST)
        val result = MadjoRecommendationEngine.recommend(
            listOf(greenChinaFloral, blackJapanFruity, greenChinaUnknownStock),
            MadjoRecommendationCriteria(listOf(criterion)),
        )
        assertEquals(setOf(blackJapanFruity.id, greenChinaUnknownStock.id), result.recommendations.map { it.tea.id }.toSet())
    }

    @Test fun noResultOffersRelaxationButDoesNotApplyItBeforeConsent() {
        val constraint = criterion(MadjoDimension.TYPE_COLOR, "category", "WHITE", MadjoReference("white", "WHITE", "Thé blanc"), MadjoCriterionKind.ABSOLUTE_CONSTRAINT)
        val criteria = MadjoRecommendationCriteria(listOf(constraint))
        val beforeConsent = MadjoRecommendationEngine.recommend(listOf(greenChinaFloral, blackJapanFruity), criteria)
        assertFalse(beforeConsent.hasResults)
        assertEquals(1, beforeConsent.relaxationOptions.size)
        val afterConsent = MadjoRecommendationEngine.recommend(
            listOf(greenChinaFloral, blackJapanFruity), criteria, acceptedRelaxations = listOf(constraint),
        )
        assertTrue(afterConsent.hasResults)
        assertEquals(listOf(constraint), afterConsent.acceptedRelaxations)
    }

    @Test fun questionEvidenceSkipsDimensionsThatCannotSeparateCompatibleCandidates() {
        val same = listOf(greenChinaFloral.copy(id = "4"), greenChinaFloral.copy(id = "5"))
        val noUsefulQuestions = MadjoRecommendationEngine.questionEvidence(same, MadjoRecommendationCriteria(emptyList()))
        assertTrue(noUsefulQuestions.first.isEmpty())
        val varied = MadjoRecommendationEngine.questionEvidence(listOf(greenChinaFloral, blackJapanFruity), MadjoRecommendationCriteria(emptyList()))
        assertTrue(MadjoQuestionId.TYPE_COLOR in varied.first)
        assertTrue(MadjoQuestionId.AROMA in varied.first)
        assertTrue(MadjoQuestionId.ORIGIN in varied.first)
    }

    @Test fun questionsAreComputedOnlyFromCandidatesThatMeetHardConstraints() {
        val hardGreen = MadjoCriterion(MadjoDimension.TYPE_COLOR, "category", "GREEN", MadjoCriterionKind.ABSOLUTE_CONSTRAINT, MadjoCriterionSource.INITIAL_REQUEST, green.id, green.label)
        val candidates = listOf(
            greenChinaFloral,
            greenChinaUnknownStock.copy(id = "green-evening"),
            blackJapanFruity,
        )
        val evidence = MadjoRecommendationEngine.questionEvidence(candidates, MadjoRecommendationCriteria(listOf(hardGreen)))

        assertTrue(MadjoQuestionId.AROMA in evidence.first)
        assertFalse(MadjoQuestionId.TYPE_COLOR in evidence.first)
        assertFalse(MadjoQuestionId.ORIGIN in evidence.first)
    }

    @Test fun aPreciseRequestWithNoUsefulFollowUpCanGoDirectlyToRecommendations() {
        val vocabulary = MadjoVocabulary(categories = listOf(green), categoryOptions = listOf(green))
        val initial = MadjoConversationEngine.start("Madjo, un thé vert", vocabulary)
        val oneChoice = listOf(tea("4", "Vert 1", green), tea("5", "Vert 2", green))
        val evidence = MadjoRecommendationEngine.questionEvidence(oneChoice, initial.criteria)
        val ready = MadjoConversationEngine.updateQuestionEvidence(
            initial, vocabulary.copy(usefulQuestionIds = evidence.first, candidateOptionIds = evidence.second),
        )

        assertTrue(ready.recommendationReady)
        assertFalse(MadjoConversationEngine.requiresDialogue(ready))
    }

    @Test fun tastingFrequencyAndRecencyRankOnlyRecordedTastings() {
        val frequent = MadjoCriterion(MadjoDimension.TASTING_HISTORY, "tasting_frequency", "frequent", MadjoCriterionKind.PREFERENCE, MadjoCriterionSource.INITIAL_REQUEST)
        val often = greenChinaFloral.copy(id = "often", tastingCount = 14, lastTastedAtEpochMillis = 500L, tasteRating = 7.0)
        val rare = blackJapanFruity.copy(id = "rare", tastingCount = 2, lastTastedAtEpochMillis = 800L, tasteRating = 7.0)
        val never = greenChinaUnknownStock.copy(id = "never", tastingCount = 0, lastTastedAtEpochMillis = null, tasteRating = 7.0)
        val frequencyResult = MadjoRecommendationEngine.recommend(listOf(rare, never, often), MadjoRecommendationCriteria(listOf(frequent)))
        assertEquals(listOf("often", "rare", "never"), frequencyResult.recommendations.map { it.tea.id })

        val forgotten = frequent.copy(concept = "tasting_recency", value = "oldest_first")
        val recent = often.copy(id = "recent", lastTastedAtEpochMillis = 900L)
        val old = rare.copy(id = "old", lastTastedAtEpochMillis = 100L)
        val recencyResult = MadjoRecommendationEngine.recommend(listOf(recent, never, old), MadjoRecommendationCriteria(listOf(forgotten)))
        assertEquals(listOf("old", "recent", "never"), recencyResult.recommendations.map { it.tea.id })
        assertTrue(recencyResult.recommendations.first().explanation.contains("plus longtemps"))
        assertFalse(recencyResult.recommendations.last().explanation.contains("plus longtemps"))
    }

    @Test fun tastingHistoryPreferenceNeverWeakensExistingCategoryOrOriginConstraints() {
        val categoryConstraint = criterion(MadjoDimension.TYPE_COLOR, "category", "GREEN", green, MadjoCriterionKind.ABSOLUTE_CONSTRAINT)
        val originConstraint = criterion(MadjoDimension.ORIGIN, "country", "CHINA", china, MadjoCriterionKind.ABSOLUTE_CONSTRAINT)
        val historyPreference = MadjoCriterion(MadjoDimension.TASTING_HISTORY, "tasting_frequency", "frequent", MadjoCriterionKind.PREFERENCE, MadjoCriterionSource.INITIAL_REQUEST)
        val frequentMatch = greenChinaFloral.copy(id = "frequent", tastingCount = 20)
        val frequentWrongOrigin = greenChinaUnknownStock.copy(id = "wrong-origin", tastingCount = 100, countries = listOf(japan))
        val result = MadjoRecommendationEngine.recommend(
            listOf(frequentMatch, frequentWrongOrigin, blackJapanFruity.copy(tastingCount = 200)),
            MadjoRecommendationCriteria(listOf(categoryConstraint, originConstraint, historyPreference)),
        )
        assertEquals(listOf("frequent"), result.recommendations.map { it.tea.id })
    }

    @Test fun tenCandidatesWithNoMeaningfulDifferencesDoNotTriggerArbitraryFollowUpQuestions() {
        val vocabulary = MadjoVocabulary(categories = listOf(green), categoryOptions = listOf(green))
        val initial = MadjoConversationEngine.start("Madjo, un thé vert", vocabulary)
        val sameCandidates = (1..10).map { tea("$it", "Vert $it", green, rating = 8.0) }
        val evidence = MadjoRecommendationEngine.questionEvidence(sameCandidates, initial.criteria)
        assertTrue(evidence.first.isEmpty())
        val ready = MadjoConversationEngine.updateQuestionEvidence(
            initial, vocabulary.copy(usefulQuestionIds = evidence.first, candidateOptionIds = evidence.second),
        )
        assertTrue(ready.recommendationReady)
        assertTrue(ready.questionsAsked.isEmpty())
    }

    @Test fun momentsMatchOnlyStoredReferencesAndDoNotInferTheine() {
        val criterion = criterion(MadjoDimension.MOMENT, "moment", "EVENING", evening, MadjoCriterionKind.ABSOLUTE_CONSTRAINT)
        val result = MadjoRecommendationEngine.recommend(listOf(greenChinaFloral, greenChinaUnknownStock), MadjoRecommendationCriteria(listOf(criterion)))
        assertEquals(listOf(greenChinaUnknownStock.id), result.recommendations.map { it.tea.id })
        assertFalse(result.recommendations.single().explanation.contains("théine", ignoreCase = true))
    }

    @Test fun criteriaFromTheDialoguePassDirectlyToRecommendationResults() {
        val vocabulary = MadjoVocabulary(
            categories = listOf(green, black), categoryOptions = listOf(green, black),
            countries = listOf(china, japan), aromas = listOf(floral, fruity), moments = listOf(evening),
        )
        val conversation = MadjoConversationEngine.start("Un thé vert floral de Chine pour le soir", vocabulary)
        val criteriaBefore = conversation.criteria
        val outcome = MadjoRecommendationEngine.recommend(listOf(greenChinaFloral, blackJapanFruity), conversation.criteria)

        assertSame(criteriaBefore, conversation.criteria)
        assertEquals(listOf(greenChinaFloral.id), outcome.recommendations.map { it.tea.id })
    }

    private fun criterion(dimension: MadjoDimension, concept: String, value: String, ref: MadjoReference, kind: MadjoCriterionKind) =
        MadjoCriterion(dimension, concept, value, kind, MadjoCriterionSource.INITIAL_REQUEST, ref.id, ref.label)

    private fun tea(
        id: String,
        name: String,
        category: MadjoReference,
        countries: List<MadjoReference> = emptyList(),
        aromas: List<MadjoReference> = emptyList(),
        moments: List<MadjoReference> = emptyList(),
        rating: Double? = null,
        stock: Long? = null,
    ) = MadjoTeaProfile(
        id, name, category.id, category.semanticKey, category.label,
        countries = countries, aromas = aromas, moments = moments,
        tasteRating = rating, stockMilliGrams = stock,
    )
}
