package fr.thesdupalais.presentation.teaform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.database.NewTea
import fr.thesdupalais.data.database.TeaRepository
import fr.thesdupalais.data.database.TeaEditableField
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TeaFormUiState(
    val editTeaId: UUID? = null,
    val isLoadingTea: Boolean = false,
    val teaUnavailable: Boolean = false,
    val initialDraft: TeaFormDraft? = null,
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
    val stockInput: String = "",
    val references: Map<ReferenceKind, List<ReferenceValue>> = emptyMap(),
    val loadedReferenceKinds: Set<ReferenceKind> = emptySet(),
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null,
) {
    val isLoadingReferences: Boolean get() = loadedReferenceKinds.size < ReferenceKind.entries.size
    val draft: TeaFormDraft get() = TeaFormDraft(
        name, categoryId, statusId, containerId, countryIds, regionIds, aromaIds, profileIds, momentIds, badgeIds,
        tasteRating, olfactoryRating, personalNotes, description, technicalInformation,
        brewTemperatureCelsius, infusionDurationInput, stockInput,
    )
    val isDirty: Boolean get() = if (editTeaId != null) {
        initialDraft != null && draft != initialDraft
    } else name.isNotEmpty() || categoryId != null || statusId != null || containerId != null ||
            countryIds.isNotEmpty() || regionIds.isNotEmpty() || aromaIds.isNotEmpty() || profileIds.isNotEmpty() ||
            momentIds.isNotEmpty() || badgeIds.isNotEmpty() || tasteRating != null || olfactoryRating != null ||
            personalNotes.isNotEmpty() || description.isNotEmpty() || technicalInformation.isNotEmpty() ||
            brewTemperatureCelsius != null || infusionDurationInput.isNotEmpty() || stockInput.isNotEmpty()
}

data class TeaFormDraft(
    val name: String,
    val categoryId: UUID?,
    val statusId: UUID?,
    val containerId: UUID?,
    val countryIds: Set<UUID>,
    val regionIds: Set<UUID>,
    val aromaIds: Set<UUID>,
    val profileIds: Set<UUID>,
    val momentIds: Set<UUID>,
    val badgeIds: Set<UUID>,
    val tasteRating: Double?,
    val olfactoryRating: Double?,
    val personalNotes: String,
    val description: String,
    val technicalInformation: String,
    val brewTemperatureCelsius: Int?,
    val infusionDurationInput: String,
    val stockInput: String = "",
) {
    fun changedFieldsSince(initial: TeaFormDraft): Set<TeaEditableField> = buildSet {
        if (name != initial.name) add(TeaEditableField.NAME)
        if (categoryId != initial.categoryId) add(TeaEditableField.CATEGORY)
        if (statusId != initial.statusId) add(TeaEditableField.STATUS)
        if (containerId != initial.containerId) add(TeaEditableField.CONTAINER)
        if (countryIds != initial.countryIds) add(TeaEditableField.COUNTRIES)
        if (regionIds != initial.regionIds) add(TeaEditableField.REGIONS)
        if (aromaIds != initial.aromaIds) add(TeaEditableField.AROMAS)
        if (profileIds != initial.profileIds) add(TeaEditableField.PROFILES)
        if (momentIds != initial.momentIds) add(TeaEditableField.MOMENTS)
        if (badgeIds != initial.badgeIds) add(TeaEditableField.BADGES)
        if (tasteRating != initial.tasteRating) add(TeaEditableField.TASTE_RATING)
        if (olfactoryRating != initial.olfactoryRating) add(TeaEditableField.OLFACTORY_RATING)
        if (personalNotes != initial.personalNotes) add(TeaEditableField.PERSONAL_NOTES)
        if (description != initial.description) add(TeaEditableField.DESCRIPTION)
        if (technicalInformation != initial.technicalInformation) add(TeaEditableField.TECHNICAL_INFORMATION)
        if (brewTemperatureCelsius != initial.brewTemperatureCelsius) add(TeaEditableField.BREW_TEMPERATURE)
        if (infusionDurationInput != initial.infusionDurationInput) add(TeaEditableField.INFUSION_DURATION)
        if (stockInput != initial.stockInput) add(TeaEditableField.STOCK)
    }
}

class TeaFormViewModel(private val repository: TeaRepository, editTeaId: UUID? = null) : ViewModel() {
    private val _uiState = MutableStateFlow(TeaFormUiState(editTeaId = editTeaId, isLoadingTea = editTeaId != null))
    val uiState = _uiState.asStateFlow()
    private var savedTeaId: UUID? = null
    private var retainedArchivedReferences: Map<ReferenceKind, List<ReferenceValue>> = emptyMap()

    init {
        ReferenceKind.entries.forEach { kind ->
            viewModelScope.launch {
                repository.observeSelectableReferences(kind).collect { values ->
                    _uiState.update {
                        it.copy(
                            references = it.references + (kind to (values + retainedArchivedReferences[kind].orEmpty()).distinctBy { value -> value.id }),
                            loadedReferenceKinds = it.loadedReferenceKinds + kind,
                        )
                    }
                }
            }
        }
        editTeaId?.let(::loadForEdit)
    }

