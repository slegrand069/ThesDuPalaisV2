package fr.thesdupalais.presentation.references

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.database.RoomReferenceRepository
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReferenceEditUiState(
    val kind: ReferenceKind,
    val referenceId: UUID?,
    val label: String = "",
    val parentCountryId: UUID? = null,
    val parentCountryLabel: String? = null,
    val countries: List<ReferenceValue> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
)

class ReferenceEditViewModel(
    private val repository: RoomReferenceRepository,
    kind: ReferenceKind,
    private val referenceId: UUID?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReferenceEditUiState(kind, referenceId))
    val uiState: StateFlow<ReferenceEditUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeCountries().collect { countries ->
                _uiState.update { it.copy(countries = countries) }
            }
        }
        viewModelScope.launch {
            val existing = referenceId?.let { repository.find(it) }
            _uiState.update {
                it.copy(
                    label = existing?.label.orEmpty(),
                    parentCountryId = existing?.parentCountryId,
                    parentCountryLabel = existing?.parentCountryId?.let { id -> repository.find(id)?.label },
                    isLoading = false,
                    error = if (referenceId != null && existing == null) "Cette référence n’existe plus." else null,
                )
            }
        }
    }

    fun updateLabel(label: String) = _uiState.update { it.copy(label = label, error = null) }
    fun selectCountry(id: UUID) = _uiState.update { it.copy(parentCountryId = id, error = null) }

    fun save(onSaved: () -> Unit) {
        if (_uiState.value.isSaving || _uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            runCatching {
                val state = _uiState.value
                if (state.referenceId == null) repository.create(state.kind, state.label, state.parentCountryId)
                else repository.rename(state.referenceId, state.label, state.kind)
            }.onSuccess {
                _uiState.update { it.copy(isSaving = false) }
                onSaved()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isSaving = false, error = error.message ?: "Impossible d’enregistrer cette valeur.")
                }
            }
        }
    }
}

class ReferenceEditViewModelFactory(
    private val repository: RoomReferenceRepository,
    private val kind: ReferenceKind,
    private val referenceId: UUID?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(ReferenceEditViewModel::class.java.isAssignableFrom(modelClass))
        return ReferenceEditViewModel(repository, kind, referenceId) as T
    }
}
