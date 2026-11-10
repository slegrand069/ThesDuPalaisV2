package fr.thesdupalais.presentation.references

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ReferenceEditScreen(viewModel: ReferenceEditViewModel, onCancel: () -> Unit, onSaved: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("reference-edit-back")) { Text("‹") }
            Text(if (state.referenceId == null) "Créer / renommer" else "Créer / renommer", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(1.dp))
        }
        Text("En création, saisissez une nouvelle valeur. En modification, le renommage conserve l’identifiant et toutes les associations.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Text("Nom de la valeur", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = state.label,
            onValueChange = viewModel::updateLabel,
            modifier = Modifier.fillMaxWidth().testTag("reference-label-input"),
            placeholder = { Text("Ex. : Thé sombre") },
            singleLine = true,
            supportingText = { Text("${state.label.trim().replace(Regex("\\s+"), " ").length}/100") },
        )
        if (state.kind == fr.thesdupalais.domain.model.ReferenceKind.REGION) {
            Spacer(Modifier.height(12.dp))
            Text("Pays parent", style = MaterialTheme.typography.labelLarge)
            if (state.referenceId != null) {
                Text(state.parentCountryLabel ?: "Pays indisponible", modifier = Modifier.padding(vertical = 12.dp).testTag("reference-parent-readonly"))
            } else {
                var expanded by remember { mutableStateOf(false) }
                val selected = state.countries.firstOrNull { it.id == state.parentCountryId }
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !state.isLoading) { expanded = true }.testTag("reference-country-picker"),
                    shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(selected?.label ?: "Sélectionner un pays", modifier = Modifier.padding(16.dp))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.countries.forEach { country -> DropdownMenuItem(
                        text = { Text(country.label) },
                        onClick = { viewModel.selectCountry(country.id); expanded = false },
                    ) }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp).testTag("reference-form-error")) }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f).testTag("reference-cancel")) { Text("Annuler") }
            Button(onClick = { viewModel.save(onSaved) }, enabled = !state.isSaving && !state.isLoading,
                modifier = Modifier.weight(1f).testTag("reference-save")) { Text(if (state.isSaving) "Enregistrement…" else "Enregistrer") }
        }
        Spacer(Modifier.height(16.dp))
    }
}
