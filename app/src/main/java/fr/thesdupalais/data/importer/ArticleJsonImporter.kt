package fr.thesdupalais.data.importer

import fr.thesdupalais.data.database.ArticleEntity
import fr.thesdupalais.data.database.ArticleImportStore
import fr.thesdupalais.data.database.ArticleWriteDisposition
import java.util.UUID
import kotlinx.coroutines.CancellationException

data class ArticleImportGlobalError(val jsonPath: String, val reason: String)

data class ArticleImportResult(
    val added: Int = 0,
    val updated: Int = 0,
    val rejections: List<ArticleImportRejection> = emptyList(),
    val globalError: ArticleImportGlobalError? = null,
) {
    val rejected: Int get() = rejections.size
}

/** Imports strict article JSON while keeping each accepted article in its own Room transaction. */
class ArticleJsonImporter(private val store: ArticleImportStore) {
    suspend fun importJson(json: String): ArticleImportResult {
        val parsed = try {
            ArticleJsonParser.parse(json)
        } catch (error: ArticleImportException) {
            return ArticleImportResult(globalError = ArticleImportGlobalError(error.jsonPath, error.message ?: "Fichier invalide."))
        } catch (error: Exception) {
            return ArticleImportResult(globalError = ArticleImportGlobalError("$", "Le fichier JSON n'a pas pu être analysé."))
        }

        var added = 0
        var updated = 0
        var activeArticleIndex: Int? = null
        try {
            store.withImportPermit {
                parsed.candidates.forEach { candidate ->
                    activeArticleIndex = candidate.index
                    val entity = ArticleEntity(
                        id = candidate.id ?: UUID.randomUUID(),
                        title = candidate.title,
                        category = candidate.category,
                        markdown = candidate.markdown,
                    )
                    when (store.upsertForImport(entity)) {
                        ArticleWriteDisposition.ADDED -> added++
                        ArticleWriteDisposition.UPDATED -> updated++
                    }
                }
            }
            return ArticleImportResult(added, updated, parsed.rejections)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return ArticleImportResult(
                added = added,
                updated = updated,
                rejections = parsed.rejections,
                globalError = ArticleImportGlobalError(
                    activeArticleIndex?.let { "$.articles[$it]" } ?: "$",
                    "Une erreur a interrompu l'import après $added ajout(s) et $updated mise(s) à jour. " +
                        "Les articles déjà traités restent enregistrés; l'écriture de l'article en erreur a été annulée. " +
                        (error.message?.let { "Détail : $it" } ?: ""),
                ),
            )
        }
    }
}
