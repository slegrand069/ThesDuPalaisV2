package fr.thesdupalais.presentation.catalogue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID
import fr.thesdupalais.domain.madjo.MadjoConversationState
import fr.thesdupalais.domain.madjo.MadjoCriterion
import fr.thesdupalais.domain.madjo.MadjoMessage
import fr.thesdupalais.domain.madjo.MadjoQuestionId
import fr.thesdupalais.domain.madjo.MadjoRecommendationOutcome
import fr.thesdupalais.domain.madjo.MadjoSpeaker
import fr.thesdupalais.domain.madjo.MadjoTeaProfile
import fr.thesdupalais.domain.madjo.MadjoVocabulary

@Composable
fun CatalogueScreen(viewModel: CatalogueViewModel, onTeaSelected: (UUID) -> Unit, onAddTea: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CatalogueContent(
        state = state,
        onTeaSelected = onTeaSelected,
        onAddTea = onAddTea,
        onQueryChange = viewModel::setQuery,
        onSubmitQuery = { vocabulary, profiles -> viewModel.submitQuery(vocabulary, profiles) },
        onDismissMadjo = viewModel::dismissMadjo,
        onMadjoAnswer = { questionId, answer, profiles -> viewModel.submitMadjoAnswer(questionId, answer, profiles) },
        onMadjoRecommendations = viewModel::requestMadjoRecommendations,
        onMadjoRelaxation = viewModel::acceptMadjoRelaxation,
        onResumeMadjo = viewModel::resumeMadjoDialogue,
        onReturnToSearch = viewModel::returnToClassicSearch,
        onMadjoReformulate = { request, profiles -> viewModel.reformulateMadjo(request, profiles) },
        onCategoriesChange = viewModel::setCategories,
        onCountriesChange = viewModel::setCountries,
        onStatusesChange = viewModel::setStatuses,
        onProfilesChange = viewModel::setAromaticProfiles,
        onBadgesChange = viewModel::setBadges,
        onContainersChange = viewModel::setContainers,
        onMomentsChange = viewModel::setMoments,
        onTasteRatingChange = viewModel::setTasteRating,
        onResetFilters = viewModel::resetFilters,
        onResetAll = viewModel::resetAll,
    )
}

