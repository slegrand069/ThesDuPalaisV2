package fr.thesdupalais.presentation.references

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.database.RoomReferenceRepository
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ReferenceListUiState(
    val kind: ReferenceKind,
    val references: List<ReferenceSummary> = emptyList(),
    val isLoading: Boolean = true,
)

class ReferenceListViewModel(
    private val repository: RoomReferenceRepository,
    private val kind: ReferenceKind,
) : ViewModel() {
    val uiState = repository.observe(kind)
        .map { references ->
            ReferenceListUiState(
                kind = kind,
                references = references,
                isLoading = false,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = ReferenceListUiState(kind = kind),
        )
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
