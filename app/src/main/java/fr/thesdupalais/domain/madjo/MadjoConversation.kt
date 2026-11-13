package fr.thesdupalais.domain.madjo

import java.text.Normalizer
import java.util.Locale

enum class MadjoSpeaker { MADJO, USER }

data class MadjoMessage(val id: Int, val speaker: MadjoSpeaker, val text: String)

enum class MadjoQuestionId { BEVERAGE_TYPE, CAFFEINE, AROMA }

data class MadjoAnswerOption(val value: String, val label: String)

data class MadjoQuestion(
    val id: MadjoQuestionId,
    val prompt: String,
    val options: List<MadjoAnswerOption>,
)

enum class MadjoCriterionKind { ABSOLUTE_CONSTRAINT, PREFERENCE }
enum class MadjoCriterionSource { INITIAL_REQUEST, USER_ANSWER }
enum class MadjoRelation { DIRECT_SYNONYM, SEMANTIC_ASSOCIATION }

/** Flat criteria contract for M3. Semantic associations remain explicit and are not inferred in M2. */
data class MadjoCriterion(
    val concept: String,
    val value: String,
    val kind: MadjoCriterionKind,
    val source: MadjoCriterionSource,
    val matchedText: String? = null,
    val relation: MadjoRelation = MadjoRelation.DIRECT_SYNONYM,
)

data class MadjoRecommendationCriteria(val criteria: List<MadjoCriterion>) {
    val absoluteConstraints: List<MadjoCriterion> get() = criteria.filter { it.kind == MadjoCriterionKind.ABSOLUTE_CONSTRAINT }
    val preferences: List<MadjoCriterion> get() = criteria.filter { it.kind == MadjoCriterionKind.PREFERENCE }
}

data class MadjoRecordedAnswer(
    val questionId: MadjoQuestionId,
    val rawText: String,
    val interpretedValue: String?,
)

data class MadjoConversationState(
    val initialRequest: String,
    val criteria: MadjoRecommendationCriteria,
    val recognizedFacts: Set<String>,
    val questionsAsked: List<MadjoQuestionId>,
    val answers: List<MadjoRecordedAnswer>,
    val currentQuestion: MadjoQuestion?,
    val messages: List<MadjoMessage>,
    val recommendationReady: Boolean,
    val recommendationsRequested: Boolean = false,
)

/** Deterministic local dialogue. Keep this independent from Compose and Android APIs. */
object MadjoConversationEngine {
    fun start(initialRequest: String): MadjoConversationState {
        val extracted = extractFacts(initialRequest)
        val base = MadjoConversationState(
            initialRequest = initialRequest,
            criteria = MadjoRecommendationCriteria(extracted.criteria),
            recognizedFacts = extracted.facts,
            questionsAsked = emptyList(),
            answers = emptyList(),
            currentQuestion = null,
            messages = emptyList(),
            recommendationReady = false,
        )
        return advance(base)
    }

    /** The question id makes repeated taps for the same displayed question harmless. */
    fun submitAnswer(state: MadjoConversationState, questionId: MadjoQuestionId, rawText: String): MadjoConversationState {
        val question = state.currentQuestion ?: return state
        if (state.recommendationsRequested || question.id != questionId || rawText.isBlank()) return state
        val lastAnswer = state.answers.lastOrNull()
        if (lastAnswer?.questionId == questionId && lastAnswer.rawText.trim() == rawText.trim() &&
            state.messages.lastOrNull()?.speaker == MadjoSpeaker.MADJO) return state
        val parsed = parseAnswer(question.id, rawText)
        val withUserMessage = state.copy(
            messages = state.messages + MadjoMessage(state.messages.size, MadjoSpeaker.USER, rawText.trim()),
        )
        if (parsed == null) {
            val clarification = "Je n’ai pas bien compris. Pour ne pas deviner, pouvez-vous répondre à cette question avec une des options proposées ou préciser votre souhait ?"
            return withUserMessage.copy(
                answers = withUserMessage.answers + MadjoRecordedAnswer(question.id, rawText, null),
                messages = withUserMessage.messages + MadjoMessage(withUserMessage.messages.size, MadjoSpeaker.MADJO, clarification),
            )
        }

        val extracted = extractFacts(rawText, MadjoCriterionSource.USER_ANSWER)
        val extraCriteria = extracted.criteria + parsed.criteria
        val criteria = mergeCriteria(state.criteria.criteria, extraCriteria)
        val next = withUserMessage.copy(
            criteria = MadjoRecommendationCriteria(criteria),
            recognizedFacts = state.recognizedFacts + extracted.facts + parsed.facts,
            answers = withUserMessage.answers + MadjoRecordedAnswer(question.id, rawText, parsed.value),
        )
        return advance(next)
    }

