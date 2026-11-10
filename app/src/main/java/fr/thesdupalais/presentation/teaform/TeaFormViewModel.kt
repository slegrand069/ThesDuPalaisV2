package fr.thesdupalais.presentation.teaform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.database.NewTea
import fr.thesdupalais.data.database.TeaRepository
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TeaFormUiState(
    val name: String = "",
    val categoryId: UUID? = null,
    val statusId: UUID? = null,
    val containerId: UUID? = null,
    val countryIds: Set<UUID> = emptySet(),
    val regionIds: Set<UUID> = emptySet(),
    val aromaIds: Set<UUID> = emptySet(),
    val profileIds: Set<UUID> = emptySet(),
    val momentIds: Set<UUID> = emptySet(),
    val badgeIds: Set<UUID> = emptySet(),
    val tasteRating: Double? = null,
    val olfactoryRating: Double? = null,
    val personalNotes: String = "",
    val description: String = "",
    val technicalInformation: String = "",
    val brewTemperatureCelsius: Int? = null,
    val infusionDurationInput: String = "",
    val references: Map<ReferenceKind, List<ReferenceValue>> = emptyMap(),
    val loadedReferenceKinds: Set<ReferenceKind> = emptySet(),
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null,
) {
    val isLoadingReferences: Boolean get() = loadedReferenceKinds.size < ReferenceKind.entries.size
    val isDirty: Boolean get() =
        name.isNotEmpty() || categoryId != null || statusId != null || containerId != null ||
            countryIds.isNotEmpty() || regionIds.isNotEmpty() || aromaIds.isNotEmpty() || profileIds.isNotEmpty() ||
            momentIds.isNotEmpty() || badgeIds.isNotEmpty() || tasteRating != null || olfactoryRating != null ||
            personalNotes.isNotEmpty() || description.isNotEmpty() || technicalInformation.isNotEmpty() ||
            brewTemperatureCelsius != null || infusionDurationInput.isNotEmpty()
}

class TeaFormViewModel(private val repository: TeaRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(TeaFormUiState())
    val uiState = _uiState.asStateFlow()
    private var savedTeaId: UUID? = null

    init {
        ReferenceKind.entries.forEach { kind ->
            viewModelScope.launch {
                repository.observeSelectableReferences(kind).collect { values ->
                    _uiState.update {
                        it.copy(
                            references = it.references + (kind to values),
                            loadedReferenceKinds = it.loadedReferenceKinds + kind,
                        )
                    }
                }
            }
        }
    }

    fun updateName(value: String) = change { copy(name = value) }
    fun updatePersonalNotes(value: String) = change { copy(personalNotes = value) }
    fun updateDescription(value: String) = change { copy(description = value) }
    fun updateTechnicalInformation(value: String) = change { copy(technicalInformation = value) }
    fun updateTasteRating(value: Double?) = change { copy(tasteRating = value) }
    fun updateOlfactoryRating(value: Double?) = change { copy(olfactoryRating = value) }
    fun updateTemperature(value: Int?) = change { copy(brewTemperatureCelsius = value) }
    fun updateDuration(value: String) = change { copy(infusionDurationInput = value) }

    fun toggleReference(kind: ReferenceKind, id: UUID, selected: Boolean) {
        change {
            when (kind) {
                ReferenceKind.CATEGORY -> copy(categoryId = if (selected) id else null)
                ReferenceKind.STATUS -> copy(statusId = if (selected) id else null)
                ReferenceKind.CONTAINER -> copy(containerId = if (selected) id else null)
                ReferenceKind.COUNTRY -> {
                    val nextCountries = if (selected) countryIds + id else countryIds - id
                    val dependentRegions = references[ReferenceKind.REGION].orEmpty()
                        .filter { it.parentCountryId == id }.map { it.id }.toSet()
                    copy(
                        countryIds = nextCountries,
                        regionIds = if (selected) regionIds else regionIds - dependentRegions,
                    )
                }
                ReferenceKind.REGION -> {
                    val region = references[ReferenceKind.REGION].orEmpty().firstOrNull { it.id == id }
                    val parent = region?.parentCountryId
                    copy(
                        regionIds = if (selected) regionIds + id else regionIds - id,
                        countryIds = if (selected && parent != null) countryIds + parent else countryIds,
                    )
                }
                ReferenceKind.AROMA -> copy(aromaIds = aromaIds.toggle(id, selected))
                ReferenceKind.AROMATIC_PROFILE -> copy(profileIds = profileIds.toggle(id, selected))
                ReferenceKind.MOMENT -> copy(momentIds = momentIds.toggle(id, selected))
                ReferenceKind.BADGE -> copy(badgeIds = badgeIds.toggle(id, selected))
            }
        }
    }

    fun save(onSaved: (UUID) -> Unit) {
        if (_uiState.value.isSaving || _uiState.value.isSaved || _uiState.value.isLoadingReferences || savedTeaId != null) return
        val current = _uiState.value
        if (current.name.isBlank()) {
            _uiState.update { it.copy(error = "Saisissez le nom du thé.") }
            return
        }
        if (current.categoryId == null) {
            _uiState.update { it.copy(error = "Choisissez une catégorie.") }
            return
        }
        val duration = current.infusionDurationInput.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
        if (current.infusionDurationInput.isNotBlank() && (duration == null || duration <= 0)) {
            _uiState.update { it.copy(error = "La durée d’infusion doit être un nombre de secondes supérieur à zéro.") }
            return
        }
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                repository.create(
                    NewTea(
                        name = current.name,
                        categoryId = current.categoryId,
                        statusId = current.statusId,
                        containerId = current.containerId,
                        countryIds = current.countryIds.toList(),
                        regionIds = current.regionIds.toList(),
                        aromaIds = current.aromaIds.toList(),
                        aromaticProfileIds = current.profileIds.toList(),
                        momentIds = current.momentIds.toList(),
                        badgeIds = current.badgeIds.toList(),
                        tasteRating = current.tasteRating,
                        olfactoryRating = current.olfactoryRating,
                        personalNotes = current.personalNotes,
                        description = current.description,
                        technicalInformation = current.technicalInformation,
                        brewTemperatureCelsius = current.brewTemperatureCelsius,
                        infusionDurationSeconds = duration,
                    ),
                )
            }.onSuccess { id ->
                savedTeaId = id
                _uiState.update { it.copy(isSaving = false, isSaved = true) }
                onSaved(id)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isSaving = false, error = error.message ?: "Impossible d’enregistrer ce thé.")
                }
            }
        }
    }

    private inline fun change(crossinline transform: TeaFormUiState.() -> TeaFormUiState) =
        _uiState.update { transform(it).copy(error = null) }

    private fun Set<UUID>.toggle(id: UUID, selected: Boolean): Set<UUID> =
        if (selected) this + id else this - id
}

class TeaFormViewModelFactory(private val repository: TeaRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(TeaFormViewModel::class.java.isAssignableFrom(modelClass))
        return TeaFormViewModel(repository) as T
    }
}
