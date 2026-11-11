package fr.thesdupalais.data.backup

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupArchiveValidatorTest {
    @Test fun validatesEmptySelectedArticlesSet() {
        val index = """{"articles":[]}""".toByteArray()
        val archive = zip(mapOf("articles/index.json" to index), setOf("articles"))
        val result = BackupArchiveValidator.validate(archive)
        assertEquals(setOf("articles"), result.includedSets)
        assertEquals(1, result.entries.size)
    }

    @Test fun rejectsUnknownManifestProperty() {
        val archive = zip(mapOf("articles/index.json" to """{"articles":[]}""".toByteArray()), setOf("articles"), extraManifest = """"unexpected":true,""")
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(archive) }
    }

    @Test fun rejectsMissingIndexEntry() {
        val archive = zip(emptyMap(), setOf("articles"))
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(archive) }
    }

    @Test fun rejectsPathTraversal() {
        val archive = zip(mapOf("../escape.json" to "{}".toByteArray()), setOf("articles"))
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(archive) }
    }

    @Test fun rejectsChecksumMismatch() {
        val archive = zip(mapOf("articles/index.json" to """{"articles":[]}""".toByteArray()), setOf("articles"), wrongDigest = true)
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(archive) }
    }

    @Test fun validatesArticleIdentityAndRequiredMarkdown() {
        val index = """{"articles":[{"id":"tea-history","title":"History","section":null,"markdownPath":"articles/content/tea-history.md","illustrations":[]}]}""".replace("\\u0000", "")
        val archive = zip(mapOf("articles/index.json" to index.toByteArray()), setOf("articles"))
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(archive) }
    }

    @Test fun rejectsDuplicateArticleIds() {
        val item = """{"id":"same","title":"A","section":null,"markdownPath":"articles/content/a.md","illustrations":[]}"""
        val index = """{"articles":[$item,$item]}""".toByteArray()
        val md = "# A".toByteArray()
        val archive = zip(mapOf("articles/index.json" to index, "articles/content/a.md" to md), setOf("articles"))
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(archive) }
    }

    @Test fun acceptsValidTeaDependenciesAndMatchingConfigurationReferences() {
        val refs = listOf(reference(CAT, "CATEGORY", label = "Vert"))
        val teas = teaPayload(refs, listOf(tea(id = TEA_A, category = CAT)))
        val config = configPayload(refs)
        val archive = zip(mapOf("data/teas.json" to teas, "data/configuration.json" to config), setOf("teas", "configuration"))
        assertEquals(setOf("teas", "configuration"), BackupArchiveValidator.validate(archive).includedSets)
    }

    @Test fun rejectsOrphanedTeaReference() {
        val archive = zip(mapOf("data/teas.json" to teaPayload(emptyList(), listOf(tea(id = TEA_A, category = CAT)))), setOf("teas"))
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(archive) }
    }

    @Test fun rejectsUnusedReferenceInSelectiveTeaExport() {
        val refs = listOf(reference(CAT, "CATEGORY"), reference(COUNTRY_A, "COUNTRY"))
        val archive = zip(mapOf("data/teas.json" to teaPayload(refs, listOf(tea(id = TEA_A, category = CAT)))), setOf("teas"))
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(archive) }
    }

    @Test fun acceptsRegionWithImplicitParentCountryDependency() {
        val refs = listOf(reference(CAT, "CATEGORY"), reference(COUNTRY_A, "COUNTRY"), reference(REGION, "REGION", parent = COUNTRY_A))
        val regionTea = tea(id = TEA_A, category = CAT).replace("\"regionIds\":[]", "\"regionIds\":[\"$REGION\"]")
        val archive = zip(mapOf("data/teas.json" to teaPayload(refs, listOf(regionTea))), setOf("teas"))
        BackupArchiveValidator.validate(archive)
    }

    @Test fun rejectsRegionWhoseParentIsNotACountry() {
        val refs = listOf(reference(CAT, "CATEGORY"), reference(REGION, "REGION", parent = CAT))
        val archive = zip(mapOf("data/teas.json" to teaPayload(refs, emptyList())), setOf("teas"))
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(archive) }
    }

    @Test fun rejectsExplicitCountryThatConflictsWithRegionParent() {
        val refs = listOf(reference(CAT, "CATEGORY"), reference(COUNTRY_A, "COUNTRY"), reference(COUNTRY_B, "COUNTRY"), reference(REGION, "REGION", parent = COUNTRY_A))
        val badTea = tea(id = TEA_A, category = CAT).replace("\"countryIds\":[]", "\"countryIds\":[\"$COUNTRY_B\"]").replace("\"regionIds\":[]", "\"regionIds\":[\"$REGION\"]")
        val archive = zip(mapOf("data/teas.json" to teaPayload(refs, listOf(badTea))), setOf("teas"))
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(archive) }
    }

    @Test fun rejectsDuplicateLegacyId() {
        val refs = listOf(reference(CAT, "CATEGORY"))
        val teas = listOf(tea(id = TEA_A, category = CAT, legacy = 42), tea(id = TEA_B, category = CAT, legacy = 42))
        val archive = zip(mapOf("data/teas.json" to teaPayload(refs, teas)), setOf("teas"))
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(archive) }
    }

    @Test fun rejectsMovementLinkedToTastingOfAnotherTea() {
        val refs = listOf(reference(CAT, "CATEGORY"))
        val teas = listOf(tea(id = TEA_A, category = CAT), tea(id = TEA_B, category = CAT))
        val tasting = """{"id":"$TASTING","teaId":"$TEA_A","occurredAt":"2026-01-01T00:00:00Z","recordedAt":"2026-01-01T00:00:00Z","consumedMilliGrams":1000}"""
        val move = """{"id":"$MOVEMENT","teaId":"$TEA_B","kind":"CONSUMPTION","deltaMilliGrams":-1000,"stockAfterMilliGrams":0,"occurredAt":"2026-01-01T00:00:00Z","recordedAt":"2026-01-01T00:00:00Z","tastingId":"$TASTING","note":null}"""
        val archive = zip(mapOf("data/teas.json" to teaPayload(refs, teas, listOf(tasting), listOf(move))), setOf("teas"))
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(archive) }
    }

    @Test fun rejectsReferenceDependencyCollisionWithConfiguration() {
        val deps = listOf(reference(CAT, "CATEGORY", label = "Vert"))
        val config = listOf(reference(CAT, "CATEGORY", label = "Noir"))
        val archive = zip(mapOf("data/teas.json" to teaPayload(deps, emptyList()), "data/configuration.json" to configPayload(config)), setOf("teas", "configuration"))
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(archive) }
    }

    private fun zip(payloads: Map<String, ByteArray>, sets: Set<String>, extraManifest: String = "", wrongDigest: Boolean = false): ByteArray {
        val entries = payloads.map { (path, bytes) ->
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            """{"path":"$path","mediaType":"${if (path.endsWith(".md")) "text/markdown; charset=utf-8" else "application/json"}","sizeBytes":${bytes.size},"sha256":"${if (wrongDigest) "0".repeat(64) else hash}"}"""
        }.joinToString(",")
        val text = """{"format":"${BackupArchiveValidator.FORMAT}","schemaVersion":1,"backupId":"123e4567-e89b-12d3-a456-426614174000","createdAt":"2026-11-11T12:00:00Z","producer":{"application":"ThésDuPalais","version":"1.0"},"includedSets":[${sets.joinToString(",") { "\"$it\"" }}],$extraManifest"entries":[$entries]}"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(text.toByteArray()); zip.closeEntry()
            payloads.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun reference(id: String, kind: String, label: String = kind, parent: String? = null): String =
        """{"id":"$id","kind":"$kind","semanticKey":null,"label":"$label","parentCountryId":${parent?.let { "\"$it\"" } ?: "null"},"archivedAt":null,"displayPriority":0}"""

    private fun tea(id: String, category: String, legacy: Int? = null): String =
        """{"id":"$id","legacyId":${legacy ?: "null"},"name":"Tea $id","categoryId":"$category","description":null,"technicalInformation":null,"personalNotes":null,"olfactoryRating":null,"tasteRating":null,"brewTemperatureCelsius":null,"infusionDurationSeconds":null,"statusId":null,"containerId":null,"countryIds":[],"regionIds":[],"aromaIds":[],"aromaticProfileIds":[],"momentIds":[],"badgeIds":[],"stockMilliGrams":null,"archivedAt":null}"""

    private fun teaPayload(refs: List<String>, teas: List<String>, tastings: List<String> = emptyList(), movements: List<String> = emptyList()) =
        """{"references":[${refs.joinToString(",")}],"teas":[${teas.joinToString(",")}],"tastings":[${tastings.joinToString(",")}],"stockMovements":[${movements.joinToString(",")}] }""".toByteArray()

    private fun configPayload(refs: List<String>) =
        """{"references":[${refs.joinToString(",")}],"preferences":{"quantityPerCupMilliGrams":7000,"lowStockThresholdMilliGrams":25000}}""".toByteArray()

    private companion object {
        const val CAT = "123e4567-e89b-12d3-a456-426614174001"
        const val COUNTRY_A = "123e4567-e89b-12d3-a456-426614174002"
        const val COUNTRY_B = "123e4567-e89b-12d3-a456-426614174003"
        const val REGION = "123e4567-e89b-12d3-a456-426614174004"
        const val TEA_A = "123e4567-e89b-12d3-a456-426614174005"
        const val TEA_B = "123e4567-e89b-12d3-a456-426614174006"
        const val TASTING = "123e4567-e89b-12d3-a456-426614174007"
        const val MOVEMENT = "123e4567-e89b-12d3-a456-426614174008"
    }
}
