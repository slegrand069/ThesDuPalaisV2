package fr.thesdupalais.data.operation

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.thesdupalais.data.backup.BackupArchiveExporter
import fr.thesdupalais.data.backup.BackupExportSet
import fr.thesdupalais.data.backup.BackupTargetRequest
import fr.thesdupalais.data.backup.BackupTargetStateBuilder
import fr.thesdupalais.data.backup.ConfirmedMergeChange
import fr.thesdupalais.data.backup.TargetOperation
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.ImportedDatasetEntity
import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.StockMovementEntity
import fr.thesdupalais.data.database.TastingEntity
import fr.thesdupalais.data.database.TeaCountryEntity
import fr.thesdupalais.data.database.TeaEntity
import fr.thesdupalais.data.database.TeaPhotoEntity
import fr.thesdupalais.data.preferences.LocalPreferencesRepository
import fr.thesdupalais.data.preferences.UserPreferences
import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRoomOperationApplierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var tempRoot: File
    private lateinit var preferences: LocalPreferencesRepository
    private lateinit var exporter: BackupArchiveExporter
    private lateinit var journal: DurableBackupOperationJournal
    private lateinit var coordinator: BackupOperationWriteCoordinator
    private var fault: RoomApplyFaultPoint? = null
    private lateinit var applier: BackupRoomOperationApplier

    @Before fun setUp() {
        tempRoot = File(context.cacheDir, "room-apply-${UUID.randomUUID()}").apply { mkdirs() }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val storeFile = File(tempRoot, "settings.preferences_pb")
        preferences = LocalPreferencesRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { storeFile }))
        exporter = BackupArchiveExporter(database, preferences, context.contentResolver)
        journal = DurableBackupOperationJournal(File(tempRoot, "journal"), File(tempRoot, "media"))
        coordinator = BackupOperationWriteCoordinator {
            val pending = journal.readPending()
            val marker = database.backupOperationMarkerDao().findActive()
            BackupOperationRecoveryInspector.inspect(
                pending.map { it.operationId }.toSet(), marker?.operationId,
                journalStates = pending.associate { it.operationId to it.state }, roomMarkerState = marker?.state,
            )
        }
        applier = BackupRoomOperationApplier(database, exporter, preferences, journal, coordinator, faultInjector = RoomApplyFaultInjector { point ->
            if (fault == point) error("Panne simulée à $point")
        })
    }

    @After fun tearDown() {
        database.close()
        scope.cancel()
        tempRoot.deleteRecursively()
    }

    @Test fun selectedTeaReplacementDeletesHistoryAtomicallyAndKeepsOriginalPhotoFile() = runBlocking {
        val category = reference(CATEGORY, "CATEGORY", "Catégorie")
        val tea = tea(OLD_TEA, 101, "Ancien thé", CATEGORY, 1000)
        val photoFile = File(tempRoot, "media/photos/old.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4)) }
        database.referenceDao().insert(category)
        database.teaDao().insert(tea)
        database.teaPhotoDao().insert(TeaPhotoEntity(PHOTO, OLD_TEA, "photos/old.jpg", 0))
        database.tastingDao().insert(TastingEntity(TASTING, OLD_TEA, 1000, 1001, 100))
        database.stockMovementDao().insert(StockMovementEntity(MOVEMENT, OLD_TEA, "INVENTORY", null, 1000, 1000, 1001, null, null))
        database.importedDatasetDao().insert(ImportedDatasetEntity(DATASET, 1, "a".repeat(64), 1, 2, 1, 1, 1, 1, 7000, 25000))
        val incoming = archive(emptySnapshot(), setOf(BackupExportSet.TEAS))
        val prepared = prepare(incoming, TargetOperation.REPLACE, setOf("teas"), replacementConfirmed = true)
        val result = applier.apply(prepared.first, prepared.second)
        assertFalse(result.alreadyCommitted)
        assertEquals(0, database.teaDao().count())
        assertEquals(0, database.tastingDao().count())
        assertEquals(0, database.stockMovementDao().count())
        assertTrue(photoFile.isFile)
        assertTrue(journal.stagedMediaFile(journal.readPending().single(), PHOTO.toString()).isFile)
        assertEquals(BackupOperationState.ROOM_COMMITTED, journal.readPending().single().state)
        assertEquals("ROOM_COMMITTED", database.backupOperationMarkerDao().findActive()!!.state)
        assertTrue(database.importedDatasetDao().allForBackup().single().isSuperseded)
    }

    @Test fun configurationOnlyReplacementPreservesTeaAssociationsAndPhotosAndDoesNotWriteDataStore() = runBlocking {
        val category = reference(CATEGORY, "CATEGORY", "Catégorie")
        val status = reference(STATUS, "STATUS", "À boire")
        database.referenceDao().insert(category)
        database.referenceDao().insert(status)
        database.teaDao().insert(tea(OLD_TEA, 101, "Thé local", CATEGORY))
        database.teaPhotoDao().insert(TeaPhotoEntity(PHOTO, OLD_TEA, "photos/keep.jpg", 0))
        val originalPrefs = preferences.preferences.first()
        val incoming = archive(BackupArchiveExporter.Snapshot(
            references = listOf(reference(STATUS, "STATUS", "À déguster")), teas = emptyList(),
            countries = emptyList(), regions = emptyList(), aromas = emptyList(), profiles = emptyList(), moments = emptyList(), badges = emptyList(), tastings = emptyList(), movements = emptyList(),
        ), setOf(BackupExportSet.CONFIGURATION), UserPreferences(8100, 30000))
        val prepared = prepare(incoming, TargetOperation.REPLACE, setOf("configuration"), replacementConfirmed = true)
        applier.apply(prepared.first, prepared.second)
        assertEquals("Thé local", database.teaDao().findById(OLD_TEA)!!.name)
        assertEquals(1, database.teaPhotoDao().allForBackup().size)
        assertEquals(originalPrefs, preferences.preferences.first())
        assertEquals("À déguster", database.referenceDao().findById(STATUS)!!.label)
    }

    @Test fun replacementOfBothSetsChangesTeasAndConfigurationTogether() = runBlocking {
        database.referenceDao().insert(reference(CATEGORY, "CATEGORY", "Ancienne catégorie"))
        database.teaDao().insert(tea(OLD_TEA, 101, "Ancien thé", CATEGORY))
        val incoming = archive(BackupArchiveExporter.Snapshot(
            references = listOf(reference(NEW_CATEGORY, "CATEGORY", "Nouvelle catégorie")),
            teas = listOf(tea(NEW_TEA, 202, "Nouveau thé", NEW_CATEGORY)), countries = emptyList(), regions = emptyList(),
            aromas = emptyList(), profiles = emptyList(), moments = emptyList(), badges = emptyList(), tastings = emptyList(), movements = emptyList(),
        ), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val prepared = prepare(incoming, TargetOperation.REPLACE, setOf("teas", "configuration"), replacementConfirmed = true)
        applier.apply(prepared.first, prepared.second)
        assertNull(database.teaDao().findById(OLD_TEA))
        assertEquals("Nouveau thé", database.teaDao().findById(NEW_TEA)!!.name)
        assertNull(database.referenceDao().findById(CATEGORY))
        assertEquals("Nouvelle catégorie", database.referenceDao().findById(NEW_CATEGORY)!!.label)
    }

    @Test fun mergeAddsTeaAssociationAndHistoricalTastingOnce() = runBlocking {
        database.referenceDao().insert(reference(CATEGORY, "CATEGORY", "Catégorie"))
        database.teaDao().insert(tea(OLD_TEA, 101, "Nom local", CATEGORY))
        val country = reference(COUNTRY, "COUNTRY", "Japon")
        val updatedTea = tea(OLD_TEA, 101, "Nom archive", CATEGORY)
        val newTea = tea(NEW_TEA, 202, "Thé ajouté", CATEGORY, stock = 1000)
        val incoming = archive(BackupArchiveExporter.Snapshot(
            references = listOf(reference(CATEGORY, "CATEGORY", "Catégorie"), country), teas = listOf(updatedTea, newTea),
            countries = listOf(TeaCountryEntity(OLD_TEA, COUNTRY), TeaCountryEntity(NEW_TEA, COUNTRY)), regions = emptyList(), aromas = emptyList(), profiles = emptyList(), moments = emptyList(), badges = emptyList(),
            tastings = listOf(TastingEntity(NEW_TASTING, NEW_TEA, 2000, 2001, null)),
            movements = listOf(StockMovementEntity(MOVEMENT, NEW_TEA, "INVENTORY", null, 1000, 2000, 2001, null, null)),
        ), setOf(BackupExportSet.TEAS))
        val prepared = prepare(incoming, TargetOperation.MERGE, setOf("teas"), confirmedChanges = setOf(ConfirmedMergeChange("Thé", OLD_TEA.toString(), "name")))
        applier.apply(prepared.first, prepared.second)
        assertEquals("Nom archive", database.teaDao().findById(OLD_TEA)!!.name)
        assertEquals("Thé ajouté", database.teaDao().findById(NEW_TEA)!!.name)
        assertTrue(database.teaDao().allCountriesForBackup().contains(TeaCountryEntity(OLD_TEA, COUNTRY)))
        assertTrue(database.teaDao().allCountriesForBackup().contains(TeaCountryEntity(NEW_TEA, COUNTRY)))
        assertEquals(listOf(NEW_TASTING), database.tastingDao().allForBackup().map { it.id })
        assertEquals(listOf(MOVEMENT), database.stockMovementDao().allForBackup().map { it.id })
        applier.apply(prepared.first, prepared.second)
        assertEquals(1, database.tastingDao().count())
        assertEquals(1, database.stockMovementDao().count())
    }

    @Test fun exceptionInsideRoomTransactionRollsBackAllRowsAndMarker() = runBlocking {
        database.referenceDao().insert(reference(CATEGORY, "CATEGORY", "Catégorie"))
        val incoming = archive(BackupArchiveExporter.Snapshot(
            references = listOf(reference(CATEGORY, "CATEGORY", "Catégorie")), teas = listOf(tea(NEW_TEA, 202, "Nouveau", CATEGORY)),
            countries = emptyList(), regions = emptyList(), aromas = emptyList(), profiles = emptyList(), moments = emptyList(), badges = emptyList(), tastings = emptyList(), movements = emptyList(),
        ), setOf(BackupExportSet.TEAS))
        val prepared = prepare(incoming, TargetOperation.REPLACE, setOf("teas"), replacementConfirmed = true)
        fault = RoomApplyFaultPoint.AFTER_TARGET_ROWS_BEFORE_MARKER
        val failure = runCatching { applier.apply(prepared.first, prepared.second) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Panne simulée"))
        assertEquals(0, database.teaDao().count())
        assertNull(database.backupOperationMarkerDao().findActive())
        assertEquals(BackupOperationState.ABORTED, journal.readAll().single().state)
    }

    @Test fun interruptionAfterRoomCommitIsRecoveredByMarkerWithoutDoubleApplyingRows() = runBlocking {
        val incoming = archive(BackupArchiveExporter.Snapshot(
            references = listOf(reference(CATEGORY, "CATEGORY", "Catégorie")), teas = listOf(tea(NEW_TEA, 202, "Nouveau", CATEGORY)),
            countries = emptyList(), regions = emptyList(), aromas = emptyList(), profiles = emptyList(), moments = emptyList(), badges = emptyList(), tastings = listOf(TastingEntity(NEW_TASTING, NEW_TEA, 2000, 2001, null)), movements = emptyList(),
        ), setOf(BackupExportSet.TEAS))
        val prepared = prepare(incoming, TargetOperation.REPLACE, setOf("teas"), replacementConfirmed = true)
        fault = RoomApplyFaultPoint.AFTER_ROOM_COMMIT_BEFORE_JOURNAL
        assertTrue(runCatching { applier.apply(prepared.first, prepared.second) }.isFailure)
        assertEquals(1, database.teaDao().count())
        assertEquals(1, database.tastingDao().count())
        assertEquals(BackupOperationState.ROOM_APPLYING, journal.readAll().single().state)
        fault = null
        val retry = applier.apply(prepared.first, prepared.second)
        assertTrue(retry.alreadyCommitted)
        assertEquals(1, database.teaDao().count())
        assertEquals(1, database.tastingDao().count())
        assertEquals(BackupOperationState.ROOM_COMMITTED, journal.readAll().single().state)
    }

    @Test fun staleLocalSnapshotIsRejectedBeforeAnyRoomChange() = runBlocking {
        database.referenceDao().insert(reference(CATEGORY, "CATEGORY", "Catégorie"))
        database.teaDao().insert(tea(OLD_TEA, 101, "Local", CATEGORY))
        val incoming = archive(emptySnapshot(), setOf(BackupExportSet.TEAS))
        val prepared = prepare(incoming, TargetOperation.REPLACE, setOf("teas"), replacementConfirmed = true)
        database.teaDao().update(tea(OLD_TEA, 101, "Modifié après prévisualisation", CATEGORY))
        val failure = runCatching { applier.apply(prepared.first, prepared.second) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("périmée"))
        assertEquals("Modifié après prévisualisation", database.teaDao().findById(OLD_TEA)!!.name)
        assertNull(database.backupOperationMarkerDao().findActive())
        assertEquals(BackupOperationState.ABORTED, journal.readAll().single().state)
    }

    private suspend fun prepare(
        archive: ByteArray,
        operation: TargetOperation,
        selected: Set<String>,
        replacementConfirmed: Boolean = false,
        confirmedChanges: Set<ConfirmedMergeChange> = emptySet(),
    ): Pair<String, ConfirmedRoomOperation> {
        val local = exporter.captureCurrentOperationSnapshot()
        val plan = if (operation == TargetOperation.MERGE) fr.thesdupalais.data.backup.BackupArchiveMergePlanner.plan(archive, local.archiveBytes, selected) else null
        val result = BackupTargetStateBuilder.build(BackupTargetRequest(
            archiveBytes = archive, localSnapshotBytes = local.archiveBytes, operation = operation, selectedSets = selected,
            replacementConfirmed = replacementConfirmed, confirmedChanges = confirmedChanges, photos = local.photos,
            importHistory = local.importHistory, planner = plan,
        ))
        assertTrue(result.blockers.joinToString(), result.applicable)
        val id = UUID.randomUUID().toString()
        journal.prepare(id, operation, selected, archive, requireNotNull(result.state), result.fingerprints)
        return id to ConfirmedRoomOperation(operation, selected, result.fingerprints)
    }

    private fun archive(snapshot: BackupArchiveExporter.Snapshot, selected: Set<BackupExportSet>, prefs: UserPreferences = UserPreferences()) =
        BackupArchiveExporter.BackupArchiveWriter.write(snapshot, selected, prefs, "instrumented-test", UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"))

    private fun emptySnapshot() = BackupArchiveExporter.Snapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    private fun reference(id: UUID, kind: String, label: String) = ReferenceEntity(id, kind, label, ReferenceLabelNormalizer.normalize(label), null, null, "", null, 0)

    private fun tea(id: UUID, legacy: Long, name: String, category: UUID, stock: Long? = null) =
        TeaEntity(id, legacy, name, category, null, null, null, null, null, null, null, null, null, stock, null)

    private companion object {
        val CATEGORY = UUID.fromString("123e4567-e89b-12d3-a456-426614174100")
        val STATUS = UUID.fromString("123e4567-e89b-12d3-a456-426614174102")
        val NEW_CATEGORY = UUID.fromString("123e4567-e89b-12d3-a456-426614174103")
        val COUNTRY = UUID.fromString("123e4567-e89b-12d3-a456-426614174101")
        val OLD_TEA = UUID.fromString("123e4567-e89b-12d3-a456-426614174110")
        val NEW_TEA = UUID.fromString("123e4567-e89b-12d3-a456-426614174111")
        val PHOTO = UUID.fromString("123e4567-e89b-12d3-a456-426614174120")
        val TASTING = UUID.fromString("123e4567-e89b-12d3-a456-426614174130")
        val MOVEMENT = UUID.fromString("123e4567-e89b-12d3-a456-426614174131")
        val NEW_TASTING = UUID.fromString("123e4567-e89b-12d3-a456-426614174132")
        val DATASET = UUID.fromString("123e4567-e89b-12d3-a456-426614174140")
    }
}
