package fr.thesdupalais.data.backup

import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.TeaEntity
import fr.thesdupalais.data.database.TeaPhotoEntity
import fr.thesdupalais.data.database.ArticleEntity
import fr.thesdupalais.data.backup.BackupReplacementPreviewPlanner
import fr.thesdupalais.data.backup.BackupLocalOperationSnapshot
import fr.thesdupalais.data.operation.DurableBackupOperationJournal
import fr.thesdupalais.data.backup.TargetOperation
import fr.thesdupalais.data.preferences.UserPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TeaPhotoBackupArchiveTest {
    @Test fun v3ArticleReplacementPreparesPortableReferencesAndMultipleImagesWithoutTouchingTeaPayloads() {
        val id = UUID.fromString("323e4567-e89b-12d3-a456-426614174000")
        val markdown = "# Plantation\n\n![Feuilles](images/feuille-a.jpg \"Première\")\n\n![Infusion](images/feuille-b.jpg)"
        val article = ArticleEntity(id, "Plantation", "Botanique", markdown)
        val source = snapshot(emptyList()).copy(articles = listOf(article), articleImageBytes = mapOf(
            "$id/feuille-a.jpg" to JPEG, "$id/feuille-b.jpg" to JPEG,
        ))
        val incoming = write(source, setOf(BackupExportSet.ARTICLES))
        val local = BackupLocalOperationSnapshot(write(snapshot(emptyList()), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION)), emptyList(), emptyList())
        val plan = BackupReplacementPreviewPlanner.plan(incoming, local, setOf("articles"))
        assertTrue(plan.target.blockers.joinToString(), plan.target.applicable)
        val target = plan.target.state!!
        assertTrue(target.replaceArticles)
        assertEquals(setOf("data/teas.json", "data/configuration.json"), target.payloads.keys)
        val restored = target.articleRecords.single()
        assertEquals(id.toString(), restored.id)
        assertEquals("Botanique", restored.category)
        assertTrue(restored.markdown.contains("![Feuilles](images/"))
        assertTrue(restored.markdown.contains("Première"))
        assertEquals(2, restored.images.size)
        assertTrue(restored.images.all { it.archivePath?.startsWith("articles/media/$id/") == true })
        assertTrue(BackupTargetStateBuilder.validatePreparedState(target).isEmpty())
        val repeated = BackupReplacementPreviewPlanner.plan(incoming, local, setOf("articles"))
        assertEquals(target.articleRecords, repeated.target.state!!.articleRecords)
    }

    @Test fun articleMediaIsDurablyStagedBeforeAnyRoomCommit() {
        val id = UUID.fromString("423e4567-e89b-12d3-a456-426614174000")
        val article = ArticleEntity(id, "Plantation", "Botanique", "![Feuilles](images/feuille.jpg)")
        val incoming = write(snapshot(emptyList()).copy(articles = listOf(article), articleImageBytes = mapOf("$id/feuille.jpg" to JPEG)), setOf(BackupExportSet.ARTICLES))
        val localBytes = write(snapshot(emptyList()), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val preview = BackupReplacementPreviewPlanner.plan(incoming, BackupLocalOperationSnapshot(localBytes, emptyList(), emptyList()), setOf("articles"))
        val target = preview.target.state!!
        val journalRoot = java.nio.file.Files.createTempDirectory("article-media-journal-").toFile()
        val mediaRoot = java.nio.file.Files.createTempDirectory("article-media-root-").toFile()
        try {
            val destination = java.io.File(mediaRoot, target.articleRecords.single().images.single().relativePath)
            destination.parentFile!!.mkdirs()
            val previousBytes = byteArrayOf(8, 7, 6)
            destination.writeBytes(previousBytes)
            val journal = DurableBackupOperationJournal(journalRoot, mediaRoot)
            val entry = journal.prepare(UUID.randomUUID().toString(), TargetOperation.REPLACE, setOf("articles"), incoming, target, preview.target.fingerprints)
            assertEquals(1, entry.mediaArtifacts.size)
            assertTrue(entry.mediaArtifacts.single().imported)
            assertTrue(journal.stagedMediaFile(entry, target.articleRecords.single().images.single().id).readBytes().contentEquals(JPEG))
            assertArrayEquals(previousBytes, destination.readBytes())
        } finally { journalRoot.deleteRecursively(); mediaRoot.deleteRecursively() }
    }

    @Test fun combinedTeaPhotoAndArticleSelectionKeepsBothMediaFamiliesInTheTarget() {
        val id = UUID.fromString("523e4567-e89b-12d3-a456-426614174000")
        val article = ArticleEntity(id, "Plantation", "Botanique", "![Feuilles](images/feuille.jpg)")
        val teaPhoto = TeaPhotoEntity(PHOTO_A, TEA_A, "tea-photos/$PHOTO_A.jpg", 0, true)
        val combined = snapshot(listOf(teaPhoto)).copy(
            articles = listOf(article), articleImageBytes = mapOf("$id/feuille.jpg" to JPEG),
        )
        val incoming = write(combined, setOf(BackupExportSet.TEAS, BackupExportSet.ARTICLES))
        val local = BackupLocalOperationSnapshot(write(snapshot(emptyList()), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION)), emptyList(), emptyList())
        val plan = BackupReplacementPreviewPlanner.plan(incoming, local, setOf("teas", "articles"))
        assertTrue(plan.target.blockers.joinToString(), plan.target.applicable)
        val target = plan.target.state!!
        assertEquals(listOf(PHOTO_A.toString()), target.photosToKeep.map { it.id })
        assertTrue(target.replaceArticles)
        assertEquals(1, target.articleRecords.single().images.size)
        assertTrue(BackupTargetStateBuilder.validatePreparedState(target).isEmpty())
    }

    @Test fun v3ExportsArticlesAndTheirPortableIllustrationFiles() {
        val articleId = UUID.fromString("223e4567-e89b-12d3-a456-426614174000")
        val article = ArticleEntity(articleId, "Plantation", "Savoirs", "# Plantation\n\n![Feuilles](images/feuilles.jpg \"Feuilles de thé\")")
        val articleSnapshot = snapshot(emptyList()).copy(
            articles = listOf(article),
            articleImageBytes = mapOf("$articleId/feuilles.jpg" to JPEG),
        )
        val archive = write(articleSnapshot, setOf(BackupExportSet.ARTICLES))
        val validated = BackupArchiveValidator.validate(archive)
        assertEquals(3, validated.schemaVersion)
        assertEquals(setOf("articles/index.json", "articles/content/$articleId.md", "articles/media/$articleId/feuilles.jpg"), validated.entries.keys)
        val index = JSONObject(String(validated.entries.getValue("articles/index.json")))
        assertEquals("$articleId", index.getJSONArray("articles").getJSONObject(0).getString("id"))
        assertEquals("articles/media/$articleId/feuilles.jpg", index.getJSONArray("articles").getJSONObject(0).getJSONArray("illustrations").getJSONObject(0).getString("path"))
        assertTrue(String(validated.entries.getValue("articles/content/$articleId.md")).contains("../media/$articleId/feuilles.jpg"))
        assertFalse(String(validated.entries.getValue("articles/index.json")).contains("base64"))
    }

    @Test fun v2ExportsMultipleTeaPhotosAsPortableFilesAndKeepsOrderAndPrimary() {
        val photos = listOf(
            TeaPhotoEntity(PHOTO_A, TEA_A, "tea-photos/$PHOTO_A.jpg", 0, true),
            TeaPhotoEntity(PHOTO_B, TEA_A, "tea-photos/$PHOTO_B.jpg", 1, false),
            TeaPhotoEntity(PHOTO_C, TEA_B, "tea-photos/$PHOTO_C.jpg", 0, true),
        )
        val archive = BackupArchiveExporter.BackupArchiveWriter.write(
            snapshot(photos), setOf(BackupExportSet.TEAS), UserPreferences(), "test", BACKUP, Instant.parse("2026-01-01T00:00:00Z"),
        )
        val validated = BackupArchiveValidator.validate(archive)
        assertEquals(3, validated.schemaVersion)
        assertEquals(setOf("photos/$PHOTO_A.jpg", "photos/$PHOTO_B.jpg", "photos/$PHOTO_C.jpg"), validated.entries.keys.filter { it.startsWith("photos/") }.toSet())
        val root = JSONObject(String(validated.entries.getValue("data/teas.json")))
        val rows = root.getJSONArray("photos")
        assertEquals(listOf(0, 1, 0), (0 until rows.length()).map { rows.getJSONObject(it).getInt("position") })
        assertEquals(listOf(true, false, true), (0 until rows.length()).map { rows.getJSONObject(it).getBoolean("isPrimary") })
        assertEquals("photos/$PHOTO_B.jpg", rows.getJSONObject(1).getString("path"))
        assertFalse(String(validated.entries.getValue("data/teas.json")).contains("base64"))
    }

    @Test fun targetPreparationStagesRestoredPhotoBytesAndPreservesMainAndOrder() {
        val incoming = write(snapshot(listOf(
            TeaPhotoEntity(PHOTO_A, TEA_A, "tea-photos/$PHOTO_A.jpg", 0, false),
            TeaPhotoEntity(PHOTO_B, TEA_A, "tea-photos/$PHOTO_B.jpg", 1, true),
        )), setOf(BackupExportSet.TEAS))
        val local = write(snapshot(emptyList()), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val plan = BackupTargetStateBuilder.build(BackupTargetRequest(incoming, local, TargetOperation.REPLACE, setOf("teas"), true))
        assertTrue(plan.blockers.joinToString(), plan.applicable)
        val targetRows = plan.state!!.photosToKeep.sortedBy { it.position }
        assertEquals(listOf(PHOTO_A.toString(), PHOTO_B.toString()), targetRows.map { it.id })
        assertEquals(listOf(false, true), targetRows.map { it.isPrimary })
        assertTrue(targetRows.all { it.archivePath != null })
        val archiveHash = sha(incoming)
        val localHash = plan.fingerprints.localSnapshotSha256
        val journalRoot = java.nio.file.Files.createTempDirectory("tea-photo-journal-").toFile()
        val mediaRoot = java.nio.file.Files.createTempDirectory("tea-photo-media-").toFile()
        try {
            val journal = DurableBackupOperationJournal(journalRoot, mediaRoot)
            val entry = journal.prepare(OPERATION.toString(), TargetOperation.REPLACE, setOf("teas"), incoming, plan.state,
                BackupTargetFingerprints(archiveHash, localHash, plan.fingerprints.operationSha256, plan.fingerprints.targetSha256))
            val bytes = journal.stagedMediaFile(entry, PHOTO_B.toString()).readBytes()
            assertArrayEquals(JPEG, bytes)
            assertTrue(BackupTargetStateBuilder.validatePreparedState(plan.state).isEmpty())
        } finally {
            journalRoot.deleteRecursively(); mediaRoot.deleteRecursively()
        }
    }

    @Test fun teaWithoutPhotoHasAnEmptyPhotoManifest() {
        val validated = BackupArchiveValidator.validate(write(snapshot(emptyList()), setOf(BackupExportSet.TEAS)))
        assertEquals(0, JSONObject(String(validated.entries.getValue("data/teas.json"))).getJSONArray("photos").length())
        assertTrue(validated.entries.keys.none { it.startsWith("photos/") })
    }

    @Test fun v1WithoutPhotosRemainsImportableAndKeepsLocalPhotos() {
        val incoming = legacyV1(write(snapshot(emptyList()), setOf(BackupExportSet.TEAS)))
        assertEquals(1, BackupArchiveValidator.validate(incoming).schemaVersion)
        val localPhotos = listOf(TargetPhotoRecord(PHOTO_A.toString(), TEA_A.toString(), "tea-photos/$PHOTO_A.jpg", 0, true))
        val local = write(snapshot(listOf(TeaPhotoEntity(PHOTO_A, TEA_A, localPhotos[0].relativePath, 0, true))), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val plan = BackupTargetStateBuilder.build(BackupTargetRequest(incoming, local, TargetOperation.REPLACE, setOf("teas"), true, photos = localPhotos))
        assertTrue(plan.blockers.joinToString(), plan.applicable)
        assertEquals(localPhotos, plan.state!!.photosToKeep)
    }

    @Test fun missingImageCorruptReferenceAndUnsafeArchiveAreRejectedBeforePlanning() {
        val valid = write(snapshot(listOf(TeaPhotoEntity(PHOTO_A, TEA_A, "tea-photos/$PHOTO_A.jpg", 0, true))), setOf(BackupExportSet.TEAS))
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(repack(valid) { it.remove("photos/$PHOTO_A.jpg") }) }
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(repack(valid) { it["photos/$PHOTO_A.jpg"] = byteArrayOf(1, 2, 3) }) }
        val badRef = repack(valid) { files ->
            val root = JSONObject(String(files.getValue("data/teas.json")))
            root.getJSONArray("photos").getJSONObject(0).put("path", "../outside.jpg")
            files["data/teas.json"] = root.toString().toByteArray()
        }
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(badRef) }
        assertThrows(BackupArchiveValidator.ValidationException::class.java) { BackupArchiveValidator.validate(zipOf("../outside.jpg" to JPEG)) }
    }

    @Test fun corruptArchiveProducesNoTargetAndLeavesLocalSnapshotBytesUntouched() {
        val local = write(snapshot(emptyList()), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val before = local.copyOf()
        val corrupt = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00)
        val plan = BackupTargetStateBuilder.build(BackupTargetRequest(corrupt, local, TargetOperation.REPLACE, setOf("teas"), true))
        assertFalse(plan.applicable)
        assertNull(plan.state)
        assertArrayEquals(before, local)
    }

    private fun snapshot(photos: List<TeaPhotoEntity>) = BackupArchiveExporter.Snapshot(
        references = listOf(ReferenceEntity(CATEGORY, "CATEGORY", "Thé vert", "the vert", null, null, "", null, 0)),
        teas = listOf(tea(TEA_A, "Sencha"), tea(TEA_B, "Gyokuro")), countries = emptyList(), regions = emptyList(), aromas = emptyList(),
        profiles = emptyList(), moments = emptyList(), badges = emptyList(), tastings = emptyList(), movements = emptyList(),
        photos = photos, photoBytes = photos.associate { it.relativePath to JPEG },
    )

    private fun tea(id: UUID, name: String) = TeaEntity(id, null, name, CATEGORY, null, null, null, null, null, null, null, null, null, null, null)
    private fun write(snapshot: BackupArchiveExporter.Snapshot, sets: Set<BackupExportSet>) = BackupArchiveExporter.BackupArchiveWriter.write(
        snapshot, sets, UserPreferences(), "test", BACKUP, Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun legacyV1(bytes: ByteArray): ByteArray = repack(bytes, schemaVersion = 1) { files ->
        val root = JSONObject(String(files.getValue("data/teas.json")))
        root.remove("photos")
        files["data/teas.json"] = root.toString().toByteArray()
        files.keys.filter { it.startsWith("photos/") }.toList().forEach(files::remove)
    }

    private fun repack(bytes: ByteArray, schemaVersion: Int? = null, update: (MutableMap<String, ByteArray>) -> Unit): ByteArray {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() }
        }
        files.remove("manifest.json")
        update(files)
        val manifest = JSONObject().put("format", BackupArchiveValidator.FORMAT)
            .put("schemaVersion", schemaVersion ?: BackupArchiveValidator.SCHEMA_VERSION)
            .put("backupId", BACKUP.toString()).put("createdAt", "2026-01-01T00:00:00Z")
            .put("producer", JSONObject().put("application", "ThésDuPalais").put("version", "test"))
            .put("includedSets", JSONArray(listOf("teas")))
        val rows = JSONArray()
        files.forEach { (path, data) -> rows.put(JSONObject().put("path", path).put("mediaType", if (path.endsWith(".jpg")) "image/jpeg" else "application/json")
            .put("sizeBytes", data.size).put("sha256", sha(data))) }
        manifest.put("entries", rows)
        return zipOf("manifest.json" to manifest.toString().toByteArray(), *files.map { it.key to it.value }.toTypedArray())
    }

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val BACKUP = UUID.fromString("123e4567-e89b-12d3-a456-426614174090")
        val OPERATION = UUID.fromString("123e4567-e89b-12d3-a456-426614174091")
        val CATEGORY = UUID.fromString("123e4567-e89b-12d3-a456-426614174092")
        val TEA_A = UUID.fromString("123e4567-e89b-12d3-a456-426614174093")
        val TEA_B = UUID.fromString("123e4567-e89b-12d3-a456-426614174094")
        val PHOTO_A = UUID.fromString("123e4567-e89b-12d3-a456-426614174095")
        val PHOTO_B = UUID.fromString("123e4567-e89b-12d3-a456-426614174096")
        val PHOTO_C = UUID.fromString("123e4567-e89b-12d3-a456-426614174097")
        val JPEG = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0x00, 0x0b, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00, 0xff.toByte(), 0xd9.toByte())
    }
}
