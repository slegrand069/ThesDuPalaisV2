package fr.thesdupalais.presentation.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.thesdupalais.data.backup.BackupArchiveMergePlan
import fr.thesdupalais.data.backup.BackupArchiveMergePreviewOptions
import fr.thesdupalais.data.backup.BackupArchiveMergePreviewReader
import fr.thesdupalais.data.backup.MergeDisposition
import fr.thesdupalais.data.backup.MergeItem
import kotlinx.coroutines.launch

@Composable
internal fun BackupMergePreviewSection(reader: BackupArchiveMergePreviewReader) {
    var loaded by remember { mutableStateOf<BackupArchiveMergePreviewOptions?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            if (loaded == null) error = "Sélection de l’archive annulée."
        } else scope.launch {
            loading = true
            error = null
            loaded = null
            runCatching { reader.read(uri) }
                .onSuccess { options ->
                    loaded = options
                }
                .onFailure { failure -> error = failure.message?.takeIf(String::isNotBlank) ?: "L’archive n’a pas pu être lue ou planifiée." }
            loading = false
        }
    }
    Card(modifier = Modifier.fillMaxWidth().testTag("settings-backup-merge-preview")) {
        Column(Modifier.padding(16.dp)) {
            Text("Prévisualiser une fusion", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Cette analyse est en lecture seule. Aucune donnée ne sera modifiée.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().testTag("backup-merge-preview-open"),
            ) { Text(if (loading) "Lecture et analyse…" else "Choisir une archive pour analyse…") }
            error?.let {
                Text(it, modifier = Modifier.padding(top = 10.dp).testTag("backup-merge-preview-error"), color = MaterialTheme.colorScheme.error)
            }
            loaded?.let { MergePlanChooser(it) }
        }
    }
}

@Composable
internal fun MergePlanChooser(
    options: BackupArchiveMergePreviewOptions,
    initialSelection: Set<String> = options.includedSets.intersect(setOf("teas", "configuration")),
) {
    val selectedState = remember(options.backupId) { mutableStateOf(initialSelection) }
    val selectedSets = selectedState.value
    Spacer(Modifier.height(12.dp))
    Text("Ensembles disponibles", style = MaterialTheme.typography.titleSmall)
    listOf("teas", "configuration").filter { it in options.includedSets }.forEach { set ->
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                selectedState.value = toggleMergeSet(selectedState.value, set)
            }.testTag("merge-preview-select-$set"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = set in selectedSets,
                onCheckedChange = { selectedState.value = toggleMergeSet(selectedState.value, set) },
                enabled = true,
                modifier = Modifier.testTag("merge-preview-checkbox-$set"),
            )
            Text(mergeSetLabel(set))
        }
    }
    options.unsupportedSets.forEach { set ->
        Text(
            "${mergeSetLabel(set)} : non pris en charge dans la fusion actuelle.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("merge-preview-unsupported-$set"),
        )
    }
    Text("Les éléments locaux absents de l’archive sont conservés. Cette page n’offre aucune action d’écriture.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (selectedSets.isEmpty()) {
        Text("Cochez au moins un ensemble pris en charge.", Modifier.padding(top = 8.dp))
    } else {
        options.plans.firstOrNull { it.selectedSets == selectedSets }?.let {
            Text("Plan sélectionné : ${selectedSets.sorted().joinToString { set -> mergeSetLabel(set) }}", style = MaterialTheme.typography.labelMedium)
            MergePlanContent(options, it)
        }
    }
}

internal fun toggleMergeSet(selectedSets: Set<String>, set: String): Set<String> =
    if (set in selectedSets) selectedSets - set else selectedSets + set