@Composable
internal fun CatalogueContent(
    state: CatalogueUiState,
    onTeaSelected: (UUID) -> Unit = {},
    onAddTea: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onSubmitQuery: (MadjoVocabulary, List<MadjoTeaProfile>) -> Unit = { _, _ -> },
    onDismissMadjo: () -> Unit = {},
    onMadjoAnswer: (MadjoQuestionId, String, List<MadjoTeaProfile>) -> Unit = { _, _, _ -> },
    onMadjoRecommendations: () -> Unit = {},
    onMadjoRelaxation: (MadjoCriterion) -> Unit = {},
    onResumeMadjo: () -> Unit = {},
    onReturnToSearch: () -> Unit = {},
    onMadjoReformulate: (String, List<MadjoTeaProfile>) -> Unit = { _, _ -> },
    onCategoriesChange: (Set<UUID>) -> Unit = {},
    onCountriesChange: (Set<UUID>) -> Unit = {},
    onStatusesChange: (Set<UUID>, Boolean) -> Unit = { _, _ -> },
    onProfilesChange: (Set<UUID>) -> Unit = {},
    onBadgesChange: (Set<UUID>) -> Unit = {},
    onContainersChange: (Set<UUID>) -> Unit = {},
    onMomentsChange: (Set<UUID>) -> Unit = {},
    onTasteRatingChange: (TasteRatingFilter) -> Unit = {},
    onResetFilters: () -> Unit = {},
    onResetAll: () -> Unit = {},
) {
    val criteria = when (state) { is CatalogueUiState.Content -> state.criteria; is CatalogueUiState.Empty -> state.criteria; else -> CatalogueCriteria() }
    val options = when (state) { is CatalogueUiState.Content -> state.options; is CatalogueUiState.Empty -> state.options; else -> CatalogueFilterOptions() }
    var showFilters by remember { mutableStateOf(false) }
    val madjoRequest = when (state) { is CatalogueUiState.Content -> state.madjoRequest; is CatalogueUiState.Empty -> state.madjoRequest; else -> false }
    val madjoDetected = containsMadjoInvocation(criteria.query)
    val madjoConversation = when (state) { is CatalogueUiState.Content -> state.madjoConversation; is CatalogueUiState.Empty -> state.madjoConversation; else -> null }
    val madjoVocabulary = when (state) { is CatalogueUiState.Content -> state.madjoVocabulary; is CatalogueUiState.Empty -> state.madjoVocabulary; else -> MadjoVocabulary() }
    val recommendationProfiles = when (state) { is CatalogueUiState.Content -> state.recommendationProfiles; else -> emptyList() }
    val madjoResults = when (state) { is CatalogueUiState.Content -> state.madjoResults; is CatalogueUiState.Empty -> state.madjoResults; else -> null }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(
            text = "ThésDuPalais",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text("Ma collection de thés", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        CatalogueSearchControls(criteria, onQueryChange, { onSubmitQuery(madjoVocabulary, recommendationProfiles) }, { showFilters = true }, onResetAll, madjoRequest)
        if (criteria.hasFilters) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("catalogue-active-filters"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                criteria.activeFilterLabels(options).forEach { label ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        when (state) {
            CatalogueUiState.Loading -> CircularProgressIndicator(modifier = Modifier.testTag("catalogue-loading"))
            is CatalogueUiState.Empty -> Box(Modifier.weight(1f).fillMaxWidth()) {
                if (madjoResults != null) MadjoResultsPanel(
                    madjoResults, onRelax = onMadjoRelaxation, onResume = onResumeMadjo,
                    onReturnToSearch = onReturnToSearch, onTeaSelected = onTeaSelected,
                    modifier = Modifier.fillMaxSize(), canResume = madjoConversation != null,
                ) else if (!state.madjoRequest) CatalogueEmptyState(Modifier.align(androidx.compose.ui.Alignment.Center))
                AddTeaButton(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp), onAddTea)
            }
            is CatalogueUiState.Content -> Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    madjoResults != null -> MadjoResultsPanel(
                        madjoResults, onRelax = onMadjoRelaxation, onResume = onResumeMadjo,
                        onReturnToSearch = onReturnToSearch, onTeaSelected = onTeaSelected,
                        modifier = Modifier.fillMaxSize(), canResume = madjoConversation != null,
                    )
                    state.madjoRequest -> Unit
                    state.noResults && !madjoDetected -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Aucun thé ne correspond à ces critères.", modifier = Modifier.testTag("catalogue-no-results"), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onResetAll) { Text("Réinitialiser") }
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("catalogue-list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.teas, key = { it.id.toString() }) { tea -> CatalogueTeaRow(tea) { onTeaSelected(tea.id) } }
                    }
                }
                AddTeaButton(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp), onAddTea)
            }
        }
    }
    if (showFilters) CatalogueFilterDialog(
        options, criteria, { showFilters = false }, onCategoriesChange, onCountriesChange, onStatusesChange,
        onProfilesChange, onBadgesChange, onContainersChange, onMomentsChange, onTasteRatingChange, onResetFilters,
    )
    if (madjoRequest && madjoConversation != null) {
        MadjoDialog(
            conversation = madjoConversation,
            onDismiss = onDismissMadjo,
            onAnswer = { questionId, answer -> onMadjoAnswer(questionId, answer, recommendationProfiles) },
            onRequestRecommendations = onMadjoRecommendations,
            onReformulate = { request -> onMadjoReformulate(request, recommendationProfiles) },
        )
    }
}

