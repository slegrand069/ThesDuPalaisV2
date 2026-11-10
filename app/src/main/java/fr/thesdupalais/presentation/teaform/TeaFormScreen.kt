package fr.thesdupalais.presentation.teaform

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import java.util.UUID

@Composable
fun TeaFormScreen(viewModel: TeaFormViewModel, onCancel: () -> Unit, onSaved: (UUID) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TeaFormContent(state, viewModel, onCancel, onSaved)
}

@Composable
internal fun TeaFormContent(
    state: TeaFormUiState,
    viewModel: TeaFormViewModel,
    onCancel: () -> Unit,
    onSaved: (UUID) -> Unit,
) {
    var showAbandonDialog by remember { mutableStateOf(false) }
    var selectionKind by remember { mutableStateOf<ReferenceKind?>(null) }
    val requestCancel = { if (state.isDirty) showAbandonDialog = true else onCancel() }
    BackHandler(enabled = state.isDirty || state.isSaving) {
        if (!state.isSaving) requestCancel()
    }

    Column(Modifier.fillMaxSize().testTag("tea-create-screen")) {
        Row(
            modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = requestCancel,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.testTag("tea-form-back"),
            ) { Text("‹", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall) }
            Column(Modifier.padding(start = 12.dp)) {
                Text(if (state.editTeaId == null) "Ajouter un thé" else "Modifier le thé", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (state.editTeaId == null) "Renseignez les informations connues" else "Modifiez les informations du thé", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.isLoadingTea || state.teaUnavailable) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.isLoadingTea) "Chargement du thé…" else state.error ?: "Ce thé n’est plus disponible.",
                    color = if (state.teaUnavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(if (state.isLoadingTea) "tea-form-loading-tea" else "tea-form-unavailable"),
                )
            }
        } else {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FormCard("Identité", "tea-form-identity") {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::updateName,
                    label = { Text("Nom du thé *") },
                    modifier = Modifier.fillMaxWidth().testTag("tea-form-name"),
                    singleLine = true,
                )
            }
            FormCard("Catégorie et statut", "tea-form-category-status") {
                Text("Catégorie *", style = MaterialTheme.typography.labelLarge)
                ReferenceChips(
                    options = state.references[ReferenceKind.CATEGORY].orEmpty(),
                    selected = setOfNotNull(state.categoryId),
                    onToggle = { id, checked -> viewModel.toggleReference(ReferenceKind.CATEGORY, id, checked) },
                    tag = "tea-form-category",
                )
                Text("Statut personnel (facultatif)", style = MaterialTheme.typography.labelLarge)
                ReferenceChips(
                    options = state.references[ReferenceKind.STATUS].orEmpty(),
                    selected = setOfNotNull(state.statusId),
                    onToggle = { id, checked -> viewModel.toggleReference(ReferenceKind.STATUS, id, checked) },
                    tag = "tea-form-status",
                )
            }
            FormCard("Origine", "tea-form-origin") {
                ReferencePickerButton("Pays d’origine", ReferenceKind.COUNTRY, state, selectionKind) { selectionKind = it }
                ReferencePickerButton("Régions", ReferenceKind.REGION, state, selectionKind) { selectionKind = it }
            }
            AssociationCard("Arômes", "Sélection multiple", ReferenceKind.AROMA, state, selectionKind) { selectionKind = it }
            AssociationCard("Profils aromatiques", "Sélection multiple", ReferenceKind.AROMATIC_PROFILE, state, selectionKind) { selectionKind = it }
            AssociationCard("Moments", "Quand aimez-vous le déguster ?", ReferenceKind.MOMENT, state, selectionKind) { selectionKind = it }
            AssociationCard("Badges", "Sélection multiple", ReferenceKind.BADGE, state, selectionKind) { selectionKind = it }

            FormCard("Notes de dégustation", "tea-form-ratings") {
                OptionalRatingSlider("Note gustative", state.tasteRating, "tea-form-taste", viewModel::updateTasteRating)
                OptionalRatingSlider("Note olfactive", state.olfactoryRating, "tea-form-olfactory", viewModel::updateOlfactoryRating)
                OutlinedTextField(
                    value = state.personalNotes,
                    onValueChange = viewModel::updatePersonalNotes,
                    label = { Text("Commentaire personnel") },
                    modifier = Modifier.fillMaxWidth().testTag("tea-form-personal-notes"),
                    minLines = 3,
                )
            }
            FormCard("Préparation", "tea-form-preparation") {
                OptionalTemperatureSlider(state.brewTemperatureCelsius, viewModel::updateTemperature)
                Text("Durée d’infusion (secondes)", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = state.infusionDurationInput,
                    onValueChange = viewModel::updateDuration,
                    label = { Text("Facultatif") },
                    modifier = Modifier.fillMaxWidth().testTag("tea-form-duration"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
            FormCard("Informations complémentaires", "tea-form-extra") {
                OutlinedTextField(
                    value = state.description,
                    onValueChange = viewModel::updateDescription,
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth().testTag("tea-form-description"),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = state.technicalInformation,
                    onValueChange = viewModel::updateTechnicalInformation,
                    label = { Text("Informations techniques") },
                    modifier = Modifier.fillMaxWidth().testTag("tea-form-technical"),
                    minLines = 3,
                )
            }
            FormCard("Contenant", "tea-form-container") {
                ReferenceChips(
                    options = state.references[ReferenceKind.CONTAINER].orEmpty(),
                    selected = setOfNotNull(state.containerId),
                    onToggle = { id, checked -> viewModel.toggleReference(ReferenceKind.CONTAINER, id, checked) },
                    tag = "tea-form-container-option",
                )
            }
            if (state.isLoadingReferences) Text("Chargement des référentiels…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("tea-form-loading"))
        }
        }

        Column(
            modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("tea-form-footer"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = requestCancel, enabled = !state.isSaving, modifier = Modifier.weight(1f).testTag("tea-form-cancel")) { Text("Annuler") }
                Button(
                    onClick = { viewModel.save(onSaved) },
                    enabled = !state.isSaving && !state.isSaved && !state.isLoadingReferences && !state.isLoadingTea && !state.teaUnavailable,
                    modifier = Modifier.weight(1.5f).testTag("tea-form-save"),
                ) { Text(if (state.isSaving) "Enregistrement…" else "Enregistrer") }
            }
            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().testTag("tea-form-error"),
                )
            }
        }
    }

    if (showAbandonDialog) {
        AlertDialog(
            onDismissRequest = { showAbandonDialog = false },
            title = { Text(if (state.editTeaId == null) "Abandonner la création ?" else "Abandonner les modifications ?") },
            text = { Text("Les informations saisies ne seront pas enregistrées.") },
            confirmButton = {
                TextButton(onClick = onCancel, modifier = Modifier.testTag("tea-form-abandon-confirm")) { Text("Abandonner") }
            },
            dismissButton = {
                TextButton(onClick = { showAbandonDialog = false }, modifier = Modifier.testTag("tea-form-abandon-continue")) { Text("Continuer la saisie") }
            },
        )
    }

    selectionKind?.let { kind ->
        val selected = selectedIds(state, kind)
        ReferenceSelectionDialog(
            kind = kind,
            options = state.references[kind].orEmpty(),
            selectedIds = selected,
            onDismiss = { selectionKind = null },
            onApply = { selectedIds ->
                val allIds = selected + selectedIds
                allIds.forEach { id -> viewModel.toggleReference(kind, id, id in selectedIds) }
                selectionKind = null
            },
        )
    }
}