    fun requestRecommendations(state: MadjoConversationState): MadjoConversationState {
        if (state.recommendationsRequested) return state
        val text = if (state.recommendationReady) {
            "Très bien, je peux préparer une sélection à partir de ces critères. Le moteur de recommandations sera disponible à l’étape M3."
        } else {
            "Je garde déjà ces critères pour préparer votre sélection. Le moteur de recommandations sera disponible à l’étape M3."
        }
        return state.copy(
            recommendationsRequested = true,
            currentQuestion = null,
            messages = state.messages + MadjoMessage(state.messages.size, MadjoSpeaker.MADJO, text),
        )
    }

    private fun advance(state: MadjoConversationState): MadjoConversationState {
        if (isReady(state)) {
            val message = "J’ai déjà assez d’éléments pour préparer une sélection."
            return state.copy(
                currentQuestion = null,
                recommendationReady = true,
                messages = state.messages + MadjoMessage(state.messages.size, MadjoSpeaker.MADJO, message),
            )
        }
        val question = nextQuestion(state)
        if (question == null) {
            val message = "Je peux déjà préparer une sélection avec les éléments que vous m’avez donnés."
            return state.copy(currentQuestion = null, recommendationReady = true,
                messages = state.messages + MadjoMessage(state.messages.size, MadjoSpeaker.MADJO, message))
        }
        return state.copy(
            currentQuestion = question,
            questionsAsked = if (question.id in state.questionsAsked) state.questionsAsked else state.questionsAsked + question.id,
            messages = state.messages + MadjoMessage(state.messages.size, MadjoSpeaker.MADJO, question.prompt),
        )
    }

    private fun isReady(state: MadjoConversationState): Boolean {
        val criteria = state.criteria.criteria
        val hasBeverage = criteria.any { it.concept == "beverage_type" }
        val hasAdditionalCriterion = criteria.any { it.concept != "beverage_type" }
        val optionalQuestionAnswered = state.answers.any { it.questionId != MadjoQuestionId.BEVERAGE_TYPE && it.interpretedValue != null }
        return hasBeverage && (hasAdditionalCriterion || optionalQuestionAnswered)
    }

    private fun nextQuestion(state: MadjoConversationState): MadjoQuestion? {
        val criteria = state.criteria.criteria
        val concepts = criteria.map { it.concept }.toSet()
        val asked = state.questionsAsked.toSet()
        if ("beverage_type" !in concepts && MadjoQuestionId.BEVERAGE_TYPE !in asked) return beverageQuestion
        if ("beverage_type" in concepts && "caffeine_free" !in concepts && MadjoQuestionId.CAFFEINE !in asked &&
            criteria.none { it.concept == "beverage_type" && it.value == "any" }) return caffeineQuestion
        if ("beverage_type" in concepts && criteria.none { it.concept in setOf("aroma", "color", "moment") } &&
            MadjoQuestionId.AROMA !in asked) return aromaQuestion
        return null
    }

    private data class Extraction(val criteria: List<MadjoCriterion>, val facts: Set<String>)
    private data class ParsedAnswer(val value: String, val criteria: List<MadjoCriterion>, val facts: Set<String>)

    private fun extractFacts(text: String, source: MadjoCriterionSource = MadjoCriterionSource.INITIAL_REQUEST): Extraction {
        val normalized = normalize(text)
        val terms = normalized.split(' ').filter(String::isNotBlank).toSet()
        val result = mutableListOf<MadjoCriterion>()
        val facts = mutableSetOf<String>()
        fun add(concept: String, value: String, kind: MadjoCriterionKind, matchedText: String) {
            result += MadjoCriterion(concept, value, kind, source, matchedText)
            facts += concept
        }
        val beverage = when {
            terms.any { it in setOf("infusion", "infusions", "tisane", "tisanes") } -> "infusion"
            "the" in terms || "thes" in terms -> "tea"
            else -> null
        }
        if (beverage != null) add("beverage_type", beverage, MadjoCriterionKind.PREFERENCE, beverage)

        val caffeineFree = listOf("sans theine", "sans cafeine", "decafeine", "de theine").firstOrNull { phrase -> " $normalized ".contains(" $phrase ") }
        if (caffeineFree != null) add("caffeine_free", "true", MadjoCriterionKind.ABSOLUTE_CONSTRAINT, caffeineFree)

        val color = listOf("vert" to "green", "verte" to "green", "noir" to "black", "noire" to "black", "blanc" to "white", "blanche" to "white", "oolong" to "oolong")
            .firstOrNull { (term, _) -> term in terms }
        if (color != null) add("color", color.second, MadjoCriterionKind.PREFERENCE, color.first)

        val aroma = listOf("floral" to "floral", "florale" to "floral", "fruite" to "fruity", "fruitee" to "fruity", "fruit" to "fruity", "fruits" to "fruity")
            .firstOrNull { (term, _) -> term in terms }
        if (aroma != null) add("aroma", aroma.second, MadjoCriterionKind.PREFERENCE, aroma.first)

        val moment = listOf("matin" to "morning", "apres midi" to "afternoon", "soir" to "evening", "nuit" to "night")
            .firstOrNull { (term, _) -> " $normalized ".contains(" $term ") }
        if (moment != null) add("moment", moment.second, MadjoCriterionKind.PREFERENCE, moment.first)
        return Extraction(result, facts)
    }

