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

@Composable
fun ReferenceListScreen(
    viewModel: ReferenceListViewModel,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onRename: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReferenceListContent(
        state = state,
        onBack = onBack,
        onCreate = onCreate,
        onRename = onRename,
    )
}

@Composable
internal fun ReferenceListContent(
    state: ReferenceListUiState,
    onBack: () -> Unit,
    onCreate: () -> Unit = {},
    onRename: (String) -> Unit = {},
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
                            ReferenceRow(item, onRename)
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
                                    ReferenceRow(item, onRename)
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
}

@Composable
private fun ReferenceRow(item: ReferenceSummary, onRename: (String) -> Unit) {
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
            IconButton(onClick = {}, enabled = false, modifier = Modifier.testTag("reference-delete-disabled-${item.value.id}")) {
                Text("⌫", modifier = Modifier.semantics { contentDescription = "Suppression désactivée" })
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
