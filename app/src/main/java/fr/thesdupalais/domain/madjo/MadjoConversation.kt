package fr.thesdupalais.domain.madjo

import java.text.Normalizer
import java.util.Locale

enum class MadjoSpeaker { MADJO, USER }
data class MadjoMessage(val id: Int, val speaker: MadjoSpeaker, val text: String)

enum class MadjoDimension { TYPE_COLOR, AROMA, MOMENT, ORIGIN, TASTE_RATING, STOCK }
enum class MadjoQuestionId { TYPE_COLOR, AROMA, MOMENT, ORIGIN, TASTE_RATING, STOCK, CLARIFICATION }
enum class MadjoCriterionKind { ABSOLUTE_CONSTRAINT, PREFERENCE }
enum class MadjoCriterionSource { INITIAL_REQUEST, USER_ANSWER }
enum class MadjoRelation { DIRECT_SYNONYM, SEMANTIC_ASSOCIATION }
enum class MadjoAmbiguityReason { UNKNOWN_REFERENCE_VALUE, CONFLICTING_VALUES }

data class MadjoReference(
    val id: String,
    val semanticKey: String?,
    val label: String,
    val parentId: String? = null,
)

data class MadjoVocabulary(
    val categories: List<MadjoReference> = emptyList(),
    val categoryOptions: List<MadjoReference> = categories,
    val aromas: List<MadjoReference> = emptyList(),
    val moments: List<MadjoReference> = emptyList(),
    val momentOptions: List<MadjoReference> = moments,
    val countries: List<MadjoReference> = emptyList(),
    val regions: List<MadjoReference> = emptyList(),
    val ratedTeaCount: Int = 0,
    val highRatedTeaCount: Int = 0,
    val availableStockCount: Int = 0,
    val outOfStockCount: Int = 0,
    val unknownStockCount: Int = 0,
    val usefulQuestionIds: Set<MadjoQuestionId>? = null,
    val candidateOptionIds: Map<MadjoQuestionId, Set<String>>? = null,
    val promptVariant: Int = 0,
)

data class MadjoCriterion(
    val dimension: MadjoDimension,
    val concept: String,
    val value: String,
    val kind: MadjoCriterionKind,
    val source: MadjoCriterionSource,
    val referenceId: String? = null,
    val displayValue: String? = null,
    val matchedText: String? = null,
    val relation: MadjoRelation = MadjoRelation.DIRECT_SYNONYM,
)

data class MadjoAmbiguity(
    val dimension: MadjoDimension,
    val phrase: String,
    val reason: MadjoAmbiguityReason,
    val detail: String,
    val intendedKind: MadjoCriterionKind? = null,
    val source: MadjoCriterionSource = MadjoCriterionSource.INITIAL_REQUEST,
    val candidateReferenceIds: List<String> = emptyList(),
    val candidateValues: List<String> = emptyList(),
)

/** Flat, traceable contract for M3.2. Criteria on each dimension can be combined. */
data class MadjoRecommendationCriteria(
    val criteria: List<MadjoCriterion>,
    val ambiguities: List<MadjoAmbiguity> = emptyList(),
) {
    val absoluteConstraints: List<MadjoCriterion> get() = criteria.filter { it.kind == MadjoCriterionKind.ABSOLUTE_CONSTRAINT }
    val preferences: List<MadjoCriterion> get() = criteria.filter { it.kind == MadjoCriterionKind.PREFERENCE }
    val dimensions: Set<MadjoDimension> get() = criteria.map { it.dimension }.toSet()
}

data class MadjoAnswerOption(
    val value: String,
    val label: String,
    val criteria: List<MadjoCriterion> = emptyList(),
)

data class MadjoQuestion(
    val id: MadjoQuestionId,
    val dimension: MadjoDimension?,
    val prompt: String,
    val options: List<MadjoAnswerOption>,
)

data class MadjoRecordedAnswer(
    val questionId: MadjoQuestionId,
    val rawText: String,
    val interpretedValue: String?,
    val resolvedQuestion: Boolean,
)

data class MadjoConversationState(
    val initialRequest: String,
    val analysisText: String,
    val criteria: MadjoRecommendationCriteria,
    val recognizedFacts: Set<String>,
    val questionsAsked: List<MadjoQuestionId>,
    val answers: List<MadjoRecordedAnswer>,
    val currentQuestion: MadjoQuestion?,
    val messages: List<MadjoMessage>,
    val recommendationReady: Boolean,
    val offTopic: Boolean = false,
    val recommendationsRequested: Boolean = false,
    val vocabulary: MadjoVocabulary = MadjoVocabulary(),
)

data class MadjoInvocationResult(val detected: Boolean, val requestText: String)

/** Shared M1/M3.1 call detection and request isolation; punctuation and spelling variants are tolerated. */
object MadjoInvocation {
    fun contains(text: String): Boolean = Regex("[\\p{L}\\p{N}]+").findAll(text)
        .any { isInvocationToken(normalizeToken(it.value)) }

    fun isolate(text: String): MadjoInvocationResult {
        val match = Regex("[\\p{L}\\p{N}]+").findAll(text).firstOrNull { isInvocationToken(normalizeToken(it.value)) }
            ?: return MadjoInvocationResult(false, text)
        val left = text.substring(0, match.range.first).replace(Regex("[\\s,;:!?—–-]+$"), "").trimEnd()
        val right = text.substring(match.range.last + 1).replace(Regex("^[\\s,;:!?—–-]+"), "").trimStart()
        val withoutName = listOf(left, right).filter(String::isNotBlank).joinToString(" ")
        return MadjoInvocationResult(true, withoutName.replace(Regex("\\s+"), " "))
    }

    private fun isInvocationToken(token: String): Boolean =
        token.isNotEmpty() && (editDistanceAtMostOne(token, "madjo") || editDistanceAtMostOne(token, "majdo"))

    private fun normalizeToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)

    private fun editDistanceAtMostOne(left: String, right: String): Boolean {
        if (kotlin.math.abs(left.length - right.length) > 1) return false
        if (left.length == right.length) return left.indices.count { left[it] != right[it] } <= 1
        val shorter = if (left.length < right.length) left else right
        val longer = if (left.length < right.length) right else left
        var shortIndex = 0
        var longIndex = 0
        var skipped = false
        while (shortIndex < shorter.length && longIndex < longer.length) {
            if (shorter[shortIndex] == longer[longIndex]) {
                shortIndex++
                longIndex++
            } else {
                if (skipped) return false
                skipped = true
                longIndex++
            }
        }
        return true
    }
}

