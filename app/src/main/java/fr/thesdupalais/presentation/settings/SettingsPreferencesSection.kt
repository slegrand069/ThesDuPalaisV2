package fr.thesdupalais.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.thesdupalais.data.preferences.UserPreferences
import kotlinx.coroutines.launch

private enum class EditablePreference(val title: String, val tag: String, val allowZero: Boolean) {
    CUP("Grammage estimé par tasse", "cup", false),
    LOW_STOCK("Seuil de stock faible", "low-stock", true),
}

@Composable
internal fun SettingsPreferencesSection(
    preferences: UserPreferences,
    preferencesLoaded: Boolean = true,
    onUpdateGramsPerCupMilli: suspend (Long) -> Unit,
    onUpdateLowStockThresholdMilli: suspend (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by rememberSaveable { mutableStateOf<EditablePreference?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PreferenceCard(
            label = EditablePreference.CUP.title,
            value = formatMilliGramsAsGrams(preferences.gramsPerCupMilli),
            help = "Valeur globale configurable",
            tag = "settings-edit-cup",
            enabled = preferencesLoaded,
        ) {
            editing = EditablePreference.CUP
            input = formatMilliGramsForInput(preferences.gramsPerCupMilli)
            error = null
        }
        PreferenceCard(
            label = EditablePreference.LOW_STOCK.title,
            value = formatMilliGramsAsGrams(preferences.lowStockThresholdMilli),
            help = null,
            tag = "settings-edit-low-stock",
            enabled = preferencesLoaded,
        ) {
            editing = EditablePreference.LOW_STOCK
            input = formatMilliGramsForInput(preferences.lowStockThresholdMilli)
            error = null
        }
    }

    editing?.let { preference ->
        AlertDialog(
            onDismissRequest = { if (!saving) editing = null },
            title = { Text("Modifier — ${preference.title}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; error = null },
                        modifier = Modifier.fillMaxWidth().testTag("settings-value-input-${preference.tag}"),
                        label = { Text("Valeur en grammes") },
                        suffix = { Text("g") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let { message -> ({ Text(message) }) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val milliGrams = runCatching { parseGramsToMilli(input, preference.allowZero) }
                            .getOrElse { validationError ->
                                error = validationError.message ?: "La valeur saisie est invalide."
                                return@TextButton
                            }
                        scope.launch {
                            saving = true
                            error = null
                            runCatching {
                                when (preference) {
                                    EditablePreference.CUP -> onUpdateGramsPerCupMilli(milliGrams)
                                    EditablePreference.LOW_STOCK -> onUpdateLowStockThresholdMilli(milliGrams)
                                }
                            }.onSuccess {
                                editing = null
                            }.onFailure { saveError ->
                                error = saveError.message ?: "La préférence n’a pas pu être enregistrée."
                            }
                            saving = false
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.testTag("settings-save-${preference.tag}"),
                ) { Text(if (saving) "Enregistrement…" else "Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }, enabled = !saving, modifier = Modifier.testTag("settings-cancel-${preference.tag}")) {
                    Text("Annuler")
                }
            },
        )
    }
}

@Composable
private fun PreferenceCard(label: String, value: String, help: String?, tag: String, enabled: Boolean, onEdit: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("settings-preference-$tag"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.width(8.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = onEdit, enabled = enabled, modifier = Modifier.testTag(tag).semantics { contentDescription = "Modifier $label" }) {
                Text("✎", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
