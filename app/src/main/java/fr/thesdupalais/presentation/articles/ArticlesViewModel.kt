package fr.thesdupalais.presentation.articles

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.thesdupalais.data.database.ArticleEntity
import fr.thesdupalais.data.database.ArticleRepository
import fr.thesdupalais.data.importer.ArticleImportGlobalError
import fr.thesdupalais.data.importer.ArticleImportResult
import fr.thesdupalais.data.importer.ArticleJsonImporter
import fr.thesdupalais.data.importer.ArticlePackageImporter
import fr.thesdupalais.data.importer.ArticlePackageExporter
import fr.thesdupalais.data.importer.readArticleJsonDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ArticlesUiState(
    val articles: List<ArticleEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isImporting: Boolean = false,
    val importResult: ArticleImportResult? = null,
    val exportMessage: String? = null,
)

class ArticlesViewModel(
    repository: ArticleRepository,
    private val importer: ArticleJsonImporter,
    private val packageImporter: ArticlePackageImporter,
    private val packageExporter: ArticlePackageExporter,
    private val contentResolver: ContentResolver,
) : ViewModel() {
    val deletion = ArticleDeletionController(repository::deleteById)
    private val mutableState = MutableStateFlow(ArticlesUiState())
    val state: StateFlow<ArticlesUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { articles ->
                deletion.onArticlesChanged(articles)
                mutableState.update { it.copy(articles = articles, isLoading = false) }
            }
        }
    }

    fun importFrom(uri: Uri) {
        if (mutableState.value.isImporting) return
        viewModelScope.launch {
            mutableState.update { it.copy(isImporting = true, importResult = null) }
            val result = try {
                withContext(Dispatchers.IO) {
                    val type = contentResolver.getType(uri).orEmpty().lowercase()
                    if (type.contains("zip") || uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase() == "zip") {
                        val bytes = contentResolver.openInputStream(uri)?.use { input ->
                            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val count = input.read(buffer); if (count < 0) break; if (out.size() + count > fr.thesdupalais.data.importer.ArticlePackageArchive.MAX_ARCHIVE_BYTES) throw java.io.IOException("Archive supérieure à 50 MiB."); out.write(buffer, 0, count) }
                            out.toByteArray()
                        } ?: throw java.io.IOException("Archive illisible.")
                        packageImporter.import(bytes)
                    } else {
                        val json = readArticleJsonDocument { contentResolver.openInputStream(uri) }
                        importer.importJson(json)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ArticleImportResult(
                    globalError = ArticleImportGlobalError(
                        "$",
                        "Lecture du fichier impossible : ${error.message ?: "erreur d'accès au document."}",
                    ),
                )
            }
            mutableState.update { it.copy(isImporting = false, importResult = result) }
        }
    }

    fun dismissImportResult() = mutableState.update { it.copy(importResult = null) }

    fun exportPackage(articleId: UUID, destination: Uri) {
        viewModelScope.launch {
            mutableState.update { it.copy(exportMessage = "Création du ZIP de l’article…") }
            runCatching { withContext(Dispatchers.IO) { packageExporter.export(articleId, destination) } }
                .onSuccess { mutableState.update { it.copy(exportMessage = "ZIP de l’article créé et vérifié.") } }
                .onFailure { error -> mutableState.update { it.copy(exportMessage = "Échec de l’export : ${error.message ?: "erreur inconnue"}") } }
        }
    }

    fun confirmArticleDeletion() {
        viewModelScope.launch { deletion.confirm() }
    }
}

class ArticlesViewModelFactory(
    private val repository: ArticleRepository,
    private val importer: ArticleJsonImporter,
    private val packageImporter: ArticlePackageImporter,
    private val packageExporter: ArticlePackageExporter,
    private val contentResolver: ContentResolver,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(ArticlesViewModel::class.java.isAssignableFrom(modelClass))
        return ArticlesViewModel(repository, importer, packageImporter, packageExporter, contentResolver) as T
    }
}

sealed interface ArticleDetailState {
    data object Loading : ArticleDetailState
    data object Unavailable : ArticleDetailState
    data class Content(val article: ArticleEntity) : ArticleDetailState
}

class ArticleDetailViewModel(repository: ArticleRepository, articleId: UUID) : ViewModel() {
    val state: StateFlow<ArticleDetailState> = repository.observeById(articleId)
        .map { article -> article?.let(ArticleDetailState::Content) ?: ArticleDetailState.Unavailable }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), ArticleDetailState.Loading)
}

class ArticleDetailViewModelFactory(
    private val repository: ArticleRepository,
    private val articleId: UUID,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(ArticleDetailViewModel::class.java.isAssignableFrom(modelClass))
        return ArticleDetailViewModel(repository, articleId) as T
    }
}