/** Deterministic local dialogue and extractor; no Compose, Room or network dependencies. */
object MadjoConversationEngine {
    fun requiresDialogue(state: MadjoConversationState): Boolean = state.offTopic || state.currentQuestion != null || state.criteria.ambiguities.isNotEmpty()

    fun start(initialRequest: String, vocabulary: MadjoVocabulary = MadjoVocabulary()): MadjoConversationState {
        val isolated = MadjoInvocation.isolate(initialRequest)
        val extracted = extract(isolated.requestText, vocabulary, MadjoCriterionSource.INITIAL_REQUEST)
        val offTopic = !isTeaRelated(isolated.requestText, extracted, isolated.detected)
        val base = MadjoConversationState(
            initialRequest = initialRequest,
            analysisText = isolated.requestText,
            criteria = MadjoRecommendationCriteria(extracted.criteria, extracted.ambiguities),
            recognizedFacts = extracted.criteria.map { it.concept }.toSet(),
            questionsAsked = emptyList(),
            answers = emptyList(),
            currentQuestion = null,
            messages = emptyList(),
            recommendationReady = false,
            offTopic = offTopic,
            vocabulary = vocabulary,
        )
        if (offTopic) return base.copy(messages = listOf(
            MadjoMessage(0, MadjoSpeaker.MADJO, "Je peux vous aider à choisir un thé ou une infusion, pas préparer un clafoutis ni servir un Coca 😉. Vous pouvez reformuler votre envie de boisson ?"),
        ))
        return advance(base)
    }

    /** A question id rejects stale or duplicate taps after the conversation has advanced. */
    fun submitAnswer(state: MadjoConversationState, questionId: MadjoQuestionId, rawText: String): MadjoConversationState {
        val question = state.currentQuestion ?: return state
        if (state.offTopic || state.recommendationsRequested || question.id != questionId || rawText.isBlank()) return state
        val trimmed = rawText.trim()
        val isAny = normalize(trimmed) in setOf("peu importe", "n importe", "comme tu veux", "aucune preference", "indifferent", "indifferente")
        val previous = state.answers.lastOrNull()
        if (previous?.questionId == questionId && previous.rawText.trim() == trimmed &&
            state.messages.lastOrNull()?.speaker == MadjoSpeaker.MADJO) return state

        val extraction = extract(trimmed, state.vocabulary, MadjoCriterionSource.USER_ANSWER)
        val selected = question.options.firstOrNull { normalize(it.label) == normalize(trimmed) || normalize(it.value) == normalize(trimmed) }
        val selectedCriteria = selected?.criteria.orEmpty().map { it.copy(source = MadjoCriterionSource.USER_ANSWER, matchedText = trimmed) }
        val priorAmbiguity = state.criteria.ambiguities.firstOrNull { it.dimension == question.dimension }
        val responseCriteria = extraction.criteria.map { item ->
            val inheritedKind = priorAmbiguity?.intendedKind
            if (inheritedKind != null) item.copy(kind = inheritedKind)
            else if (item.dimension == question.dimension && !hasExplicitConstraint(normalize(trimmed))) item.copy(kind = MadjoCriterionKind.PREFERENCE) else item
        }
        val inheritedSelectedCriteria = selectedCriteria.map { item ->
            priorAmbiguity?.intendedKind?.let { item.copy(kind = it) } ?: item
        }
        val added = mergeDistinct(responseCriteria + inheritedSelectedCriteria)
        val resolved = selected != null || (isAny && question.id != MadjoQuestionId.CLARIFICATION) || added.any { question.dimension == it.dimension } ||
            (question.id == MadjoQuestionId.CLARIFICATION && added.isNotEmpty())
        val userMessage = MadjoMessage(state.messages.size, MadjoSpeaker.USER, trimmed)
        val withUser = state.copy(messages = state.messages + userMessage)

        if (added.isEmpty() && extraction.ambiguities.isNotEmpty() || (!resolved && added.isEmpty())) {
            val ambiguities = mergeAmbiguities(state.criteria.ambiguities, extraction.ambiguities)
            val clarifier = if (extraction.ambiguities.isNotEmpty()) {
                "Je ne trouve pas cette valeur dans les référentiels de la collection. Pouvez-vous choisir une option connue ou reformuler ?"
            } else {
                "Je n’ai pas compris ce point. Pour éviter de deviner, pouvez-vous préciser ou choisir une des options ?"
            }
            val answer = MadjoRecordedAnswer(questionId, trimmed, null, false)
            return withUser.copy(
                criteria = state.criteria.copy(ambiguities = ambiguities),
                answers = state.answers + answer,
                messages = withUser.messages + MadjoMessage(withUser.messages.size, MadjoSpeaker.MADJO, clarifier),
            )
        }

        val replacesDimension = question.dimension.takeIf { resolved }
        val updatedCriteria = mergeAnswerCriteria(state.criteria.criteria, added, replacesDimension, trimmed)
        val remainingAmbiguities = state.criteria.ambiguities.filterNot { ambiguity ->
            added.any { it.dimension == ambiguity.dimension && ambiguityResolvedBy(ambiguity, it.concept) }
        }
        val nextQuestionAnswered = selected != null || (isAny && question.id != MadjoQuestionId.CLARIFICATION) || added.any { it.dimension == question.dimension }
        val answerValue = selected?.value ?: if (isAny && added.isEmpty()) "any:${question.dimension?.name.orEmpty()}" else added.joinToString { "${it.concept}:${it.value}" }
        val answer = MadjoRecordedAnswer(questionId, trimmed, answerValue, nextQuestionAnswered)
        val next = withUser.copy(
            criteria = MadjoRecommendationCriteria(updatedCriteria, mergeAmbiguities(remainingAmbiguities, extraction.ambiguities)),
            recognizedFacts = state.recognizedFacts + added.map { it.concept },
            answers = state.answers + answer,
        )
        if (!nextQuestionAnswered) {
            val acknowledgement = if (added.isEmpty()) question.prompt else "D’accord, j’ai mis à jour ce critère. ${question.prompt}"
            return next.copy(
                currentQuestion = question,
                messages = next.messages + MadjoMessage(next.messages.size, MadjoSpeaker.MADJO, acknowledgement),
            )
        }
        return advance(next)
    }

    fun reformulate(state: MadjoConversationState, newRequest: String): MadjoConversationState {
        if (!state.offTopic || newRequest.isBlank()) return state
        val restarted = start(newRequest, state.vocabulary)
        val priorMessages = state.messages + MadjoMessage(state.messages.size, MadjoSpeaker.USER, newRequest.trim())
        return restarted.copy(
            initialRequest = state.initialRequest,
            messages = priorMessages + restarted.messages.mapIndexed { index, message -> message.copy(id = priorMessages.size + index) },
        )
    }