@Composable
private fun MadjoDialog(
    conversation: MadjoConversationState,
    onDismiss: () -> Unit,
    onAnswer: (MadjoQuestionId, String) -> Unit,
    onRequestRecommendations: () -> Unit,
    onReformulate: (String) -> Unit,
) {
    var freeText by remember(conversation.initialRequest) { mutableStateOf("") }
    val messageListState = rememberLazyListState()
    LaunchedEffect(conversation.messages.size) {
        val layout = messageListState.layoutInfo
        val wasNearBottom = layout.totalItemsCount == 0 || layout.visibleItemsInfo.lastOrNull()?.index?.let { it >= layout.totalItemsCount - 2 } == true
        if (wasNearBottom && conversation.messages.isNotEmpty()) messageListState.animateScrollToItem(conversation.messages.lastIndex)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f).heightIn(max = 700.dp).imePadding().testTag("madjo-dialog"),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(38.dp)) {
                        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text("M", color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Madjo", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(conversation.initialRequest, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onDismiss) { Text("Fermer") }
                }
                HorizontalDividerMadjo()
                LazyColumn(
                    state = messageListState,
                    modifier = Modifier.weight(1f, fill = true).fillMaxWidth().testTag("madjo-message-list"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(conversation.messages, key = MadjoMessage::id) { message -> MadjoMessageBubble(message) }
                }
                if (conversation.offTopic) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = freeText,
                            onValueChange = { freeText = it },
                            modifier = Modifier.weight(1f).testTag("madjo-free-answer"),
                            placeholder = { Text("Reformuler votre envie") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (freeText.isNotBlank()) {
                                    onReformulate(freeText.trim())
                                    freeText = ""
                                }
                            }),
                        )
                        TextButton(onClick = {
                            if (freeText.isNotBlank()) {
                                onReformulate(freeText.trim())
                                freeText = ""
                            }
                        }, enabled = freeText.isNotBlank(), modifier = Modifier.testTag("madjo-send-reformulation")) { Text("Envoyer") }
                    }
                } else if (conversation.recommendationsRequested) {
                    Text("Transition M2 → M3", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("Aucune recommandation n’est affichée avant le branchement du moteur M3.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    conversation.currentQuestion?.let { question ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 138.dp).verticalScroll(rememberScrollState())) {
                            question.options.forEach { option ->
                                TextButton(
                                    onClick = { onAnswer(question.id, option.label); freeText = "" },
                                    modifier = Modifier.fillMaxWidth().testTag("madjo-answer-${option.value}"),
                                ) { Text(option.label) }
                            }
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = freeText,
                                onValueChange = { freeText = it },
                                modifier = Modifier.weight(1f).testTag("madjo-free-answer"),
                                placeholder = { Text("Ou écrivez votre réponse") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    if (freeText.isNotBlank()) {
                                        onAnswer(question.id, freeText.trim())
                                        freeText = ""
                                    }
                                }),
                            )
                            TextButton(
                                onClick = {
                                    if (freeText.isNotBlank()) {
                                        onAnswer(question.id, freeText.trim())
                                        freeText = ""
                                    }
                                },
                                enabled = freeText.isNotBlank(),
                                modifier = Modifier.testTag("madjo-send-answer"),
                            ) { Text("Envoyer") }
                        }
                    }
                    Button(
                        onClick = onRequestRecommendations,
                        modifier = Modifier.fillMaxWidth().testTag("madjo-recommendations"),
                    ) { Text("Voir mes recommandations") }
                }
            }
        }
    }
}

