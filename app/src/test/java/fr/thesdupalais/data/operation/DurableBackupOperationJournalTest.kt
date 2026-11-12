package fr.thesdupalais.data.operation

import fr.thesdupalais.data.backup.BackupArchiveExporter
import fr.thesdupalais.data.backup.BackupExportSet
import fr.thesdupalais.data.backup.BackupTargetState
import fr.thesdupalais.data.backup.BackupTargetStateBuilder
import fr.thesdupalais.data.backup.BackupTargetFingerprints
import fr.thesdupalais.data.backup.TargetPhotoRecord
import fr.thesdupalais.data.backup.TargetOperation
import fr.thesdupalais.data.preferences.UserPreferences
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableBackupOperationJournalTest {
    private val roots = mutableListOf<File>()

    @After fun cleanUp() { roots.forEach(File::deleteRecursively) }

    @Test fun preparationIsDurableVerifiedAndIdempotent() {
        val journal = journal()
        val archive = archive()
        val target = target()
        val fingerprint = fingerprints(archive, target)
        val entry = journal.prepare(OP_ID, TargetOperation.REPLACE, setOf("teas", "configuration"), archive, target, fingerprint)
        assertEquals(BackupOperationState.PREPARED, entry.state)
        assertTrue(journal.readArchive(entry).contentEquals(archive))
        assertEquals(1, journal.readPending().size)
        assertEquals(entry, journal.prepare(OP_ID, TargetOperation.REPLACE, setOf("configuration", "teas"), archive, target, fingerprint))

        val applying = journal.transition(OP_ID, BackupOperationState.ROOM_APPLYING)
        assertEquals(BackupOperationState.ROOM_APPLYING, applying.state)
        assertEquals(applying, journal.transition(OP_ID, BackupOperationState.ROOM_APPLYING))
        assertTrue(journal.readPending().isNotEmpty())
    }

    @Test fun simulatedPreparationCrashesNeverProducePreparedOrCompleteState() {
        PreparationFaultPoint.entries.forEachIndexed { index, failingPoint ->
            val root = newRoot("fault-$index")
            var injected = false
            val faulty = DurableBackupOperationJournal(root, faultInjector = PreparationFaultInjector { point ->
                if (!injected && point == failingPoint) { injected = true; throw IOException("simulated crash at $point") }
            })
            assertThrows(IOException::class.java) {
                val state = target()
                val archiveBytes = archive()
                faulty.prepare("123e4567-e89b-12d3-a456-42661417400${index + 1}", TargetOperation.MERGE, setOf("teas"), archiveBytes, state, fingerprints(archiveBytes, state))
            }
            assertTrue(injected)
            val entryBeforeRetry = faulty.readPending().single()
            assertEquals(BackupOperationState.PREPARING, entryBeforeRetry.state)

            val retryTarget = target()
            val retryArchive = archive()
            val recoveredByRetry = DurableBackupOperationJournal(root).prepare(
                entryBeforeRetry.operationId, TargetOperation.MERGE, setOf("teas"), retryArchive, retryTarget, fingerprints(retryArchive, retryTarget),
            )
            assertEquals(BackupOperationState.PREPARED, recoveredByRetry.state)
            assertFalse(faulty.readPending().isEmpty())
        }
    }

    @Test fun artifactTamperingIsDetectedBeforeReading() {
        val root = newRoot("tamper")
        val journal = DurableBackupOperationJournal(root)
        val state = target()
        val archiveBytes = archive()
        val entry = journal.prepare(OP_ID, TargetOperation.REPLACE, setOf("teas"), archiveBytes, state, fingerprints(archiveBytes, state))
        File(root, "$OP_ID/${DurableBackupOperationJournal.ARCHIVE_NAME}").writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(IOException::class.java) { journal.readArchive(entry) }
    }

    @Test fun mediaForRemovedTeaIsCopiedIntoPrivateOperationWorkspaceAndVerified() {
        val root = newRoot("media-journal")
        val mediaRoot = newRoot("media-source")
        val source = File(mediaRoot, "tea-photos/old.jpg")
        source.parentFile!!.mkdirs()
        val contents = byteArrayOf(10, 20, 30, 40)
        source.writeBytes(contents)
        val photo = TargetPhotoRecord(PHOTO_ID, TEA_ID, "tea-photos/old.jpg", 0)
        val target = target().copy(photosToQuarantine = listOf(photo))
        val bytes = archive()
        val journal = DurableBackupOperationJournal(root, mediaRoot)
        val entry = journal.prepare(OP_ID, TargetOperation.REPLACE, setOf("teas"), bytes, target, fingerprints(bytes, target))
        assertEquals(1, entry.mediaArtifacts.size)
        assertTrue(journal.stagedMediaFile(entry, PHOTO_ID).readBytes().contentEquals(contents))
        journal.stagedMediaFile(entry, PHOTO_ID).writeBytes(byteArrayOf(1))
        assertThrows(IOException::class.java) { journal.verifyArtifacts(entry) }
    }

    private fun journal() = DurableBackupOperationJournal(newRoot("normal"), nowEpochMillis = { 100L })
    private fun newRoot(name: String): File = Files.createTempDirectory("backup-operation-$name").toFile().also(roots::add)
    private fun target() = BackupTargetState(
        payloads = mapOf("data/configuration.json" to "{\"preferences\":{}}", "data/teas.json" to "{\"teas\":[]}"),
        photosToKeep = emptyList(),
        supersededImportDatasetIds = setOf("legacy-set"),
    )
    private fun fingerprints(archive: ByteArray, target: BackupTargetState): BackupTargetFingerprints = BackupTargetFingerprints(
        archiveSha256 = sha(archive),
        localSnapshotSha256 = "c".repeat(64),
        operationSha256 = "b".repeat(64),
        targetSha256 = BackupTargetStateBuilder.fingerprintTargetState(target),
    )
    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun archive(): ByteArray = BackupArchiveExporter.BackupArchiveWriter.write(
        BackupArchiveExporter.Snapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION), UserPreferences(), "test", BACKUP_ID, Instant.parse("2026-01-01T00:00:00Z"),
    )

    private companion object {
        val BACKUP_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        const val OP_ID = "123e4567-e89b-12d3-a456-426614174001"
        const val PHOTO_ID = "123e4567-e89b-12d3-a456-426614174002"
        const val TEA_ID = "123e4567-e89b-12d3-a456-426614174003"
    }
}
