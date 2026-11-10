package fr.thesdupalais.presentation.references

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.database.RoomReferenceRepository
import fr.thesdupalais.data.database.ReferenceDeletionStatus
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReferenceListUiState(
    val kind: ReferenceKind,
    val references: List<ReferenceSummary> = emptyList(),
    val isLoading: Boolean = true,
    val deletePrompt: ReferenceDeletePrompt? = null,
    val isCheckingDeletion: Boolean = false,
    val isDeleting: Boolean = false,
)

data class ReferenceDeletePrompt(
    val referenceId: java.util.UUID,
    val label: String,
    val status: ReferenceDeletionStatus,
)

class ReferenceListViewModel(
    private val repository: RoomReferenceRepository,
    private val kind: ReferenceKind,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReferenceListUiState(kind = kind))
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observe(kind).collect { references ->
                _uiState.update { it.copy(references = references, isLoading = false) }
            }
        }
    }

    fun requestDelete(id: java.util.UUID) {
        if (_uiState.value.isCheckingDeletion || _uiState.value.isDeleting) return
        val label = _uiState.value.references.firstOrNull { it.value.id == id }?.value?.label ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingDeletion = true, deletePrompt = null) }
            val status = runCatching { repository.checkDeletion(id) }
                .getOrElse { ReferenceDeletionStatus.RestrictedByDependency }
            _uiState.update { it.copy(isCheckingDeletion = false, deletePrompt = ReferenceDeletePrompt(id, label, status)) }
        }
    }

    fun dismissDeletePrompt() = _uiState.update { it.copy(deletePrompt = null, isDeleting = false) }

    fun confirmDelete() {
        val prompt = _uiState.value.deletePrompt ?: return
        if (prompt.status != ReferenceDeletionStatus.Eligible || _uiState.value.isDeleting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            val result = runCatching { repository.deleteIfUnused(prompt.referenceId) }
                .getOrElse { ReferenceDeletionStatus.RestrictedByDependency }
            if (result == ReferenceDeletionStatus.Deleted) {
                _uiState.update { it.copy(deletePrompt = null, isDeleting = false) }
            } else {
                _uiState.update {
                    it.copy(isDeleting = false, deletePrompt = prompt.copy(status = result))
                }
            }
        }
    }
}

class ReferenceListViewModelFactory(
    private val repository: RoomReferenceRepository,
    private val kind: ReferenceKind,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(ReferenceListViewModel::class.java.isAssignableFrom(modelClass))
        return ReferenceListViewModel(repository, kind) as T
    }
}