    fun requestRecommendations(state: MadjoConversationState): MadjoConversationState {
        if (state.offTopic || state.recommendationsRequested) return state
        val text = "Je garde ces critères pour la sélection. Le moteur de recommandations sera branché à l’étape M3.2."
        return state.copy(
            recommendationsRequested = true,
            currentQuestion = null,
            messages = state.messages + MadjoMessage(state.messages.size, MadjoSpeaker.MADJO, text),
        )
    }

    /** Re-evaluate only the next prompt using the compatible-candidate evidence from the catalogue layer. */
    fun updateQuestionEvidence(state: MadjoConversationState, vocabulary: MadjoVocabulary): MadjoConversationState {
        if (state.offTopic || state.recommendationsRequested) return state.copy(vocabulary = vocabulary)
        val current = state.currentQuestion
        val removePrompt = current != null && state.messages.lastOrNull()?.let { it.speaker == MadjoSpeaker.MADJO && it.text == current.prompt } == true
        val removeReadyMessage = state.recommendationReady && state.messages.lastOrNull()?.let {
            it.speaker == MadjoSpeaker.MADJO && (it.text.startsWith("J’ai assez d’éléments") || it.text.startsWith("Je peux préparer une sélection"))
        } == true
        val base = state.copy(
            vocabulary = vocabulary,
            currentQuestion = null,
            recommendationReady = false,
            questionsAsked = if (current != null && state.questionsAsked.lastOrNull() == current.id) state.questionsAsked.dropLast(1) else state.questionsAsked,
            messages = if (removePrompt || removeReadyMessage) state.messages.dropLast(1) else state.messages,
        )
        return advance(base)
    }

    private fun advance(state: MadjoConversationState): MadjoConversationState {
        if (state.criteria.ambiguities.isEmpty() && isReady(state)) {
            return state.copy(
                currentQuestion = null,
                recommendationReady = true,
                messages = state.messages + MadjoMessage(state.messages.size, MadjoSpeaker.MADJO, "J’ai assez d’éléments pour préparer une sélection."),
            )
        }
        val question = nextQuestion(state)
        if (question == null) {
            return state.copy(
                currentQuestion = null,
                recommendationReady = true,
                messages = state.messages + MadjoMessage(state.messages.size, MadjoSpeaker.MADJO, "Je peux préparer une sélection avec les éléments que vous m’avez donnés."),
            )
        }
        return state.copy(
            currentQuestion = question,
            questionsAsked = if (question.id in state.questionsAsked) state.questionsAsked else state.questionsAsked + question.id,
            messages = state.messages + MadjoMessage(state.messages.size, MadjoSpeaker.MADJO, question.prompt),
        )
    }

    private fun isReady(state: MadjoConversationState): Boolean {
        if (state.criteria.dimensions.size >= 2) return true
        val acceptedOptionalAnswers = state.answers.count { it.resolvedQuestion && it.interpretedValue?.startsWith("any:") == true }
        val acceptedSpecificAnswers = state.answers.any { it.resolvedQuestion && it.interpretedValue?.startsWith("any:") != true }
        return acceptedOptionalAnswers >= 2 || acceptedSpecificAnswers
    }

    private fun nextQuestion(state: MadjoConversationState): MadjoQuestion? {
        state.criteria.ambiguities.firstOrNull()?.let { return clarificationQuestion(it, state.vocabulary) }
        val known = state.criteria.dimensions
        val asked = state.questionsAsked.toSet()
        val baseOrder = questionOrder(state.criteria.criteria, state.analysisText)
        val order = state.vocabulary.candidateOptionIds?.let { evidence ->
            baseOrder.withIndex().sortedWith(compareByDescending<IndexedValue<MadjoQuestionId>> { evidence[it.value]?.size ?: 0 }.thenBy { it.index }).map { it.value }
        } ?: baseOrder
        return order.firstNotNullOfOrNull { id ->
            if (id in asked || dimensionFor(id) in known || state.vocabulary.usefulQuestionIds?.contains(id) == false) null
            else questionFor(id, state.vocabulary)?.let { question ->
                val candidates = state.vocabulary.candidateOptionIds?.get(id)
                if (candidates == null) question else question.copy(options = question.options.filter { option ->
                    option.value in candidates || option.value.startsWith("any:")
                })
            }
        }
    }

    private fun questionOrder(criteria: List<MadjoCriterion>, request: String): List<MadjoQuestionId> {
        val dimensions = criteria.map { it.dimension }.toSet()
        if (dimensions.isEmpty()) return listOf(MadjoQuestionId.TYPE_COLOR, MadjoQuestionId.AROMA, MadjoQuestionId.ORIGIN, MadjoQuestionId.MOMENT, MadjoQuestionId.STOCK, MadjoQuestionId.TASTE_RATING)
        return when {
            MadjoDimension.TYPE_COLOR in dimensions -> listOf(MadjoQuestionId.AROMA, MadjoQuestionId.ORIGIN, MadjoQuestionId.MOMENT, MadjoQuestionId.STOCK, MadjoQuestionId.TASTE_RATING, MadjoQuestionId.TYPE_COLOR)
            MadjoDimension.AROMA in dimensions -> listOf(MadjoQuestionId.TYPE_COLOR, MadjoQuestionId.MOMENT, MadjoQuestionId.ORIGIN, MadjoQuestionId.STOCK, MadjoQuestionId.TASTE_RATING, MadjoQuestionId.AROMA)
            MadjoDimension.MOMENT in dimensions -> listOf(MadjoQuestionId.TYPE_COLOR, MadjoQuestionId.AROMA, MadjoQuestionId.ORIGIN, MadjoQuestionId.STOCK, MadjoQuestionId.TASTE_RATING, MadjoQuestionId.MOMENT)
            MadjoDimension.ORIGIN in dimensions -> listOf(MadjoQuestionId.TYPE_COLOR, MadjoQuestionId.AROMA, MadjoQuestionId.MOMENT, MadjoQuestionId.STOCK, MadjoQuestionId.TASTE_RATING, MadjoQuestionId.ORIGIN)
            MadjoDimension.TASTE_RATING in dimensions -> listOf(MadjoQuestionId.TYPE_COLOR, MadjoQuestionId.STOCK, MadjoQuestionId.AROMA, MadjoQuestionId.ORIGIN, MadjoQuestionId.MOMENT, MadjoQuestionId.TASTE_RATING)
            else -> listOf(MadjoQuestionId.TYPE_COLOR, MadjoQuestionId.AROMA, MadjoQuestionId.ORIGIN, MadjoQuestionId.MOMENT, MadjoQuestionId.TASTE_RATING, MadjoQuestionId.STOCK)
        }
    }

