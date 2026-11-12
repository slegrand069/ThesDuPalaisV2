package fr.thesdupalais.presentation.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.thesdupalais.data.backup.BackupReplacementPreviewPlan
import fr.thesdupalais.data.backup.PreviewDisposition
import fr.thesdupalais.data.operation.BackupReplacementProgress
import fr.thesdupalais.data.operation.BackupReplacementSelection
import fr.thesdupalais.data.operation.BackupReplacementSession
import fr.thesdupalais.data.operation.BackupReplacementWorkflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BackupRestoreSection(workflow: BackupReplacementWorkflow) {
    var session by remember { mutableStateOf<BackupReplacementSession?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selection by remember { mutableStateOf<BackupReplacementSelection?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun recalculate(current: BackupReplacementSession, sets: Set<String>) {
        val request = ++generation
        selection = null
        if (sets.isEmpty()) { loading = false; return }
        scope.launch {
            loading = true
            val calculated = runCatching { workflow.preview(current, sets) }
            if (request == generation) {
                calculated.onSuccess { selection = it }
                    .onFailure { message = it.message ?: "La prévisualisation du remplacement a échoué."; messageIsError = true }
                loading = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            if (session == null) { message = "Sélection de l’archive annulée."; messageIsError = false }
        } else scope.launch {
            loading = true
            busy = false
            session = null
            selection = null
            selected = emptySet()
            message = null
            runCatching { workflow.read(uri) }
                .onSuccess { loaded ->
                    session = loaded
                    val available = loaded.includedSets.intersect(SUPPORTED_RESTORE_SETS)
                    selected = available
                    recalculate(loaded, available)
                }
                .onFailure {
                    message = it.message?.takeIf(String::isNotBlank) ?: "L’archive n’a pas pu être lue ou validée."
                    messageIsError = true
                    loading = false
                }
        }
    }

    Card(modifier = Modifier.fillMaxWidth().testTag("settings-backup-restore")) {
        Column(Modifier.padding(16.dp)) {
            Text("Restaurer une sauvegarde", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Vérifiez les conséquences, puis confirmez le remplacement.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                enabled = !busy && !loading,
                modifier = Modifier.fillMaxWidth().testTag("backup-restore-open"),
            ) { Text(if (loading && session == null) "Lecture et validation…" else "Choisir une archive ZIP…") }

            message?.let { Text(it, Modifier.padding(top = 10.dp).testTag("backup-restore-message"), color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            session?.let { current ->
                Spacer(Modifier.height(12.dp))
                Text("Sauvegarde créée le ${current.createdAt}", style = MaterialTheme.typography.bodySmall)
                Text("Ensembles à restaurer", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                current.includedSets.intersect(SUPPORTED_RESTORE_SETS).sorted().forEach { set ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(
                            checked = set in selected,
                            enabled = !busy,
                            onCheckedChange = { checked ->
                                val next = if (checked) selected + set else selected - set
                                selected = next
                                message = null
                                recalculate(current, next)
                            },
                            modifier = Modifier.testTag("backup-restore-select-$set"),
                        )
                        Text(restoreSetLabel(set), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if ("articles" in current.includedSets) {
                    Text("Articles : présents dans l’archive, mais pas encore restaurables.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Les ensembles décochés restent inchangés.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    selected.isEmpty() -> Text("Sélectionnez au moins un ensemble.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    loading -> Text("Actualisation des conséquences…", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    selection != null -> {
                        Text("Le ZIP v1 ne sauvegarde pas les photos. Les photos locales liées aux thés conservés restent en place.", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                        RestoreConsequenceSummary(selection!!.plan, Modifier.padding(top = 8.dp))
                        selection!!.plan.target.warnings.forEach { Text(it, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
                        val blockers = selection!!.plan.blockers
                        blockers.forEach { Text(it, Modifier.padding(top = 4.dp).testTag("backup-restore-blocker"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Button(
                            onClick = { confirmation = true },
                            enabled = !busy && selection!!.plan.applicable,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("backup-restore-confirm-open"),
                        ) { Text("Vérifier et restaurer…") }
                    }
                }
            }
            if (busy) Text("${message ?: "Restauration en cours…"}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
    }

    val confirmedSelection = selection
    val confirmedSession = session
    if (confirmation && confirmedSelection != null && confirmedSession != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmation = false },
            title = { Text("Confirmer le remplacement ?") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Les ensembles cochés seront remplacés par ceux de l’archive. Les autres ensembles resteront inchangés.")
                    RestoreConsequenceSummary(confirmedSelection.plan, Modifier.padding(top = 12.dp))
                    confirmedSelection.plan.target.warnings.forEach { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
                    Text("Le ZIP v1 ne contient pas les photos. Les photos locales des thés conservés restent en place; celles liées à des thés retirés seront mises en quarantaine puis supprimées après vérification.", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    Text("Cette action modifie la collection et ne peut pas être annulée depuis l’application.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        confirmation = false
                        busy = true
                        message = "Préparation de la restauration…"
                        messageIsError = false
                        scope.launch {
                            runCatching {
                                workflow.restore(confirmedSession, confirmedSelection, confirmed = true) { stage ->
                                    val label = progressLabel(stage)
                                    withContext(Dispatchers.Main.immediate) { message = label }
                                }
                            }.onSuccess {
                                message = if (it.resumedAfterInterruption) "Restauration terminée après reprise." else "Restauration terminée et vérifiée."
                                messageIsError = false
                                session = null
                                selection = null
                                selected = emptySet()
                            }.onFailure {
                                message = it.message ?: "La restauration a échoué. Les écritures restent bloquées si une récupération est nécessaire."
                                messageIsError = true
                                session = null
                                selection = null
                                selected = emptySet()
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.testTag("backup-restore-confirm-apply"),
                ) { Text("Confirmer le remplacement") }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = false }, enabled = !busy, modifier = Modifier.testTag("backup-restore-confirm-cancel")) { Text("Annuler") }
            },
            modifier = Modifier.testTag("backup-restore-confirm-dialog"),
        )
    }
}

@Composable
private fun RestoreConsequenceSummary(plan: BackupReplacementPreviewPlan, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("Conséquences", style = MaterialTheme.typography.titleSmall)
        plan.comparison.groups.forEach { group ->
            Text(group.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
            group.counts.filter { it.count > 0 }.forEach { count ->
                Text("${restoreDisposition(count.disposition)} : ${count.count}", style = MaterialTheme.typography.bodySmall)
            }
        }
        plan.target.preserved.filter { it.set == "reference" }.takeIf { it.isNotEmpty() }?.let {
            Text("Référentiels conservés car utilisés par des thés : ${it.size}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (plan.comparison.groups.isEmpty()) Text("Aucune donnée sélectionnée à comparer.", style = MaterialTheme.typography.bodySmall)
    }
}

private val SUPPORTED_RESTORE_SETS = setOf("teas", "configuration")
private fun restoreSetLabel(set: String) = when (set) {
    "teas" -> "Thés, associations, dégustations et mouvements"
    "configuration" -> "Configuration et référentiels"
    else -> set
}
private fun restoreDisposition(disposition: PreviewDisposition) = when (disposition) {
    PreviewDisposition.ADDED -> "À ajouter depuis l’archive"
    PreviewDisposition.MODIFIED -> "À remplacer"
    PreviewDisposition.IDENTICAL -> "Déjà identiques"
    PreviewDisposition.REMOVED -> "À supprimer par remplacement"
    PreviewDisposition.PRESERVED -> "À conserver"
    PreviewDisposition.CONFLICT -> "Conflits bloquants"
    PreviewDisposition.NOT_COMPARABLE -> "Non comparables"
}
private fun progressLabel(progress: BackupReplacementProgress) = when (progress) {
    BackupReplacementProgress.PREPARING -> "Vérification et préparation…"
    BackupReplacementProgress.APPLYING_ROOM -> "Application des données…"
    BackupReplacementProgress.FINALIZING -> "Finalisation des préférences et photos…"
    BackupReplacementProgress.RECOVERING -> "Reprise sécurisée de l’opération…"
    BackupReplacementProgress.COMPLETE -> "Restauration vérifiée."
}
