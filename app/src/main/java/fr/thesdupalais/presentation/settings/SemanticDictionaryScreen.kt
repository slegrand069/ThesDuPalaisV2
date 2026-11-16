package fr.thesdupalais.presentation.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import fr.thesdupalais.domain.model.frenchLabelComparator
import fr.thesdupalais.domain.semantics.MadjoSemanticResolver
import fr.thesdupalais.domain.semantics.SemanticConcept
import fr.thesdupalais.domain.semantics.SemanticDomain
import fr.thesdupalais.domain.semantics.SemanticExpression
import fr.thesdupalais.domain.semantics.SemanticRelation
import fr.thesdupalais.domain.semantics.SemanticRelationType
import fr.thesdupalais.domain.semantics.SemanticDictionaryValidator
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun SemanticDictionaryScreen(
    viewModel: SemanticDictionaryViewModel,
    onBack: () -> Unit,
    onEditConcept: (String) -> Unit,
    onCreateConcept: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportContent by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = exportContent
        exportContent = null
        if (uri != null && content != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                    ?: error("Impossible d’ouvrir le fichier de destination.") }
            }
            result.onSuccess { viewModel.reportNotice("Dictionnaire exporté.") }
                .onFailure { viewModel.reportError(it.message ?: "Échec de l’export.") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MAX_DICTIONARY_JSON_BYTES) { "Le fichier dépasse la taille maximale autorisée (5 Mo)." }
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    } ?: error("Impossible de lire ce fichier.")
                }
            }
            result.onSuccess(viewModel::prepareImport)
                .onFailure { viewModel.reportError(it.message ?: "Échec de la lecture du fichier.") }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("semantic-list-back")) { Text("‹") }
            Text("Dictionnaire Madjo", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Text("Concepts, expressions et relations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.exportJson { text -> exportContent = text; exportLauncher.launch("dictionnaire-madjo.json") } }, modifier = Modifier.weight(1f).testTag("semantic-export")) {
                Text("Exporter")
            }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) }, modifier = Modifier.weight(1f).testTag("semantic-import")) {
                Text("Importer")
            }
        }
        Text("L’import remplace uniquement le dictionnaire Madjo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        var domainMenu by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { domainMenu = true }, modifier = Modifier.fillMaxWidth().testTag("semantic-domain-picker")) {
            Text(semanticDomainLabel(state.domain))
        }
        DropdownMenu(expanded = domainMenu, onDismissRequest = { domainMenu = false }) {
            SemanticDomain.entries.sortedWith(frenchLabelComparator(::semanticDomainLabel, { it.name })).forEach { domain ->
                DropdownMenuItem(text = { Text(semanticDomainLabel(domain)) }, onClick = {
                    viewModel.selectDomain(domain)
                    domainMenu = false
                })
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth().testTag("semantic-search"),
            label = { Text("Rechercher un concept ou synonyme") },
            singleLine = true,
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp).testTag("semantic-error")) }
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp).testTag("semantic-notice")) }
        Spacer(Modifier.height(10.dp))
        val concepts = state.filteredConcepts()
        if (state.isLoading) {
            Text("Chargement du dictionnaire…", modifier = Modifier.padding(12.dp))
        } else if (concepts.isEmpty()) {
            Text("Aucun concept dans ce domaine.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(concepts, key = SemanticConcept::id) { concept ->
                    val synonyms = state.snapshot.expressions.filter { it.conceptId == concept.id && !it.isPrimary && it.expression.isNotBlank() }
                        .sortedWith(frenchLabelComparator(SemanticExpression::expression, SemanticExpression::id)).map(SemanticExpression::expression)
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onEditConcept(concept.id) }
                            .testTag("semantic-concept-${concept.id}"),
                        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(concept.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(if (concept.active) "Actif" else "Inactif", color = if (concept.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("semantic-active-${concept.id}"))
                            }
                            if (synonyms.isNotEmpty()) {
                                Text(synonyms.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("Ouvrir la fiche ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
        Button(onClick = onCreateConcept, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("semantic-create")) {
            Text("Nouveau concept")
        }
    }
    state.importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Remplacer le dictionnaire ?") },
            text = { Column {
                Text("Le fichier contient : ${preview.concepts.size} concepts, ${preview.synonymCount} synonymes, ${preview.relationCount} relations.")
                Text("Concepts inactifs : ${preview.inactiveConceptCount} · suppressions logiques : ${preview.tombstoneCount}")
                Spacer(Modifier.height(8.dp))
                Text("Le dictionnaire Madjo actuel sera remplacé intégralement. Les fiches de thé, dégustations, stocks, articles et références métier ne seront pas modifiés.")
            } },
            confirmButton = { TextButton(onClick = { viewModel.confirmImport { } }, enabled = !state.importInProgress, modifier = Modifier.testTag("semantic-import-confirm")) { Text(if (state.importInProgress) "Restauration…" else "Remplacer") } },
            dismissButton = { TextButton(onClick = viewModel::cancelImport, enabled = !state.importInProgress, modifier = Modifier.testTag("semantic-import-cancel")) { Text("Annuler") } },
        )
    }
}

private const val MAX_DICTIONARY_JSON_BYTES = 5 * 1024 * 1024

@Composable
fun SemanticConceptEditorScreen(
    viewModel: SemanticDictionaryViewModel,
    conceptId: String?,
    onCancel: () -> Unit,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val concept = conceptId?.let { id -> state.snapshot.concepts.firstOrNull { it.id == id } }
    if (state.isLoading) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) { Text("Chargement du dictionnaire…") }
        return
    }
    if (conceptId != null && concept == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("Concept indisponible", style = MaterialTheme.typography.titleLarge)
            Text("Il a peut-être été supprimé.", modifier = Modifier.padding(vertical = 12.dp))
            TextButton(onClick = onCancel) { Text("Retour") }
        }
        return
    }
    val isNew = conceptId == null
    val localId = remember { conceptId ?: UUID.randomUUID().toString() }
    val domainInitial = concept?.domain ?: state.domain
    var selectedDomain by remember(localId, concept?.id) { mutableStateOf(domainInitial) }
    val originalExpressions = concept?.let { state.snapshot.expressions.filter { expression -> expression.conceptId == it.id } }.orEmpty()
    val originalSynonyms = originalExpressions.filter { !it.isPrimary && it.expression.isNotBlank() }
        .sortedWith(frenchLabelComparator(SemanticExpression::expression, SemanticExpression::id)).map(SemanticExpression::expression)
    val initialMapping = concept?.let { findMappedReference(it, state.referenceOptions) }
    var label by remember(localId, concept?.id) { mutableStateOf(concept?.label.orEmpty()) }
    var synonyms by remember(localId, concept?.id) { mutableStateOf(originalSynonyms.joinToString("\n")) }
    var active by remember(localId, concept?.id) { mutableStateOf(concept?.active ?: true) }
    var selectedMapping by remember(localId, concept?.id) {
        mutableStateOf(initialMapping?.id?.toString() ?: if (concept?.businessValue != null) KEEP_EXISTING_MAPPING else NO_MAPPING)
    }
    var referenceMenu by remember { mutableStateOf(false) }
    var domainMenu by remember { mutableStateOf(false) }
    var relationMenu by remember { mutableStateOf(false) }
    var relationToDelete by remember { mutableStateOf<SemanticRelation?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var localError by remember(localId) { mutableStateOf<String?>(null) }
    val protected = concept?.isProtectedSystemConcept() == true
    val synonymsList = synonyms.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    val dirty = isNew || concept?.let {
        label != it.label || synonymsList != originalSynonyms || active != it.active ||
            (selectedMapping != (initialMapping?.id?.toString() ?: if (it.businessValue != null) KEEP_EXISTING_MAPPING else NO_MAPPING))
    } ?: false
    BackHandler(enabled = dirty) { showDiscardDialog = true }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (dirty) showDiscardDialog = true else onCancel() }, modifier = Modifier.testTag("semantic-editor-back")) { Text("‹ Annuler") }
            Text(if (isNew) "Nouveau concept" else "Fiche du concept", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        if (isNew) {
            FieldLabel("Domaine")
            OutlinedButton(onClick = { domainMenu = true }, modifier = Modifier.fillMaxWidth().testTag("semantic-editor-domain")) {
                Text(semanticDomainLabel(selectedDomain))
            }
            DropdownMenu(expanded = domainMenu, onDismissRequest = { domainMenu = false }) {
                SemanticDomain.entries.sortedWith(frenchLabelComparator(::semanticDomainLabel, { it.name })).forEach { domain -> DropdownMenuItem(text = { Text(semanticDomainLabel(domain)) }, onClick = {
                    selectedDomain = domain
                    selectedMapping = NO_MAPPING
                    domainMenu = false
                }) }
            }
        } else {
            FieldLabel("Domaine")
            Text(semanticDomainLabel(selectedDomain), modifier = Modifier.padding(vertical = 8.dp).testTag("semantic-editor-domain-readonly"))
        }
        FieldLabel("Libellé")
        OutlinedTextField(
            value = label,
            onValueChange = { label = it; localError = null },
            modifier = Modifier.fillMaxWidth().testTag("semantic-label-input"),
            placeholder = { Text("Ex. : Fruits des bois") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        FieldLabel("Synonymes (une expression par ligne)")
        OutlinedTextField(
            value = synonyms,
            onValueChange = { synonyms = it; localError = null },
            modifier = Modifier.fillMaxWidth().testTag("semantic-synonyms-input"),
            placeholder = { Text("fruit des bois\nfruits rouges") },
            minLines = 3,
            maxLines = 8,
        )
        Spacer(Modifier.height(14.dp))
        FieldLabel("Référence ThésDuPalais associée (facultatif)")
        val availableReferences = state.referenceOptions.filter { it.kind in selectedDomain.referenceKinds() }
            .sortedWith(frenchLabelComparator(ReferenceValue::label) { it.id.toString() })
        val selectedReference = availableReferences.firstOrNull { it.id.toString() == selectedMapping }
        if (protected) {
            Text("Liaison interne protégée par Madjo", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("semantic-protected-link"))
        } else if (selectedDomain.referenceKinds().isEmpty()) {
            Text("Ce domaine utilise des intentions internes. Leur liaison métier est gérée par Madjo.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("semantic-internal-link"))
        } else {
            OutlinedButton(onClick = { referenceMenu = true }, modifier = Modifier.fillMaxWidth().testTag("semantic-reference-picker")) {
                Text(when {
                    selectedMapping == NO_MAPPING -> "Aucune liaison"
                    selectedMapping == KEEP_EXISTING_MAPPING -> "Conserver la liaison actuelle"
                    selectedReference != null -> selectedReference.label
                    else -> "Référence indisponible"
                })
            }
            DropdownMenu(expanded = referenceMenu, onDismissRequest = { referenceMenu = false }) {
                DropdownMenuItem(text = { Text("Aucune liaison") }, onClick = { selectedMapping = NO_MAPPING; referenceMenu = false })
                if (selectedMapping == KEEP_EXISTING_MAPPING) DropdownMenuItem(
                    text = { Text("Conserver la liaison actuelle") }, onClick = { referenceMenu = false },
                )
                availableReferences.forEach { reference ->
                    DropdownMenuItem(text = { Text(reference.displayName()) }, onClick = {
                        selectedMapping = reference.id.toString()
                        referenceMenu = false
                    })
                }
            }
        }
        val mappingConfigured = selectedMapping == KEEP_EXISTING_MAPPING ||
            (selectedMapping != NO_MAPPING && selectedReference != null)
        val hasMappedSpecialization = concept != null && state.snapshot.relations.any { relation ->
            relation.active && relation.sourceConceptId == concept.id && state.snapshot.concepts.any { child ->
                child.id == relation.targetConceptId && !child.businessValue.isNullOrBlank()
            }
        }
        if (!mappingConfigured && !hasMappedSpecialization) {
            Text("Sans liaison métier ni relation hiérarchique exploitable, ce concept sera enregistré mais n’influencera pas les recommandations.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp).testTag("semantic-no-mapping-warning"))
        }
        if (!isNew) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Concept actif", fontWeight = FontWeight.SemiBold)
                    Text(if (protected) "Ce concept est indispensable aux intentions Madjo." else "Un concept inactif n’est plus reconnu.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = active, onCheckedChange = { active = it }, enabled = !protected, modifier = Modifier.testTag("semantic-active-switch"))
            }
        }

        if (!isNew && concept != null) {
            Spacer(Modifier.height(16.dp))
            Text("Relations hiérarchiques", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val relations = state.snapshot.relations.filter { it.active && it.type == SemanticRelationType.SPECIALIZATION && (it.sourceConceptId == concept.id || it.targetConceptId == concept.id) }
            if (relations.isEmpty()) Text("Aucune relation", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            relations.forEach { relation ->
                val source = state.snapshot.concepts.firstOrNull { it.id == relation.sourceConceptId }
                val target = state.snapshot.concepts.firstOrNull { it.id == relation.targetConceptId }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${source?.label ?: "Concept"} → ${target?.label ?: "concept"}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { relationToDelete = relation }, modifier = Modifier.testTag("semantic-relation-delete-${relation.id}")) { Text("Supprimer") }
                }
            }
            val candidates = state.snapshot.concepts.filter { candidate ->
                candidate.id != concept.id && candidate.active && candidate.domain == concept.domain &&
                    runCatching { SemanticDictionaryValidator.validateSpecialization(concept, candidate, state.snapshot.relations) }.isSuccess
            }.sortedWith(frenchLabelComparator(SemanticConcept::label, SemanticConcept::id))
            if (candidates.isNotEmpty()) {
                OutlinedButton(onClick = { relationMenu = true }, modifier = Modifier.fillMaxWidth().testTag("semantic-add-relation")) {
                    Text("Ajouter une spécialisation de ${concept.label}")
                }
                DropdownMenu(expanded = relationMenu, onDismissRequest = { relationMenu = false }) {
                    candidates.forEach { target -> DropdownMenuItem(text = { Text("${concept.label} → ${target.label}") }, onClick = {
                        viewModel.addSpecialization(concept.id, target.id)
                        relationMenu = false
                    }) }
                }
            }
        }

        localError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp).testTag("semantic-form-error")) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp).testTag("semantic-operation-error")) }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { if (dirty) showDiscardDialog = true else onCancel() }, modifier = Modifier.weight(1f).testTag("semantic-cancel")) { Text("Annuler") }
            Button(onClick = {
                val trimmedSynonyms = synonymsList
                val chosenRef = availableReferences.firstOrNull { it.id.toString() == selectedMapping }
                val current = concept ?: SemanticConcept(localId, selectedDomain, label.trim())
                val mapped = when {
                    selectedMapping == NO_MAPPING -> current.copy(businessDimension = null, businessConcept = null, businessValue = null)
                    selectedMapping == KEEP_EXISTING_MAPPING -> current
                    chosenRef != null -> runCatching {
                        val mapping = semanticMappingFor(selectedDomain, chosenRef)
                        current.copy(businessDimension = mapping.businessDimension, businessConcept = mapping.businessConcept, businessValue = mapping.businessValue)
                    }.getOrElse { localError = it.message; null }
                    else -> current
                }
                if (mapped != null) {
                    val forms = listOf(label.trim()) + trimmedSynonyms
                    val normalized = forms.map(MadjoSemanticResolver::normalize)
                    if (label.isBlank()) localError = "Le libellé est obligatoire."
                    else if (normalized.any(String::isBlank)) localError = "Chaque expression doit contenir du texte."
                    else if (normalized.distinct().size != normalized.size) localError = "Le libellé ou un synonyme est présent plusieurs fois après normalisation."
                    else {
                        saving = true
                        localError = null
                        val saveConcept = mapped.copy(label = label.trim(), active = active)
                        viewModel.saveConcept(saveConcept, trimmedSynonyms,
                            onSaved = { saving = false; onSaved(saveConcept.id) },
                            onFailed = { saving = false },
                        )
                    }
                }
            }, enabled = !saving && (isNew || concept != null), modifier = Modifier.weight(1f).testTag("semantic-save")) {
                Text(if (saving) "Enregistrement…" else "Enregistrer")
            }
        }
        if (!isNew && concept != null) {
            TextButton(onClick = { viewModel.requestDelete(concept.id) }, enabled = !protected, modifier = Modifier.fillMaxWidth().testTag("semantic-delete-concept")) {
                Text(if (protected) "Concept système protégé" else "Supprimer ce concept")
            }
        }
    }

    if (state.deleteImpact != null && state.deletingConceptId == concept?.id) {
        val impact = state.deleteImpact!!
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Supprimer « ${impact.label} » ?") },
            text = { Text("Cette action supprimera ${impact.synonymCount} synonyme(s) et ${impact.relationCount} relation(s). Aucune fiche de thé ne sera modifiée.") },
            confirmButton = { TextButton(onClick = { viewModel.confirmDelete(onDeleted) }, modifier = Modifier.testTag("semantic-confirm-delete")) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete, modifier = Modifier.testTag("semantic-dismiss-delete")) { Text("Annuler") } },
        )
    }
    relationToDelete?.let { relation ->
        AlertDialog(
            onDismissRequest = { relationToDelete = null },
            title = { Text("Supprimer cette relation ?") },
            text = { Text("Les deux concepts resteront dans le dictionnaire.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteSpecialization(relation.id); relationToDelete = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { relationToDelete = null }) { Text("Annuler") } },
        )
    }
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Abandonner les modifications ?") },
            text = { Text("Les changements non enregistrés seront perdus. Les données enregistrées restent inchangées.") },
            confirmButton = { TextButton(onClick = { showDiscardDialog = false; onCancel() }, modifier = Modifier.testTag("semantic-confirm-discard")) { Text("Abandonner") } },
            dismissButton = { TextButton(onClick = { showDiscardDialog = false }, modifier = Modifier.testTag("semantic-dismiss-discard")) { Text("Continuer") } },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

private const val NO_MAPPING = "__none__"
private const val KEEP_EXISTING_MAPPING = "__keep__"

private fun findMappedReference(concept: SemanticConcept, references: List<ReferenceValue>): ReferenceValue? {
    val value = concept.businessValue ?: return null
    if (value.startsWith("ref:", ignoreCase = true)) {
        val id = runCatching { UUID.fromString(value.substringAfter(':')) }.getOrNull()
        return references.firstOrNull { it.id == id && it.kind in concept.domain.referenceKinds() }
    }
    val normalized = MadjoSemanticResolver.normalize(value.replace('_', ' '))
    return references.firstOrNull { it.kind in concept.domain.referenceKinds() &&
        (it.semanticKey?.let { key -> MadjoSemanticResolver.normalize(key.replace('_', ' ')) == normalized } == true ||
            MadjoSemanticResolver.normalize(it.label) == normalized)
    }
}

private fun ReferenceValue.displayName(): String = when {
    kind == ReferenceKind.REGION -> "${label} · Région"
    kind == ReferenceKind.COUNTRY -> "${label} · Pays"
    else -> label
}
