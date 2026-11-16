package fr.thesdupalais.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.database.ReferenceDao
import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.SemanticDictionaryRepository
import fr.thesdupalais.data.database.SemanticDictionaryArchive
import fr.thesdupalais.data.database.SemanticDictionaryArchiveJson
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import fr.thesdupalais.domain.model.frenchLabelComparator
import fr.thesdupalais.domain.semantics.MadjoSemanticResolver
import fr.thesdupalais.domain.semantics.SemanticConcept
import fr.thesdupalais.domain.semantics.SemanticDictionarySnapshot
import fr.thesdupalais.domain.semantics.SemanticDomain
import fr.thesdupalais.domain.semantics.SemanticExpression
import fr.thesdupalais.domain.semantics.SemanticRelation
import fr.thesdupalais.domain.semantics.SemanticRelationType
import fr.thesdupalais.domain.semantics.SemanticDictionaryValidator
import fr.thesdupalais.domain.semantics.BuiltInSemanticConceptIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class SemanticDictionaryAdminState(
    val snapshot: SemanticDictionarySnapshot = SemanticDictionarySnapshot(),
    val domain: SemanticDomain = SemanticDomain.AROMA,
    val query: String = "",
    val referenceOptions: List<ReferenceValue> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
    val deleteImpact: SemanticDictionaryRepository.DeleteImpact? = null,
    val deletingConceptId: String? = null,
    val deleteInProgress: Boolean = false,
    val importPreview: SemanticDictionaryArchive? = null,
    val importInProgress: Boolean = false,
)