    private fun dimensionFor(id: MadjoQuestionId): MadjoDimension? = when (id) {
        MadjoQuestionId.TYPE_COLOR -> MadjoDimension.TYPE_COLOR
        MadjoQuestionId.AROMA -> MadjoDimension.AROMA
        MadjoQuestionId.MOMENT -> MadjoDimension.MOMENT
        MadjoQuestionId.ORIGIN -> MadjoDimension.ORIGIN
        MadjoQuestionId.TASTE_RATING -> MadjoDimension.TASTE_RATING
        MadjoQuestionId.STOCK -> MadjoDimension.STOCK
        MadjoQuestionId.CLARIFICATION -> null
    }

    private fun questionFor(id: MadjoQuestionId, vocabulary: MadjoVocabulary): MadjoQuestion? = when (id) {
        MadjoQuestionId.TYPE_COLOR -> typeQuestion(vocabulary)
        MadjoQuestionId.AROMA -> aromaQuestion(vocabulary)
        MadjoQuestionId.MOMENT -> momentQuestion(vocabulary)
        MadjoQuestionId.ORIGIN -> originQuestion(vocabulary)
        MadjoQuestionId.TASTE_RATING -> ratingQuestion(vocabulary)
        MadjoQuestionId.STOCK -> stockQuestion(vocabulary)
        MadjoQuestionId.CLARIFICATION -> null
    }

    private fun typeQuestion(v: MadjoVocabulary): MadjoQuestion? {
        val options = v.categoryOptions.distinctBy { it.id }.map { ref ->
            val value = ref.semanticKey ?: normalize(ref.label).replace(' ', '_')
            val type = if (isInfusion(ref)) "infusion" else "tea"
            val criteria = listOf(
                criterion(MadjoDimension.TYPE_COLOR, "beverage_type", type, MadjoCriterionKind.PREFERENCE, display = if (type == "tea") "Thé" else "Infusion"),
                criterion(MadjoDimension.TYPE_COLOR, "category", value, MadjoCriterionKind.PREFERENCE, ref, ref.label),
            )
            MadjoAnswerOption("category:${ref.id}", ref.label, criteria)
        }.toMutableList()
        if (options.isEmpty()) return null
        options.add(0, MadjoAnswerOption("beverage:tea", "Un thé", listOf(criterion(MadjoDimension.TYPE_COLOR, "beverage_type", "tea", MadjoCriterionKind.PREFERENCE))))
        options.add(1, MadjoAnswerOption("beverage:infusion", "Une infusion", listOf(criterion(MadjoDimension.TYPE_COLOR, "beverage_type", "infusion", MadjoCriterionKind.PREFERENCE))))
        options += anyOption(MadjoDimension.TYPE_COLOR)
        val prompt = if (v.promptVariant % 2 == 0) "Quel type de boisson ou quelle couleur vous tente ?" else "Avez-vous une couleur ou un type de boisson en tête ?"
        return MadjoQuestion(MadjoQuestionId.TYPE_COLOR, MadjoDimension.TYPE_COLOR, prompt, options)
    }

    private fun aromaQuestion(v: MadjoVocabulary): MadjoQuestion? = v.aromas.takeIf { it.isNotEmpty() }?.let { values ->
        MadjoQuestion(MadjoQuestionId.AROMA, MadjoDimension.AROMA, if (v.promptVariant % 2 == 0) "Un arôme particulier vous ferait plaisir ?" else "Y a-t-il une note aromatique que vous aimeriez retrouver ?", values.map { ref ->
            MadjoAnswerOption("aroma:${ref.id}", ref.label, listOf(criterion(MadjoDimension.AROMA, "aroma", criterionValue(ref), MadjoCriterionKind.PREFERENCE, ref, ref.label)))
        } + anyOption(MadjoDimension.AROMA))
    }

    private fun momentQuestion(v: MadjoVocabulary): MadjoQuestion? = v.momentOptions.takeIf { it.isNotEmpty() }?.let { values ->
        MadjoQuestion(MadjoQuestionId.MOMENT, MadjoDimension.MOMENT, if (v.promptVariant % 2 == 0) "À quel moment aimeriez-vous le déguster ?" else "Vous le boiriez plutôt à quel moment ?", values.map { ref ->
            MadjoAnswerOption("moment:${ref.id}", ref.label, listOf(criterion(MadjoDimension.MOMENT, "moment", criterionValue(ref), MadjoCriterionKind.PREFERENCE, ref, ref.label)))
        } + anyOption(MadjoDimension.MOMENT))
    }

    private fun originQuestion(v: MadjoVocabulary): MadjoQuestion? {
        val refs = (v.countries + v.regions).distinctBy { it.id }
        if (refs.isEmpty()) return null
        return MadjoQuestion(MadjoQuestionId.ORIGIN, MadjoDimension.ORIGIN, if (v.promptVariant % 2 == 0) "Une origine vous attire particulièrement ?" else "Un pays ou une région vous tente ?", refs.map { ref ->
            val concept = if (ref in v.regions) "region" else "country"
            MadjoAnswerOption("origin:${ref.id}", ref.label, listOf(criterion(MadjoDimension.ORIGIN, concept, criterionValue(ref), MadjoCriterionKind.PREFERENCE, ref, ref.label)))
        } + anyOption(MadjoDimension.ORIGIN))
    }

    private fun ratingQuestion(v: MadjoVocabulary): MadjoQuestion? {
        if (v.ratedTeaCount == 0) return null
        val options = buildList {
            add(MadjoAnswerOption("rating:top_rated", "Mes mieux notés", listOf(criterion(MadjoDimension.TASTE_RATING, "taste_rating", "top_rated", MadjoCriterionKind.PREFERENCE))))
            if (v.highRatedTeaCount > 0) add(MadjoAnswerOption("rating:at_least_8", "Notés 8/10 ou plus", listOf(criterion(MadjoDimension.TASTE_RATING, "taste_rating", "at_least_8", MadjoCriterionKind.PREFERENCE))))
            add(anyOption(MadjoDimension.TASTE_RATING))
        }
        return MadjoQuestion(MadjoQuestionId.TASTE_RATING, MadjoDimension.TASTE_RATING, "Souhaitez-vous privilégier les thés les mieux notés ?", options)
    }

