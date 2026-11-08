package fr.thesdupalais.presentation.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.importer.CollectionImportResult
import fr.thesdupalais.FirstRunOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface FirstRunState {
    data object Loading : FirstRunState
    data object Welcome : FirstRunState
    data object SelectingFile : FirstRunState
    data object Importing : FirstRunState
    data class Error(val message: String) : FirstRunState
    data class Completed(val message: String) : FirstRunState
    data object Catalogue : FirstRunState
}

class FirstRunViewModel(
    private val container: FirstRunOperations,
    private val readDocument: suspend (android.net.Uri) -> String,
) : ViewModel() {
    private val _state = MutableStateFlow<FirstRunState>(FirstRunState.Loading)
    val state: StateFlow<FirstRunState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                container.initialize()
                _state.value = if (container.isInstallationInitialized()) FirstRunState.Catalogue else FirstRunState.Welcome
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.value = FirstRunState.Error("L'initialisation des référentiels a échoué. Réessayez.")
            }
        }
    }

    fun retryInitialization() {
        if (_state.value !is FirstRunState.Error) return
        _state.value = FirstRunState.Loading
        viewModelScope.launch {
            try {
                container.initialize()
                _state.value = if (container.isInstallationInitialized()) FirstRunState.Catalogue else FirstRunState.Welcome
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.value = FirstRunState.Error("L'initialisation des référentiels a échoué. Réessayez.")
            }
        }
    }

    fun requestFile() {
        if (_state.value is FirstRunState.Welcome || _state.value is FirstRunState.Error) {
            _state.value = FirstRunState.SelectingFile
        }
    }

    fun onPickerCancelled() {
        if (_state.value is FirstRunState.SelectingFile) _state.value = FirstRunState.Welcome
    }

    fun onDocumentSelected(uri: android.net.Uri) {
        if (_state.value !is FirstRunState.SelectingFile) return
        _state.value = FirstRunState.Importing
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { readDocument(uri) }
                val result = container.importCollection(json)
                container.markInstallationInitialized()
                val count = result.teaCount
                _state.value = FirstRunState.Completed(
                    if (result is CollectionImportResult.AlreadyImported) {
                        "Ce jeu de données avait déjà été importé ($count thé(s))."
                    } else "Import réussi : $count thé(s) ajouté(s).",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = FirstRunState.Error(
                    error.message?.takeIf(String::isNotBlank) ?: "Le fichier n'a pas pu être importé. Vérifiez le JSON puis réessayez.",
                )
            }
        }
    }

    fun startWithoutFile() {
        if (_state.value !is FirstRunState.Welcome && _state.value !is FirstRunState.Error) return
        _state.value = FirstRunState.Loading
        viewModelScope.launch {
            try {
                container.initialize()
                container.markInstallationInitialized()
                _state.value = FirstRunState.Completed("L'application est prête. Votre catalogue est vide.")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.value = FirstRunState.Error("L'initialisation a échoué. Aucune installation n'a été validée ; réessayez.")
            }
        }
    }

    fun openCatalogue() {
        if (_state.value is FirstRunState.Completed) _state.value = FirstRunState.Catalogue
    }
}

class FirstRunViewModelFactory(
    private val container: FirstRunOperations,
    private val readDocument: suspend (android.net.Uri) -> String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        FirstRunViewModel(container, readDocument) as T
}