class SemanticDictionaryViewModel(
    private val repository: SemanticDictionaryRepository,
    references: ReferenceDao,
) : ViewModel() {
    private val domain = MutableStateFlow(SemanticDomain.AROMA)
    private val query = MutableStateFlow("")
    private val message = MutableStateFlow<Pair<String?, String?>>(null to null)
    private val deleteImpact = MutableStateFlow<SemanticDictionaryRepository.DeleteImpact?>(null)
    private val deletingConceptId = MutableStateFlow<String?>(null)
    private val deleteInProgress = MutableStateFlow(false)
    private val importPreview = MutableStateFlow<SemanticDictionaryArchive?>(null)
    private val importJson = MutableStateFlow<String?>(null)
    private val importInProgress = MutableStateFlow(false)

    private val referenceCore = combine(
        references.observeSelectableByKind(ReferenceKind.AROMA.name),
        references.observeSelectableByKind(ReferenceKind.AROMATIC_PROFILE.name),
        references.observeSelectableByKind(ReferenceKind.CATEGORY.name),
        references.observeSelectableByKind(ReferenceKind.COUNTRY.name),
    ) { aromas, profiles, categories, countries ->
        (aromas + profiles + categories + countries).map(ReferenceEntity::toDomain)
    }
    private val referenceOptions = combine(
        referenceCore,
        references.observeSelectableByKind(ReferenceKind.REGION.name),
        references.observeSelectableByKind(ReferenceKind.MOMENT.name),
    ) { core, regions, moments ->
        (core + regions.map(ReferenceEntity::toDomain) + moments.map(ReferenceEntity::toDomain))
            .sortedWith(frenchLabelComparator(ReferenceValue::label) { it.id.toString() })
    }

    private val contentState = combine(repository.snapshot, domain, query, referenceOptions) { snapshot, selectedDomain, currentQuery, refs ->
        SemanticDictionaryAdminState(
            snapshot = snapshot,
            domain = selectedDomain,
            query = currentQuery,
            referenceOptions = refs,
            isLoading = false,
        )
    }
    private val deleteState = combine(deleteImpact, deletingConceptId, deleteInProgress) { impact, deleting, isDeleting -> Triple(impact, deleting, isDeleting) }
    private val importState = combine(importPreview, importInProgress) { preview, isImporting -> preview to isImporting }
    val state = combine(contentState, message, deleteState, importState) { content, messages, deletion, importing ->
        content.copy(error = messages.first, notice = messages.second, deleteImpact = deletion.first, deletingConceptId = deletion.second,
            deleteInProgress = deletion.third, importPreview = importing.first, importInProgress = importing.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SemanticDictionaryAdminState())

    fun selectDomain(value: SemanticDomain) { domain.value = value }
    fun setQuery(value: String) { query.value = value }
    fun clearMessage() { message.value = null to null }
    fun reportNotice(value: String) { message.value = null to value }
    fun reportError(value: String) { message.value = value to null }

    fun exportJson(onReady: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { SemanticDictionaryArchiveJson.encode(repository.exportArchive()) }
                .onSuccess(onReady)
                .onFailure { error -> message.value = (error.message ?: "Impossible de préparer la sauvegarde.") to null }
        }
    }

    fun prepareImport(json: String) {
        viewModelScope.launch {
            message.value = null to null
            importPreview.value = null
            importJson.value = null
            runCatching { repository.validateArchive(json) }
                .onSuccess { archive -> importJson.value = json; importPreview.value = archive }
                .onFailure { error -> message.value = (error.message ?: "Fichier de dictionnaire invalide.") to null }
        }
    }

    fun cancelImport() {
        if (importInProgress.value) return
        importPreview.value = null
        importJson.value = null
    }

    fun confirmImport(onImported: (SemanticDictionaryArchive) -> Unit) {
        if (importInProgress.value) return
        val json = importJson.value ?: return
        viewModelScope.launch {
            importInProgress.value = true
            message.value = null to null
            runCatching { repository.replaceArchive(json) }
                .onSuccess { archive ->
                    importInProgress.value = false
                    cancelImport()
                    message.value = null to "Dictionnaire restauré : ${archive.concepts.size} concepts, ${archive.synonymCount} synonymes, ${archive.relationCount} relations, ${archive.inactiveConceptCount} inactifs et ${archive.tombstoneCount} suppressions logiques."
                    onImported(archive)
                }
                .onFailure { error ->
                    importInProgress.value = false
                    message.value = (error.message ?: "Le remplacement a échoué ; le dictionnaire courant est conservé.") to null
                }
        }
    }

    fun saveConcept(concept: SemanticConcept, synonyms: List<String>, onSaved: () -> Unit, onFailed: () -> Unit = {}) {
        viewModelScope.launch {
            message.value = null to null
            runCatching { repository.saveConcept(concept, synonyms) }
                .onSuccess {
                    message.value = null to "Concept enregistré."
                    onSaved()
                }
                .onFailure { error ->
                    message.value = (error.message ?: "Impossible d’enregistrer ce concept.") to null
                    onFailed()
                }
        }
    }

    fun addSpecialization(sourceId: String, targetId: String) {
        viewModelScope.launch {
            message.value = null to null
            runCatching { repository.createSpecialization(sourceId, targetId) }
                .onFailure { error -> message.value = (error.message ?: "Impossible d’ajouter cette relation.") to null }
        }
    }

    fun deleteSpecialization(id: String) {
        viewModelScope.launch {
            message.value = null to null
            runCatching { repository.deleteSpecialization(id) }
                .onSuccess { message.value = null to "Relation supprimée." }
                .onFailure { error -> message.value = (error.message ?: "Impossible de supprimer cette relation.") to null }
        }
    }

    fun requestDelete(id: String) {
        viewModelScope.launch {
            message.value = null to null
            runCatching { repository.deleteImpact(id) }
                .onSuccess { impact ->
                    deleteImpact.value = impact
                    deletingConceptId.value = id
                }
                .onFailure { error -> message.value = (error.message ?: "Ce concept n’est plus disponible.") to null }
        }
    }

    fun cancelDelete() {
        if (deleteInProgress.value) return
        deleteImpact.value = null
        deletingConceptId.value = null
    }

    fun confirmDelete(onDeleted: () -> Unit) {
        if (deleteInProgress.value) return
        val id = deletingConceptId.value ?: return
        viewModelScope.launch {
            deleteInProgress.value = true
            message.value = null to null
            runCatching { repository.deleteConcept(id) }
                .onSuccess {
                    deleteInProgress.value = false
                    cancelDelete()
                    message.value = null to "Concept supprimé."
                    onDeleted()
                }
                .onFailure { error ->
                    deleteInProgress.value = false
                    message.value = (error.message ?: "Impossible de supprimer ce concept.") to null
                }
        }
    }
}