@Composable
private fun MadjoResultsPanel(
    outcome: MadjoRecommendationOutcome,
    onRelax: (MadjoCriterion) -> Unit,
    onResume: () -> Unit,
    onReturnToSearch: () -> Unit,
    onTeaSelected: (UUID) -> Unit,
    modifier: Modifier = Modifier,
    canResume: Boolean = false,
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Les recommandations de Madjo", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        outcome.acceptedRelaxations.takeIf { it.isNotEmpty() }?.let { relaxed ->
            Text("Critère assoupli avec ton accord : ${relaxed.joinToString { criterionLabel(it) }}.", style = MaterialTheme.typography.bodyMedium)
        }
        if (outcome.unresolvedAmbiguities.isNotEmpty()) {
            Text("Je ne peux pas encore appliquer ces critères : ${outcome.unresolvedAmbiguities.joinToString { it.phrase }}. Reprenons la précision de ta demande.")
        } else if (!outcome.hasResults) {
            Text(if (outcome.collectionEmpty) "Ta collection ne contient aucun thé à recommander." else "Aucun thé ne respecte toutes les contraintes indiquées. Je ne les assouplis pas sans ton accord.")
            outcome.relaxationOptions.forEach { option ->
                TextButton(onClick = { onRelax(option.criterion) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Assouplir « ${criterionLabel(option.criterion)} » (${option.matchingTeaCount} résultat${if (option.matchingTeaCount > 1) "s" else ""})")
                }
            }
        } else {
            outcome.recommendations.forEach { recommendation ->
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("madjo-result-${recommendation.tea.id}"),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    TextButton(
                        onClick = { runCatching { UUID.fromString(recommendation.tea.id) }.getOrNull()?.let(onTeaSelected) },
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    ) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(recommendation.tea.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(recommendation.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (canResume && (outcome.unresolvedAmbiguities.isNotEmpty() || !outcome.hasResults)) {
            TextButton(onClick = onResume, modifier = Modifier.testTag("madjo-resume-dialog")) { Text("Reprendre le dialogue") }
        }
        TextButton(onClick = onReturnToSearch, modifier = Modifier.testTag("madjo-return-search")) { Text("Retour à la recherche") }
    }
}

private fun criterionLabel(criterion: MadjoCriterion): String = when (criterion.concept) {
    "beverage_type" -> if (criterion.value == "infusion") "infusion" else "thé"
    "stock" -> when (criterion.value) {
        "available" -> "disponible en stock"
        "out_of_stock" -> "épuisé"
        else -> "stock inconnu"
    }
    "taste_rating" -> criterion.displayValue ?: "note gustative ${criterion.value}"
    else -> criterion.displayValue ?: criterion.value
}

@Composable
private fun HorizontalDividerMadjo() {
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun MadjoMessageBubble(message: MadjoMessage) {
    val fromUser = message.speaker == MadjoSpeaker.USER
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(16.dp),
            color = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                message.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                color = if (fromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CatalogueSearchControls(criteria: CatalogueCriteria, onQueryChange: (String) -> Unit, onSubmitQuery: () -> Unit, showFilters: () -> Unit, resetAll: () -> Unit, madjoRequest: Boolean) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SearchGlyph(Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = criteria.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f).testTag("catalogue-search"),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmitQuery() }),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    decorationBox = { inner ->
                        Box {
                            if (criteria.query.isEmpty()) Text("Rechercher un thé ou demander à Madjo…", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                            inner()
                        }
                    },
                )
            }
        }
        Surface(onClick = showFilters, enabled = !madjoRequest, modifier = Modifier.size(46.dp).testTag("catalogue-filter-button"), shape = RoundedCornerShape(12.dp),
            color = if (criteria.hasFilters) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                FilterGlyph(Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                if (criteria.hasFilters) Text("•", modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(end = 5.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        if (criteria.hasAnyCriteria) TextButton(onClick = resetAll, modifier = Modifier.testTag("catalogue-reset")) { Text("Effacer") }
    }
}

@Composable
private fun CatalogueFilterDialog(options: CatalogueFilterOptions, criteria: CatalogueCriteria, onDismiss: () -> Unit,
    onCategories: (Set<UUID>) -> Unit,
    onCountries: (Set<UUID>) -> Unit,
    onStatuses: (Set<UUID>, Boolean) -> Unit,
    onProfiles: (Set<UUID>) -> Unit,
    onBadges: (Set<UUID>) -> Unit,
    onContainers: (Set<UUID>) -> Unit,
    onMoments: (Set<UUID>) -> Unit,
    onTasteRating: (TasteRatingFilter) -> Unit,
    onReset: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Filtres du catalogue") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            FilterSection("Catégorie (couleur)", options.categories, criteria.categoryIds, onCategories)
            FilterSection("Pays", options.countries, criteria.countryIds, onCountries)
            Text("Statut", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            if (options.hasUnspecifiedStatus) FilterChoice("Non renseigné", criteria.includeUnspecifiedStatus) { onStatuses(criteria.statusIds, !criteria.includeUnspecifiedStatus) }
            options.statuses.forEach { ref -> FilterChoice(ref.label, ref.id in criteria.statusIds) { onStatuses(criteria.statusIds.toggle(ref.id), criteria.includeUnspecifiedStatus) } }
            FilterSection("Profil aromatique", options.aromaticProfiles, criteria.aromaticProfileIds, onProfiles)
            FilterSection("Badge", options.badges, criteria.badgeIds, onBadges)
            FilterSection("Contenant", options.containers, criteria.containerIds, onContainers)
            FilterSection("Moments", options.moments, criteria.momentIds, onMoments)
            Text("Note gustative", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            TasteRatingFilter.entries.forEach { filter ->
                Row(Modifier.fillMaxWidth().height(44.dp).selectable(
                    selected = criteria.tasteRating == filter,
                    role = Role.RadioButton,
                    onClick = { onTasteRating(filter) },
                ).testTag("catalogue-note-option-${filter.name}"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = criteria.tasteRating == filter, onClick = null)
                    Text(filter.label, modifier = Modifier.weight(1f))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Terminé") } }, dismissButton = { TextButton(onClick = onReset) { Text("Réinitialiser") } })
}

private fun CatalogueCriteria.activeFilterLabels(options: CatalogueFilterOptions): List<String> = buildList {
    fun addSelected(prefix: String, values: List<fr.thesdupalais.data.database.ReferenceEntity>, selected: Set<UUID>) {
        if (selected.isNotEmpty()) add("$prefix : ${values.filter { it.id in selected }.joinToString(", ") { it.label }}")
    }
    addSelected("Catégorie", options.categories, categoryIds)
    addSelected("Pays", options.countries, countryIds)
    addSelected("Statut", options.statuses, statusIds)
    if (includeUnspecifiedStatus) add("Statut : Non renseigné")
    addSelected("Profil", options.aromaticProfiles, aromaticProfileIds)
    addSelected("Badge", options.badges, badgeIds)
    addSelected("Contenant", options.containers, containerIds)
    addSelected("Moment", options.moments, momentIds)
    if (tasteRating != TasteRatingFilter.ALL) add("Note : ${tasteRating.label}")
}

@Composable
private fun FilterSection(title: String, values: List<fr.thesdupalais.data.database.ReferenceEntity>, selected: Set<UUID>, onChange: (Set<UUID>) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    values.forEach { ref -> FilterChoice(ref.label, ref.id in selected) { onChange(selected.toggle(ref.id)) } }
}

@Composable
private fun FilterChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() }).testTag("catalogue-filter-option-$label"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = selected, onCheckedChange = null)
        Text(label, modifier = Modifier.weight(1f))
    }
}

private fun Set<UUID>.toggle(id: UUID): Set<UUID> = if (id in this) this - id else this + id

@Composable
private fun SearchGlyph(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        drawCircle(tint, size.minDimension * .3f, Offset(size.width * .42f, size.height * .42f), style = Stroke(1.8.dp.toPx()))
        drawLine(tint, Offset(size.width * .64f, size.height * .64f), Offset(size.width * .9f, size.height * .9f), 1.8.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun FilterGlyph(modifier: Modifier, tint: Color) {
    val background = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        val ys = listOf(size.height * .25f, size.height * .5f, size.height * .75f)
        ys.forEachIndexed { index, y ->
            drawLine(tint, Offset(size.width * .08f, y), Offset(size.width * .92f, y), stroke, cap = StrokeCap.Round)
            val knobX = when (index) { 0 -> .38f; 1 -> .68f; else -> .48f }
            drawCircle(background, size.height * .13f, Offset(size.width * knobX, y))
            drawCircle(tint, size.height * .13f, Offset(size.width * knobX, y), style = Stroke(stroke))
        }
    }
}

@Composable
private fun AddTeaButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(56.dp).testTag("catalogue-add"),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("+", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Normal)
        }
    }
}

@Composable
private fun CatalogueEmptyState(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("catalogue-empty-state"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Votre collection est vide", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "Aucun thé n'est enregistré dans votre collection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CatalogueTeaRow(tea: CatalogueTea, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("catalogue-tea-${tea.id}"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 88.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            TeaCategoryPlaceholder(tea.categoryKey)
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = tea.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(tea.category)
                        if (tea.origins.isNotEmpty()) append(" - ${tea.origins.joinToString(" · ")}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tea.tasteRating?.let { rating ->
                    Text(
                        text = "★ ${formatTasteRating(rating)}/10",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

private fun formatTasteRating(rating: Double): String =
    if (rating % 1.0 == 0.0) rating.toInt().toString() else rating.toString().replace('.', ',')

@Composable
private fun TeaCategoryPlaceholder(categoryKey: String?) {
    val (background, illustration) = when (categoryKey?.uppercase()) {
        "GREEN" -> Color(0xFFD1E5C9) to Color(0xFF405245)
        "BLACK" -> Color(0xFF405245) to Color(0xFFE5EDE5)
        "WHITE" -> Color(0xFFF5EDD4) to Color(0xFFC7A166)
        "OOLONG" -> Color(0xFFC7A166) to Color(0xFFF5EDD4)
        "YELLOW" -> Color(0xFFE8C96B) to Color(0xFF6B5730)
        "PU_ERH", "PU-ERH", "PU’ERH", "PU'ERH" -> Color(0xFF574033) to Color(0xFFF5EDD4)
        else -> Color(0xFFD6DBC7) to Color(0xFF66715C)
    }
    Surface(
        modifier = Modifier.size(68.dp).testTag("catalogue-placeholder-${categoryKey ?: "generic"}"),
        shape = RoundedCornerShape(8.dp),
        color = background,
    ) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            Canvas(Modifier.size(36.dp)) {
                val cupWidth = size.width * .68f
                val cupHeight = size.height * .38f
                val cupLeft = size.width * .12f
                val cupTop = size.height * .42f
                drawRoundRect(
                    color = illustration,
                    topLeft = Offset(cupLeft, cupTop),
                    size = Size(cupWidth, cupHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawArc(
                    color = illustration,
                    startAngle = -70f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(size.width * .68f, cupTop + cupHeight * .12f),
                    size = Size(size.width * .28f, cupHeight * .7f),
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawLine(
                    color = illustration,
                    start = Offset(size.width * .16f, size.height * .88f),
                    end = Offset(size.width * .82f, size.height * .88f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawOval(
                    color = illustration,
                    topLeft = Offset(size.width * .4f, size.height * .06f),
                    size = Size(size.width * .2f, size.height * .28f),
                )
            }
        }
    }
}
