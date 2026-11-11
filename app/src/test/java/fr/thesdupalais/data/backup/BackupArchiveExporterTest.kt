package fr.thesdupalais.data.backup

import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.TeaEntity
import fr.thesdupalais.data.database.TeaCountryEntity
import fr.thesdupalais.data.database.TeaRegionEntity
import fr.thesdupalais.data.database.TeaAromaEntity
import fr.thesdupalais.data.database.TeaAromaticProfileEntity
import fr.thesdupalais.data.database.TeaMomentEntity
import fr.thesdupalais.data.database.TeaBadgeEntity
import fr.thesdupalais.data.database.TastingEntity
import fr.thesdupalais.data.database.StockMovementEntity
import fr.thesdupalais.data.preferences.UserPreferences
import java.time.Instant
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupArchiveExporterTest {
    @Test fun writesAndValidatesEmptySelectedSets() {
        val archive = write(emptySnapshot(), setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val validated = BackupArchiveValidator.validate(archive)
        assertEquals(setOf("teas", "configuration"), validated.includedSets)
        val teas = JSONObject(String(validated.entries.getValue("data/teas.json")))
        assertEquals(0, teas.getJSONArray("teas").length())
        val config = JSONObject(String(validated.entries.getValue("data/configuration.json")))
        assertEquals(0, config.getJSONArray("references").length())
    }

    @Test fun exportsTeaOnlyWithUsedArchivedReferencesAndPreservesRelations() {
        val snapshot = populatedSnapshot()
        val validated = BackupArchiveValidator.validate(write(teaDependenciesOnly(snapshot), setOf(BackupExportSet.TEAS)))
        assertEquals(setOf("teas"), validated.includedSets)
        val payload = JSONObject(String(validated.entries.getValue("data/teas.json")))
        val tea = payload.getJSONArray("teas").getJSONObject(0)
        assertEquals(TEA.toString(), tea.getString("id"))
        assertEquals(707L, tea.getLong("legacyId"))
        assertEquals(COUNTRY.toString(), tea.getJSONArray("countryIds").getString(0))
        assertEquals(REGION.toString(), tea.getJSONArray("regionIds").getString(0))
        assertEquals(4000, tea.getLong("stockMilliGrams"))
        val refs = payload.getJSONArray("references")
        assertTrue((0 until refs.length()).map { refs.getJSONObject(it) }.any { it.getString("id") == REGION.toString() && it.get("archivedAt") != JSONObject.NULL })
        assertEquals(1, payload.getJSONArray("tastings").length())
        assertEquals(TASTING.toString(), payload.getJSONArray("stockMovements").getJSONObject(1).getString("tastingId"))
    }

    @Test fun exportsConfigurationOnlyAndCombinedSelections() {
        val snapshot = populatedSnapshot()
        val configOnly = BackupArchiveValidator.validate(write(snapshot, setOf(BackupExportSet.CONFIGURATION)))
        assertEquals(setOf("configuration"), configOnly.includedSets)
        val allConfigRefs = JSONObject(String(configOnly.entries.getValue("data/configuration.json"))).getJSONArray("references")
        assertEquals(snapshot.references.size, allConfigRefs.length())

        val combined = BackupArchiveValidator.validate(write(snapshot, setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION)))
        assertEquals(setOf("teas", "configuration"), combined.includedSets)
        val configRefs = JSONObject(String(combined.entries.getValue("data/configuration.json"))).getJSONArray("references")
        assertEquals(snapshot.references.size, configRefs.length())
    }

    @Test fun outputIsDeterministicForSameSnapshotAndMetadata() {
        val snapshot = populatedSnapshot()
        val teasOnly = teaDependenciesOnly(snapshot)
        val first = BackupArchiveExporter.BackupArchiveWriter.write(teasOnly, setOf(BackupExportSet.TEAS), UserPreferences(), "test", BACKUP, Instant.parse("2026-01-01T00:00:00Z"))
        val second = BackupArchiveExporter.BackupArchiveWriter.write(teasOnly, setOf(BackupExportSet.TEAS), UserPreferences(), "test", BACKUP, Instant.parse("2026-01-01T00:00:00Z"))
        assertTrue(first.contentEquals(second))
    }

    @Test fun propagatesDestinationWriteFailure() {
        val broken = object : OutputStream() {
            override fun write(value: Int) { throw IOException("disk full") }
        }
        assertThrows(IOException::class.java) { writeArchiveBytes(broken, byteArrayOf(1, 2, 3)) }
    }

    private fun write(snapshot: BackupArchiveExporter.Snapshot, sets: Set<BackupExportSet>) =
        BackupArchiveExporter.BackupArchiveWriter.write(snapshot, sets, UserPreferences(7200, 30000), "test", BACKUP, Instant.parse("2026-01-01T00:00:00Z"))

    private fun teaDependenciesOnly(s: BackupArchiveExporter.Snapshot) = s.copy(references = s.references.filter { it.id in setOf(CATEGORY, COUNTRY, REGION, AROMA) })

    private fun emptySnapshot() = BackupArchiveExporter.Snapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    private fun populatedSnapshot(): BackupArchiveExporter.Snapshot {
        val refs = listOf(
            ref(CATEGORY, "CATEGORY", "Thé vert"),
            ref(COUNTRY, "COUNTRY", "Japon"),
            ref(REGION, "REGION", "Shizuoka", COUNTRY, archived = 1_700_000_000_000),
            ref(AROMA, "AROMA", "Herbe"),
        )
        val tea = TeaEntity(TEA, 707, "Sencha", CATEGORY, null, "Info", null, 7.0, null, 75, 45, null, null, 4000, null)
        return BackupArchiveExporter.Snapshot(
            references = refs,
            teas = listOf(tea),
            countries = listOf(TeaCountryEntity(TEA, COUNTRY)),
            regions = listOf(TeaRegionEntity(TEA, REGION, COUNTRY)),
            aromas = listOf(TeaAromaEntity(TEA, AROMA)), profiles = emptyList(), moments = emptyList(), badges = emptyList(),
            tastings = listOf(TastingEntity(TASTING, TEA, 1_700_000_001_000, 1_700_000_001_500, 1000)),
            movements = listOf(
                StockMovementEntity(MOVEMENT_INVENTORY, TEA, "INVENTORY", null, 5000, 1_700_000_000_000, 1_700_000_000_100, null, null),
                StockMovementEntity(MOVEMENT_CONSUMPTION, TEA, "CONSUMPTION", -1000, 4000, 1_700_000_001_000, 1_700_000_001_500, TASTING, null),
            ),
        )
    }

    private fun ref(id: UUID, kind: String, label: String, parent: UUID? = null, archived: Long? = null) = ReferenceEntity(
        id, kind, label, label.lowercase(), null, parent, parent?.toString().orEmpty(), archived, 0,
    )

    private companion object {
        val BACKUP = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val CATEGORY = UUID.fromString("123e4567-e89b-12d3-a456-426614174001")
        val COUNTRY = UUID.fromString("123e4567-e89b-12d3-a456-426614174002")
        val REGION = UUID.fromString("123e4567-e89b-12d3-a456-426614174003")
        val AROMA = UUID.fromString("123e4567-e89b-12d3-a456-426614174004")
        val TEA = UUID.fromString("123e4567-e89b-12d3-a456-426614174005")
        val TASTING = UUID.fromString("123e4567-e89b-12d3-a456-426614174006")
        val MOVEMENT_INVENTORY = UUID.fromString("123e4567-e89b-12d3-a456-426614174007")
        val MOVEMENT_CONSUMPTION = UUID.fromString("123e4567-e89b-12d3-a456-426614174008")
    }
}