    private fun stockQuestion(v: MadjoVocabulary): MadjoQuestion? {
        if (v.availableStockCount + v.outOfStockCount + v.unknownStockCount == 0) return null
        val options = buildList {
            if (v.availableStockCount > 0) add(stockOption("available", "Disponibles"))
            if (v.outOfStockCount > 0) add(stockOption("out_of_stock", "Épuisés"))
            if (v.unknownStockCount > 0) add(stockOption("unknown", "Stock inconnu"))
            add(anyOption(MadjoDimension.STOCK))
        }
        return MadjoQuestion(MadjoQuestionId.STOCK, MadjoDimension.STOCK, "Le stock est-il important pour vous ?", options)
    }

    private fun clarificationQuestion(ambiguity: MadjoAmbiguity, v: MadjoVocabulary): MadjoQuestion {
        val candidates = when {
            ambiguity.detail == "beverage_type" -> listOf(
                MadjoAnswerOption("beverage_type:tea", "Thé", listOf(criterion(MadjoDimension.TYPE_COLOR, "beverage_type", "tea", ambiguity.intendedKind ?: MadjoCriterionKind.ABSOLUTE_CONSTRAINT))),
                MadjoAnswerOption("beverage_type:infusion", "Infusion", listOf(criterion(MadjoDimension.TYPE_COLOR, "beverage_type", "infusion", ambiguity.intendedKind ?: MadjoCriterionKind.ABSOLUTE_CONSTRAINT))),
            )
            else -> when (ambiguity.dimension) {
            MadjoDimension.TYPE_COLOR -> v.categoryOptions.map { ref ->
                MadjoAnswerOption("category:${ref.id}", ref.label, listOf(
                    criterion(MadjoDimension.TYPE_COLOR, "beverage_type", if (isInfusion(ref)) "infusion" else "tea", MadjoCriterionKind.PREFERENCE),
                    criterion(MadjoDimension.TYPE_COLOR, "category", criterionValue(ref), MadjoCriterionKind.PREFERENCE, ref, ref.label),
                ))
            }
            MadjoDimension.AROMA -> v.aromas.map { ref -> refOption("aroma", MadjoDimension.AROMA, ref) }
            MadjoDimension.MOMENT -> v.moments.map { ref -> refOption("moment", MadjoDimension.MOMENT, ref) }
            MadjoDimension.ORIGIN -> (v.countries + v.regions).distinctBy { it.id }.map { ref -> refOption(if (ref in v.regions) "region" else "country", MadjoDimension.ORIGIN, ref) }
            MadjoDimension.TASTE_RATING -> listOf(MadjoAnswerOption("rating:top_rated", "Mes mieux notés", listOf(criterion(MadjoDimension.TASTE_RATING, "taste_rating", "top_rated", MadjoCriterionKind.PREFERENCE))))
            MadjoDimension.STOCK -> listOf(stockOption("available", "Disponibles"), stockOption("out_of_stock", "Épuisés"), stockOption("unknown", "Stock inconnu"))
            }
        }
        val options = when {
            ambiguity.reason == MadjoAmbiguityReason.UNKNOWN_REFERENCE_VALUE -> emptyList()
            ambiguity.detail == "category" -> candidates.filter { option -> option.value.substringAfter(':') in ambiguity.candidateReferenceIds }
            ambiguity.detail == "beverage_type" -> candidates
            ambiguity.detail == "stock" -> candidates.filter { option -> option.value.substringAfter(':') in ambiguity.candidateValues }
            else -> candidates
        }
        val label = when (ambiguity.reason) {
            MadjoAmbiguityReason.UNKNOWN_REFERENCE_VALUE -> "Je ne trouve pas « ${ambiguity.phrase} » dans le référentiel de la collection. Pouvez-vous préciser ou reformuler ce critère ?"
            MadjoAmbiguityReason.CONFLICTING_VALUES -> "J’ai compris plusieurs choix incompatibles pour ${ambiguity.phrase}. Lequel voulez-vous garder ?"
        }
        return MadjoQuestion(MadjoQuestionId.CLARIFICATION, ambiguity.dimension, label, options)
    }

    private data class Extraction(
        val criteria: List<MadjoCriterion>,
        val ambiguities: List<MadjoAmbiguity>,
        val knownConcepts: Set<String>,
    )