    private fun loadForEdit(teaId: UUID) {
        viewModelScope.launch {
            runCatching { repository.loadForEdit(teaId) }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoadingTea = false, teaUnavailable = true, error = error.message ?: "Impossible de charger ce thé.") }
                }
                .onSuccess { data ->
                    if (data == null) {
                        _uiState.update { it.copy(isLoadingTea = false, teaUnavailable = true, error = "Ce thé n’est plus disponible pour modification.") }
                    } else {
                        val value = data.values
                        retainedArchivedReferences = data.references.mapValues { (_, references) -> references.filter { it.archivedAt != null } }
                        val draft = TeaFormDraft(
                            name = value.name,
                            categoryId = value.categoryId,
                            statusId = value.statusId,
                            containerId = value.containerId,
                            countryIds = value.countryIds.toSet(),
                            regionIds = value.regionIds.toSet(),
                            aromaIds = value.aromaIds.toSet(),
                            profileIds = value.aromaticProfileIds.toSet(),
                            momentIds = value.momentIds.toSet(),
                            badgeIds = value.badgeIds.toSet(),
                            tasteRating = value.tasteRating,
                            olfactoryRating = value.olfactoryRating,
                            personalNotes = value.personalNotes.orEmpty(),
                            description = value.description.orEmpty(),
                            technicalInformation = value.technicalInformation.orEmpty(),
                            brewTemperatureCelsius = value.brewTemperatureCelsius,
                            infusionDurationInput = value.infusionDurationSeconds?.toString().orEmpty(),
                            stockInput = value.stockMilliGrams?.let(::formatStockInput).orEmpty(),
                        )
                        _uiState.update { state ->
                            state.copy(
                                name = draft.name,
                                categoryId = draft.categoryId,
                                statusId = draft.statusId,
                                containerId = draft.containerId,
                                countryIds = draft.countryIds,
                                regionIds = draft.regionIds,
                                aromaIds = draft.aromaIds,
                                profileIds = draft.profileIds,
                                momentIds = draft.momentIds,
                                badgeIds = draft.badgeIds,
                                tasteRating = draft.tasteRating,
                                olfactoryRating = draft.olfactoryRating,
                                personalNotes = draft.personalNotes,
                                description = draft.description,
                                technicalInformation = draft.technicalInformation,
                                brewTemperatureCelsius = draft.brewTemperatureCelsius,
                                infusionDurationInput = draft.infusionDurationInput,
                                stockInput = draft.stockInput,
                                references = state.references.mapValues { (kind, options) ->
                                    (options + retainedArchivedReferences[kind].orEmpty()).distinctBy { it.id }
                                },
                                initialDraft = draft,
                                isLoadingTea = false,
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
    fun updateStock(value: String) = change { copy(stockInput = value) }

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
        if (_uiState.value.isSaving || _uiState.value.isSaved || _uiState.value.isLoadingReferences || _uiState.value.isLoadingTea || savedTeaId != null) return
        val current = _uiState.value
        if (current.teaUnavailable) return
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
        val stockMilliGrams = try {
            parseStockInput(current.stockInput)
        } catch (_: Exception) {
            _uiState.update { it.copy(error = "Saisissez un stock positif ou nul, avec au plus trois décimales.") }
            return
        }
        val initial = current.initialDraft
        if (current.editTeaId != null && initial != null && current.draft == initial) {
            savedTeaId = current.editTeaId
            _uiState.update { it.copy(isSaved = true, error = null) }
            onSaved(current.editTeaId)
            return
        }
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val values = NewTea(
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
                        stockMilliGrams = stockMilliGrams,
                    )
                if (current.editTeaId == null) repository.create(values)
                else {
                    repository.update(current.editTeaId, values, current.draft.changedFieldsSince(requireNotNull(initial)))
                    current.editTeaId
                }
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

private fun formatStockInput(milliGrams: Long): String =
    java.math.BigDecimal.valueOf(milliGrams, 3).stripTrailingZeros().toPlainString().replace('.', ',')

internal fun parseStockInput(input: String): Long? {
    val text = input.trim()
    if (text.isEmpty()) return null
    require(text.matches(Regex("(?:\\d+(?:[,.]\\d{0,3})?|[,.]\\d{1,3})"))) { "Format de stock invalide." }
    val grams = java.math.BigDecimal(text.replace(',', '.'))
    require(grams.signum() >= 0) { "Le stock ne peut pas être négatif." }
    return try {
        grams.movePointRight(3).longValueExact()
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("Le stock est trop élevé.", error)
    }
}

class TeaFormViewModelFactory(
    private val repository: TeaRepository,
    private val editTeaId: UUID? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(TeaFormViewModel::class.java.isAssignableFrom(modelClass))
        return TeaFormViewModel(repository, editTeaId) as T
    }
}
