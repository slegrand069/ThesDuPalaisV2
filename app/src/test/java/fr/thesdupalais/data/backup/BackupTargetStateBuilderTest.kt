package fr.thesdupalais.data.backup

import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.TeaEntity
import fr.thesdupalais.data.preferences.UserPreferences
import java.time.Instant
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupTargetStateBuilderTest {
    @Test fun canonicalPayloadComparisonIgnoresArrayOrderingButPreservesMultiplicity() {
        val ordered = JSONObject("""{"references":[{"id":"b"},{"id":"a"}]}""")
        val reversed = JSONObject("""{"references":[{"id":"a"},{"id":"b"}]}""")
        val duplicate = JSONObject("""{"references":[{"id":"a"},{"id":"a"},{"id":"b"}]}""")

        assertEquals(BackupTargetStateBuilder.canonicalJson(ordered), BackupTargetStateBuilder.canonicalJson(reversed))
        assertFalse(BackupTargetStateBuilder.canonicalJson(ordered) == BackupTargetStateBuilder.canonicalJson(duplicate))
    }

    @Test fun replacementRequiresExplicitConfirmationAndKeepsUnselectedSets() {
        val snapshot = snapshot()
        val archive = archive(snapshot, setOf(BackupExportSet.CONFIGURATION))
        val local = archive(snapshot, setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val notConfirmed = BackupTargetStateBuilder.build(request(archive, local, TargetOperation.REPLACE, setOf("configuration")))
        assertFalse(notConfirmed.applicable)
        assertNull(notConfirmed.state)

        val result = BackupTargetStateBuilder.build(request(archive, local, TargetOperation.REPLACE, setOf("configuration"), true))
        assertTrue(result.applicable)
        assertEquals("*", result.preserved.single { it.set == "teas" }.identity)
        assertEquals(1, JSONObject(result.state!!.payloads.getValue("data/teas.json")).getJSONArray("teas").length())
    }

    @Test fun replacementPreviewPlansExactlyTheSelectedSetsWithoutApplyingAnything() {
        val localBytes = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val incoming = archive(emptySnapshot(), setOf(BackupExportSet.CONFIGURATION))
        val local = BackupLocalOperationSnapshot(localBytes, emptyList(), emptyList())

        val configurationOnly = BackupReplacementPreviewPlanner.plan(incoming, local, setOf("configuration"))
        assertTrue(configurationOnly.target.applicable)
        assertEquals(setOf("configuration"), configurationOnly.selectedSets)
        assertEquals(1, JSONObject(configurationOnly.target.state!!.payloads.getValue("data/teas.json")).getJSONArray("teas").length())

        val changedSelection = BackupReplacementPreviewPlanner.plan(
            archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION)), local, setOf("teas", "configuration"),
        )
        assertTrue(changedSelection.target.applicable)
        assertEquals(setOf("teas", "configuration"), changedSelection.selectedSets)
    }

    @Test fun replacementPreviewRejectsArticlesAsAnApplicableSelection() {
        val bytes = archive(emptySnapshot(), setOf(BackupExportSet.ARTICLES))
        val localBytes = archive(emptySnapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val local = BackupLocalOperationSnapshot(localBytes, emptyList(), emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            BackupReplacementPreviewPlanner.plan(bytes, local, setOf("articles"))
        }
    }

    @Test fun replacementOfBothSetsCarriesStableIdsAndEmitsDeterministicFingerprints() {
        val source = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val local = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val first = BackupTargetStateBuilder.build(request(source, local, TargetOperation.REPLACE, setOf("teas", "configuration"), true))
        val second = BackupTargetStateBuilder.build(request(source, local, TargetOperation.REPLACE, setOf("configuration", "teas"), true))
        assertTrue(first.applicable)
        assertEquals(first.fingerprints, second.fingerprints)
        val payload = JSONObject(first.state!!.payloads.getValue("data/teas.json"))
        assertEquals(TEA.toString(), payload.getJSONArray("teas").getJSONObject(0).getString("id"))
        assertEquals(810L, payload.getJSONArray("teas").getJSONObject(0).getLong("legacyId"))
    }

    @Test fun configurationOnlyDoesNotReplaceBusinessDataOrTechnicalPreferences() {
        val current = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val configOnly = archive(snapshot(), setOf(BackupExportSet.CONFIGURATION))
        val result = BackupTargetStateBuilder.build(request(configOnly, current, TargetOperation.REPLACE, setOf("configuration"), true))
        assertTrue(result.applicable)
        assertEquals(1, JSONObject(result.state!!.payloads.getValue("data/teas.json")).getJSONArray("teas").length())
    }

    @Test fun teaOnlyReplacementAddsItsReferenceDependenciesToTheTargetCatalog() {
        val current = archive(emptySnapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val incoming = archive(snapshot(), setOf(BackupExportSet.TEAS))
        val result = BackupTargetStateBuilder.build(request(incoming, current, TargetOperation.REPLACE, setOf("teas"), true))
        assertTrue(result.blockers.toString(), result.applicable)
        val catalog = JSONObject(result.state!!.payloads.getValue("data/configuration.json"))
        assertEquals(CATEGORY.toString(), catalog.getJSONArray("references").getJSONObject(0).getString("id"))
    }

    @Test fun configurationReplacementPreservesReferenceUsedByRetainedTea() {
        val current = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val incoming = archive(emptySnapshot(), setOf(BackupExportSet.CONFIGURATION))
        val result = BackupTargetStateBuilder.build(request(incoming, current, TargetOperation.REPLACE, setOf("configuration"), true))
        assertTrue(result.blockers.toString(), result.applicable)
        val references = JSONObject(result.state!!.payloads.getValue("data/configuration.json")).getJSONArray("references")
        assertEquals(CATEGORY.toString(), references.getJSONObject(0).getString("id"))
        assertTrue(result.preserved.any { it.set == "reference" && it.identity == CATEGORY.toString() })
    }

    @Test fun malformedOrUnselectedArchiveSetNeverProducesTarget() {
        val local = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val onlyConfig = archive(snapshot(), setOf(BackupExportSet.CONFIGURATION))
        val result = BackupTargetStateBuilder.build(request(onlyConfig, local, TargetOperation.REPLACE, setOf("teas"), true))
        assertFalse(result.applicable)
        assertNotNull(result.blockers.firstOrNull { it.contains("absent") })
    }

    @Test fun mergeKeepsCompatibleStandardReferenceDefinitionLocalByDefault() {
        val local = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val incoming = archive(snapshot().copy(references = listOf(reference(CATEGORY, "CATEGORY", "Catégorie modifiée"))), setOf(BackupExportSet.CONFIGURATION))
        val result = BackupTargetStateBuilder.build(request(incoming, local, TargetOperation.MERGE, setOf("configuration")))
        assertTrue(result.applicable)
        val output = JSONObject(result.state!!.payloads.getValue("data/configuration.json"))
        assertEquals("Catégorie", output.getJSONArray("references").getJSONObject(0).getString("label"))
        assertTrue(result.preserved.any { it.set == "configuration" && it.fields.contains("label") })
    }

    @Test fun mergeAddsMissingTeaAndKeepsIdentity() {
        val local = archive(emptySnapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val incoming = archive(snapshot(), setOf(BackupExportSet.TEAS))
        val result = BackupTargetStateBuilder.build(request(incoming, local, TargetOperation.MERGE, setOf("teas")))
        assertTrue(result.blockers.toString(), result.applicable)
        val output = JSONObject(result.state!!.payloads.getValue("data/teas.json"))
        assertEquals(TEA.toString(), output.getJSONArray("teas").getJSONObject(0).getString("id"))
        assertEquals(810L, output.getJSONArray("teas").getJSONObject(0).getLong("legacyId"))
        assertEquals(1, JSONObject(result.state.payloads.getValue("data/configuration.json")).getJSONArray("references").length())
    }

    @Test fun mergeAppliesOnlyConfirmedScalarAndPreservesNullValues() {
        val local = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val changed = snapshot().copy(teas = listOf(TeaEntity(TEA, 810, "Thé modifié", CATEGORY, null, null, null, null, null, null, null, null, null, null, null)))
        val incoming = archive(changed, setOf(BackupExportSet.TEAS))
        val plan = BackupArchiveMergePlanner.plan(incoming, local, setOf("teas"))
        val request = BackupTargetRequest(incoming, local, TargetOperation.MERGE, setOf("teas"), confirmedChanges = setOf(ConfirmedMergeChange("Thé", TEA.toString(), "name")), planner = plan)
        val result = BackupTargetStateBuilder.build(request)
        assertTrue(result.blockers.toString(), result.applicable)
        val tea = JSONObject(result.state!!.payloads.getValue("data/teas.json")).getJSONArray("teas").getJSONObject(0)
        assertEquals("Thé modifié", tea.getString("name"))
        assertTrue(tea.isNull("description"))
    }

    @Test fun mergeBlocksLegacyIdCollisionAndInconsistentStock() {
        val local = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val otherTea = UUID.fromString("123e4567-e89b-12d3-a456-426614174099")
        val colliding = snapshot().copy(teas = listOf(TeaEntity(otherTea, 810, "Autre", CATEGORY, null, null, null, null, null, null, null, null, null, null, null)))
        val collisionArchive = archive(colliding, setOf(BackupExportSet.TEAS))
        val collision = BackupTargetStateBuilder.build(request(collisionArchive, local, TargetOperation.MERGE, setOf("teas")))
        assertFalse(collision.applicable)
        assertTrue(collision.blockers.any { it.contains("legacyId") })

        val stockLocal = archive(snapshot().copy(teas = listOf(TeaEntity(TEA, 810, "Thé test", CATEGORY, null, null, null, null, null, null, null, null, null, 500, null))), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val stockArchive = archive(snapshot().copy(teas = listOf(TeaEntity(TEA, 810, "Thé test", CATEGORY, null, null, null, null, null, null, null, null, null, 1000, null))), setOf(BackupExportSet.TEAS))
        val stock = BackupTargetStateBuilder.build(request(stockArchive, stockLocal, TargetOperation.MERGE, setOf("teas")))
        assertFalse(stock.applicable)
        assertTrue(stock.blockers.any { it.contains("stock") || it.contains("Stocks") })
    }

    @Test fun photoForRemovedTeaIsExcludedAndReportedForQuarantine() {
        val local = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val emptyTarget = archive(emptySnapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val photo = TargetPhotoRecord(PHOTO.toString(), TEA.toString(), "tea-photos/$TEA/main.jpg", 0)
        val result = BackupTargetStateBuilder.build(BackupTargetRequest(emptyTarget, local, TargetOperation.REPLACE, setOf("teas"), true, photos = listOf(photo)))
        assertTrue(result.applicable)
        assertTrue(result.state!!.photosToKeep.isEmpty())
        assertEquals(listOf(photo), result.state.photosToQuarantine)
        assertTrue(result.warnings.any { it.contains("quarantaine") })
    }

    @Test fun repeatedBuildHasNoSideEffectsAndSameTargetFingerprint() {
        val bytes = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val request = request(bytes, bytes, TargetOperation.REPLACE, setOf("teas", "configuration"), true)
        val first = BackupTargetStateBuilder.build(request)
        val second = BackupTargetStateBuilder.build(request)
        assertEquals(first, second)
        assertTrue(first.applicable)
    }

    @Test fun preparedTargetIsValidatedAndRejectsOrphanedRelations() {
        val bytes = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val result = BackupTargetStateBuilder.build(request(bytes, bytes, TargetOperation.REPLACE, setOf("teas", "configuration"), true))
        assertTrue(result.applicable)
        assertTrue(BackupTargetStateBuilder.validatePreparedState(requireNotNull(result.state)).isEmpty())

        val brokenPayloads = result.state!!.payloads.toMutableMap()
        val teas = JSONObject(brokenPayloads.getValue("data/teas.json"))
        val rows = teas.getJSONArray("teas")
        rows.getJSONObject(0).put("categoryId", "123e4567-e89b-12d3-a456-426614174099")
        brokenPayloads["data/teas.json"] = teas.toString()
        val errors = BackupTargetStateBuilder.validatePreparedState(result.state.copy(payloads = brokenPayloads))
        assertTrue(errors.toString(), errors.isNotEmpty())
    }

    @Test fun localFingerprintIncludesImportSupersededDecision() {
        val bytes = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val record = TargetImportRecord("123e4567-e89b-12d3-a456-426614174140", "a".repeat(64), 1)
        val active = BackupTargetStateBuilder.build(BackupTargetRequest(
            bytes, bytes, TargetOperation.REPLACE, setOf("teas"), replacementConfirmed = true, importHistory = listOf(record),
        ))
        val superseded = BackupTargetStateBuilder.build(BackupTargetRequest(
            bytes, bytes, TargetOperation.REPLACE, setOf("teas"), replacementConfirmed = true, importHistory = listOf(record.copy(isSuperseded = true)),
        ))
        assertTrue(active.applicable && superseded.applicable)
        assertTrue(active.fingerprints.localSnapshotSha256 != superseded.fingerprints.localSnapshotSha256)
    }

    @Test fun fingerprintsCoverPreferencesPhotosAndConfirmationDecisions() {
        val localA = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val localB = archive(snapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION), UserPreferences(7100, 25000))
        val incomingSnapshot = snapshot().copy(teas = listOf(TeaEntity(TEA, 810, "Thé mis à jour", CATEGORY, null, null, null, null, null, null, null, null, null, null, null)))
        val incoming = archive(incomingSnapshot, setOf(BackupExportSet.TEAS))
        val plan = BackupArchiveMergePlanner.plan(incoming, localA, setOf("teas"))
        val plain = BackupTargetStateBuilder.build(BackupTargetRequest(incoming, localA, TargetOperation.MERGE, setOf("teas"), planner = plan))
        val confirmed = BackupTargetStateBuilder.build(BackupTargetRequest(
            incoming, localA, TargetOperation.MERGE, setOf("teas"),
            confirmedChanges = setOf(ConfirmedMergeChange("Thé", TEA.toString(), "name")), planner = plan,
        ))
        val photoInput = BackupTargetStateBuilder.build(BackupTargetRequest(
            incoming, localA, TargetOperation.MERGE, setOf("teas"), planner = plan,
            photos = listOf(TargetPhotoRecord(PHOTO.toString(), TEA.toString(), "photos/$TEA.jpg", 0)),
        ))
        val changedLocal = BackupTargetStateBuilder.build(request(incoming, localB, TargetOperation.MERGE, setOf("teas")))
        assertTrue(plain.applicable && confirmed.applicable && photoInput.applicable && changedLocal.applicable)
        assertTrue(plain.fingerprints.operationSha256 != confirmed.fingerprints.operationSha256)
        assertTrue(plain.fingerprints.targetSha256 != confirmed.fingerprints.targetSha256)
        assertTrue(plain.fingerprints.localSnapshotSha256 != photoInput.fingerprints.localSnapshotSha256)
        assertTrue(plain.fingerprints.localSnapshotSha256 != changedLocal.fingerprints.localSnapshotSha256)
    }

    private fun request(a: ByteArray, l: ByteArray, op: TargetOperation, selected: Set<String>, confirm: Boolean = false) =
        BackupTargetRequest(a, l, op, selected, replacementConfirmed = confirm)

    private fun archive(s: BackupArchiveExporter.Snapshot, sets: Set<BackupExportSet>, prefs: UserPreferences = UserPreferences(7000, 25000)): ByteArray =
        BackupArchiveExporter.BackupArchiveWriter.write(s, sets, prefs, "test", BACKUP, Instant.parse("2026-01-01T00:00:00Z"))

    private fun snapshot() = BackupArchiveExporter.Snapshot(
        references = listOf(reference(CATEGORY, "CATEGORY", "Catégorie")),
        teas = listOf(TeaEntity(TEA, 810, "Thé test", CATEGORY, null, null, null, null, null, null, null, null, null, null, null)),
        countries = emptyList(), regions = emptyList(), aromas = emptyList(), profiles = emptyList(), moments = emptyList(), badges = emptyList(), tastings = emptyList(), movements = emptyList(),
    )

    private fun emptySnapshot() = BackupArchiveExporter.Snapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    private fun reference(id: UUID, kind: String, label: String) = ReferenceEntity(id, kind, label, label.lowercase(), null, null, "", null, 0)

    private companion object {
        val BACKUP: UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val CATEGORY: UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174001")
        val TEA: UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174002")
        val PHOTO: UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174003")
    }
}