@Composable
private fun FormCard(title: String, tag: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ReferenceChips(options: List<ReferenceValue>, selected: Set<UUID>, onToggle: (UUID, Boolean) -> Unit, tag: String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        options.forEach { reference ->
            FilterChip(
                selected = reference.id in selected,
                enabled = reference.archivedAt == null || reference.id in selected,
                onClick = { onToggle(reference.id, reference.id !in selected) },
                label = { Text(reference.label + if (reference.archivedAt != null) " (archivée)" else "") },
                modifier = Modifier.testTag("$tag-${reference.id}"),
            )
        }
    }
}

@Composable
private fun AssociationCard(
    title: String,
    helper: String,
    kind: ReferenceKind,
    state: TeaFormUiState,
    selectionKind: ReferenceKind?,
    onOpen: (ReferenceKind) -> Unit,
) {
    val selectedIds = selectedIds(state, kind)
    val labelMap = state.references[kind].orEmpty().associateBy { it.id }
    FormCard(title, "tea-form-${kind.name.lowercase()}") {
        Text(helper, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(
            selectedIds.mapNotNull { labelMap[it]?.label }.joinToString(" · ").ifEmpty { "Aucune valeur sélectionnée" },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("tea-form-selected-${kind.name}"),
        )
        Surface(
            onClick = { onOpen(kind) },
            modifier = Modifier.fillMaxWidth().height(32.dp).testTag("tea-form-open-${kind.name}"),
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Text("Choisir ${if (kind == ReferenceKind.MOMENT) "les moments" else kind.labelForPicker()}  ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ReferencePickerButton(
    title: String,
    kind: ReferenceKind,
    state: TeaFormUiState,
    selectionKind: ReferenceKind?,
    onOpen: (ReferenceKind) -> Unit,
) {
    val selectedIds = selectedIds(state, kind)
    val labels = state.references[kind].orEmpty().filter { it.id in selectedIds }
        .map { it.label + if (it.archivedAt != null) " (archivée)" else "" }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Surface(
            onClick = { onOpen(kind) },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("tea-form-open-${kind.name}"),
            enabled = selectionKind == null || selectionKind == kind,
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(labels.joinToString(" · ").ifEmpty { "À renseigner" }, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReferenceSelectionDialog(
    kind: ReferenceKind,
    options: List<ReferenceValue>,
    selectedIds: Set<UUID>,
    onDismiss: () -> Unit,
    onApply: (Set<UUID>) -> Unit,
) {
    var draft by remember(kind, selectedIds) { mutableStateOf(selectedIds) }
    var query by remember(kind) { mutableStateOf("") }
    val multiple = kind in MULTI_SELECT_KINDS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choisir ${kind.labelForPicker()}") },
        text = {
            if (options.isEmpty()) {
                Text("Aucune valeur disponible pour ce choix.", modifier = Modifier.testTag("tea-form-no-options"))
            } else {
                Column(Modifier.height(380.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().testTag("tea-form-reference-search"),
                        label = { Text("Rechercher") },
                        singleLine = true,
                    )
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (!multiple) {
                        Row(Modifier.fillMaxWidth().clickable { draft = emptySet() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = draft.isEmpty(), onClick = { draft = emptySet() })
                            Text("Non renseigné")
                        }
                    }
                    options.filter { it.label.contains(query.trim(), ignoreCase = true) }.forEach { reference ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = reference.archivedAt == null || reference.id in draft) {
                                draft = if (multiple) draft.toggleMembership(reference.id) else if (reference.id in draft) emptySet() else setOf(reference.id)
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (multiple) Checkbox(
                                checked = reference.id in draft,
                                onCheckedChange = { checked -> draft = draft.toggle(reference.id, checked) },
                                enabled = reference.archivedAt == null || reference.id in draft,
                            )
                            else RadioButton(
                                selected = reference.id in draft,
                                onClick = { draft = setOf(reference.id) },
                                enabled = reference.archivedAt == null || reference.id in draft,
                            )
                            Text(reference.label + if (reference.archivedAt != null) " (archivée)" else "")
                        }
                    }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }, modifier = Modifier.testTag("tea-form-selection-apply")) { Text("Valider la sélection") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun OptionalRatingSlider(label: String, value: Double?, tag: String, onValueChange: (Double?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { onValueChange(null) }, modifier = Modifier.testTag("$tag-unset")) { Text("Non renseignée") }
        }
        if (value == null) {
            Text("Non renseignée", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("${formatRatingValue(value)} / 10", modifier = Modifier.testTag("$tag-value"))
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(ratingFromSliderPosition(it)) },
                valueRange = 0f..10f,
                steps = 99,
                modifier = Modifier.testTag(tag),
            )
        }
        if (value == null) {
            TextButton(onClick = { onValueChange(0.0) }, modifier = Modifier.testTag("$tag-set")) { Text("Renseigner") }
        }
    }
}

@Composable
private fun OptionalTemperatureSlider(value: Int?, onValueChange: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Température", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { onValueChange(null) }, modifier = Modifier.testTag("tea-form-temperature-unset")) { Text("Non renseignée") }
        }
        if (value == null) {
            Text("Non renseignée", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onValueChange(60) }, modifier = Modifier.testTag("tea-form-temperature-set")) { Text("Renseigner") }
        } else {
            val isStandardSliderValue = value in 60..100 && value % 5 == 0
            Text(
                text = if (isStandardSliderValue) "$value °C" else "$value °C · valeur historique",
                modifier = Modifier.testTag("tea-form-temperature-value"),
            )
            Slider(
                value = value.toFloat().coerceIn(60f, 100f),
                onValueChange = { onValueChange(temperatureFromSliderPosition(it)) },
                valueRange = 60f..100f,
                steps = 7,
                modifier = Modifier.testTag("tea-form-temperature"),
            )
        }
    }
}

private fun selectedIds(state: TeaFormUiState, kind: ReferenceKind): Set<UUID> = when (kind) {
    ReferenceKind.CATEGORY -> setOfNotNull(state.categoryId)
    ReferenceKind.STATUS -> setOfNotNull(state.statusId)
    ReferenceKind.CONTAINER -> setOfNotNull(state.containerId)
    ReferenceKind.COUNTRY -> state.countryIds
    ReferenceKind.REGION -> state.regionIds
    ReferenceKind.AROMA -> state.aromaIds
    ReferenceKind.AROMATIC_PROFILE -> state.profileIds
    ReferenceKind.MOMENT -> state.momentIds
    ReferenceKind.BADGE -> state.badgeIds
}

private fun ReferenceKind.labelForPicker(): String = when (this) {
    ReferenceKind.CATEGORY -> "une catégorie"
    ReferenceKind.STATUS -> "un statut"
    ReferenceKind.CONTAINER -> "un contenant"
    ReferenceKind.COUNTRY -> "les pays d’origine"
    ReferenceKind.REGION -> "les régions"
    ReferenceKind.AROMA -> "les arômes"
    ReferenceKind.AROMATIC_PROFILE -> "les profils aromatiques"
    ReferenceKind.MOMENT -> "les moments"
    ReferenceKind.BADGE -> "les badges"
}

private val MULTI_SELECT_KINDS = setOf(
    ReferenceKind.COUNTRY,
    ReferenceKind.REGION,
    ReferenceKind.AROMA,
    ReferenceKind.AROMATIC_PROFILE,
    ReferenceKind.MOMENT,
    ReferenceKind.BADGE,
)

private fun Set<UUID>.toggleMembership(id: UUID): Set<UUID> = if (id in this) this - id else this + id
private fun Set<UUID>.toggle(id: UUID, selected: Boolean): Set<UUID> = if (selected) this + id else this - id