@Composable
internal fun MergePlanContent(options: BackupArchiveMergePreviewOptions, plan: BackupArchiveMergePlan) {
    var showTechnical by remember(plan.archiveSha256, plan.localSnapshotSha256) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().height(460.dp).verticalScroll(rememberScrollState()).testTag("merge-preview-plan")) {
        Spacer(Modifier.height(10.dp))
        Text("Créée le ${options.createdAt}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { showTechnical = !showTechnical }, modifier = Modifier.testTag("merge-preview-technical-toggle")) {
            Text(if (showTechnical) "Masquer les informations techniques" else "Afficher les informations techniques")
        }
        if (showTechnical) {
            Text("Identifiant de sauvegarde : ${options.backupId}", style = MaterialTheme.typography.bodySmall)
            Text("SHA-256 archive : ${plan.archiveSha256}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("merge-preview-archive-hash"))
            Text("SHA-256 état local : ${plan.localSnapshotSha256}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("merge-preview-local-hash"))
            plan.items.filter { it.disposition != MergeDisposition.IDENTICAL }.forEach { item ->
                Text("${item.entityType} · ${item.identity}", style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        val conflicts = plan.items.filter { it.disposition == MergeDisposition.CONFLICT }
        if (conflicts.isNotEmpty()) {
            Text("Conflits bloquants (${conflicts.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            conflicts.forEach { MergePlanItem(it, options) }
        }
        val order = listOf(MergeDisposition.UPDATE_PROPOSED, MergeDisposition.ADD, MergeDisposition.PRESERVED, MergeDisposition.NOT_COMPARABLE)
        order.forEach { disposition ->
            val entries = plan.items.filter { it.disposition == disposition }
            if (entries.isNotEmpty()) {
                Text("${mergeDispositionLabel(disposition)} (${entries.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                entries.forEach { MergePlanItem(it, options) }
            }
        }
        val identicalCount = plan.items.count { it.disposition == MergeDisposition.IDENTICAL }
        if (identicalCount > 0) Text("Éléments identiques : $identicalCount", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp).testTag("merge-preview-identical-count"))
        if (plan.items.isEmpty()) Text("Aucun changement détecté.", style = MaterialTheme.typography.bodyMedium)
        Text(
            if (plan.canApplyAfterConfirmation) "Aucun conflit bloquant détecté. Les propositions restent soumises à une confirmation future." else "Cette sélection contient des conflits bloquants.",
            color = if (plan.canApplyAfterConfirmation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 10.dp).testTag(if (plan.canApplyAfterConfirmation) "merge-preview-confirmable" else "merge-preview-blocked"),
        )
        plan.limitations.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun MergePlanItem(item: MergeItem, options: BackupArchiveMergePreviewOptions) {
    val conflict = item.disposition == MergeDisposition.CONFLICT
    val proposed = item.disposition == MergeDisposition.UPDATE_PROPOSED
    val background = when {
        conflict -> MaterialTheme.colorScheme.errorContainer
        proposed -> MaterialTheme.colorScheme.primaryContainer
        item.disposition == MergeDisposition.PRESERVED -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("merge-item-${item.identity}"),
        colors = CardDefaults.cardColors(containerColor = background),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(mergeItemBusinessTitle(item, options), style = MaterialTheme.typography.bodyMedium)
            if (conflict) Text(item.details.joinToString(" ") { humanize(it, options) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            else item.details.forEach { detail ->
                val change = parseFieldChange(detail)
                if (change != null) {
                    Text(change.first, style = MaterialTheme.typography.labelMedium)
                    Text("Avant : ${humanize(change.second, options)}", style = MaterialTheme.typography.bodySmall)
                    Text("Après : ${humanize(change.third, options)}", style = MaterialTheme.typography.bodySmall)
                } else Text(humanize(detail, options), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun mergeItemBusinessTitle(item: MergeItem, options: BackupArchiveMergePreviewOptions): String = when (item.entityType) {
    "Thé" -> "Thé : ${options.teaNames[item.identity] ?: "fiche de thé"}"
    "Dégustation" -> "Dégustation · ${options.historyTeaNames[item.identity] ?: "historique du thé"}"
    "Mouvement de stock" -> "Mouvement de stock · ${options.historyTeaNames[item.identity] ?: "historique du thé"}"
    "Stock" -> "Stock · ${options.teaNames[item.identity] ?: "thé concerné"}"
    "Référence", "Référence standard", "Référence personnalisée", "Référence locale" -> "${item.entityType} : ${options.businessLabels[item.identity] ?: "élément de référentiel"}"
    "Préférences métier" -> "Préférences métier"
    else -> item.entityType
}

private fun parseFieldChange(detail: String): Triple<String, String, String>? {
    val match = Regex("^([^:]+) : (.*?) → (.*?)(?:\\.?)$").matchEntire(detail) ?: return null
    return Triple(fieldLabel(match.groupValues[1]), match.groupValues[2], match.groupValues[3].removeSuffix("."))
}

private fun fieldLabel(field: String) = when (field) {
    "legacyId" -> "Identifiant historique"
    "name" -> "Nom"
    "categoryId" -> "Catégorie"
    "description" -> "Description"
    "technicalInformation" -> "Informations techniques"
    "personalNotes" -> "Notes personnelles"
    "olfactoryRating" -> "Appréciation olfactive"
    "tasteRating" -> "Appréciation gustative"
    "brewTemperatureCelsius" -> "Température d’infusion"
    "infusionDurationSeconds" -> "Durée d’infusion"
    "statusId" -> "Statut"
    "containerId" -> "Contenant"
    "archivedAt" -> "Date d’archivage"
    "label" -> "Libellé"
    "semanticKey" -> "Clé standard"
    "parentCountryId" -> "Pays parent"
    "displayPriority" -> "Ordre d’affichage"
    "quantityPerCupMilliGrams" -> "Quantité par tasse"
    "lowStockThresholdMilliGrams" -> "Seuil de stock faible"
    else -> field
}

private fun humanize(value: String, options: BackupArchiveMergePreviewOptions): String {
    var result = value
    options.businessLabels.entries.sortedByDescending { it.key.length }.forEach { (id, label) -> result = result.replace(id, label) }
    options.teaNames.entries.sortedByDescending { it.key.length }.forEach { (id, name) -> result = result.replace(id, name) }
    options.historyTeaNames.entries.sortedByDescending { it.key.length }.forEach { (id, name) -> result = result.replace(id, "historique de $name") }
    result = result.replace("countryIds", "Pays").replace("regionIds", "Régions").replace("aromaIds", "Arômes")
        .replace("aromaticProfileIds", "Profils aromatiques").replace("momentIds", "Moments").replace("badgeIds", "Badges")
    result = result.replace("legacyId", "identifiant historique").replace("semanticKey", "clé standard")
        .replace("parentCountryId", "pays parent").replace("UUID", "identifiant")
        .replace("même identifiant avec un contenu différent", "identifiant déjà utilisé pour un contenu différent")
    result = result.replace(": ajout ", " · ajoutés : ").replace("; conservation ", " · conservés : ")
        .replace(": conservation ", " · conservés : ").replace("absente(s) de l’archive", "absents de l’archive")
    return result
}

private fun mergeSetLabel(set: String) = when (set) {
    "teas" -> "Thés, associations, dégustations, mouvements et références nécessaires"
    "configuration" -> "Référentiels complets et préférences métier"
    "articles" -> "Articles"
    else -> set
}

private fun mergeDispositionLabel(disposition: MergeDisposition) = when (disposition) {
    MergeDisposition.ADD -> "Ajouts"
    MergeDisposition.IDENTICAL -> "Identiques"
    MergeDisposition.UPDATE_PROPOSED -> "Modifications proposées · confirmation requise"
    MergeDisposition.PRESERVED -> "Éléments conservés"
    MergeDisposition.CONFLICT -> "Conflits bloquants"
    MergeDisposition.NOT_COMPARABLE -> "Non comparables"
}
