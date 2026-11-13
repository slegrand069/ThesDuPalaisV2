package fr.thesdupalais.domain.madjo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MadjoConversationEngineTest {
    private val categoryGreen = MadjoReference("cat-green", "GREEN", "Thé vert")
    private val categoryBlack = MadjoReference("cat-black", "BLACK", "Thé noir")
    private val categoryWhite = MadjoReference("cat-white", "WHITE", "Thé blanc")
    private val categoryInfusion = MadjoReference("cat-infusion", "INFUSION", "Infusion")
    private val aromaFloral = MadjoReference("aroma-floral", "FLORAL", "Floral")
    private val aromaFruity = MadjoReference("aroma-fruity", "FRUITY", "Fruité")
    private val aromaSpicy = MadjoReference("aroma-spicy", "SPICY", "Épicé")
    private val momentEvening = MadjoReference("moment-evening", "EVENING", "Soir")
    private val momentAfternoon = MadjoReference("moment-afternoon", "AFTERNOON", "Après-midi")
    private val momentBedtime = MadjoReference("moment-bedtime", "BEDTIME", "Coucher")
    private val japan = MadjoReference("country-japan", "JAPAN", "Japon")
    private val france = MadjoReference("country-france", "FRANCE", "France")
    private val darjeeling = MadjoReference("region-darjeeling", "DARJEELING", "Darjeeling", japan.id)
    private val vocabulary = MadjoVocabulary(
        categories = listOf(categoryGreen, categoryBlack, categoryWhite, categoryInfusion),
        categoryOptions = listOf(categoryGreen, categoryBlack, categoryWhite, categoryInfusion),
        aromas = listOf(aromaFloral, aromaFruity, aromaSpicy),
        moments = listOf(momentEvening, momentAfternoon, momentBedtime),
        momentOptions = listOf(momentEvening, momentAfternoon, momentBedtime),
        countries = listOf(japan, france),
        regions = listOf(darjeeling),
        ratedTeaCount = 5,
        highRatedTeaCount = 2,
        availableStockCount = 4,
        outOfStockCount = 1,
        unknownStockCount = 1,
    )

    @Test fun isolatesMadjoAtBeginningMiddleAndEndWithoutLosingTheRequest() {
        listOf(
            "Madjo, quel thé vert ce soir ?" to "quel thé vert ce soir ?",
            "Pour ce soir, Majdo, un thé noir ?" to "Pour ce soir un thé noir ?",
            "Un thé fruité, Madjo !" to "Un thé fruité",
        ).forEach { (input, expected) ->
            val isolated = MadjoInvocation.isolate(input)
            assertTrue(isolated.detected)
            assertEquals(expected, isolated.requestText)
        }
        assertFalse(MadjoInvocation.contains("une majesté"))
    }

    @Test fun extractsAllExplicitDimensionsFromOneSentenceAndUsesExistingReferences() {
        val state = MadjoConversationEngine.start(
            "Majdo, je cherche un thé noir fruité du Japon pour ce soir, un de mes meilleurs encore en stock.",
            vocabulary,
        )
        val criteria = state.criteria.criteria

        assertEquals(setOf(MadjoDimension.TYPE_COLOR, MadjoDimension.AROMA, MadjoDimension.ORIGIN, MadjoDimension.MOMENT, MadjoDimension.TASTE_RATING, MadjoDimension.STOCK), criteria.map { it.dimension }.toSet())
        assertEquals("BLACK", criteria.single { it.concept == "category" }.value)
        assertEquals("FRUITY", criteria.single { it.concept == "aroma" }.value)
        assertEquals(japan.id, criteria.single { it.concept == "country" }.referenceId)
        assertEquals("EVENING", criteria.single { it.concept == "moment" }.value)
        assertEquals("top_rated", criteria.single { it.concept == "taste_rating" }.value)
        assertEquals("available", criteria.single { it.concept == "stock" }.value)
        assertEquals(MadjoCriterionKind.ABSOLUTE_CONSTRAINT, criteria.single { it.concept == "category" }.kind)
        assertEquals(MadjoCriterionKind.ABSOLUTE_CONSTRAINT, criteria.single { it.concept == "country" }.kind)
        assertEquals(MadjoCriterionKind.PREFERENCE, criteria.single { it.concept == "moment" }.kind)
        assertEquals(MadjoCriterionKind.PREFERENCE, criteria.single { it.concept == "taste_rating" }.kind)
        assertTrue(state.recommendationReady)
        assertTrue(state.questionsAsked.isEmpty())
        assertTrue(state.criteria.ambiguities.isEmpty())
    }

    @Test fun explicitHedgesMakeSoftPreferencesWhilePlainCharacteristicsAreConstraints() {
        val explicit = MadjoConversationEngine.start("Un thé noir floral du Japon", vocabulary)
        assertTrue(explicit.criteria.criteria.filter { it.concept in setOf("category", "aroma", "country") }
            .all { it.kind == MadjoCriterionKind.ABSOLUTE_CONSTRAINT })

        val hedged = MadjoConversationEngine.start("Un thé plutôt floral, si possible japonais", vocabulary)
        assertEquals(MadjoCriterionKind.PREFERENCE, hedged.criteria.criteria.single { it.concept == "aroma" }.kind)
        assertEquals(MadjoCriterionKind.PREFERENCE, hedged.criteria.criteria.single { it.concept == "country" }.kind)
    }

    @Test fun recognizedInformationIsNotAskedAgainAndQuestionChoiceUsesAvailableData() {
        val teaAndMoment = MadjoConversationEngine.start("Un thé vert pour ce soir", vocabulary)
        assertTrue(teaAndMoment.recommendationReady)
        assertNull(teaAndMoment.currentQuestion)

        val teaOnly = MadjoConversationEngine.start("Un thé vert", vocabulary)
        assertEquals(MadjoQuestionId.AROMA, teaOnly.currentQuestion?.id)
        assertEquals(setOf("Floral", "Fruité", "Épicé", "Peu importe"), teaOnly.currentQuestion?.options?.map { it.label }?.toSet())
        val answered = MadjoConversationEngine.submitAnswer(teaOnly, MadjoQuestionId.AROMA, "Fruité")
        assertTrue(answered.recommendationReady)
        assertTrue(answered.questionsAsked.contains(MadjoQuestionId.AROMA))
        assertEquals(2, answered.criteria.dimensions.size)
        assertEquals(answered, MadjoConversationEngine.submitAnswer(answered, MadjoQuestionId.AROMA, "Fruité"))
    }

    @Test fun freeTextCanCorrectAnEarlierColorWithoutKeepingConflictingCriteria() {
        val initial = MadjoConversationEngine.start("Je veux un thé vert", vocabulary)
        assertEquals(MadjoQuestionId.AROMA, initial.currentQuestion?.id)

        val corrected = MadjoConversationEngine.submitAnswer(initial, MadjoQuestionId.AROMA, "finalement plutôt noir")
        assertEquals(listOf("BLACK"), corrected.criteria.criteria.filter { it.concept == "category" }.map { it.value })
        assertFalse(corrected.criteria.criteria.any { it.concept == "category" && it.value == "GREEN" })
        assertEquals(MadjoQuestionId.AROMA, corrected.currentQuestion?.id)
        assertFalse(corrected.answers.last().resolvedQuestion)
        assertEquals("BLACK", corrected.criteria.criteria.single { it.concept == "category" }.value)
        assertEquals(MadjoCriterionKind.PREFERENCE, corrected.criteria.criteria.single { it.concept == "category" }.kind)
    }

    @Test fun explicitAndCorrectedFactsRemainInTheProfileWithTheirProvenance() {
        val initial = MadjoConversationEngine.start("Un thé vert", vocabulary)
        val answered = MadjoConversationEngine.submitAnswer(initial, MadjoQuestionId.AROMA, "plutôt fruité")

        assertEquals("GREEN", answered.criteria.criteria.single { it.concept == "category" }.value)
        assertEquals(MadjoCriterionSource.INITIAL_REQUEST, answered.criteria.criteria.single { it.concept == "category" }.source)
        assertEquals("FRUITY", answered.criteria.criteria.single { it.concept == "aroma" }.value)
        assertEquals(MadjoCriterionSource.USER_ANSWER, answered.criteria.criteria.single { it.concept == "aroma" }.source)
        assertEquals("plutôt fruité", answered.messages.last { it.speaker == MadjoSpeaker.USER }.text)
    }

    @Test fun unknownAromaAndOriginBecomeAmbiguitiesInsteadOfInventedCriteria() {
        val narrowVocabulary = vocabulary.copy(aromas = emptyList())
        val state = MadjoConversationEngine.start("Un thé floral du Wakanda", narrowVocabulary)

        assertTrue(state.criteria.criteria.none { it.concept == "aroma" || it.concept == "country" })
        assertEquals(setOf(MadjoDimension.AROMA, MadjoDimension.ORIGIN), state.criteria.ambiguities.map { it.dimension }.toSet())
        assertEquals(MadjoQuestionId.CLARIFICATION, state.currentQuestion?.id)
        assertFalse(state.recommendationReady)
    }

    @Test fun correctingAnUnknownExplicitValueKeepsItsConstraintNature() {
        val initial = MadjoConversationEngine.start("Un thé floral du Wakanda", vocabulary.copy(aromas = listOf(aromaFruity)))
        val corrected = MadjoConversationEngine.submitAnswer(initial, MadjoQuestionId.CLARIFICATION, "Fruité")

        assertEquals(MadjoCriterionKind.ABSOLUTE_CONSTRAINT, corrected.criteria.criteria.single { it.concept == "aroma" }.kind)
        assertEquals(MadjoCriterionSource.USER_ANSWER, corrected.criteria.criteria.single { it.concept == "aroma" }.source)
        assertTrue(corrected.criteria.ambiguities.any { it.dimension == MadjoDimension.ORIGIN })
    }

    @Test fun bedtimeIsOnlyAContextPreferenceAndNeverCreatesATheineCriterion() {
        val state = MadjoConversationEngine.start("Que boire avant de dormir ?", vocabulary)

        assertEquals("BEDTIME", state.criteria.criteria.single { it.concept == "moment" }.value)
        assertEquals(MadjoCriterionKind.PREFERENCE, state.criteria.criteria.single { it.concept == "moment" }.kind)
        assertFalse(state.criteria.criteria.any { it.concept.contains("theine", ignoreCase = true) || it.concept.contains("caffeine", ignoreCase = true) })
        assertEquals(MadjoQuestionId.TYPE_COLOR, state.currentQuestion?.id)
    }

    @Test fun stockAndTasteExpressionsUseOnlyExistingApplicationFields() {
        val state = MadjoConversationEngine.start("Mes meilleurs thés encore en stock", vocabulary)

        assertEquals("top_rated", state.criteria.criteria.single { it.dimension == MadjoDimension.TASTE_RATING }.value)
        assertEquals("available", state.criteria.criteria.single { it.dimension == MadjoDimension.STOCK }.value)
        assertEquals(MadjoCriterionKind.PREFERENCE, state.criteria.criteria.single { it.dimension == MadjoDimension.TASTE_RATING }.kind)
        assertFalse(state.criteria.criteria.any { it.concept.contains("theine", ignoreCase = true) })
    }

    @Test fun outOfScopeRequestGetsAReformulationPathWithoutQuestions() {
        val state = MadjoConversationEngine.start("Madjo, un clafoutis et un Coca s’il te plaît", vocabulary)

        assertTrue(state.offTopic)
        assertNull(state.currentQuestion)
        assertFalse(state.recommendationReady)
        assertTrue(state.messages.single().text.contains("reformuler"))
        val reformulated = MadjoConversationEngine.reformulate(state, "Je cherche un thé noir du Japon")
        assertFalse(reformulated.offTopic)
        assertTrue(reformulated.initialRequest.contains("clafoutis"))
        assertTrue(reformulated.messages.any { it.speaker == MadjoSpeaker.USER && it.text.contains("thé noir") })
    }

    @Test fun userCanAskForRecommendationsBeforeQuestionsAreFinished() {
        val state = MadjoConversationEngine.start("Madjo", vocabulary)
        assertFalse(state.recommendationReady)
        val transition = MadjoConversationEngine.requestRecommendations(state)

        assertTrue(transition.recommendationsRequested)
        assertNull(transition.currentQuestion)
        assertFalse(transition.messages.last().text.contains("recommandation :"))
        assertTrue(transition.messages.last().text.contains("M3.2"))
        assertEquals(transition, MadjoConversationEngine.requestRecommendations(transition))
    }

    @Test fun chineseAdjectiveAndCountryPhraseResolveToTheSameReference() {
        val china = MadjoReference("country-china", "CHINA", "Chine")
        val references = vocabulary.copy(countries = vocabulary.countries + china)
        val adjective = MadjoConversationEngine.start("Un thé vert chinois", references).criteria.criteria.single { it.concept == "country" }
        val preposition = MadjoConversationEngine.start("Un thé vert de Chine", references).criteria.criteria.single { it.concept == "country" }

        assertEquals(china.id, adjective.referenceId)
        assertEquals(adjective.referenceId, preposition.referenceId)
        assertEquals(adjective.value, preposition.value)
    }

    @Test fun stockPreferenceKeepsItsSoftNatureAndUnknownAdjectiveIsNotInvented() {
        val softStock = MadjoConversationEngine.start("Un thé, si possible en stock", vocabulary)
            .criteria.criteria.single { it.concept == "stock" }
        assertEquals(MadjoCriterionKind.PREFERENCE, softStock.kind)

        val withoutChina = MadjoConversationEngine.start("Un thé chinois", vocabulary)
        assertTrue(withoutChina.criteria.criteria.none { it.concept == "country" })
        assertTrue(withoutChina.criteria.ambiguities.any { it.dimension == MadjoDimension.ORIGIN })
    }

    @Test fun knownOriginIsNeverSelectedForASecondOriginQuestion() {
        val state = MadjoConversationEngine.start("Un thé vert du Japon", vocabulary)
        assertFalse(state.currentQuestion?.id == MadjoQuestionId.ORIGIN)
        assertTrue(state.criteria.criteria.any { it.concept == "country" && it.referenceId == japan.id })
    }
}