    private fun extract(text: String, vocabulary: MadjoVocabulary, source: MadjoCriterionSource): Extraction {
        val normalized = normalize(text)
        val output = mutableListOf<MadjoCriterion>()
        val ambiguities = mutableListOf<MadjoAmbiguity>()
        val known = mutableSetOf<String>()
        fun add(dimension: MadjoDimension, concept: String, value: String, reference: MadjoReference? = null, matched: String, kind: MadjoCriterionKind = nature(dimension, normalized, matched, source)) {
            output += criterion(dimension, concept, value, kind, reference, reference?.label ?: value, matched, source)
            known += concept
        }

        val categories = vocabulary.categories.filter { ref -> categoryTerms(ref).any { containsPhrase(normalized, normalize(it)) } }
        val uniqueCategories = categories.distinctBy { it.id }
        if (uniqueCategories.size > 1) {
            ambiguities += MadjoAmbiguity(MadjoDimension.TYPE_COLOR, uniqueCategories.joinToString(" / ") { it.label }, MadjoAmbiguityReason.CONFLICTING_VALUES, "category", nature(MadjoDimension.TYPE_COLOR, normalized, uniqueCategories.first().label, source), source, uniqueCategories.map { it.id })
        } else uniqueCategories.singleOrNull()?.let { ref -> add(MadjoDimension.TYPE_COLOR, "category", criterionValue(ref), ref, ref.label) }

        val beverage = when {
            hasWord(normalized, "infusion") || hasWord(normalized, "tisane") -> "infusion"
            hasWord(normalized, "thé") || hasWord(normalized, "the") || hasWord(normalized, "tea") -> "tea"
            uniqueCategories.singleOrNull()?.let(::isInfusion) == true -> "infusion"
            uniqueCategories.isNotEmpty() -> "tea"
            else -> null
        }
        if (beverage != null) {
            if (hasWord(normalized, "infusion") && (hasWord(normalized, "thé") || hasWord(normalized, "the") || hasWord(normalized, "tea"))) {
                ambiguities += MadjoAmbiguity(MadjoDimension.TYPE_COLOR, "thé / infusion", MadjoAmbiguityReason.CONFLICTING_VALUES, "beverage_type", nature(MadjoDimension.TYPE_COLOR, normalized, "thé", source), source, candidateValues = listOf("tea", "infusion"))
            } else add(MadjoDimension.TYPE_COLOR, "beverage_type", beverage, matched = beverage)
        }

        unknownCategoryTerm(normalized)?.takeIf { uniqueCategories.isEmpty() }?.let { term ->
            ambiguities += MadjoAmbiguity(MadjoDimension.TYPE_COLOR, term, MadjoAmbiguityReason.UNKNOWN_REFERENCE_VALUE, "category", nature(MadjoDimension.TYPE_COLOR, normalized, term, source), source)
        }

        val aromaMatches = vocabulary.aromas.flatMap { ref -> aromaTerms(ref).filter { containsPhrase(normalized, normalize(it)) }.map { ref to it } }
        aromaMatches.distinctBy { it.first.id }.forEach { (ref, matched) -> add(MadjoDimension.AROMA, "aroma", criterionValue(ref), ref, matched) }
        aromaTermsByConcept.forEach { (concept, terms) ->
            terms.firstOrNull { containsPhrase(normalized, normalize(it)) }?.let { matched ->
                val present = vocabulary.aromas.firstOrNull { referenceMatchesConcept(it, concept) }
                if (present == null) ambiguities += MadjoAmbiguity(MadjoDimension.AROMA, matched, MadjoAmbiguityReason.UNKNOWN_REFERENCE_VALUE, "aroma:$concept", nature(MadjoDimension.AROMA, normalized, matched, source), source)
                else if (aromaMatches.none { it.first.id == present.id }) add(MadjoDimension.AROMA, "aroma", criterionValue(present), present, matched)
            }
        }

        val moments = vocabulary.moments.flatMap { ref -> momentTerms(ref).filter { containsPhrase(normalized, normalize(it)) }.map { ref to it } }
        moments.distinctBy { it.first.id }.forEach { (ref, matched) -> add(MadjoDimension.MOMENT, "moment", criterionValue(ref), ref, matched) }

        val originMatches = (vocabulary.countries.map { it to "country" } + vocabulary.regions.map { it to "region" })
            .flatMap { (ref, concept) -> originTerms(ref).filter { containsPhrase(normalized, normalize(it)) }.map { Triple(ref, concept, it) } }
        originMatches.distinctBy { it.first.id }.forEach { (ref, concept, matched) -> add(MadjoDimension.ORIGIN, concept, criterionValue(ref), ref, matched) }
        unknownOrigin(normalized)?.takeIf { originMatches.isEmpty() }?.let { candidate ->
            ambiguities += MadjoAmbiguity(MadjoDimension.ORIGIN, candidate, MadjoAmbiguityReason.UNKNOWN_REFERENCE_VALUE, "origin", nature(MadjoDimension.ORIGIN, normalized, candidate, source), source)
        }

        if (Regex("\\b(mes|les) meilleurs?\\b|\\bmes preferes?\\b|\\bmes favoris\\b|\\bun de mes preferes\\b").containsMatchIn(normalized)) {
            add(MadjoDimension.TASTE_RATING, "taste_rating", "top_rated", matched = "meilleurs / préférés", kind = MadjoCriterionKind.PREFERENCE)
        } else {
            Regex("\\b(?:note|notes|note de degustation) (?:de )?(10|9|8|7|6)(?: sur 10|/10)?\\b").find(normalized)?.let { match ->
                add(MadjoDimension.TASTE_RATING, "taste_rating", "exact:${match.groupValues[1]}", matched = match.value)
            }
            Regex("\\b(?:note de )?(?:au moins|minimum) (8|9|10)\\b").find(normalized)?.let { match ->
                add(MadjoDimension.TASTE_RATING, "taste_rating", "at_least:${match.groupValues[1]}", matched = match.value)
            }
        }

        val stockValues = buildSet {
            if (listOf("en stock", "encore en stock", "disponible", "disponibles").any { containsPhrase(normalized, it) }) add("available")
            if (listOf("epuise", "epuises", "rupture de stock", "plus de stock").any { containsPhrase(normalized, it) }) add("out_of_stock")
            if (containsPhrase(normalized, "stock inconnu") || containsPhrase(normalized, "stock non renseigne")) add("unknown")
        }
        if (stockValues.size > 1) {
            val matched = stockTerms.getValue(stockValues.first()).firstOrNull { containsPhrase(normalized, normalize(it)) }.orEmpty()
            ambiguities += MadjoAmbiguity(MadjoDimension.STOCK, stockValues.joinToString(" / "), MadjoAmbiguityReason.CONFLICTING_VALUES, "stock", nature(MadjoDimension.STOCK, normalized, matched, source), source, candidateValues = stockValues.toList())
        } else stockValues.singleOrNull()?.let { value ->
            val matched = stockTerms.getValue(value).firstOrNull { containsPhrase(normalized, normalize(it)) } ?: value
            add(MadjoDimension.STOCK, "stock", value, matched = matched)
        }

        if (listOf("apres un repas", "apres le repas", "apres manger").any { containsPhrase(normalized, it) } &&
            moments.none { (_, term) -> listOf("apres un repas", "apres le repas", "apres manger").any { normalize(it) == normalize(term) } }) {
            ambiguities += MadjoAmbiguity(MadjoDimension.MOMENT, "après un repas", MadjoAmbiguityReason.UNKNOWN_REFERENCE_VALUE, "moment", nature(MadjoDimension.MOMENT, normalized, "après un repas", source), source)
        }

        return Extraction(mergeDistinct(output), mergeAmbiguities(emptyList(), ambiguities), known)
    }

    private fun mergeAnswerCriteria(
        current: List<MadjoCriterion>,
        incoming: List<MadjoCriterion>,
        answeredDimension: MadjoDimension?,
        response: String,
    ): List<MadjoCriterion> {
        val merged = current.toMutableList()
        if (answeredDimension != null) {
            val conceptsToReplace = when (answeredDimension) {
                MadjoDimension.TYPE_COLOR -> buildSet {
                    if (incoming.any { it.concept == "category" }) add("category")
                    val incomingType = incoming.firstOrNull { it.concept == "beverage_type" }?.value
                    if (incomingType != null && current.any { it.concept == "beverage_type" && it.value != incomingType }) add("beverage_type")
                }
                MadjoDimension.AROMA -> setOf("aroma")
                MadjoDimension.MOMENT -> setOf("moment")
                MadjoDimension.ORIGIN -> buildSet {
                    if (incoming.any { it.concept == "country" }) add("country")
                    if (incoming.any { it.concept == "region" }) add("region")
                }
                MadjoDimension.TASTE_RATING -> setOf("taste_rating")
                MadjoDimension.STOCK -> setOf("stock")
            }
            if (incoming.any { it.dimension == answeredDimension }) merged.removeAll { it.concept in conceptsToReplace }
        }
        val correction = correctionMarkers.any { containsPhrase(normalize(response), it) }
        if (correction) {
            incoming.groupBy { it.concept }.forEach { (concept, replacements) ->
                val replacementValues = replacements.map { it.value }.toSet()
                merged.removeAll { it.concept == concept && it.value !in replacementValues }
            }
        }
        val knownPairs = merged.map { it.concept to it.value }.toSet()
        return mergeDistinct(merged + incoming.filterNot { (it.concept to it.value) in knownPairs })
    }

