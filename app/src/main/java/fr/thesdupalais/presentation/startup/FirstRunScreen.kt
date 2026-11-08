package fr.thesdupalais.presentation.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun FirstRunScreen(viewModel: FirstRunViewModel, onChooseFile: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("ThésDuPalais", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(28.dp))
            when (val current = state) {
                FirstRunState.Loading, FirstRunState.Importing -> {
                    CircularProgressIndicator(modifier = Modifier.testTag("first-run-loading"))
                    Spacer(Modifier.height(16.dp))
                    Text(if (current is FirstRunState.Importing) "Import du fichier…" else "Préparation de l'application…")
                }
                FirstRunState.Welcome, FirstRunState.SelectingFile -> {
                    Text("Possédez-vous un fichier de données à importer ?", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onChooseFile, modifier = Modifier.fillMaxWidth().testTag("first-run-import")) {
                        Text("Importer un fichier")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = viewModel::startWithoutFile, modifier = Modifier.fillMaxWidth().testTag("first-run-empty")) {
                        Text("Commencer sans fichier")
                    }
                }
                is FirstRunState.Error -> {
                    Text("Impossible de terminer l'initialisation", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onChooseFile, modifier = Modifier.fillMaxWidth().testTag("first-run-retry-import")) {
                        Text("Réessayer l'import")
                    }
                    OutlinedButton(onClick = viewModel::startWithoutFile, modifier = Modifier.fillMaxWidth()) {
                        Text("Commencer sans fichier")
                    }
                }
                is FirstRunState.Completed -> {
                    Text("Initialisation terminée", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(current.message)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = viewModel::openCatalogue, modifier = Modifier.testTag("first-run-open-catalogue")) {
                        Text("Accéder au catalogue")
                    }
                }
                FirstRunState.Catalogue -> Unit
            }
        }
    }
}
