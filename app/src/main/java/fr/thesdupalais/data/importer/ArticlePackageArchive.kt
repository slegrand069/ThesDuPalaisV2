package fr.thesdupalais.data.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** Side-effect-free validator for an individual article package (article.json + article.md + images/). */
object ArticlePackageArchive {
    const val MAX_ARCHIVE_BYTES = 50L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 100L * 1024 * 1024
    const val MAX_FILES = 150
    const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    data class Package(val id: String, val title: String, val category: String, val markdown: String, val images: Map<String, ByteArray>)

    fun read(bytes: ByteArray): Package {
        require(bytes.size.toLong() <= MAX_ARCHIVE_BYTES) { "Archive d’article supérieure à 50 MiB." }
        val files = linkedMapOf<String, ByteArray>()
        val seen = mutableSetOf<String>()
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.removeSuffix("/")
                require(path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\') && !path.contains('\\') && !path.contains(':') &&
                    path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Chemin ZIP dangereux : ${entry.name}" }
                require(seen.add(path.lowercase(java.util.Locale.ROOT))) { "Entrée ZIP dupliquée : ${entry.name}" }
                if (entry.isDirectory) { zip.closeEntry(); continue }
                require(files.size < MAX_FILES) { "Trop de fichiers dans le paquet d’article." }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer); if (count < 0) break
                    expanded += count
                    require(expanded <= MAX_EXPANDED_BYTES) { "Archive décompressée supérieure à 100 MiB." }
                    require(out.size().toLong() + count <= if (path.startsWith("images/")) MAX_IMAGE_BYTES else 2L * 1024 * 1024) { "Fichier trop volumineux : $path" }
                    out.write(buffer, 0, count)
                }
                files[path] = out.toByteArray()
                zip.closeEntry()
            }
        }
        val manifestBytes = files["article.json"] ?: error("Manifeste article.json absent.")
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        require(manifest.keys().asSequence().toSet() == setOf("format", "schemaVersion", "id", "title", "category", "markdownPath")) { "Champs du manifeste invalides." }
        require(manifest.getString("format") == "thesdupalais.article" && manifest.getInt("schemaVersion") == 1) { "Format de paquet d’article non pris en charge." }
        val id = java.util.UUID.fromString(manifest.getString("id")).toString()
        val title = manifest.getString("title").trim(); val category = manifest.getString("category").trim()
        require(title.isNotEmpty() && category.isNotEmpty()) { "Titre et catégorie sont obligatoires." }
        require(manifest.getString("markdownPath") == "article.md") { "Le Markdown doit être article.md." }
        val markdownBytes = files["article.md"] ?: error("Article Markdown absent.")
        val markdown = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(markdownBytes)).toString()
        val refs = IMAGE_REF.findAll(markdown).map { match -> match.groupValues[2] }.toList()
        refs.forEach { ref -> require(ref.startsWith("images/") && ref.split('/').size == 2 && ref.substringAfter('/').matches(Regex("[A-Za-z0-9._-]{1,100}\\.(jpg|jpeg)", RegexOption.IGNORE_CASE))) { "Référence d’image locale invalide : $ref" } }
        val expected = setOf("article.json", "article.md") + refs
        require(files.keys == expected) { "Le paquet contient des fichiers non référencés ou des images manquantes." }
        val images = refs.associateWith { path -> files.getValue(path).also(::requireJpeg) }
        return Package(id, title, category, markdown, images)
    }

    private fun requireJpeg(bytes: ByteArray) {
        require(bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()) { "Image JPEG illisible ou invalide." }
    }
    internal val IMAGE_REF = Regex("!\\[([^\\]]*)]\\(<?([^\\s)>]+)>?(\\s+\"[^\"]*\")?\\)")
}
