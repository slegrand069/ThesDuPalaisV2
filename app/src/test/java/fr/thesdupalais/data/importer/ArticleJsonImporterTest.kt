package fr.thesdupalais.data.importer

import fr.thesdupalais.data.database.ArticleEntity
import fr.thesdupalais.data.database.ArticleImportStore
import fr.thesdupalais.data.database.ArticleWriteDisposition
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleJsonImporterTest {
    @Test fun acceptsValidEnvelopeAndEmptyMarkdown() {
        val parsed = ArticleJsonParser.parse(document(article(id = UUID_A, markdown = "")))
        assertEquals(1, parsed.candidates.size)
        assertEquals("Texte", parsed.candidates.single().title)
        assertEquals("", parsed.candidates.single().markdown)
        assertTrue(parsed.rejections.isEmpty())
    }

    @Test fun rejectsInvalidEnvelopesAsGlobalErrors() = runBlocking {
        val importer = ArticleJsonImporter(MemoryStore())
        listOf(
            "[]",
            "{\"format\":\"wrong\",\"schemaVersion\":1,\"articles\":[]}",
            "{\"format\":\"thesdupalais.articles\",\"schemaVersion\":2,\"articles\":[]}",
            "{\"format\":\"thesdupalais.articles\",\"schemaVersion\":1,\"articles\":[],\"extra\":true}",
            "{\"format\":\"thesdupalais.articles\",\"schemaVersion\":1}",
            "{'format':'thesdupalais.articles','schemaVersion':1,'articles':[]}",
            "{\"format\":\"thesdupalais.articles\",\"schemaVersion\":1,\"articles\":[],}",
            "{broken",
        ).forEach { json ->
            val result = importer.importJson(json)
            assertNotNull(result.globalError)
            assertEquals(0, result.added + result.updated + result.rejected)
        }
    }

    @Test fun rejectsInvalidArticleFieldsButKeepsValidItems() {
        val parsed = ArticleJsonParser.parse(
            document(
                article(id = UUID_A),
                "{}",
                article(id = UUID_B, title = "  "),
                article(id = UUID_C, category = "  "),
                article(id = UUID_D, markdown = "not used", suffix = ",\"extra\":1"),
                article(id = UUID_E, idText = "null"),
                article(id = UUID_F, idText = "\"\""),
                article(id = UUID_F, idText = "\"not-a-uuid\""),
                article(id = UUID_A.replaceLastDigit('7'), markdown = null),
            ),
        )
        assertEquals(1, parsed.candidates.size)
        assertEquals(8, parsed.rejections.size)
    }

    @Test fun absentUuidIsGeneratedAndSameTitleWithDifferentUuidsIsAccepted() = runBlocking {
        val store = MemoryStore()
        val result = ArticleJsonImporter(store).importJson(
            document(article(title = "Même titre", id = UUID_A), article(title = "Même titre", id = UUID_B), article(title = "Sans ID", idText = null)),
        )
        assertEquals(3, result.added)
        assertEquals(0, result.updated)
        assertEquals(0, result.rejected)
        assertNull(result.globalError)
        assertEquals(3, store.values.size)
        assertTrue(store.values.values.any { it.title == "Même titre" && it.id == UUID_A.toUuid() })
        assertTrue(store.values.values.any { it.title == "Même titre" && it.id == UUID_B.toUuid() })
        assertTrue(store.values.values.any { it.title == "Sans ID" && it.id.toString().isNotBlank() })
    }

    @Test fun allOccurrencesOfRepeatedUuidAreRejected() {
        val parsed = ArticleJsonParser.parse(document(article(id = UUID_A), article(id = UUID_A), article(id = UUID_B)))
        assertEquals(1, parsed.candidates.size)
        assertEquals(listOf(0, 1), parsed.rejections.map { it.index })
        assertTrue(parsed.rejections.all { it.reason.contains("toutes ses occurrences") })
    }

    @Test fun importerSeparatesUpdateAndInsertDecisionsAndReportsPartialResults() = runBlocking {
        val existingId = UUID_A.toUuid()
        val store = MemoryStore(mutableMapOf(existingId to ArticleEntity(existingId, "Avant", "Histoire", "ancien")))
        val result = ArticleJsonImporter(store).importJson(
            document(article(id = UUID_A, title = "Après"), article(id = UUID_B), "{}"),
        )
        assertEquals(1, result.added)
        assertEquals(1, result.updated)
        assertEquals(1, result.rejected)
        assertEquals(2, store.values.size)
        assertEquals("Après", store.values.getValue(existingId).title)
        assertEquals(2, result.rejections.single().index)
        assertNull(result.globalError)
    }

    @Test fun persistenceFailureIsGlobalAndStopsAfterCompletedArticleWrites() = runBlocking {
        val store = MemoryStore().apply { failOnId = UUID_B.toUuid() }
        val result = ArticleJsonImporter(store).importJson(document(article(id = UUID_A), article(id = UUID_B), article(id = UUID_C)))
        assertEquals(1, result.added)
        assertEquals(0, result.updated)
        assertEquals(0, result.rejected)
        assertNotNull(result.globalError)
        assertEquals(1, store.values.size)
    }

    @Test fun coordinatorFailureIsReportedAsGlobalWithoutWriting() = runBlocking {
        val store = MemoryStore().apply { blockWrites = true }
        val result = ArticleJsonImporter(store).importJson(document(article(id = UUID_A)))
        assertNotNull(result.globalError)
        assertTrue(store.values.isEmpty())
        assertFalse(store.upsertWasCalled)
    }

    private class MemoryStore(initial: MutableMap<UUID, ArticleEntity> = mutableMapOf()) : ArticleImportStore {
        val values = initial
        var failOnId: UUID? = null
        var blockWrites = false
        var upsertWasCalled = false

        override suspend fun <T> withImportPermit(block: suspend () -> T): T {
            if (blockWrites) error("Écriture bloquée par une opération de récupération.")
            return block()
        }

        override suspend fun upsertForImport(article: ArticleEntity): ArticleWriteDisposition {
            upsertWasCalled = true
            if (article.id == failOnId) error("Échec d'écriture simulé.")
            return if (values.containsKey(article.id)) {
                values[article.id] = article
                ArticleWriteDisposition.UPDATED
            } else {
                values[article.id] = article
                ArticleWriteDisposition.ADDED
            }
        }
    }

    private fun document(vararg entries: String): String =
        """{"format":"thesdupalais.articles","schemaVersion":1,"articles":[${entries.joinToString(",") }]}"""

    private fun article(
        id: String? = UUID_A,
        idText: String? = id?.let { "\"$it\"" },
        title: String = "Texte",
        category: String = "Culture",
        markdown: String? = "# contenu",
        suffix: String = "",
    ): String {
        val idField = idText?.let { "\"id\":$it," } ?: ""
        val markdownField = markdown?.let { "\"markdown\":\"$it\"" } ?: "\"markdown\":12"
        return "{$idField\"title\":\"$title\",\"category\":\"$category\",$markdownField$suffix}"
    }

    private fun String.toUuid() = UUID.fromString(this)
    private fun String.replaceLastDigit(replacement: Char) = dropLast(1) + replacement

    private companion object {
        const val UUID_A = "123e4567-e89b-12d3-a456-426614174000"
        const val UUID_B = "123e4567-e89b-12d3-a456-426614174001"
        const val UUID_C = "123e4567-e89b-12d3-a456-426614174002"
        const val UUID_D = "123e4567-e89b-12d3-a456-426614174003"
        const val UUID_E = "123e4567-e89b-12d3-a456-426614174004"
        const val UUID_F = "123e4567-e89b-12d3-a456-426614174005"
    }
}
