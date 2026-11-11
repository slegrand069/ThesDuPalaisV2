package fr.thesdupalais.data.backup

import fr.thesdupalais.data.database.ReferenceEntity
import fr.thesdupalais.data.database.TeaAromaEntity
import fr.thesdupalais.data.database.TeaEntity
import fr.thesdupalais.data.database.StockMovementEntity
import fr.thesdupalais.data.database.TastingEntity
import fr.thesdupalais.data.preferences.UserPreferences
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveMergePlannerTest {
    @Test fun identicalArchiveProducesDeterministicNoOpPlanAndFingerprints() {
        val snapshot = snapshot(tea = tea(), refs = listOf(category()))
        val zip = archive(snapshot)
        val plan = planner(zip, zip, setOf("teas", "configuration"))
        assertTrue(plan.canApplyAfterConfirmation)
        assertEquals(0, plan.items.count { it.disposition == MergeDisposition.UPDATE_PROPOSED || it.disposition == MergeDisposition.ADD })
        assertEquals(64, plan.archiveSha256.length)
        assertEquals(plan.localSnapshotSha256, planner(zip, zip, setOf("teas", "configuration")).localSnapshotSha256)
    }

    @Test fun teaScalarNullAndAssociationsAreExplicitlyPlannedWithoutDroppingLocalLinks() {
        val aroma = reference(AROMA, "AROMA", "Herbe")
        val local = snapshot(tea = tea(description = "ancienne description", aromas = listOf(AROMA)), refs = listOf(category(), aroma), aromas = listOf(AROMA))
        val incoming = snapshot(tea = tea(description = null), refs = listOf(category()))
        val plan = planner(archive(incoming, setOf(BackupExportSet.TEAS)), archive(local), setOf("teas"))
        val teaPlan = plan.items.single { it.entityType == "Thé" }
        assertEquals(MergeDisposition.UPDATE_PROPOSED, teaPlan.disposition)
        assertTrue("description" in teaPlan.changedFields)
        assertTrue(teaPlan.details.any { it.contains("aromaIds") && it.contains("conservation") })
        assertTrue(plan.canApplyAfterConfirmation)
    }

    @Test fun legacyIdCollisionBlocksAddition() {
        val local = snapshot(tea = tea(id = TEA_2, legacyId = 41), refs = listOf(category()))
        val incoming = snapshot(tea = tea(id = TEA, legacyId = 41), refs = listOf(category()))
        val plan = planner(archive(incoming, setOf(BackupExportSet.TEAS)), archive(local), setOf("teas"))
        assertFalse(plan.canApplyAfterConfirmation)
        assertTrue(plan.blockingConflicts.any { it.contains("legacyId 41") })
    }

    @Test fun standardReferenceDefinitionIsPreservedByDefaultAndArchivedChangeIsShown() {
        val localRef = category(label = "Thé vert", archived = null)
        val incomingRef = category(label = "Vert", archived = 1_700_000_000_000)
        val local = snapshot(tea = tea(), refs = listOf(localRef))
        val incoming = snapshot(tea = tea(), refs = listOf(incomingRef))
        val plan = planner(archive(incoming, setOf(BackupExportSet.TEAS)), archive(local), setOf("teas"))
        val refItem = plan.items.first { it.entityType == "Référence standard" }
        assertEquals(MergeDisposition.PRESERVED, refItem.disposition)
        assertTrue("label" in refItem.changedFields)
        assertTrue("archivedAt" in refItem.changedFields)
        assertTrue(refItem.details.any { it.contains("confirmer") })
    }

    @Test fun referenceUuidTypeCollisionIsBlocking() {
        val localRef = category()
        val wrongType = reference(CATEGORY, "STATUS", "Actif")
        val local = snapshot(tea = null, refs = listOf(localRef))
        val incoming = snapshot(tea = null, refs = listOf(wrongType))
        val plan = planner(
            archive(incoming, setOf(BackupExportSet.CONFIGURATION)),
            archive(local, setOf(BackupExportSet.CONFIGURATION)),
            setOf("configuration"),
        )
        assertTrue(plan.blockingConflicts.any { it.contains("collision UUID") })
    }

    @Test fun regionParentResolvesThroughStandardCountryIdentity() {
        val importedCountryId = uuid(20)
        val local = snapshot(tea = null, refs = listOf(
            reference(COUNTRY, "COUNTRY", "Japon", semantic = "JAPAN"),
            reference(REGION, "REGION", "Shizuoka", semantic = "SHIZUOKA", parent = COUNTRY),
        ))
        val incoming = snapshot(tea = null, refs = listOf(
            reference(importedCountryId, "COUNTRY", "Japon", semantic = "JAPAN"),
            reference(REGION, "REGION", "Shizuoka", semantic = "SHIZUOKA", parent = importedCountryId),
        ))
        val plan = planner(
            archive(incoming, setOf(BackupExportSet.CONFIGURATION)),
            archive(local, setOf(BackupExportSet.CONFIGURATION)),
            setOf("configuration"),
        )
        assertTrue(plan.canApplyAfterConfirmation)
        assertEquals(3, plan.items.count { it.disposition == MergeDisposition.IDENTICAL })
        assertTrue(plan.items.any { it.entityType == "Référence" && it.identity == COUNTRY.toString() && it.details.any { detail -> detail.contains(importedCountryId.toString()) } })
    }

    @Test fun changedTastingWithSameUuidIsBlockingAndLocalHistoryIsPreserved() {
        val local = snapshot(tea = tea(), refs = listOf(category()), tastings = listOf(tasting(amount = 900)))
        val incoming = snapshot(tea = tea(), refs = listOf(category()), tastings = listOf(tasting(amount = 1100)))
        val plan = planner(archive(incoming, setOf(BackupExportSet.TEAS)), archive(local), setOf("teas"))
        assertTrue(plan.blockingConflicts.any { it.contains("Dégustation") && it.contains("contenu différent") })
        assertTrue(plan.items.any { it.entityType == "Dégustation" && it.disposition == MergeDisposition.CONFLICT })
    }

    @Test fun divergentStocksAreBlockingAndNeverChooseOneSide() {
        val local = snapshot(tea = tea(stock = 5000), refs = listOf(category()), movements = listOf(movement(balance = 5000)))
        val incoming = snapshot(tea = tea(stock = 6000), refs = listOf(category()), movements = listOf(movement(balance = 6000)))
        val plan = planner(archive(incoming, setOf(BackupExportSet.TEAS)), archive(local), setOf("teas"))
        assertFalse(plan.canApplyAfterConfirmation)
        assertTrue(plan.blockingConflicts.any { it.contains("stocks local") && it.contains("divergents") })
    }

    @Test fun configurationPreferencesAreComparedPerFieldAndRemainConfirmationProposals() {
        val local = snapshot(tea = null, refs = listOf(category()))
        val incoming = snapshot(tea = null, refs = listOf(category()))
        val plan = planner(archive(incoming, setOf(BackupExportSet.CONFIGURATION), UserPreferences(8000, 25000)), archive(local, preferences = UserPreferences(7000, 25000)), setOf("configuration"))
        val preference = plan.items.single { it.entityType == "Préférences métier" }
        assertEquals(MergeDisposition.UPDATE_PROPOSED, preference.disposition)
        assertEquals(listOf("quantityPerCupMilliGrams"), preference.changedFields)
        assertTrue(preference.details.single().contains("7000 → 8000"))
    }

    @Test fun articlesAndAbsentSetsCannotBeSelected() {
        val zip = archive(snapshot(tea = tea(), refs = listOf(category())), setOf(BackupExportSet.TEAS))
        assertTrue(runCatching { BackupArchiveMergePlanner.plan(zip, zip, setOf("articles")) }.isFailure)
        assertTrue(runCatching { BackupArchiveMergePlanner.plan(zip, zip, setOf("configuration")) }.isFailure)
        assertTrue(runCatching { BackupArchiveMergePlanner.plan(zip, zip, emptySet()) }.isFailure)
    }

    @Test fun localFingerprintChangesWhenLocalPayloadChanges() {
        val incoming = archive(snapshot(tea = tea(), refs = listOf(category())))
        val localA = archive(snapshot(tea = tea(name = "Sencha"), refs = listOf(category())))
        val localB = archive(snapshot(tea = tea(name = "Gyokuro"), refs = listOf(category())))
        assertNotEquals(
            planner(incoming, localA, setOf("teas")).localSnapshotSha256,
            planner(incoming, localB, setOf("teas")).localSnapshotSha256,
        )
    }

    private fun planner(incoming: ByteArray, local: ByteArray, sets: Set<String>) = BackupArchiveMergePlanner.plan(incoming, local, sets)

    private fun archive(
        snapshot: BackupArchiveExporter.Snapshot,
        sets: Set<BackupExportSet> = setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION),
        preferences: UserPreferences = UserPreferences(),
    ) = BackupArchiveExporter.BackupArchiveWriter.write(snapshot, sets, preferences, "test", BACKUP, Instant.parse("2026-01-01T00:00:00Z"))

    private fun snapshot(
        tea: TeaEntity?, refs: List<ReferenceEntity>, tastings: List<TastingEntity> = emptyList(),
        movements: List<StockMovementEntity> = emptyList(), aromas: List<UUID> = emptyList(),
    ) = BackupArchiveExporter.Snapshot(
        references = refs, teas = listOfNotNull(tea), countries = emptyList(), regions = emptyList(), aromas = aromas.map { TeaAromaEntity(requireNotNull(tea).id, it) },
        profiles = emptyList(), moments = emptyList(), badges = emptyList(), tastings = tastings, movements = movements,
    )

    private fun category(id: UUID = CATEGORY, label: String = "Catégorie", archived: Long? = null) =
        reference(id, "CATEGORY", label, semantic = "GREEN", archived = archived)

    private fun reference(id: UUID, kind: String, label: String, semantic: String? = null, archived: Long? = null, parent: UUID? = null) = ReferenceEntity(
        id, kind, label, label.lowercase(), semantic, parent, parent?.toString().orEmpty(), archived, 0,
    )

    private fun tea(
        id: UUID = TEA, name: String = "Sencha", legacyId: Long? = null, description: String? = null,
        stock: Long? = null, aromas: List<UUID> = emptyList(),
    ) = TeaEntity(id, legacyId, name, CATEGORY, description, null, null, null, null, null, null, null, null, stock, null)

    private fun tasting(amount: Long) = TastingEntity(TASTING, TEA, 1_700_000_000_000, 1_700_000_000_100, amount)
    private fun movement(balance: Long) = StockMovementEntity(MOVEMENT, TEA, "INVENTORY", null, balance, 1_700_000_000_000, 1_700_000_000_100, null, null)

    private companion object {
        val BACKUP = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val CATEGORY = UUID.fromString("123e4567-e89b-12d3-a456-426614174001")
        val AROMA = UUID.fromString("123e4567-e89b-12d3-a456-426614174002")
        val COUNTRY = UUID.fromString("123e4567-e89b-12d3-a456-426614174007")
        val REGION = UUID.fromString("123e4567-e89b-12d3-a456-426614174008")
        val TEA = UUID.fromString("123e4567-e89b-12d3-a456-426614174003")
        val TEA_2 = UUID.fromString("123e4567-e89b-12d3-a456-426614174004")
        val TASTING = UUID.fromString("123e4567-e89b-12d3-a456-426614174005")
        val MOVEMENT = UUID.fromString("123e4567-e89b-12d3-a456-426614174006")
        fun uuid(n: Int) = UUID.fromString("123e4567-e89b-12d3-a456-${n.toString().padStart(12, '0')}")
    }
}
