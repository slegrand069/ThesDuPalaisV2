package fr.thesdupalais.presentation.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.database.InventoryTeaChange
import fr.thesdupalais.data.database.TeaRepository
import fr.thesdupalais.data.database.TeaWithCatalogData
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import fr.thesdupalais.domain.quantity.parseOptionalGramsMilliGrams
import fr.thesdupalais.domain.quantity.formatGrams
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InventoryDraft(val containerId: UUID? = null, val containerChanged: Boolean = false, val stockInput: String? = null)
data class InventoryUiState(
    val teas: List<TeaWithCatalogData> = emptyList(), val containers: List<ReferenceValue> = emptyList(),
    val drafts: Map<UUID, InventoryDraft> = emptyMap(), val query: String = "",
    val saving: Boolean = false, val message: String? = null,
) {
    val hasChanges: Boolean get() = drafts.any { (id, draft) ->
        (draft.stockInput != null && (draft.stockInput.isNotBlank() || teas.firstOrNull { it.tea.id == id }?.tea?.stockMilliGrams != null)) ||
            (draft.containerChanged && teas.firstOrNull { it.tea.id == id }?.tea?.containerRefId != draft.containerId)
    }
    val visibleTeas: List<TeaWithCatalogData> get() {
        val q = normalizeInventorySearch(query)
        return if (q.isEmpty()) teas else teas.filter { normalizeInventorySearch(it.tea.name).contains(q) }
    }
}

fun normalizeInventorySearch(value: String): String = Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")

fun inventoryStockInput(stockMilliGrams: Long?, draftInput: String?): String =
    draftInput ?: stockMilliGrams?.let(::formatGrams).orEmpty()

/** Pure validation/planning step: every edited row is parsed before a repository write starts. */
fun buildInventoryChanges(state: InventoryUiState): List<InventoryTeaChange> {
    val active = state.teas.associateBy { it.tea.id }
    return state.drafts.mapNotNull { (id, draft) ->
        val tea = active[id]?.tea
            ?: throw IllegalStateException("Une fiche avec des modifications en attente n’est plus active. Rechargez l’inventaire avant d’enregistrer.")
        val stockInput = draft.stockInput
        val stockText = stockInput?.trim()
        if (stockText != null && stockText.isEmpty() && tea.stockMilliGrams != null) {
            throw IllegalArgumentException("${tea.name} : saisissez une quantité ou restaurez la valeur initiale.")
        }
        val stock = if (stockText.isNullOrEmpty()) null else try {
            parseOptionalGramsMilliGrams(stockText)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("${tea.name} : ${error.message}", error)
        }
        val changeContainer = draft.containerChanged && draft.containerId != tea.containerRefId
        val changeStock = stockInput != null && !stockText.isNullOrEmpty() && stock != tea.stockMilliGrams
        if (!changeContainer && !changeStock) null else InventoryTeaChange(
            teaId = id,
            containerId = if (changeContainer) draft.containerId else tea.containerRefId,
            changeContainer = changeContainer,
            stockMilliGrams = stock,
            changeStock = changeStock,
        )
    }
}

class InventoryViewModel(private val repository: TeaRepository, private val teaDao: fr.thesdupalais.data.database.TeaDao) : ViewModel() {
    private val drafts = MutableStateFlow<Map<UUID, InventoryDraft>>(emptyMap())
    private val query = MutableStateFlow("")
    private val saving = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val inventoryData = combine(
        teaDao.observeActiveCatalog(), repository.observeSelectableReferences(ReferenceKind.CONTAINER),
    ) { teas, containers -> teas to containers }
    private val controls = combine(drafts, query, saving, message) { drafts, query, saving, message ->
        InventoryControls(drafts, query, saving, message)
    }
    val state = combine(inventoryData, controls) { (teas, containers), controls ->
        InventoryUiState(teas, containers, controls.drafts, controls.query, controls.saving, controls.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InventoryUiState())

    fun setQuery(value: String) { query.value = value }
    fun clearMessage() { message.value = null }
    fun hasUnsavedChanges(): Boolean = state.value.hasChanges
    fun setStock(id: UUID, value: String) { drafts.value = drafts.value + (id to (drafts.value[id] ?: InventoryDraft()).copy(stockInput = value)) }
    fun setContainer(id: UUID, value: UUID?) { drafts.value = drafts.value + (id to (drafts.value[id] ?: InventoryDraft()).copy(containerId = value, containerChanged = true)) }
    fun discardChanges() { drafts.value = emptyMap(); message.value = null }

    fun save(onResult: (Boolean) -> Unit) {
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true; message.value = null
            try {
                val current = state.value
                val changes = buildInventoryChanges(current)
                val count = repository.applyInventoryChanges(changes)
                drafts.value = emptyMap()
                message.value = if (count == 0) "Aucune modification à enregistrer." else "$count fiche(s) mise(s) à jour."
                onResult(true)
            } catch (error: Exception) {
                message.value = error.message ?: "Les modifications n’ont pas été enregistrées."
                onResult(false)
            } finally { saving.value = false }
        }
    }
}

private data class InventoryControls(val drafts: Map<UUID, InventoryDraft>, val query: String, val saving: Boolean, val message: String?)

class InventoryViewModelFactory(private val repository: TeaRepository, private val teaDao: fr.thesdupalais.data.database.TeaDao) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = InventoryViewModel(repository, teaDao) as T
}
