package fr.thesdupalais

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.thesdupalais.presentation.startup.FirstRunScreen
import fr.thesdupalais.presentation.startup.FirstRunState
import fr.thesdupalais.presentation.startup.FirstRunViewModel
import fr.thesdupalais.presentation.startup.FirstRunViewModelFactory
import fr.thesdupalais.presentation.navigation.AppNavigation
import fr.thesdupalais.presentation.theme.ThesDuPalaisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ThesDuPalaisApplication).container
        val resolver = applicationContext.contentResolver
        setContent {
            ThesDuPalaisTheme {
                val firstRun: FirstRunViewModel = viewModel(
                    factory = FirstRunViewModelFactory(container) { uri ->
                        resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: error("Impossible d'ouvrir le fichier sélectionné.")
                    },
                )
                val state by firstRun.state.collectAsStateWithLifecycle()
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri == null) firstRun.onPickerCancelled() else firstRun.onDocumentSelected(uri)
                }
                if (state is FirstRunState.Catalogue) {
                    AppNavigation(container)
                } else {
                    FirstRunScreen(firstRun) {
                        firstRun.requestFile()
                        picker.launch(arrayOf("application/json", "text/json", "application/*+json"))
                    }
                }
            }
        }
    }
}