    private fun parseAnswer(questionId: MadjoQuestionId, rawText: String): ParsedAnswer? {
        val normalized = normalize(rawText)
        val any = normalized in setOf("peu importe", "n importe", "comme tu veux", "les deux", "aucune preference", "indifferent")
        val direct = when (questionId) {
            MadjoQuestionId.BEVERAGE_TYPE -> when {
                any -> "any"
                normalized.split(' ').any { it in setOf("the", "thes") } -> "tea"
                normalized.split(' ').any { it in setOf("infusion", "tisane") } -> "infusion"
                else -> null
            }
            MadjoQuestionId.CAFFEINE -> when {
                any -> "any"
                normalized in setOf("oui", "oui sans theine", "oui sans cafeine", "eviter", "sans theine", "sans cafeine") -> "avoid"
                normalized in setOf("non", "non merci", "pas besoin") -> "no_constraint"
                else -> null
            }
            MadjoQuestionId.AROMA -> when {
                any -> "any"
                normalized.split(' ').any { it in setOf("floral", "florale") } -> "floral"
                normalized.split(' ').any { it in setOf("fruite", "fruitee", "fruit", "fruits") } -> "fruity"
                else -> null
            }
        } ?: return null
        val source = MadjoCriterionSource.USER_ANSWER
        val criteria = when (questionId) {
            MadjoQuestionId.BEVERAGE_TYPE -> listOf(MadjoCriterion("beverage_type", direct, MadjoCriterionKind.PREFERENCE, source, rawText.trim()))
            MadjoQuestionId.CAFFEINE -> if (direct == "avoid") listOf(MadjoCriterion("caffeine_free", "true", MadjoCriterionKind.ABSOLUTE_CONSTRAINT, source, rawText.trim())) else emptyList()
            MadjoQuestionId.AROMA -> if (direct == "any") emptyList() else listOf(MadjoCriterion("aroma", direct, MadjoCriterionKind.PREFERENCE, source, rawText.trim()))
        }
        return ParsedAnswer(direct, criteria, setOf(questionId.name.lowercase(Locale.ROOT)))
    }

    private fun mergeCriteria(current: List<MadjoCriterion>, incoming: List<MadjoCriterion>): List<MadjoCriterion> {
        val merged = current.toMutableList()
        incoming.forEach { item ->
            merged.removeAll { it.concept == item.concept && it.kind == item.kind }
            merged += item
        }
        return merged
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private val beverageQuestion = MadjoQuestion(
        MadjoQuestionId.BEVERAGE_TYPE,
        "Vous préférez un thé ou une infusion ?",
        listOf(MadjoAnswerOption("tea", "Un thé"), MadjoAnswerOption("infusion", "Une infusion"), MadjoAnswerOption("any", "Peu importe")),
    )
    private val caffeineQuestion = MadjoQuestion(
        MadjoQuestionId.CAFFEINE,
        "Souhaitez-vous éviter la théine ?",
        listOf(MadjoAnswerOption("avoid", "Oui, sans théine"), MadjoAnswerOption("no_constraint", "Non"), MadjoAnswerOption("any", "Peu importe")),
    )
    private val aromaQuestion = MadjoQuestion(
        MadjoQuestionId.AROMA,
        "Quel profil vous ferait plaisir ?",
        listOf(MadjoAnswerOption("floral", "Floral"), MadjoAnswerOption("fruity", "Fruité"), MadjoAnswerOption("any", "Peu importe")),
    )
}
