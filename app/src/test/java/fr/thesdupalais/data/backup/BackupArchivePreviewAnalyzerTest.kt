package fr.thesdupalais.data.backup

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupArchivePreviewAnalyzerTest {
    @Test fun selectedTeaReplacementClassifiesAddedModifiedIdenticalRemoved() {
        val refs = listOf(reference(CAT, "CATEGORY", "Vert"))
        val idAdded = uuid(20); val idIdentical = uuid(21); val idRemoved = uuid(22)
        val archiveTeas = listOf(tea(idAdded, CAT, "Nouveau"), tea(idIdentical, CAT, "Même"), tea(TEA, CAT, "Nom importé"))
        val localTeas = listOf(tea(idIdentical, CAT, "Même"), tea(TEA, CAT, "Nom local"), tea(idRemoved, CAT, "À retirer"))
        val incomingBytes = archive(setOf("teas"), refs, archiveTeas)
        val localBytes = archive(setOf("teas", "configuration"), refs, localTeas, refs)
        val preview = analyze(incomingBytes, localBytes, setOf("teas"))
        val group = preview.groups.first { it.name == "Fiches de thé" }
        assertEquals(1, group.count(PreviewDisposition.ADDED))
        assertEquals(1, group.count(PreviewDisposition.MODIFIED))
        assertEquals(1, group.count(PreviewDisposition.IDENTICAL))
        assertEquals(1, group.count(PreviewDisposition.REMOVED))
        assertTrue(group.blockingConflicts.isEmpty())
    }

    @Test fun legacyIdCollisionIsBlockingEvenWhenUuidDiffers() {
        val refs = listOf(reference(CAT, "CATEGORY", "Vert"))
        val incoming = tea(TEA, CAT, "Thé", legacy = 55)
        val local = tea(uuid(30), CAT, "Autre UUID", legacy = 55)
        val preview = analyze(archive(setOf("teas"), refs, listOf(incoming)), archive(setOf("teas", "configuration"), refs, listOf(local), refs), setOf("teas"))
        val group = preview.groups.first { it.name == "Fiches de thé" }
        assertEquals(1, group.count(PreviewDisposition.CONFLICT))
        assertTrue(group.blockingConflicts.isNotEmpty())
    }

    @Test fun configurationReplacementPreservesLocalReferenceUsedByUnselectedTea() {
        val localRef = reference(CAT, "CATEGORY", "Vert")
        val incomingBytes = archive(setOf("configuration"), emptyList(), emptyList(), emptyList())
        val localBytes = archive(setOf("teas", "configuration"), listOf(localRef), listOf(tea(TEA, CAT, "Sencha")), listOf(localRef))
        val preview = analyze(incomingBytes, localBytes, setOf("configuration"))
        val refs = preview.groups.first { it.name == "Référentiels de configuration" }
        assertEquals(1, refs.count(PreviewDisposition.PRESERVED))
        assertEquals(0, refs.count(PreviewDisposition.REMOVED))
    }

    @Test fun incompatibleReferenceIdentityIsBlocking() {
        val localRef = reference(CAT, "CATEGORY", "Vert")
        val incomingRef = reference(CAT, "STATUS", "Vert")
        val incomingBytes = archive(setOf("configuration"), emptyList(), emptyList(), listOf(incomingRef))
        val localBytes = archive(setOf("teas", "configuration"), emptyList(), emptyList(), listOf(localRef))
        val preview = analyze(incomingBytes, localBytes, setOf("configuration"))
        val refs = preview.groups.first { it.name == "Référentiels de configuration" }
        assertEquals(1, refs.count(PreviewDisposition.CONFLICT))
        assertTrue(preview.hasBlockingConflicts)
    }

    @Test fun missingSetCannotBeSelectedAndPhotosLimitationIsAlwaysClear() {
        val bytes = archive(setOf("teas"), emptyList(), emptyList())
        val validated = BackupArchiveValidator.validate(bytes)
        val localBytes = archive(setOf("teas", "configuration"), emptyList(), emptyList(), emptyList())
        val local = BackupArchiveValidator.validate(localBytes)
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchivePreviewAnalyzer.analyze(bytes, validated, local, setOf("configuration"))
        }
        val preview = BackupArchivePreviewAnalyzer.analyze(bytes, validated, local, setOf("teas"))
        assertTrue(preview.limitations.any { it.contains("photos") })
    }

    private fun analyze(input: ByteArray, local: ByteArray, selected: Set<String>): BackupArchivePreview {
        return BackupArchivePreviewAnalyzer.analyze(input, BackupArchiveValidator.validate(input), BackupArchiveValidator.validate(local), selected)
    }

    private fun archive(sets: Set<String>, teaRefs: List<String>, teas: List<String>, configRefs: List<String> = emptyList()): ByteArray {
        val payloads = linkedMapOf<String, ByteArray>()
        if ("teas" in sets) payloads["data/teas.json"] = """{"references":[${teaRefs.joinToString(",")}],"teas":[${teas.joinToString(",")}],"tastings":[],"stockMovements":[]}""".toByteArray()
        if ("configuration" in sets) payloads["data/configuration.json"] = """{"references":[${configRefs.joinToString(",")}],"preferences":{"quantityPerCupMilliGrams":7000,"lowStockThresholdMilliGrams":25000}}""".toByteArray()
        val entries = JSONArray()
        payloads.forEach { (path, content) ->
            entries.put(JSONObject().put("path", path).put("mediaType", "application/json").put("sizeBytes", content.size).put("sha256", sha256(content)))
        }
        val manifest = JSONObject().put("format", BackupArchiveValidator.FORMAT).put("schemaVersion", 1)
            .put("backupId", BACKUP.toString()).put("createdAt", "2026-01-01T00:00:00Z")
            .put("producer", JSONObject().put("application", "test").put("version", "1"))
            .put("includedSets", JSONArray(sets.toList())).put("entries", entries)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            payloads.forEach { (path, content) -> zip.putNextEntry(ZipEntry(path)); zip.write(content); zip.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun reference(id: String, kind: String, label: String) =
        """{"id":"$id","kind":"$kind","semanticKey":null,"label":"$label","parentCountryId":null,"archivedAt":null,"displayPriority":0}"""

    private fun tea(id: String, category: String, name: String, legacy: Int? = null) =
        """{"id":"$id","legacyId":${legacy ?: "null"},"name":"$name","categoryId":"$category","description":null,"technicalInformation":null,"personalNotes":null,"olfactoryRating":null,"tasteRating":null,"brewTemperatureCelsius":null,"infusionDurationSeconds":null,"statusId":null,"containerId":null,"countryIds":[],"regionIds":[],"aromaIds":[],"aromaticProfileIds":[],"momentIds":[],"badgeIds":[],"stockMilliGrams":null,"archivedAt":null}"""

    private fun PreviewGroup.count(status: PreviewDisposition) = counts.firstOrNull { it.disposition == status }?.count ?: 0
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private companion object {
        const val CAT = "123e4567-e89b-12d3-a456-426614174001"
        const val TEA = "123e4567-e89b-12d3-a456-426614174002"
        val BACKUP = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        fun uuid(n: Int) = "123e4567-e89b-12d3-a456-${n.toString().padStart(12, '0')}"
    }
}
