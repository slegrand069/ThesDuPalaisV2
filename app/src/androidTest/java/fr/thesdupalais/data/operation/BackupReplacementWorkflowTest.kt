package fr.thesdupalais.data.operation

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.thesdupalais.data.backup.BackupArchiveExporter
import fr.thesdupalais.data.backup.BackupExportSet
import fr.thesdupalais.data.backup.TargetOperation
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.TeaEntity
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
class BackupReplacementWorkflowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var root: File
    private lateinit var preferences: LocalPreferencesRepository
    private lateinit var exporter: BackupArchiveExporter
    private lateinit var journal: DurableBackupOperationJournal
    private lateinit var coordinator: BackupOperationWriteCoordinator
    private lateinit var finalizer: BackupOperationFinalizer
    private lateinit var workflow: BackupReplacementWorkflow
    private var injectDataStoreFailure = false
    private var injectBeforeDataStoreFailure = false

    @Before fun setUp() {
        root = File(context.cacheDir, "replacement-${UUID.randomUUID()}").apply { mkdirs() }
        database = openDatabase()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        journal = DurableBackupOperationJournal(File(root, "journal"), File(root, "media"))
        coordinator = BackupOperationWriteCoordinator { inspect() }
        val storeFile = File(root, "settings.preferences_pb")
        preferences = LocalPreferencesRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { storeFile }), coordinator)
        buildWorkflowServices()
    }

    @After fun tearDown() {
        database.close()
        scope.cancel()
        root.deleteRecursively()
    }

    @Test fun noConfirmationDoesNotCreateJournalOrChangeRoom() = runBlocking {
        seedLocalTea()
        val session = workflow.previewBytes(archive(emptySnapshot(), setOf(BackupExportSet.TEAS)))
        val selection = workflow.preview(session, setOf("teas"))
        val failure = runCatching { workflow.restore(session, selection, confirmed = false) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("confirmation"))
        assertEquals("Local", database.teaDao().findById(LOCAL_TEA)!!.name)
        assertTrue(journal.readAll().isEmpty())
        assertNull(database.backupOperationMarkerDao().findActive())
    }

    @Test fun staleLocalSnapshotIsRejectedBeforeDurablePreparation() = runBlocking {
        seedLocalTea()
        val session = workflow.previewBytes(archive(emptySnapshot(), setOf(BackupExportSet.TEAS)))
        val selection = workflow.preview(session, setOf("teas"))
        database.teaDao().update(tea(LOCAL_TEA, 101, "Changed after preview", CATEGORY))
        val failure = runCatching { workflow.restore(session, selection, confirmed = true) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("a changé depuis la prévisualisation"))
        assertEquals("Changed after preview", database.teaDao().findById(LOCAL_TEA)!!.name)
        assertTrue(journal.readAll().isEmpty())
        assertNull(database.backupOperationMarkerDao().findActive())
    }

    @Test fun confirmedReplacementUpdatesObservedRoomFlows() = runBlocking {
        seedLocalTea()
        val replacement = snapshot(tea(NEW_TEA, 202, "Restauré", CATEGORY))
        val session = workflow.previewBytes(archive(replacement, setOf(BackupExportSet.TEAS)))
        val selection = workflow.preview(session, setOf("teas"))
        val stages = mutableListOf<BackupReplacementProgress>()
        val result = workflow.restore(session, selection, confirmed = true) { stages += it }
        assertFalse(result.resumedAfterInterruption)
        assertNull(database.teaDao().findById(LOCAL_TEA))
        assertEquals("Restauré", database.teaDao().findById(NEW_TEA)!!.name)
        assertTrue(database.teaDao().observeActiveCatalog().first().any { it.tea.id == NEW_TEA })
        assertEquals(BackupOperationState.COMPLETE, journal.readAll().single().state)
        assertNull(database.backupOperationMarkerDao().findActive())
        assertTrue(stages.contains(BackupReplacementProgress.COMPLETE))
    }

    @Test fun interruptionAfterRoomCommitResumesAndReturnsOnlyAfterFullCompletion() = runBlocking {
        preferences.markInstallationInitialized()
        val session = workflow.previewBytes(archive(emptySnapshot(), setOf(BackupExportSet.CONFIGURATION), UserPreferences(9400, 37000)))
        val selection = workflow.preview(session, setOf("configuration"))
        injectDataStoreFailure = true
        val result = workflow.restore(session, selection, confirmed = true)
        assertTrue(result.resumedAfterInterruption)
        assertEquals(UserPreferences(9400, 37000), preferences.preferences.first())
        assertTrue(preferences.isInstallationInitialized())
        assertEquals(BackupOperationState.COMPLETE, journal.readAll().single().state)
        assertNull(database.backupOperationMarkerDao().findActive())
    }

    @Test fun interruptionAfterRoomCommitBeforeDataStoreResumesForward() = runBlocking {
        val session = workflow.previewBytes(archive(emptySnapshot(), setOf(BackupExportSet.CONFIGURATION), UserPreferences(9400, 37000)))
        val selection = workflow.preview(session, setOf("configuration"))
        injectBeforeDataStoreFailure = true

        val result = workflow.restore(session, selection, confirmed = true)

        assertTrue(result.resumedAfterInterruption)
        assertEquals(UserPreferences(9400, 37000), preferences.preferences.first())
        assertEquals(BackupOperationState.COMPLETE, journal.readAll().single().state)
        assertNull(database.backupOperationMarkerDao().findActive())
    }

    @Test fun selectiveTeaRestoreFromEmptyRoomAddsOnlyItsReferenceDependencies() = runBlocking {
        val status = reference(STATUS, "STATUS", "À tester")
        val category = reference(CATEGORY, "CATEGORY", "Thé vert")
        val snapshot = BackupArchiveExporter.Snapshot(
            references = listOf(category), teas = listOf(tea(NEW_TEA, 202, "Restauré", CATEGORY)),
            countries = emptyList(), regions = emptyList(), aromas = emptyList(), profiles = emptyList(),
            moments = emptyList(), badges = emptyList(), tastings = emptyList(), movements = emptyList(),
        )
        val bytes = archive(snapshot, setOf(BackupExportSet.TEAS))
        val session = workflow.previewBytes(bytes)
        val selection = workflow.preview(session, setOf("teas"))

        workflow.restore(session, selection, confirmed = true)

        assertEquals(1, database.teaDao().count())
        assertEquals(listOf(CATEGORY), database.referenceDao().allForBackup().map { it.id })
        assertEquals(UserPreferences(), preferences.preferences.first())
        assertEquals(BackupOperationState.COMPLETE, journal.readAll().single().state)
        assertNull(database.backupOperationMarkerDao().findActive())
    }

    @Test fun restoringFullArchiveIntoEmptyRoomWithMixedReferenceKindsCompletesAndSurvivesReopen() = runBlocking {
        val status = reference(STATUS, "STATUS", "À tester")
        val category = reference(CATEGORY, "CATEGORY", "Thé vert")
        val oldCollection = BackupArchiveExporter.Snapshot(
            references = listOf(status, category),
            teas = (1L..26L).map { index ->
                tea(UUID.nameUUIDFromBytes("historical-tea-$index".toByteArray()), index, "Thé historique $index", CATEGORY)
            },
            countries = emptyList(), regions = emptyList(), aromas = emptyList(), profiles = emptyList(),
            moments = emptyList(), badges = emptyList(), tastings = emptyList(), movements = emptyList(),
        )
        val bytes = archive(oldCollection, setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val session = workflow.previewBytes(bytes)
        val selection = workflow.preview(session, setOf("teas", "configuration"))
        val restored = workflow.restore(session, selection, confirmed = true)

        assertFalse(restored.resumedAfterInterruption)
        assertEquals(26, database.teaDao().count())
        assertEquals(2, database.referenceDao().allForBackup().size)
        assertEquals(UserPreferences(), preferences.preferences.first())
        assertEquals(BackupOperationState.COMPLETE, journal.readAll().single().state)
        assertNull(database.backupOperationMarkerDao().findActive())
        assertFalse(coordinator.withWritePermit { false })

        database.close()
        database = openDatabase()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = LocalPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") }),
            coordinator,
        )
        buildWorkflowServices()
        val recovered = recover()
        assertFalse(recovered.blocksWrites)
        assertEquals(26, database.teaDao().count())
        assertEquals(BackupOperationState.COMPLETE, journal.readAll().single().state)
        preferences.setLowStockThresholdMilli(26_000)
        assertEquals(26_000L, preferences.preferences.first().lowStockThresholdMilli)
    }

    private suspend fun seedLocalTea() {
        database.referenceDao().insert(reference(CATEGORY, "CATEGORY", "Catégorie"))
        database.teaDao().insert(tea(LOCAL_TEA, 101, "Local", CATEGORY))
    }

    private fun openDatabase() = Room.databaseBuilder(
        context, AppDatabase::class.java, File(root, "room.db").absolutePath,
    ).allowMainThreadQueries().build()

    private fun buildWorkflowServices() {
        exporter = BackupArchiveExporter(database, preferences, context.contentResolver)
        val applier = BackupRoomOperationApplier(database, exporter, preferences, journal, coordinator)
        finalizer = BackupOperationFinalizer(database, exporter, preferences, journal, coordinator, FinalizationFaultInjector { point ->
            if (injectBeforeDataStoreFailure && point == FinalizationFaultPoint.BEFORE_DATASTORE_EDIT) {
                injectBeforeDataStoreFailure = false
                error("Panne simulée avant DataStore")
            }
            if (injectDataStoreFailure && point == FinalizationFaultPoint.AFTER_DATASTORE_EDIT_BEFORE_FLAG) {
                injectDataStoreFailure = false
                error("Panne simulée après DataStore")
            }
        })
        workflow = BackupReplacementWorkflow(
            context.contentResolver, exporter, coordinator, journal, applier, finalizer,
            hasNoActiveMarker = { database.backupOperationMarkerDao().findActive() == null },
            recoverPendingOperation = { recover() },
        )
    }

    private suspend fun recover(): RecoveryInspection {
        val marker = database.backupOperationMarkerDao().findActive()
        if (marker != null) finalizer.finalize(marker.operationId)
        val all = journal.readAll()
        val currentMarker = database.backupOperationMarkerDao().findActive()
        val active = if (currentMarker != null) all else all.filterNot { it.state in setOf(BackupOperationState.COMPLETE, BackupOperationState.ABORTED) }
        return BackupOperationRecoveryInspector.inspect(
            active.map { it.operationId }.toSet(), currentMarker?.operationId,
            journalStates = active.associate { it.operationId to it.state }, roomMarkerState = currentMarker?.state,
        )
    }

    private suspend fun inspect(): RecoveryInspection {
        val pending = journal.readPending()
        val marker = database.backupOperationMarkerDao().findActive()
        return BackupOperationRecoveryInspector.inspect(
            pending.map { it.operationId }.toSet(), marker?.operationId,
            journalStates = pending.associate { it.operationId to it.state }, roomMarkerState = marker?.state,
        )
    }

    private fun archive(snapshot: BackupArchiveExporter.Snapshot, sets: Set<BackupExportSet>, prefs: UserPreferences = UserPreferences()) =
        BackupArchiveExporter.BackupArchiveWriter.write(snapshot, sets, prefs, "instrumented-test", UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"))

    private fun snapshot(tea: TeaEntity? = null) = BackupArchiveExporter.Snapshot(
        references = listOf(reference(CATEGORY, "CATEGORY", "Catégorie")), teas = listOfNotNull(tea),
        countries = emptyList(), regions = emptyList(), aromas = emptyList(), profiles = emptyList(), moments = emptyList(), badges = emptyList(), tastings = emptyList(), movements = emptyList(),
    )

    private fun emptySnapshot() = BackupArchiveExporter.Snapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    private fun reference(id: UUID, kind: String, label: String) = ReferenceEntity(id, kind, label, ReferenceLabelNormalizer.normalize(label), null, null, "", null, 0)
    private fun tea(id: UUID, legacyId: Long, name: String, category: UUID) = TeaEntity(id, legacyId, name, category, null, null, null, null, null, null, null, null, null, null, null)

    private companion object {
        val CATEGORY = UUID.fromString("123e4567-e89b-12d3-a456-426614174200")
        val STATUS = UUID.fromString("023e4567-e89b-12d3-a456-426614174200")
        val LOCAL_TEA = UUID.fromString("123e4567-e89b-12d3-a456-426614174201")
        val NEW_TEA = UUID.fromString("123e4567-e89b-12d3-a456-426614174202")
    }
}