class SemanticDictionaryViewModelFactory(
    private val repository: SemanticDictionaryRepository,
    private val references: ReferenceDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(SemanticDictionaryViewModel::class.java.isAssignableFrom(modelClass))
        return SemanticDictionaryViewModel(repository, references) as T
    }
}

fun SemanticDictionaryAdminState.filteredConcepts(): List<SemanticConcept> {
    val normalized = MadjoSemanticResolver.normalize(query)
    val expressionsByConcept = snapshot.expressions.groupBy(SemanticExpression::conceptId)
    return snapshot.concepts.asSequence()
        .filter { it.domain == domain }
        .filter { concept -> normalized.isBlank() ||
            MadjoSemanticResolver.normalize(concept.label).contains(normalized) ||
                expressionsByConcept[concept.id].orEmpty().any { MadjoSemanticResolver.normalize(it.expression).contains(normalized) }
        }
        .sortedWith(compareBy<SemanticConcept> { !it.active }.then(frenchLabelComparator(SemanticConcept::label, SemanticConcept::id)))
        .toList()
}

fun SemanticConcept.isProtectedSystemConcept(): Boolean = id in BuiltInSemanticConceptIds.protected

fun semanticDomainLabel(domain: SemanticDomain): String = when (domain) {
    SemanticDomain.AROMA -> "Arômes"
    SemanticDomain.AROMATIC_PROFILE -> "Profils aromatiques"
    SemanticDomain.CATEGORY -> "Couleurs et types de thé"
    SemanticDomain.ORIGIN -> "Origines"
    SemanticDomain.MOMENT -> "Moments"
    SemanticDomain.TASTE_RATING -> "Notes gustatives"
    SemanticDomain.STOCK -> "Stocks"
    SemanticDomain.TASTING_HISTORY -> "Historique des dégustations"
}

fun SemanticDomain.referenceKinds(): Set<ReferenceKind> = when (this) {
    SemanticDomain.AROMA -> setOf(ReferenceKind.AROMA)
    SemanticDomain.AROMATIC_PROFILE -> setOf(ReferenceKind.AROMATIC_PROFILE)
    SemanticDomain.CATEGORY -> setOf(ReferenceKind.CATEGORY)
    SemanticDomain.ORIGIN -> setOf(ReferenceKind.COUNTRY, ReferenceKind.REGION)
    SemanticDomain.MOMENT -> setOf(ReferenceKind.MOMENT)
    SemanticDomain.TASTE_RATING, SemanticDomain.STOCK, SemanticDomain.TASTING_HISTORY -> emptySet()
}

data class SemanticBusinessMapping(val businessDimension: String, val businessConcept: String, val businessValue: String)

fun semanticMappingFor(domain: SemanticDomain, reference: ReferenceValue): SemanticBusinessMapping = when (domain) {
    SemanticDomain.AROMA -> {
        require(reference.kind == ReferenceKind.AROMA) { "Choisissez un arôme" }
        SemanticBusinessMapping("AROMA", "aroma", "ref:${reference.id}")
    }
    SemanticDomain.AROMATIC_PROFILE -> {
        require(reference.kind == ReferenceKind.AROMATIC_PROFILE) { "Choisissez un profil aromatique" }
        SemanticBusinessMapping("AROMA", "aromatic_profile", "ref:${reference.id}")
    }
    SemanticDomain.CATEGORY -> {
        require(reference.kind == ReferenceKind.CATEGORY) { "Choisissez une couleur ou un type de thé" }
        SemanticBusinessMapping("TYPE_COLOR", "category", "ref:${reference.id}")
    }
    SemanticDomain.ORIGIN -> when (reference.kind) {
        ReferenceKind.COUNTRY -> SemanticBusinessMapping("ORIGIN", "country", "ref:${reference.id}")
        ReferenceKind.REGION -> SemanticBusinessMapping("ORIGIN", "region", "ref:${reference.id}")
        else -> error("Choisissez un pays ou une région")
    }
    SemanticDomain.MOMENT -> {
        require(reference.kind == ReferenceKind.MOMENT) { "Choisissez un moment" }
        SemanticBusinessMapping("MOMENT", "moment", "ref:${reference.id}")
    }
    SemanticDomain.TASTE_RATING, SemanticDomain.STOCK, SemanticDomain.TASTING_HISTORY -> error("Les liaisons internes de ce domaine sont protégées.")
}
