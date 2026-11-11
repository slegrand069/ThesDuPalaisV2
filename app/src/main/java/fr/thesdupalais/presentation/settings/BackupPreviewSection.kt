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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import fr.thesdupalais.data.backup.BackupArchivePreview
import fr.thesdupalais.data.backup.BackupArchivePreviewReader
import fr.thesdupalais.data.backup.BackupPreviewOptions
import fr.thesdupalais.data.backup.PreviewDisposition
import kotlinx.coroutines.launch

@Composable
internal fun BackupPreviewSection(reader: BackupArchivePreviewReader) {
    var loaded by remember { mutableStateOf<BackupPreviewOptions?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            if (loaded == null) error = "Lecture de la sauvegarde annulée."
        } else scope.launch {
            loading = true; error = null; loaded = null
            runCatching { reader.read(uri) }
                .onSuccess { options -> loaded = options; selected = options.includedSets }
                .onFailure { failure -> error = failure.message?.takeIf(String::isNotBlank) ?: "L’archive n’a pas pu être lue ou validée." }
            loading = false
        }
    }
    Card(modifier = Modifier.fillMaxWidth().testTag("settings-backup-preview")) {
        Column(Modifier.padding(16.dp)) {
            Text("Examiner une sauvegarde", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("La lecture et la prévisualisation ne modifient aucune donnée.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed")) }, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("backup-preview-open")) {
                Text(if (loading) "Lecture et validation…" else "Choisir une archive ZIP…")
            }
            error?.let { Text(it, modifier = Modifier.padding(top = 10.dp).testTag("backup-preview-error"), color = MaterialTheme.colorScheme.error) }
            loaded?.let { options ->
                Spacer(Modifier.height(12.dp))
                Text("Ensembles présents dans l’archive", style = MaterialTheme.typography.titleSmall)
                options.includedSets.sorted().forEach { set ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = set in selected, onCheckedChange = { checked ->
                            selected = if (checked) selected + set else selected - set
                        }, modifier = Modifier.testTag("backup-preview-select-$set"))
                        Text(setLabel(set), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "La prévisualisation décrit le remplacement des ensembles cochés uniquement. Les ensembles décochés ou absents resteraient inchangés. Rien n’est appliqué depuis cet écran.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selected.isEmpty()) Text("Sélectionnez au moins un ensemble pour afficher ses conséquences.", style = MaterialTheme.typography.bodySmall)
                else options.previews.firstOrNull { it.selectedSets == selected }?.let { PreviewContent(it) }
            }
        }
    }
}

@Composable
private fun PreviewContent(preview: BackupArchivePreview) {
    Column(Modifier.fillMaxWidth().heightInCompat().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        Text("Sauvegarde ${preview.backupId}", style = MaterialTheme.typography.bodySmall)
        Text("Créée le ${preview.createdAt}", style = MaterialTheme.typography.bodySmall)
        Text("SHA-256 de l’archive : ${preview.archiveSha256}", style = MaterialTheme.typography.bodySmall)
        preview.archiveCounts.forEach { (label, count) -> Text("$label : $count", style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        preview.groups.forEach { group ->
            Text(group.name, style = MaterialTheme.typography.titleSmall)
            group.counts.forEach { count -> Text("${dispositionLabel(count.disposition)} : ${count.count}", style = MaterialTheme.typography.bodySmall) }
            group.blockingConflicts.forEach { Text("Conflit bloquant : $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            group.details.take(12).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (group.details.size > 12) Text("… ${group.details.size - 12} autre(s) élément(s)", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
        }
        preview.limitations.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
        Text("Cette page est une prévisualisation en lecture seule.", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
    }
}

private fun setLabel(set: String) = when (set) {
    "teas" -> "Thés, associations, dégustations et mouvements"
    "configuration" -> "Configuration, référentiels et préférences"
    "articles" -> "Articles"
    else -> set
}

private fun dispositionLabel(status: PreviewDisposition) = when (status) {
    PreviewDisposition.ADDED -> "Ajoutés"
    PreviewDisposition.MODIFIED -> "Modifiés"
    PreviewDisposition.IDENTICAL -> "Identiques"
    PreviewDisposition.REMOVED -> "Supprimés par le remplacement"
    PreviewDisposition.PRESERVED -> "Conservés"
    PreviewDisposition.CONFLICT -> "Conflits bloquants"
    PreviewDisposition.NOT_COMPARABLE -> "Sans comparaison locale"
}

private fun Modifier.heightInCompat() = this.height(420.dp)