    private fun criterion(
        dimension: MadjoDimension,
        concept: String,
        value: String,
        kind: MadjoCriterionKind,
        ref: MadjoReference? = null,
        display: String? = null,
        matched: String? = null,
        source: MadjoCriterionSource = MadjoCriterionSource.INITIAL_REQUEST,
    ) = MadjoCriterion(dimension, concept, value, kind, source, ref?.id, display, matched)

    private fun refOption(concept: String, dimension: MadjoDimension, ref: MadjoReference) = MadjoAnswerOption(
        "$concept:${ref.id}", ref.label,
        listOf(criterion(dimension, concept, criterionValue(ref), MadjoCriterionKind.PREFERENCE, ref, ref.label)),
    )

    private fun stockOption(value: String, label: String) = MadjoAnswerOption(
        "stock:$value", label,
        listOf(criterion(MadjoDimension.STOCK, "stock", value, MadjoCriterionKind.PREFERENCE, display = label)),
    )

    private fun anyOption(dimension: MadjoDimension) = MadjoAnswerOption("any:${dimension.name}", "Peu importe")

    private fun nature(dimension: MadjoDimension, normalizedRequest: String, matchedText: String, source: MadjoCriterionSource): MadjoCriterionKind {
        val normalizedMatch = normalize(matchedText)
        val matchIndex = normalizedRequest.indexOf(normalizedMatch).takeIf { it >= 0 } ?: 0
        val before = normalizedRequest.substring(maxOf(0, matchIndex - 40), matchIndex)
        val after = normalizedRequest.substring((matchIndex + normalizedMatch.length).coerceAtMost(normalizedRequest.length))
        if (source == MadjoCriterionSource.USER_ANSWER) {
            return if (hasExplicitConstraint(normalizedRequest)) MadjoCriterionKind.ABSOLUTE_CONSTRAINT else MadjoCriterionKind.PREFERENCE
        }
        if (dimension == MadjoDimension.MOMENT) {
            val explicitlyRequired = listOf("uniquement", "obligatoirement", "strictement", "impérativement", "imperativement").any { it in before || it in after }
            return if (explicitlyRequired) MadjoCriterionKind.ABSOLUTE_CONSTRAINT else MadjoCriterionKind.PREFERENCE
        }
        val hedgedBefore = Regex(".*(?:si possible|de preference|plutot|idealement|j aimerais)(?: (?:un|une|du|des|de|d))? ?$").matches(before)
        val hedgedAfter = listOf("si possible", "de preference").any { after.startsWith(it) }
        return if (hedgedBefore || hedgedAfter) MadjoCriterionKind.PREFERENCE else MadjoCriterionKind.ABSOLUTE_CONSTRAINT
    }

    private fun isTeaRelated(text: String, extraction: Extraction, invoked: Boolean): Boolean {
        val normalized = normalize(text)
        val explicitTea = listOf("the", "tea", "infusion", "tisane", "boisson chaude").any { hasWord(normalized, it) }
        if (explicitTea) return true
        val unrelated = listOf("clafoutis", "coca", "coca cola", "coke", "pizza", "biere", "vin", "cafe").any { containsPhrase(normalized, it) }
        if (unrelated) return false
        if (extraction.criteria.isNotEmpty() || extraction.ambiguities.isNotEmpty()) return true
        if (normalized.isBlank()) return invoked
        return invoked && listOf("aide", "conseille", "recommande", "propose").any { hasWord(normalized, it) }
    }

    private fun hasExplicitConstraint(text: String): Boolean =
        listOf("uniquement", "seulement", "absolument", "obligatoirement", "strictement", "imperativement").any { containsPhrase(text, it) }

    private fun categoryTerms(ref: MadjoReference): Set<String> {
        val key = ref.semanticKey?.uppercase(Locale.ROOT)?.replace('-', '_')
        val aliases = categoryAliases[key].orEmpty()
        return aliases + ref.label
    }

    private fun isInfusion(ref: MadjoReference) = ref.semanticKey.equals("INFUSION", true) || normalize(ref.label) in setOf("infusion", "tisane")

    private fun aromaTerms(ref: MadjoReference): Set<String> = buildSet {
        add(ref.label)
        val concept = aromaTermsByConcept.keys.firstOrNull { referenceMatchesConcept(ref, it) }
        if (concept != null) addAll(aromaTermsByConcept.getValue(concept))
    }

    private fun referenceMatchesConcept(ref: MadjoReference, concept: String): Boolean {
        val normalizedKey = normalize(ref.semanticKey.orEmpty().replace('_', ' '))
        val normalizedLabel = normalize(ref.label)
        return concept in normalizedKey || concept in normalizedLabel ||
            (concept == "bergamot" && "bergamote" in normalizedLabel) ||
            (concept == "jasmine" && "jasmin" in normalizedLabel) ||
            (concept == "citrus" && listOf("agrume", "citron", "orange").any { it in normalizedLabel }) ||
            (concept == "fruity" && listOf("fruit", "fruite").any { it in normalizedLabel }) ||
            (concept == "floral" && listOf("floral", "fleuri", "fleur").any { it in normalizedLabel })
    }

    private fun momentTerms(ref: MadjoReference): Set<String> = buildSet {
        add(ref.label)
        when (ref.semanticKey?.uppercase(Locale.ROOT)) {
            "WAKE_UP" -> addAll(listOf("au réveil", "réveil"))
            "MORNING" -> add("ce matin")
            "LUNCH" -> addAll(listOf("à midi", "au déjeuner"))
            "AFTERNOON" -> addAll(listOf("cet après-midi", "après midi"))
            "EVENING" -> add("ce soir")
            "BEDTIME" -> addAll(listOf("avant de dormir", "avant de me coucher", "au coucher"))
        }
    }

    private fun originTerms(ref: MadjoReference): Set<String> = buildSet {
        add(ref.label)
        when (ref.semanticKey?.uppercase(Locale.ROOT)) {
            "JAPAN" -> addAll(listOf("Japonais", "Japonaise", "du Japon"))
            "CHINA" -> addAll(listOf("Chinois", "Chinoise", "de Chine"))
            "INDIA" -> addAll(listOf("Indien", "Indienne", "de l’Inde"))
            else -> countryAdjectives[normalize(ref.label)]?.let(::addAll)
        }
    }

