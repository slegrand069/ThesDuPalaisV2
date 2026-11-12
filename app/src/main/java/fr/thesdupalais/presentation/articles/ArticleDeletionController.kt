package fr.thesdupalais.presentation.articles

import fr.thesdupalais.data.database.ArticleEntity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ArticleDeletionState(
    val pendingArticle: ArticleEntity? = null,
    val deletingId: UUID? = null,
    val errorMessage: String? = null,
)

/** UI-level confirmation state; persistence is delegated to the coordinated article repository. */
class ArticleDeletionController(
    private val deleteById: suspend (UUID) -> Boolean,
) {
    private val mutableState = MutableStateFlow(ArticleDeletionState())
    val state: StateFlow<ArticleDeletionState> = mutableState.asStateFlow()

    fun request(article: ArticleEntity) {
        mutableState.update { current ->
            if (current.deletingId != null || current.pendingArticle != null) current
            else current.copy(pendingArticle = article, errorMessage = null)
        }
    }

    fun cancel() {
        mutableState.update { current ->
            if (current.deletingId != null) current else current.copy(pendingArticle = null)
        }
    }

    fun onArticlesChanged(articles: List<ArticleEntity>) {
        mutableState.update { current ->
            val pending = current.pendingArticle
            if (pending == null || current.deletingId != null) return@update current
            val latest = articles.firstOrNull { it.id == pending.id }
            if (latest == null) {
                current.copy(
                    pendingArticle = null,
                    errorMessage = "« ${pending.title} » n'est plus disponible. La demande de suppression a été annulée.",
                )
            } else {
                current.copy(pendingArticle = latest)
            }
        }
    }

    fun clearError() = mutableState.update { it.copy(errorMessage = null) }

    suspend fun confirm() {
        val article = mutableState.value.pendingArticle ?: return
        if (mutableState.value.deletingId != null) return
        mutableState.update { it.copy(deletingId = article.id, errorMessage = null) }
        try {
            val deleted = deleteById(article.id)
            mutableState.update {
                it.copy(
                    pendingArticle = null,
                    deletingId = null,
                    errorMessage = if (deleted) null else "« ${article.title} » n'est plus disponible. Aucune suppression n'a été effectuée.",
                )
            }
        } catch (error: CancellationException) {
            mutableState.update { it.copy(pendingArticle = null, deletingId = null) }
            throw error
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    pendingArticle = null,
                    deletingId = null,
                    errorMessage = "Impossible de confirmer la suppression de « ${article.title} ». ${error.message.orEmpty()}".trim(),
                )
            }
        }
    }
}
