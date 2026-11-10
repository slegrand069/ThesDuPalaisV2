package fr.thesdupalais.presentation.references

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.thesdupalais.domain.model.ReferenceSummary
import fr.thesdupalais.data.database.ReferenceDeletionStatus
import java.util.UUID

@Composable
fun ReferenceListScreen(
    viewModel: ReferenceListViewModel,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (UUID) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReferenceListContent(
        state = state,
        onBack = onBack,
        onCreate = onCreate,
        onRename = onRename,
        onDelete = onDelete,
        onDismissDelete = onDismissDelete,
        onConfirmDelete = onConfirmDelete,
    )
}

@Composable
internal fun ReferenceListContent(
    state: ReferenceListUiState,
    onBack: () -> Unit,
    onCreate: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onDelete: (UUID) -> Unit = {},
    onDismissDelete: () -> Unit = {},
    onConfirmDelete: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                onClick = onBack,
                modifier = Modifier.testTag("reference-back"),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    text = "‹",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = state.kind.displayName(),
                modifier = Modifier.testTag("reference-list-title-${state.kind.name}"),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "Valeurs du référentiel",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        val sections = geographicReferenceSections(state.kind, state.references)
        when {
            state.isLoading -> ReferenceEmptyMessage("Chargement des valeurs…")
            state.references.isEmpty() && sections == null -> ReferenceEmptyMessage("Aucune valeur dans ce référentiel")
            else -> {
                Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("reference-list-${state.kind.name}"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (sections == null) {
                        items(state.references, key = { it.value.id.toString() }) { item ->
                            ReferenceRow(item, onRename, onDelete)
                        }
                    } else {
                        sections.forEachIndexed { index, section ->
                            item(key = "reference-section-$index") {
                                Text(
                                    text = section.title,
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(top = if (index == 0) 0.dp else 8.dp)
                                        .testTag("reference-section-$index"),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (section.references.isEmpty()) {
                                item(key = "reference-section-empty-$index") {
                                    ReferenceEmptyMessage(section.emptyMessage)
                                }
                            } else {
                                items(section.references, key = { it.value.id.toString() }) { item ->
                                    ReferenceRow(item, onRename, onDelete)
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    FloatingActionButton(onClick = onCreate, modifier = Modifier.testTag("reference-create")) {
                        Text("＋", style = MaterialTheme.typography.headlineSmall)
                    }
                }
                }
            }
        }
    }
    state.deletePrompt?.let { prompt ->
        val blocked = prompt.status != ReferenceDeletionStatus.Eligible
        val (title, message) = when (val status = prompt.status) {
            ReferenceDeletionStatus.Eligible -> "Supprimer cette valeur ?" to
                "« ${prompt.label} » n’est liée à aucun thé. La suppression sera définitive."
            ReferenceDeletionStatus.Standard -> "Référence standard protégée" to
                "« ${prompt.label} » est gérée par les références standard et ne peut pas être supprimée. Vous pouvez la renommer."
            is ReferenceDeletionStatus.InUse -> {
                val count = status.teaCount
                val teaText = "« ${prompt.label} » est associée à $count ${if (count == 1) "thé" else "thés"}."
                val regionText = if (status.regionCount > 0) " Elle possède aussi ${status.regionCount} ${if (status.regionCount == 1) "région" else "régions"}." else ""
                "Suppression impossible" to "$teaText$regionText Pour éviter toute perte d’association, cette valeur ne peut pas être supprimée tant qu’elle est utilisée. Vous pouvez la renommer ; les liens seront conservés."
            }
            is ReferenceDeletionStatus.HasRegions -> "Suppression impossible" to
                "« ${prompt.label} » possède ${status.regionCount} ${if (status.regionCount == 1) "région" else "régions"}. Supprimez d’abord ses régions."
            ReferenceDeletionStatus.Missing -> "Valeur indisponible" to "Cette valeur n’existe plus dans le référentiel."
            ReferenceDeletionStatus.RestrictedByDependency -> "Suppression impossible" to
                "Cette valeur vient d’être utilisée ou possède une dépendance. Elle n’a pas été supprimée. Vous pouvez la renommer."
            ReferenceDeletionStatus.Deleted -> "Valeur supprimée" to "La valeur a été supprimée."
        }
        AlertDialog(
            onDismissRequest = { if (!state.isDeleting) onDismissDelete() },
            title = { Text(title, modifier = Modifier.testTag("reference-delete-dialog-title")) },
            text = {
                Column {
                    Text(message, modifier = Modifier.testTag("reference-delete-dialog-message"))
                    if (!blocked) Text("La confirmation est demandée avant suppression.", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                if (blocked) {
                    TextButton(onClick = onDismissDelete, modifier = Modifier.testTag("reference-delete-understood")) { Text("Compris") }
                } else {
                    TextButton(onClick = onConfirmDelete, enabled = !state.isDeleting, modifier = Modifier.testTag("reference-delete-confirm")) { Text("Supprimer") }
                }
            },
            dismissButton = if (blocked) null else ({
                TextButton(onClick = onDismissDelete, enabled = !state.isDeleting, modifier = Modifier.testTag("reference-delete-cancel")) { Text("Annuler") }
            }),
        )
    }
}

@Composable
private fun ReferenceRow(item: ReferenceSummary, onRename: (String) -> Unit, onDelete: (UUID) -> Unit) {
    val usageText = buildList {
        if (item.teaUsageCount > 0) {
            add("${item.teaUsageCount} ${if (item.teaUsageCount == 1) "thé" else "thés"}")
        }
        if (item.childRegionCount > 0) {
            add("${item.childRegionCount} ${if (item.childRegionCount == 1) "région" else "régions"}")
        }
    }.joinToString(" · ")

    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("reference-row-${item.value.id}"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(item.displayLabel(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(if (usageText.isNotEmpty()) "Utilisée · $usageText" else "Disponible", style = MaterialTheme.typography.labelSmall,
                    color = if (item.teaUsageCount > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onRename(item.value.id.toString()) }, modifier = Modifier.testTag("reference-rename-${item.value.id}")) {
                Text("✎", modifier = Modifier.semantics { contentDescription = "Renommer ${item.value.label}" })
            }
            IconButton(onClick = { onDelete(item.value.id) }, modifier = Modifier.testTag("reference-delete-${item.value.id}")) {
                Text("×", modifier = Modifier.semantics { contentDescription = "Supprimer ${item.value.label}" })
            }
        }
    }
}

@Composable
private fun ReferenceEmptyMessage(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
