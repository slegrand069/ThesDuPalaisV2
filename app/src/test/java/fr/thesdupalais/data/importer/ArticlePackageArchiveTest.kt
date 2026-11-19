package fr.thesdupalais.data.importer

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArticlePackageArchiveTest {
    @Test fun `reads portable package with referenced image`() {
        val result = ArticlePackageArchive.read(zip(mapOf(
            "article.json" to manifest(),
            "article.md" to "# Thé\n\n![Feuilles](images/feuilles.jpg)".toByteArray(),
            "images/feuilles.jpg" to jpeg,
        )))
        assertEquals("Le thé", result.title)
        assertEquals(listOf("images/feuilles.jpg"), result.images.keys.toList())
    }

    @Test fun `rejects missing image, corrupt image, and path traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArticlePackageArchive.read(zip(mapOf("article.json" to manifest(), "article.md" to "![x](images/a.jpg)".toByteArray())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArticlePackageArchive.read(zip(mapOf("article.json" to manifest(), "article.md" to "![x](images/a.jpg)".toByteArray(), "images/a.jpg" to byteArrayOf(1, 2, 3))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArticlePackageArchive.read(zip(mapOf("../article.md" to byteArrayOf(1))))
        }
    }

    private fun manifest() = """{"format":"thesdupalais.article","schemaVersion":1,"id":"123e4567-e89b-12d3-a456-426614174000","title":"Le thé","category":"Savoirs","markdownPath":"article.md"}""".toByteArray()
    private fun zip(files: Map<String, ByteArray>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
}
