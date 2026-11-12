package fr.thesdupalais.data.operation

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.thesdupalais.data.backup.BackupArchiveExporter
import fr.thesdupalais.data.backup.BackupExportSet
import fr.thesdupalais.data.backup.BackupTargetStateBuilder
import fr.thesdupalais.data.backup.ConfirmedMergeChange
import fr.thesdupalais.data.backup.TargetOperation
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.TastingEntity
import fr.thesdupalais.data.database.TeaAromaEntity
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
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class BackupMergeWorkflowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File
    private lateinit var database: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var journal: DurableBackupOperationJournal
    private lateinit var coordinator: BackupOperationWriteCoordinator
    private lateinit var preferences: LocalPreferencesRepository
    private lateinit var exporter: BackupArchiveExporter
    private lateinit var applier: BackupRoomOperationApplier
    private lateinit var finalizer: BackupOperationFinalizer
    private lateinit var workflow: BackupMergeWorkflow

    @Before fun setUp() {
        root = File(context.cacheDir, "merge-workflow-${UUID.randomUUID()}").apply { mkdirs() }
        database = openDatabase()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        journal = DurableBackupOperationJournal(File(root, "journal"), File(root, "media"))
        coordinator = BackupOperationWriteCoordinator { inspect() }
        preferences = createPreferences()
        buildServices()
    }

    @After fun tearDown() {
        database.close()
        scope.cancel()
        root.deleteRecursively()
    }

    @Test fun addsAndUpdatesConfirmedFieldsWhilePreservingLocalItemsAndAssociations() = runBlocking {
        val category = reference(CATEGORY, "CATEGORY", "Thé vert")
        val localAroma = reference(LOCAL_AROMA, "AROMA", "Agrumes")
        val incomingAroma = reference(INCOMING_AROMA, "AROMA", "Amande")
        database.referenceDao().insert(category)
        database.referenceDao().insert(localAroma)
        database.teaDao().insert(tea(EXISTING_TEA, 101, "Nom local", CATEGORY))
        database.teaDao().insert(tea(LOCAL_ONLY_TEA, 102, "Thé local conservé", CATEGORY))
        database.teaDao().insertAromas(listOf(TeaAromaEntity(EXISTING_TEA, LOCAL_AROMA)))
        database.tastingDao().insert(TastingEntity(LOCAL_TASTING, EXISTING_TEA, 1000, 1001, 4))

        val archive = archive(
            references = listOf(category, incomingAroma),
            teas = listOf(
                tea(EXISTING_TEA, 101, "Nom d’archive", CATEGORY),
                tea(NEW_TEA, 103, "Nouveau thé", CATEGORY),
            ),
            aromas = listOf(TeaAromaEntity(EXISTING_TEA, INCOMING_AROMA)),
            tastings = listOf(TastingEntity(NEW_TASTING, EXISTING_TEA, 2000, 2001, 8)),
        )
        val session = workflow.previewBytes(archive)
        val decisions = setOf(ConfirmedMergeChange("Thé", EXISTING_TEA.toString(), "name"))
        val preview = workflow.preview(session, setOf("teas"), decisions)
        assertTrue(preview.target.blockers.joinToString(), preview.applicable)
        assertTrue(preview.mergePlan.items.any { it.disposition == fr.thesdupalais.data.backup.MergeDisposition.PRESERVED && it.identity == LOCAL_ONLY_TEA.toString() })

        assertTrue(runCatching { workflow.apply(session, preview, confirmed = false) }.isFailure)
        assertTrue(journal.readAll().isEmpty())
        workflow.apply(session, preview, confirmed = true)

        assertEquals(3, database.teaDao().count())
        assertEquals("Nom d’archive", database.teaDao().findById(EXISTING_TEA)!!.name)
        assertEquals("Thé local conservé", database.teaDao().findById(LOCAL_ONLY_TEA)!!.name)
        assertEquals(setOf(LOCAL_AROMA, INCOMING_AROMA), database.teaDao().allAromasForBackup().filter { it.teaId == EXISTING_TEA }.map { it.aromaRefId }.toSet())
        assertEquals(setOf(LOCAL_TASTING, NEW_TASTING), database.tastingDao().allForBackup().map { it.id }.toSet())
        assertEquals(BackupOperationState.COMPLETE, journal.readAll().single().state)
        assertNull(database.backupOperationMarkerDao().findActive())
    }

    @Test fun blockingHistoryIdentityConflictCannotBeConfirmedAndCreatesNoJournal() = runBlocking {
        database.referenceDao().insert(reference(CATEGORY, "CATEGORY", "Thé vert"))
        database.teaDao().insert(tea(EXISTING_TEA, 101, "Local", CATEGORY))
        database.tastingDao().insert(TastingEntity(LOCAL_TASTING, EXISTING_TEA, 1000, 1001, 3))
        val session = workflow.previewBytes(archive(
            listOf(reference(CATEGORY, "CATEGORY", "Thé vert")),
            listOf(tea(EXISTING_TEA, 101, "Local", CATEGORY)),
            tastings = listOf(TastingEntity(LOCAL_TASTING, EXISTING_TEA, 1000, 1001, 9)),
        ))
        val preview = workflow.preview(session, setOf("teas"), emptySet())

        assertFalse(preview.applicable)
        assertTrue(preview.mergePlan.blockingConflicts.any { it.contains("même UUID avec un contenu différent") })
        assertTrue(runCatching { workflow.apply(session, preview, confirmed = true) }.isFailure)
        assertTrue(journal.readAll().isEmpty())
        assertEquals(3L, database.tastingDao().allForBackup().single().consumedMilliGrams)
    }

    @Test fun stalePreviewIsRejectedBeforeDurablePreparation() = runBlocking {
        database.referenceDao().insert(reference(CATEGORY, "CATEGORY", "Thé vert"))
        database.teaDao().insert(tea(EXISTING_TEA, 101, "Local", CATEGORY))
        val session = workflow.previewBytes(archive(
            listOf(reference(CATEGORY, "CATEGORY", "Thé vert")),
            listOf(tea(NEW_TEA, 103, "À ajouter", CATEGORY)),
        ))
        val preview = workflow.preview(session, setOf("teas"), emptySet())
        database.teaDao().update(tea(EXISTING_TEA, 101, "Modifié après prévisualisation", CATEGORY))

        val failure = runCatching { workflow.apply(session, preview, confirmed = true) }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("a changé depuis la prévisualisation"))
        assertTrue(journal.readAll().isEmpty())
        assertEquals("Modifié après prévisualisation", database.teaDao().findById(EXISTING_TEA)!!.name)
    }

    @Test fun configurationOnlyMergePreservesLocalTeaAndAppliesOnlyConfirmedPreference() = runBlocking {
        database.referenceDao().insert(reference(CATEGORY, "CATEGORY", "Thé vert"))
        database.teaDao().insert(tea(EXISTING_TEA, 101, "Conservé", CATEGORY))
        val archive = archive(
            references = listOf(reference(STATUS, "STATUS", "À tester")),
            teas = emptyList(),
            sets = setOf(BackupExportSet.CONFIGURATION),
            preferences = UserPreferences(9000, 40000),
        )
        val session = workflow.previewBytes(archive)
        val decisions = setOf(ConfirmedMergeChange("Préférences métier", "configuration", "quantityPerCupMilliGrams"))
        val preview = workflow.preview(session, setOf("configuration"), decisions)
        assertTrue(preview.target.blockers.joinToString(), preview.applicable)
        workflow.apply(session, preview, confirmed = true)

        assertEquals("Conservé", database.teaDao().findById(EXISTING_TEA)!!.name)
        assertTrue(database.referenceDao().findById(CATEGORY) != null)
        assertTrue(database.referenceDao().findById(STATUS) != null)
        assertEquals(UserPreferences(9000, UserPreferences.DEFAULT_LOW_STOCK_THRESHOLD_MILLI), preferences.preferences.first())
    }

    @Test fun committedMergeResumesAfterDatabaseAndDataStoreAreReopened() = runBlocking {
        val originalPrefs = UserPreferences()
        val archive = archive(
            references = emptyList(), teas = emptyList(), sets = setOf(BackupExportSet.CONFIGURATION),
            preferences = UserPreferences(9200, 41000),
        )
        val session = workflow.previewBytes(archive)
        val choices = setOf(ConfirmedMergeChange("Préférences métier", "configuration", "quantityPerCupMilliGrams"))
        val selection = workflow.preview(session, setOf("configuration"), choices)
        assertTrue(selection.target.blockers.joinToString(), selection.applicable)

        val operationId = UUID.randomUUID().toString()
        journal.prepare(operationId, TargetOperation.MERGE, selection.selectedSets, session.archiveCopy(), requireNotNull(selection.target.state), selection.target.fingerprints)
        applier.apply(operationId, ConfirmedRoomOperation(TargetOperation.MERGE, selection.selectedSets, selection.target.fingerprints))
        assertEquals(originalPrefs, preferences.preferences.first())
        assertNotNullMarker(operationId)

        database.close()
        scope.cancel()
        database = openDatabase()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = createPreferences()
        buildServices()
        val recovered = recover()

        assertFalse(recovered.blocksWrites)
        assertEquals(UserPreferences(9200, originalPrefs.lowStockThresholdMilli), preferences.preferences.first())
        assertEquals(BackupOperationState.COMPLETE, journal.readAll().single().state)
        assertNull(database.backupOperationMarkerDao().findActive())
        preferences.setLowStockThresholdMilli(26000)
    }

    @Test fun mergeWithLocalOnlyTeaAndUnusedConfigurationRefsRecoversAndVerifiesAfterReopen() = runBlocking {
        val unusedStatus = reference(STATUS, "STATUS", "Statut non utilisé")
        database.referenceDao().insert(reference(CATEGORY, "CATEGORY", "Thé vert"))
        database.referenceDao().insert(reference(LOCAL_AROMA, "AROMA", "Arôme local"))
        database.teaDao().insert(tea(EXISTING_TEA, 101, "Nom local", CATEGORY))
        database.teaDao().insert(tea(LOCAL_ONLY_TEA, 102, "Thé conservé", CATEGORY))
        database.teaDao().insertAromas(listOf(TeaAromaEntity(LOCAL_ONLY_TEA, LOCAL_AROMA)))
        database.tastingDao().insert(TastingEntity(LOCAL_TASTING, LOCAL_ONLY_TEA, 1000, 1001, 5))

        val archive = archive(
            references = listOf(reference(CATEGORY, "CATEGORY", "Thé vert"), unusedStatus),
            teas = listOf(tea(EXISTING_TEA, 101, "Nom archive", CATEGORY), tea(NEW_TEA, 103, "Thé ajouté", CATEGORY)),
            sets = setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION),
            preferences = UserPreferences(9000, 41000),
        )
        val session = workflow.previewBytes(archive)
        val selectedSets = setOf("teas", "configuration")
        val decisions = setOf(
            ConfirmedMergeChange("Thé", EXISTING_TEA.toString(), "name"),
            ConfirmedMergeChange("Préférences métier", "configuration", "quantityPerCupMilliGrams"),
        )
        val selection = workflow.preview(session, selectedSets, decisions)
        assertTrue(selection.target.blockers.toString(), selection.applicable)
        assertTrue(selection.mergePlan.items.any { it.entityType == "Thé" && it.identity == LOCAL_ONLY_TEA.toString() && it.disposition == fr.thesdupalais.data.backup.MergeDisposition.PRESERVED })

        // Emulate the older durable target shape: all configuration rows were copied into teas.json.
        val state = requireNotNull(selection.target.state)
        val legacyTeaPayload = JSONObject(state.payloads.getValue("data/teas.json"))
        val fullCatalog = JSONObject(state.payloads.getValue("data/configuration.json")).getJSONArray("references")
        legacyTeaPayload.put("references", JSONArray(fullCatalog.toString()))
        val legacyState = state.copy(payloads = state.payloads + ("data/teas.json" to legacyTeaPayload.toString()))
        val fingerprints = selection.target.fingerprints.copy(targetSha256 = BackupTargetStateBuilder.fingerprintTargetState(legacyState))
        val operationId = UUID.randomUUID().toString()
        journal.prepare(operationId, TargetOperation.MERGE, selectedSets, session.archiveCopy(), legacyState, fingerprints)
        applier.apply(operationId, ConfirmedRoomOperation(TargetOperation.MERGE, selectedSets, fingerprints))

        database.close()
        scope.cancel()
        database = openDatabase()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = createPreferences()
        buildServices()
        val recovered = recover()

        assertFalse("Une opération cohérente doit reprendre vers l’avant", recovered.blocksWrites)
        assertEquals(3, database.teaDao().count())
        assertEquals("Nom archive", database.teaDao().findById(EXISTING_TEA)!!.name)
        assertEquals("Thé conservé", database.teaDao().findById(LOCAL_ONLY_TEA)!!.name)
        assertEquals(setOf(LOCAL_AROMA), database.teaDao().allAromasForBackup().filter { it.teaId == LOCAL_ONLY_TEA }.map { it.aromaRefId }.toSet())
        assertEquals(setOf(LOCAL_TASTING), database.tastingDao().allForBackup().map { it.id }.toSet())
        assertEquals(BackupOperationState.COMPLETE, journal.readAll().single().state)
        assertNull(database.backupOperationMarkerDao().findActive())
        assertEquals(UserPreferences(9000, UserPreferences.DEFAULT_LOW_STOCK_THRESHOLD_MILLI), preferences.preferences.first())
        val persisted = fr.thesdupalais.data.backup.BackupArchiveValidator.validate(exporter.captureCurrentOperationSnapshot().archiveBytes)
        assertEquals(3, JSONObject(String(persisted.entries.getValue("data/configuration.json"), Charsets.UTF_8)).getJSONArray("references").length())
        assertEquals(2, JSONObject(String(persisted.entries.getValue("data/teas.json"), Charsets.UTF_8)).getJSONArray("references").length())
        preferences.setLowStockThresholdMilli(26000)
    }

    private suspend fun assertNotNullMarker(expectedId: String) {
        assertEquals(expectedId, database.backupOperationMarkerDao().findActive()!!.operationId)
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
        val all = journal.readAll()
        val marker = database.backupOperationMarkerDao().findActive()
        val active = if (marker != null) all else all.filterNot { it.state in setOf(BackupOperationState.COMPLETE, BackupOperationState.ABORTED) }
        return BackupOperationRecoveryInspector.inspect(
            active.map { it.operationId }.toSet(), marker?.operationId,
            journalStates = active.associate { it.operationId to it.state }, roomMarkerState = marker?.state,
        )
    }

    private fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, File(root, "room.db").absolutePath)
        .allowMainThreadQueries().build()

    private fun createPreferences() = LocalPreferencesRepository(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") }), coordinator,
    )

    private fun buildServices() {
        exporter = BackupArchiveExporter(database, preferences, context.contentResolver)
        applier = BackupRoomOperationApplier(database, exporter, preferences, journal, coordinator)
        finalizer = BackupOperationFinalizer(database, exporter, preferences, journal, coordinator)
        workflow = BackupMergeWorkflow(
            context.contentResolver, exporter, coordinator, journal, applier, finalizer,
            hasNoActiveMarker = { database.backupOperationMarkerDao().findActive() == null },
            recoverPendingOperation = { recover() },
        )
    }

    private fun archive(
        references: List<ReferenceEntity>,
        teas: List<TeaEntity>,
        aromas: List<TeaAromaEntity> = emptyList(),
        tastings: List<TastingEntity> = emptyList(),
        sets: Set<BackupExportSet> = setOf(BackupExportSet.TEAS),
        preferences: UserPreferences = UserPreferences(),
    ): ByteArray = BackupArchiveExporter.BackupArchiveWriter.write(
        BackupArchiveExporter.Snapshot(
            references = references, teas = teas, countries = emptyList(), regions = emptyList(), aromas = aromas,
            profiles = emptyList(), moments = emptyList(), badges = emptyList(), tastings = tastings, movements = emptyList(),
        ), sets, preferences, "merge-test", UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun reference(id: UUID, kind: String, label: String) = ReferenceEntity(
        id, kind, label, ReferenceLabelNormalizer.normalize(label), null, null, "", null, 0,
    )

    private fun tea(id: UUID, legacyId: Long, name: String, category: UUID) = TeaEntity(
        id, legacyId, name, category, null, null, null, null, null, null, null, null, null, null, null,
    )

    private companion object {
        val CATEGORY = UUID.fromString("123e4567-e89b-12d3-a456-426614174500")
        val STATUS = UUID.fromString("123e4567-e89b-12d3-a456-426614174501")
        val LOCAL_AROMA = UUID.fromString("123e4567-e89b-12d3-a456-426614174502")
        val INCOMING_AROMA = UUID.fromString("123e4567-e89b-12d3-a456-426614174503")
        val EXISTING_TEA = UUID.fromString("123e4567-e89b-12d3-a456-426614174510")
        val LOCAL_ONLY_TEA = UUID.fromString("123e4567-e89b-12d3-a456-426614174511")
        val NEW_TEA = UUID.fromString("123e4567-e89b-12d3-a456-426614174512")
        val LOCAL_TASTING = UUID.fromString("123e4567-e89b-12d3-a456-426614174520")
        val NEW_TASTING = UUID.fromString("123e4567-e89b-12d3-a456-426614174521")
    }
}
