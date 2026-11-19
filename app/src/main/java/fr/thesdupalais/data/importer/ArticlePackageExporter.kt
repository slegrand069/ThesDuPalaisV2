package fr.thesdupalais.data.importer

import android.content.ContentResolver
import android.net.Uri
import fr.thesdupalais.data.database.ArticleImageStore
import fr.thesdupalais.data.database.ArticleRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

class ArticlePackageExporter(
    private val repository: ArticleRepository,
    private val resolver: ContentResolver,
    filesDir: File,
) {
    private val imageStore = ArticleImageStore(filesDir)

    suspend fun export(articleId: UUID, destination: Uri) {
        val article = repository.findById(articleId) ?: throw IOException("Article indisponible.")
        val images = linkedMapOf<String, ByteArray>()
        ArticlePackageArchive.IMAGE_REF.findAll(article.markdown).forEach { match ->
            val path = match.groupValues[2]
            if (!path.startsWith("images/") || path.split('/').size != 2) throw IOException("Référence d’image non portable : $path")
            val name = path.removePrefix("images/")
            if (!name.matches(Regex("[0-9a-fA-F-]{36}\\.jpg"))) throw IOException("Image d’article non reconnue : $name")
            val file = imageStore.file(article.id.toString(), name)
            if (!file.isFile || file.length() > ArticlePackageArchive.MAX_IMAGE_BYTES) throw IOException("Image absente ou trop volumineuse : $name")
            images[path] = file.readBytes()
        }
        val manifest = JSONObject().put("format", "thesdupalais.article").put("schemaVersion", 1)
            .put("id", article.id.toString()).put("title", article.title).put("category", article.category).put("markdownPath", "article.md")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            write(zip, "article.json", manifest.toString().toByteArray(Charsets.UTF_8))
            write(zip, "article.md", article.markdown.toByteArray(Charsets.UTF_8))
            images.forEach { (path, bytes) -> write(zip, path, bytes) }
        }
        val bytes = out.toByteArray()
        if (bytes.size.toLong() > ArticlePackageArchive.MAX_ARCHIVE_BYTES) throw IOException("Archive d’article supérieure à 50 MiB.")
        resolver.openOutputStream(destination, "wt")?.use { it.write(bytes); it.flush() } ?: throw IOException("Fichier de destination inaccessible.")
        // Do not report success for a truncated provider write.
        val readBack = resolver.openInputStream(destination)?.use { it.readBytes() } ?: throw IOException("Archive exportée impossible à relire.")
        ArticlePackageArchive.read(readBack)
    }

    private fun write(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path).apply { time = 0L }); zip.write(bytes); zip.closeEntry()
    }
}
