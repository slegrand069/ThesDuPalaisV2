package fr.thesdupalais.presentation.inventory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID

@Composable
fun InventoryScreen(viewModel: InventoryViewModel, onNavigateBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDiscardDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = !showDiscardDialog) {
        if (viewModel.hasUnsavedChanges()) showDiscardDialog = true else onNavigateBack()
    }
    if (showDiscardDialog) AlertDialog(
        onDismissRequest = { showDiscardDialog = false },
        title = { Text("Modifications non enregistrées") },
        text = { Text("Quitter l’inventaire sans enregistrer les modifications ?") },
        confirmButton = { TextButton(onClick = { showDiscardDialog = false; viewModel.discardChanges(); onNavigateBack() }) { Text("Quitter") } },
        dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text("Continuer") } },
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Inventaire", style = MaterialTheme.typography.headlineSmall)
        Text("${state.teas.size} thés actifs", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.query, onValueChange = viewModel::setQuery, label = { Text("Rechercher un thé") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Thé", Modifier.weight(0.46f), style = MaterialTheme.typography.labelMedium)
            Text("Contenant", Modifier.weight(0.28f), style = MaterialTheme.typography.labelMedium)
            Text("Stock (g)", Modifier.weight(0.26f), style = MaterialTheme.typography.labelMedium)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.visibleTeas, key = { it.tea.id }) { item ->
                val id = item.tea.id
                val draft = state.drafts[id] ?: InventoryDraft()
                val chosenContainer = if (draft.containerChanged) draft.containerId else item.tea.containerRefId
                Row(Modifier.fillMaxWidth().height(70.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.tea.name, Modifier.weight(0.46f).padding(end = 4.dp).semantics { contentDescription = item.tea.name }, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    )
                    ContainerPicker(
                        value = state.containers.firstOrNull { it.id == chosenContainer }?.label ?: if (chosenContainer == null) "Aucun" else "Indisponible",
                        containers = state.containers,
                        modifier = Modifier.weight(0.28f),
                        onSelect = { viewModel.setContainer(id, it) },
                    )
                    Column(Modifier.weight(0.26f)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(36.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                            shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface,
                        ) {
                            Row(Modifier.padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                BasicTextField(
                                    value = inventoryStockInput(item.tea.stockMilliGrams, draft.stockInput),
                                    onValueChange = { viewModel.setStock(id, it) },
                                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    decorationBox = { inner -> inner() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = { viewModel.save {} }, enabled = state.hasChanges && !state.saving,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(if (state.saving) "Enregistrement…" else "Enregistrer les modifications") }
    }
}

@Composable
private fun ContainerPicker(value: String, containers: List<fr.thesdupalais.domain.model.ReferenceValue>, modifier: Modifier, onSelect: (UUID?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.width(1.dp)) {
        TextButtonCompact(value = value, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Aucun") }, onClick = { expanded = false; onSelect(null) })
            containers.sortedWith(fr.thesdupalais.domain.model.frenchLabelComparator({ it.label }, { it.id.toString() })).forEach { ref ->
                DropdownMenuItem(text = { Text(ref.label) }, onClick = { expanded = false; onSelect(ref.id) })
            }
        }
    }
}

@Composable
private fun TextButtonCompact(value: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
}