    private fun unknownOrigin(normalized: String): String? {
        val marker = Regex("\\b(?:de l|de la|du|des|de|d|en|au|aux)\\s+([\\p{L}][\\p{L}'-]*)").findAll(normalized).lastOrNull()
        val candidate = marker?.groupValues?.get(1)
        if (candidate != null && candidate !in originStopWords && candidate !in setOf("stock", "disponible", "disponibles") &&
            candidate !in knownCategoryWords && candidate !in knownAromaWords && candidate !in knownMomentWords) return candidate
        return countryAdjectives.values.flatten().plus(listOf("chinois", "chinoise", "japonais", "japonaise", "indien", "indienne"))
            .firstOrNull { hasWord(normalized, it) }
    }

    private fun unknownCategoryTerm(normalized: String): String? {
        if (!hasWord(normalized, "the") && !hasWord(normalized, "thé")) return null
        return unknownCategoryWords.firstOrNull { hasWord(normalized, it) }
    }

    private fun mergeDistinct(items: List<MadjoCriterion>): List<MadjoCriterion> = items.distinctBy {
        listOf(it.dimension.name, it.concept, it.referenceId ?: it.value, it.kind.name)
    }

    private fun mergeAmbiguities(existing: List<MadjoAmbiguity>, incoming: List<MadjoAmbiguity>): List<MadjoAmbiguity> =
        (existing + incoming).distinctBy { listOf(it.dimension.name, it.phrase.lowercase(Locale.ROOT), it.reason.name, it.detail) }

    private fun ambiguityResolvedBy(ambiguity: MadjoAmbiguity, concept: String): Boolean = when (ambiguity.detail.substringBefore(':')) {
        "category" -> concept == "category"
        "beverage_type" -> concept == "beverage_type"
        "origin" -> concept in setOf("country", "region")
        "moment" -> concept == "moment"
        "stock" -> concept == "stock"
        else -> concept == "aroma"
    }

    private fun criterionValue(ref: MadjoReference): String = ref.semanticKey?.takeIf(String::isNotBlank) ?: normalize(ref.label).replace(' ', '_')

    private fun containsPhrase(text: String, phrase: String): Boolean = phrase.isNotBlank() && " $text ".contains(" $phrase ")
    private fun hasWord(text: String, word: String) = word in text.split(' ')

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private val categoryAliases = mapOf(
        "GREEN" to setOf("vert", "verte", "green tea"),
        "BLACK" to setOf("noir", "noire", "black tea"),
        "WHITE" to setOf("blanc", "blanche", "white tea"),
        "YELLOW" to setOf("jaune", "yellow tea"),
        "OOLONG" to setOf("oolong"),
        "PU_ERH" to setOf("pu erh", "pu er", "pu erh tea"),
        "INFUSION" to setOf("infusion", "tisane"),
        "MATE" to setOf("mate"),
    )
    private val aromaTermsByConcept = mapOf(
        "floral" to setOf("floral", "florale", "fleuri", "fleurie"),
        "fruity" to setOf("fruité", "fruitée", "fruités", "fruitées", "fruit", "fruits"),
        "spicy" to setOf("épicé", "épicée", "épicés", "épicées", "épicé", "épices"),
        "woody" to setOf("boisé", "boisée", "boisés", "boisées"),
        "bergamot" to setOf("bergamote"),
        "jasmine" to setOf("jasmin"),
        "citrus" to setOf("agrumes", "citron", "orange"),
        "vanilla" to setOf("vanille"),
        "mint" to setOf("menthe"),
    )
    private val correctionMarkers = listOf("finalement", "en fait", "plutot", "a la place")
    private val knownCategoryWords = setOf("vert", "verte", "noir", "noire", "blanc", "blanche", "jaune", "oolong", "mate", "infusion", "tisane")
    private val knownAromaWords = aromaTermsByConcept.values.flatten().map(::normalize).toSet()
    private val knownMomentWords = setOf("matin", "apres midi", "soir", "nuit", "coucher", "reveil")
    private val unknownCategoryWords = setOf("turquoise", "rouge", "violet", "violette", "bleu", "bleue", "gris", "grise")
    private val originStopWords = setOf("the", "infusion", "tisane", "fruit", "fruite", "floral", "florale", "preference", "possible", "ce", "cet", "soir", "matin", "apres", "midi", "coucher", "dormir", "bois", "epice", "mes", "meilleurs", "favoris", "preferes", "un", "une", "stock", "disponible", "disponibles")
    /** Applied only while matching an existing country reference; this table cannot introduce a new origin. */
    private val countryAdjectives = mapOf(
        "afghanistan" to setOf("afghan", "afghane"),
        "allemagne" to setOf("allemand", "allemande"),
        "argentine" to setOf("argentin", "argentine"),
        "australie" to setOf("australien", "australienne"),
        "autriche" to setOf("autrichien", "autrichienne"),
        "bangladesh" to setOf("bangladais", "bangladaise"),
        "bresil" to setOf("bresilien", "bresilienne"),
        "chine" to setOf("chinois", "chinoise"),
        "coree du sud" to setOf("coreen", "coreenne"),
        "egypte" to setOf("egyptien", "egyptienne"),
        "ethiopie" to setOf("ethiopien", "ethiopienne"),
        "georgie" to setOf("georgien", "georgienne"),
        "indonesie" to setOf("indonesien", "indonesienne"),
        "iran" to setOf("iranien", "iranienne"),
        "inde" to setOf("indien", "indienne"),
        "japon" to setOf("japonais", "japonaise"),
        "kenya" to setOf("kenyan", "kenyane"),
        "malaisie" to setOf("malaisien", "malaisienne"),
        "nepal" to setOf("nepalais", "nepalaise"),
        "nouvelle zelande" to setOf("neo zelandais", "neo zelandaise"),
        "russie" to setOf("russe"),
        "sri lanka" to setOf("sri lankais", "sri lankaise"),
        "taiwan" to setOf("taiwanais", "taiwanaise"),
        "thailande" to setOf("thailandais", "thailandaise"),
        "turquie" to setOf("turc", "turque"),
        "vietnam" to setOf("vietnamien", "vietnamienne"),
    )
    private val stockTerms = mapOf(
        "available" to listOf("encore en stock", "en stock", "disponible", "disponibles"),
        "out_of_stock" to listOf("rupture de stock", "plus de stock", "épuisé", "épuisés"),
        "unknown" to listOf("stock inconnu", "stock non renseigné"),
    )
}
