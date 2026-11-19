package fr.thesdupalais.data.importer

import fr.thesdupalais.data.database.ArticleEntity
import fr.thesdupalais.data.database.ArticleImageStore
import fr.thesdupalais.data.database.ArticleRepository
import fr.thesdupalais.data.database.ArticleWriteDisposition
import java.io.File
import java.util.UUID

/** Stages all media first, commits private files, writes the article, then removes obsolete assets. */
class ArticlePackageImporter(private val repository: ArticleRepository, filesDir: File) {
    private val imageStore = ArticleImageStore(filesDir)

    suspend fun import(bytes: ByteArray): ArticleImportResult {
        val packageData = try { ArticlePackageArchive.read(bytes) }
        catch (error: Exception) { return ArticleImportResult(globalError = ArticleImportGlobalError("$", error.message ?: "Paquet d’article invalide.")) }
        val articleId = UUID.fromString(packageData.id)
        val staged = linkedMapOf<String, fr.thesdupalais.data.database.StagedArticleImage>()
        val installed = mutableListOf<String>()
        try {
            packageData.images.forEach { (sourcePath, imageBytes) -> staged[sourcePath] = imageStore.stage(imageBytes) }
            val rewritten = ArticlePackageArchive.IMAGE_REF.replace(packageData.markdown) { match ->
                val asset = staged.getValue(match.groupValues[2]).id
                "![${match.groupValues[1]}](images/$asset.jpg${match.groupValues[3]})"
            }
            staged.values.forEach { imageStore.commit(articleId.toString(), it).also { installed += it.nameWithoutExtension } }
            val disposition = repository.upsertForImport(ArticleEntity(articleId, packageData.title, packageData.category, rewritten))
            val retained = staged.values.map { "${it.id}.jpg" }.toSet()
            imageStore.cleanup(articleId.toString(), retained)
            return when (disposition) {
                ArticleWriteDisposition.ADDED -> ArticleImportResult(added = 1)
                ArticleWriteDisposition.UPDATED -> ArticleImportResult(updated = 1)
            }
        } catch (error: Exception) {
            imageStore.rollback(articleId.toString(), installed)
            return ArticleImportResult(globalError = ArticleImportGlobalError("$", "L’article précédent a été conservé : ${error.message ?: "échec de l’import"}"))
        } finally {
            staged.values.forEach { it.file.delete() }
        }
    }
}
