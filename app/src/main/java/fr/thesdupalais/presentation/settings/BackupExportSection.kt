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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import fr.thesdupalais.data.backup.BackupArchiveExporter
import fr.thesdupalais.data.backup.BackupExportSet
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
internal fun BackupExportSection(exporter: BackupArchiveExporter) {
    var teas by remember { mutableStateOf(true) }
    var configuration by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingSets by remember { mutableStateOf<Set<BackupExportSet>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        dispatchPickerResult(uri,
            onCancelled = { message = "Export annulé : aucun fichier n’a été créé." },
            onSelected = { destination -> scope.launch {
                message = "Création de la sauvegarde…"
                runCatching { exporter.exportTo(destination, pendingSets) }
                    .onSuccess { message = "Sauvegarde ZIP créée et vérifiée." }
                    .onFailure { error -> message = "Échec de l’export : ${error.message ?: "erreur d’écriture inconnue"}" }
            } },
        )
    }
    Card(modifier = Modifier.fillMaxWidth().testTag("settings-backup-export")) {
        Column(Modifier.padding(16.dp)) {
            Text("Sauvegarder", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Créez une archive ZIP vérifiée des données choisies.", style = MaterialTheme.typography.bodyMedium)
            ExportChoice("Thés et données associées", teas, { teas = it }, "backup-set-teas")
            ExportChoice("Configuration et référentiels", configuration, { configuration = it }, "backup-set-configuration")
            ExportChoice("Articles", false, {}, "backup-set-articles", enabled = false, supporting = "Disponible lorsque le modèle d’articles sera implémenté.")
            Button(
                onClick = { confirm = true },
                enabled = teas || configuration,
                modifier = Modifier.fillMaxWidth().testTag("backup-export-button"),
            ) { Text("Exporter la sélection…") }
            message?.let { Text(it, modifier = Modifier.padding(top = 10.dp).testTag("backup-export-message"), style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Créer une sauvegarde ?") },
            text = { Text("Une archive ZIP sera créée pour : ${buildList { if (teas) add("les thés"); if (configuration) add("la configuration") }.joinToString(" et ")}.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingSets = buildSet { if (teas) add(BackupExportSet.TEAS); if (configuration) add(BackupExportSet.CONFIGURATION) }
                    confirm = false
                    picker.launch("thes-du-palais-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.zip")
                }, modifier = Modifier.testTag("backup-export-confirm")) { Text("Choisir un fichier") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
        )
    }
}

internal fun <T> dispatchPickerResult(value: T?, onCancelled: () -> Unit, onSelected: (T) -> Unit) {
    if (value == null) onCancelled() else onSelected(value)
}

@Composable
private fun ExportChoice(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String, enabled: Boolean = true, supporting: String? = null) {
    Row(Modifier.fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked, onChange, enabled = enabled)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
