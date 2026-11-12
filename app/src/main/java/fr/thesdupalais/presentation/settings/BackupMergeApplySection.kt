package fr.thesdupalais.presentation.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.thesdupalais.data.backup.ConfirmedMergeChange
import fr.thesdupalais.data.backup.BackupMergeConfigurationDecisionPolicy
import fr.thesdupalais.data.backup.MergeDisposition
import fr.thesdupalais.data.backup.MergeItem
import fr.thesdupalais.data.backup.TargetChange
import fr.thesdupalais.data.operation.BackupMergeProgress
import fr.thesdupalais.data.operation.BackupMergeSelection
import fr.thesdupalais.data.operation.BackupMergeSession
import fr.thesdupalais.data.operation.BackupMergeWorkflow
import kotlinx.coroutines.launch

@Composable
internal fun BackupMergeApplySection(workflow: BackupMergeWorkflow) {
    var session by remember { mutableStateOf<BackupMergeSession?>(null) }
    var selectedSets by remember { mutableStateOf(emptySet<String>()) }
    var decisions by remember { mutableStateOf(emptySet<ConfirmedMergeChange>()) }
    var selection by remember { mutableStateOf<BackupMergeSelection?>(null) }
    var reading by remember { mutableStateOf(false) }
    var planning by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var showTechnical by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<BackupMergeProgress?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            if (session == null) error = "Sélection de l’archive annulée."
        } else scope.launch {
            reading = true
            error = null
            resultMessage = null
            showTechnical = false
            session = null
            selection = null
            selectedSets = emptySet()
            decisions = emptySet()
            runCatching { workflow.read(uri) }
                .onSuccess { loaded ->
                    session = loaded
                    selectedSets = loaded.includedSets.intersect(setOf("teas", "configuration"))
                }
                .onFailure { error = it.message?.takeIf(String::isNotBlank) ?: "L’archive n’a pas pu être validée." }
            reading = false
        }
    }

    LaunchedEffect(session?.sessionId, selectedSets, decisions) {
        val active = session
        if (active != null && selectedSets.isNotEmpty()) {
            planning = true
            selection = null
            runCatching { workflow.preview(active, selectedSets, decisions) }
                .onSuccess { selection = it }
                .onFailure { error = it.message?.takeIf(String::isNotBlank) ?: "La fusion n’a pas pu être planifiée." }
            planning = false
        } else selection = null
    }

    Card(modifier = Modifier.fillMaxWidth().testTag("settings-backup-merge-apply")) {
        Column(Modifier.padding(16.dp)) {
            Text("Fusionner une archive", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Les éléments locaux absents de l’archive sont conservés. Les conflits bloquants ne peuvent pas être forcés.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                enabled = !reading && !planning && !applying,
                modifier = Modifier.fillMaxWidth().testTag("backup-merge-open"),
            ) { Text(if (reading) "Lecture et validation…" else "Choisir une archive ZIP…") }
            error?.let { Text(it, Modifier.padding(top = 8.dp).testTag("backup-merge-error"), color = MaterialTheme.colorScheme.error) }
            resultMessage?.let { Text(it, Modifier.padding(top = 8.dp).testTag("backup-merge-result"), color = MaterialTheme.colorScheme.primary) }
            session?.let { loaded ->
                Text("Sauvegarde du ${loaded.createdAt}", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall)
                Text("Les photos des thés ne sont pas transportées par le ZIP v1 ; les photos locales sont conservées.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                Text("Ensembles disponibles", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                listOf("teas", "configuration").filter { it in loaded.includedSets }.forEach { set ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !applying) {
                            selectedSets = toggleMergeSelection(selectedSets, set)
                            decisions = emptySet()
                        }.testTag("backup-merge-select-$set"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = set in selectedSets,
                            onCheckedChange = {
                                selectedSets = toggleMergeSelection(selectedSets, set)
                                decisions = emptySet()
                            },
                            enabled = !applying,
                            modifier = Modifier.testTag("backup-merge-checkbox-$set"),
                        )
                        Text(if (set == "teas") "Thés, associations et historiques" else "Référentiels et préférences")
                    }
                }
                loaded.unsupportedSets.forEach { Text("${mergeSetLabel2(it)} : non pris en charge.", color = MaterialTheme.colorScheme.tertiary) }
                if (selectedSets.isEmpty()) Text("Sélectionnez au moins un ensemble.", Modifier.padding(top = 8.dp))
            }
            if (planning) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.height(18.dp))
                Text("Calcul de la prévisualisation…", style = MaterialTheme.typography.bodySmall)
            }
            selection?.let { current ->
                TextButton(onClick = { showTechnical = !showTechnical }, modifier = Modifier.testTag("backup-merge-technical-toggle")) {
                    Text(if (showTechnical) "Masquer les informations techniques" else "Informations techniques")
                }
                if (showTechnical) Column {
                    Text("Identifiant de sauvegarde : ${requireNotNull(session).backupId}", style = MaterialTheme.typography.bodySmall)
                    Text("SHA-256 archive : ${current.mergePlan.archiveSha256}", style = MaterialTheme.typography.bodySmall)
                    Text("SHA-256 état local : ${current.mergePlan.localSnapshotSha256}", style = MaterialTheme.typography.bodySmall)
                }
                MergeApplicationPreview(
                    selection = current,
                    labels = requireNotNull(session).labels,
                    decisions = decisions,
                    onDecision = { decision -> decisions = if (decision in decisions) decisions - decision else decisions + decision },
                    onDecisionsChange = { decisions = it },
                )
                if (current.target.warnings.isNotEmpty()) {
                    current.target.warnings.forEach { Text(it, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
                }
                Button(
                    onClick = { showConfirmation = true },
                    enabled = current.applicable && !applying,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("backup-merge-confirm"),
                ) { Text("Vérifier et confirmer la fusion…") }
                if (applying) Text("${progressLabel(progress)}…", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showConfirmation) {
        val current = selection
        if (current != null) AlertDialog(
            onDismissRequest = { if (!applying) showConfirmation = false },
            title = { Text("Confirmer la fusion") },
            text = {
                Column(Modifier.height(420.dp).verticalScroll(rememberScrollState())) {
                    Text("Les décisions ci-dessous seront appliquées. Les autres valeurs proposées et les éléments locaux absents seront conservés.")
                    ConfirmationChanges(current, requireNotNull(session).labels)
                    current.target.warnings.forEach { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.tertiary) }
                    Text("Le ZIP v1 ne contient pas les photos des thés.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = current.applicable && !applying,
                    onClick = {
                        showConfirmation = false
                        applying = true
                        error = null
                        resultMessage = null
                        scope.launch {
                            runCatching {
                                workflow.apply(requireNotNull(session), current, confirmed = true) { progress = it }
                            }.onSuccess {
                                resultMessage = "Fusion terminée et vérifiée${if (it.resumedAfterInterruption) " après reprise" else ""}."
                                selection = null
                                session = null
                            }.onFailure {
                                error = it.message?.takeIf(String::isNotBlank) ?: "La fusion a été interrompue."
                                selection = null
                                session = null
                            }
                            applying = false
                        }
                    },
                    modifier = Modifier.testTag("backup-merge-confirm-final"),
                ) { Text("Confirmer la fusion") }
            },
            dismissButton = { TextButton(enabled = !applying, onClick = { showConfirmation = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun MergeApplicationPreview(
    selection: BackupMergeSelection,
    labels: fr.thesdupalais.data.operation.BackupMergeLabels,
    decisions: Set<ConfirmedMergeChange>,
    onDecision: (ConfirmedMergeChange) -> Unit,
    onDecisionsChange: (Set<ConfirmedMergeChange>) -> Unit,
) {
    val plan = selection.mergePlan
    val target = selection.target
    Column(Modifier.fillMaxWidth().height(450.dp).verticalScroll(rememberScrollState()).testTag("backup-merge-preview")) {
        Spacer(Modifier.height(8.dp))
        val conflicts = plan.items.filter { it.disposition == MergeDisposition.CONFLICT }
        if (conflicts.isNotEmpty()) {
            Text("Conflits bloquants (${conflicts.size})", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
            conflicts.forEach { MergeReadableItem(it, labels) }
        }
        val configurationRows = plan.items.filter {
            (it.entityType.startsWith("Référence") || it.entityType == "Préférences métier") &&
                it.disposition in setOf(MergeDisposition.ADD, MergeDisposition.UPDATE_PROPOSED, MergeDisposition.PRESERVED)
        }
        if (plan.items.any { it.entityType.startsWith("Référence") || it.entityType == "Préférences métier" }) {
            Text("Configuration", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
            val identicalConfiguration = plan.items.count {
                (it.entityType.startsWith("Référence") || it.entityType == "Préférences métier") && it.disposition == MergeDisposition.IDENTICAL
            }
            if (identicalConfiguration > 0) Text("Éléments identiques : $identicalConfiguration", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onDecisionsChange(BackupMergeConfigurationDecisionPolicy.acceptAll(plan, decisions)) },
                    enabled = BackupMergeConfigurationDecisionPolicy.candidates(plan).isNotEmpty(),
                    modifier = Modifier.weight(1f).testTag("backup-merge-configuration-accept-all"),
                ) { Text("Tout accepter") }
                OutlinedButton(
                    onClick = { onDecisionsChange(BackupMergeConfigurationDecisionPolicy.refuseAll(plan, decisions)) },
                    enabled = BackupMergeConfigurationDecisionPolicy.candidates(plan).isNotEmpty(),
                    modifier = Modifier.weight(1f).testTag("backup-merge-configuration-refuse-all"),
                ) { Text("Tout refuser") }
            }
            configurationRows.forEach { item -> MergeDecisionRow(item, labels, decisions, onDecision) }
        }
        listOf(MergeDisposition.ADD, MergeDisposition.UPDATE_PROPOSED, MergeDisposition.PRESERVED, MergeDisposition.IDENTICAL).forEach { disposition ->
            val rows = plan.items.filter { item ->
                item.disposition == disposition && !(item.entityType.startsWith("Référence") || item.entityType == "Préférences métier")
            }
            if (rows.isNotEmpty()) {
                val title = when (disposition) {
                    MergeDisposition.ADD -> "Ajouts"
                    MergeDisposition.UPDATE_PROPOSED -> "Modifications proposées"
                    MergeDisposition.PRESERVED -> "Éléments conservés"
                    else -> ""
                }
                if (disposition != MergeDisposition.IDENTICAL) Text("$title (${rows.size})", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                if (disposition == MergeDisposition.IDENTICAL) {
                    Text("Éléments identiques : ${rows.size}", Modifier.padding(top = 8.dp).testTag("backup-merge-identical-count"), style = MaterialTheme.typography.titleSmall)
                } else rows.forEach { item ->
                    MergeDecisionRow(item, labels, decisions, onDecision)
                }
            }
        }
        target.blockers.forEach { Text(humanizeMergeDetail(it, labels), Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.error) }
        target.preserved.takeIf { it.isNotEmpty() }?.let {
            Text("Conservations calculées : ${it.size}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
        Text("Changements qui seront appliqués : ${target.changes.size}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
        target.changes.forEach { TargetChangeLine(it, labels) }
        Text(
            if (selection.applicable) "Prévisualisation valide. Une confirmation finale reste nécessaire." else "Fusion bloquée : résolvez les choix proposés ou les conflits signalés.",
            Modifier.padding(top = 8.dp).testTag(if (selection.applicable) "backup-merge-ready" else "backup-merge-blocked"),
            color = if (selection.applicable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun MergeDecisionRow(
    item: MergeItem,
    labels: fr.thesdupalais.data.operation.BackupMergeLabels,
    decisions: Set<ConfirmedMergeChange>,
    onDecision: (ConfirmedMergeChange) -> Unit,
) {
    MergeReadableItem(item, labels)
    decisionFields(item).forEach { field ->
        val choice = ConfirmedMergeChange(item.entityType, item.identity, field)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = choice in decisions,
                onCheckedChange = { onDecision(choice) },
                modifier = Modifier.testTag("backup-merge-decision-${item.entityType}-${item.identity}-$field"),
            )
            Text("Appliquer la valeur de l’archive pour « ${mergeFieldLabel(field)} »", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MergeReadableItem(item: MergeItem, labels: fr.thesdupalais.data.operation.BackupMergeLabels) {
    val title = when (item.entityType) {
        "Thé" -> "Thé · ${labels.teaNames[item.identity] ?: "fiche"}"
        "Dégustation", "Mouvement de stock" -> "${item.entityType} · ${labels.historyTeaNames[item.identity] ?: "historique"}"
        "Préférences métier" -> "Préférences métier"
        else -> "${item.entityType} · ${labels.businessLabels[item.identity] ?: "référence"}"
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(9.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            item.details.forEach { detail ->
                val change = Regex("^([^:]+) : (.*?) → (.*?)(?:\\.)?$").matchEntire(detail)
                if (change != null) {
                    Text("${mergeFieldLabel(change.groupValues[1])} · avant : ${humanizeMergeDetail(change.groupValues[2], labels)}", style = MaterialTheme.typography.bodySmall)
                    Text("Après : ${humanizeMergeDetail(change.groupValues[3], labels)}", style = MaterialTheme.typography.bodySmall)
                } else Text(humanizeMergeDetail(detail, labels), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TargetChangeLine(change: TargetChange, labels: fr.thesdupalais.data.operation.BackupMergeLabels) {
    val identity = labels.teaNames[change.identity] ?: labels.historyTeaNames[change.identity]?.let { "historique de $it" }
        ?: labels.businessLabels[change.identity] ?: change.identity.takeIf { it == "preferences" || it == "*" } ?: "élément"
    Text("• $identity — ${change.action}${change.fields.takeIf { it.isNotEmpty() }?.joinToString(prefix = " : ", transform = ::mergeFieldLabel).orEmpty()}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ConfirmationChanges(selection: BackupMergeSelection, labels: fr.thesdupalais.data.operation.BackupMergeLabels) {
    val target = selection.target
    Text("Changements appliqués (${target.changes.size})", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
    target.changes.forEach { change ->
        TargetChangeLine(change, labels)
        val item = selection.mergePlan.items.firstOrNull { it.identity == change.identity && (it.entityType == "Thé" || it.entityType == "Préférences métier" || it.entityType.startsWith("Référence")) }
        item?.details?.filter { detail ->
            val field = detail.substringBefore(" :")
            field in change.fields || (change.action.contains("associations") && field in setOf("countryIds", "regionIds", "aromaIds", "aromaticProfileIds", "momentIds", "badgeIds"))
        }?.forEach { detail -> Text(humanizeMergeDetail(detail, labels), style = MaterialTheme.typography.bodySmall) }
    }
    val retainedItems = selection.mergePlan.items.filter { it.disposition == MergeDisposition.PRESERVED }
    Text("Éléments locaux conservés (${retainedItems.size})", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
    retainedItems.take(30).forEach { MergeReadableItem(it, labels) }
    if (retainedItems.size > 30) Text("… et ${retainedItems.size - 30} autres éléments locaux conservés.")
    Text("Valeurs proposées laissées inchangées (${target.preserved.size})", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
    target.preserved.take(30).forEach { TargetChangeLine(it, labels) }
    if (target.preserved.size > 30) Text("… et ${target.preserved.size - 30} autres éléments conservés.")
}

private fun decisionFields(item: MergeItem): List<String> = when {
    item.entityType == "Thé" -> item.changedFields.filterNot { it in setOf("countryIds", "regionIds", "aromaIds", "aromaticProfileIds", "momentIds", "badgeIds", "stockMilliGrams") }
    item.entityType.startsWith("Référence") -> item.changedFields
    item.entityType == "Préférences métier" -> item.changedFields
    else -> emptyList()
}

private fun toggleMergeSelection(current: Set<String>, set: String): Set<String> = if (set in current) current - set else current + set
private fun mergeSetLabel2(set: String) = when (set) { "articles" -> "Articles"; else -> set }
private fun mergeFieldLabel(field: String) = when (field) {
    "legacyId" -> "identifiant historique"; "name" -> "nom"; "categoryId" -> "catégorie"; "statusId" -> "statut"
    "containerId" -> "contenant"; "description" -> "description"; "technicalInformation" -> "informations techniques"
    "personalNotes" -> "notes personnelles"; "olfactoryRating" -> "appréciation olfactive"; "tasteRating" -> "appréciation gustative"
    "brewTemperatureCelsius" -> "température d’infusion"; "infusionDurationSeconds" -> "durée d’infusion"
    "archivedAt" -> "date d’archivage"; "label" -> "libellé"; "parentCountryId" -> "pays parent"
    "semanticKey" -> "clé standard"; "displayPriority" -> "ordre d’affichage"
    "quantityPerCupMilliGrams" -> "quantité par tasse"; "lowStockThresholdMilliGrams" -> "seuil de stock faible"
    else -> field
}
private fun humanizeMergeDetail(detail: String, labels: fr.thesdupalais.data.operation.BackupMergeLabels): String {
    var result = detail
    labels.businessLabels.forEach { (id, label) -> result = result.replace(id, label) }
    labels.teaNames.forEach { (id, name) -> result = result.replace(id, name) }
    listOf("countryIds" to "pays", "regionIds" to "régions", "aromaIds" to "arômes", "aromaticProfileIds" to "profils aromatiques", "momentIds" to "moments", "badgeIds" to "badges").forEach { (key, label) -> result = result.replace(key, label) }
    return result
}
private fun progressLabel(progress: BackupMergeProgress?) = when (progress) {
    BackupMergeProgress.PREPARING -> "Préparation de la fusion"
    BackupMergeProgress.APPLYING_ROOM -> "Application des données"
    BackupMergeProgress.FINALIZING -> "Finalisation et vérifications"
    BackupMergeProgress.RECOVERING -> "Reprise de l’opération"
    BackupMergeProgress.COMPLETE -> "Fusion terminée"
    null -> "Fusion en cours"
}
