package fr.thesdupalais.domain.madjo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MadjoConversationEngineTest {
    @Test fun extractsExplicitInfusionCaffeineAndMomentWithoutAskingAgain() {
        val state = MadjoConversationEngine.start("Une infusion sans théine pour ce soir")

        assertEquals("infusion", state.criteria.preferences.single { it.concept == "beverage_type" }.value)
        assertEquals("true", state.criteria.absoluteConstraints.single { it.concept == "caffeine_free" }.value)
        assertEquals("evening", state.criteria.preferences.single { it.concept == "moment" }.value)
        assertTrue(state.recommendationReady)
        assertNull(state.currentQuestion)
        assertTrue(state.questionsAsked.isEmpty())
    }

    @Test fun extractsTeaColorAndAromaAsPreferences() {
        val state = MadjoConversationEngine.start("Un thé vert floral")

        assertEquals(setOf("beverage_type", "color", "aroma"), state.criteria.preferences.map { it.concept }.toSet())
        assertTrue(state.criteria.absoluteConstraints.isEmpty())
        assertTrue(state.recommendationReady)
        assertNull(state.currentQuestion)
    }

    @Test fun fruityRequestAsksForTheUndeterminedBeverageOnly() {
        val state = MadjoConversationEngine.start("Quelque chose de fruité")

        assertEquals("fruity", state.criteria.preferences.single { it.concept == "aroma" }.value)
        assertEquals(MadjoQuestionId.BEVERAGE_TYPE, state.currentQuestion?.id)
        assertFalse(state.recommendationReady)

        val answered = MadjoConversationEngine.submitAnswer(state, MadjoQuestionId.BEVERAGE_TYPE, "Un thé")
        assertTrue(answered.recommendationReady)
        assertNull(answered.currentQuestion)
        assertEquals("tea", answered.criteria.preferences.first { it.concept == "beverage_type" }.value)
    }

    @Test fun remembersButtonAnswersAndAvoidsDoubleSubmission() {
        val state = MadjoConversationEngine.start("Un thé")
        assertEquals(MadjoQuestionId.CAFFEINE, state.currentQuestion?.id)

        val answered = MadjoConversationEngine.submitAnswer(state, MadjoQuestionId.CAFFEINE, "Oui, sans théine")
        assertTrue(answered.recommendationReady)
        assertEquals("true", answered.criteria.absoluteConstraints.single { it.concept == "caffeine_free" }.value)
        assertEquals("avoid", answered.answers.single().interpretedValue)
        assertEquals("Oui, sans théine", answered.messages.last { it.speaker == MadjoSpeaker.USER }.text)
        assertEquals(answered, MadjoConversationEngine.submitAnswer(answered, MadjoQuestionId.CAFFEINE, "Oui, sans théine"))
    }

    @Test fun nonAvoidanceAnswerDoesNotBecomeAConstraint() {
        val state = MadjoConversationEngine.start("Un thé")
        val answered = MadjoConversationEngine.submitAnswer(state, MadjoQuestionId.CAFFEINE, "Non")

        assertTrue(answered.criteria.absoluteConstraints.isEmpty())
        assertTrue(answered.recommendationReady)
        assertEquals("no_constraint", answered.answers.single().interpretedValue)
    }

    @Test fun ambiguousFreeTextIsRecordedAndClarifiedWithoutInventingCriteria() {
        val state = MadjoConversationEngine.start("Un thé")
        val answered = MadjoConversationEngine.submitAnswer(state, MadjoQuestionId.CAFFEINE, "plutôt léger")

        assertEquals(state.currentQuestion, answered.currentQuestion)
        assertEquals(listOf("plutôt léger"), answered.answers.map { it.rawText })
        assertNull(answered.answers.single().interpretedValue)
        assertTrue(answered.criteria.criteria.all { it.concept == "beverage_type" })
        assertTrue(answered.messages.last().text.contains("pas bien compris"))
        assertFalse(answered.recommendationReady)
    }

    @Test fun userCanRequestRecommendationsEarlyAndReceivesOnlyTransitionState() {
        val state = MadjoConversationEngine.start("Quelque chose de fruité")
        assertFalse(state.recommendationReady)

        val transition = MadjoConversationEngine.requestRecommendations(state)
        assertTrue(transition.recommendationsRequested)
        assertFalse(transition.recommendationReady)
        assertTrue(transition.messages.last().text.contains("M3"))
        assertNull(transition.currentQuestion)
        assertTrue(transition.criteria.preferences.any { it.concept == "aroma" })
        assertEquals(transition, MadjoConversationEngine.requestRecommendations(transition))
    }
}
