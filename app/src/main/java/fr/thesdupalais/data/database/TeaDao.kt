package fr.thesdupalais.data.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface TeaDao {
    @Query("SELECT * FROM teas ORDER BY id")
    suspend fun allForBackup(): List<TeaEntity>

    @Query("SELECT * FROM tea_countries ORDER BY teaId, countryRefId")
    suspend fun allCountriesForBackup(): List<TeaCountryEntity>

    @Query("SELECT * FROM tea_regions ORDER BY teaId, regionRefId")
    suspend fun allRegionsForBackup(): List<TeaRegionEntity>

    @Query("SELECT * FROM tea_aromas ORDER BY teaId, aromaRefId")
    suspend fun allAromasForBackup(): List<TeaAromaEntity>

    @Query("SELECT * FROM tea_aromatic_profiles ORDER BY teaId, profileRefId")
    suspend fun allAromaticProfilesForBackup(): List<TeaAromaticProfileEntity>

    @Query("SELECT * FROM tea_moments ORDER BY teaId, momentRefId")
    suspend fun allMomentsForBackup(): List<TeaMomentEntity>

    @Query("SELECT * FROM tea_badges ORDER BY teaId, badgeRefId")
    suspend fun allBadgesForBackup(): List<TeaBadgeEntity>
    @Query("SELECT COUNT(*) FROM teas")
    suspend fun count(): Int

    @Query("SELECT * FROM teas WHERE id = :id")
    suspend fun findById(id: UUID): TeaEntity?

    @Query("SELECT * FROM teas WHERE id = :id AND archivedAtEpochMillis IS NULL")
    suspend fun findActiveById(id: UUID): TeaEntity?

    @Query("UPDATE teas SET stockMilliGrams = :stockMilliGrams WHERE id = :teaId AND archivedAtEpochMillis IS NULL")
    suspend fun updateStock(teaId: UUID, stockMilliGrams: Long): Int

    @Query("SELECT * FROM teas WHERE archivedAtEpochMillis IS NULL ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<TeaEntity>>

    @Transaction
    @Query("SELECT * FROM teas WHERE archivedAtEpochMillis IS NULL ORDER BY name COLLATE NOCASE")
    fun observeActiveCatalog(): Flow<List<TeaWithCatalogData>>

    @Transaction
    @Query("SELECT * FROM teas WHERE id = :teaId AND archivedAtEpochMillis IS NULL LIMIT 1")
    fun observeActiveDetail(teaId: UUID): Flow<TeaWithDetailData?>

    @Transaction
    @Query("SELECT * FROM teas WHERE id = :teaId AND archivedAtEpochMillis IS NULL LIMIT 1")
    suspend fun findActiveDetailForEdit(teaId: UUID): TeaWithDetailData?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tea: TeaEntity)

    @Update
    suspend fun update(tea: TeaEntity)

    @Query("DELETE FROM teas WHERE id = :teaId")
    suspend fun deleteById(teaId: UUID): Int

    @Query("""
        UPDATE teas SET name = :name, categoryRefId = :categoryRefId, description = :description,
            technicalInformation = :technicalInformation, personalNotes = :personalNotes,
            olfactoryRating = :olfactoryRating, tasteRating = :tasteRating,
            brewTemperatureCelsius = :brewTemperatureCelsius, infusionDurationSeconds = :infusionDurationSeconds,
            statusRefId = :statusRefId, containerRefId = :containerRefId, stockMilliGrams = :stockMilliGrams
        WHERE id = :teaId AND archivedAtEpochMillis IS NULL
    """)
    suspend fun updateEditableFields(
        teaId: UUID,
        name: String,
        categoryRefId: UUID,
        description: String?,
        technicalInformation: String?,
        personalNotes: String?,
        olfactoryRating: Double?,
        tasteRating: Double?,
        brewTemperatureCelsius: Int?,
        infusionDurationSeconds: Int?,
        statusRefId: UUID?,
        containerRefId: UUID?,
        stockMilliGrams: Long?,
    ): Int

    @Query("DELETE FROM tea_regions WHERE teaId = :teaId")
    suspend fun deleteRegionsForTea(teaId: UUID)

    @Query("DELETE FROM tea_regions WHERE teaId = :teaId AND regionRefId = :regionId")
    suspend fun deleteRegion(teaId: UUID, regionId: UUID)

    @Query("DELETE FROM tea_countries WHERE teaId = :teaId")
    suspend fun deleteCountriesForTea(teaId: UUID)

    @Query("DELETE FROM tea_countries WHERE teaId = :teaId AND countryRefId = :countryId")
    suspend fun deleteCountry(teaId: UUID, countryId: UUID)

    @Query("DELETE FROM tea_aromas WHERE teaId = :teaId")
    suspend fun deleteAromasForTea(teaId: UUID)

    @Query("DELETE FROM tea_aromas WHERE teaId = :teaId AND aromaRefId = :referenceId")
    suspend fun deleteAroma(teaId: UUID, referenceId: UUID)

    @Query("DELETE FROM tea_aromatic_profiles WHERE teaId = :teaId")
    suspend fun deleteAromaticProfilesForTea(teaId: UUID)

    @Query("DELETE FROM tea_aromatic_profiles WHERE teaId = :teaId AND profileRefId = :referenceId")
    suspend fun deleteAromaticProfile(teaId: UUID, referenceId: UUID)

    @Query("DELETE FROM tea_moments WHERE teaId = :teaId")
    suspend fun deleteMomentsForTea(teaId: UUID)

    @Query("DELETE FROM tea_moments WHERE teaId = :teaId AND momentRefId = :referenceId")
    suspend fun deleteMoment(teaId: UUID, referenceId: UUID)

    @Query("DELETE FROM tea_badges WHERE teaId = :teaId")
    suspend fun deleteBadgesForTea(teaId: UUID)

    @Query("DELETE FROM tea_badges WHERE teaId = :teaId AND badgeRefId = :referenceId")
    suspend fun deleteBadge(teaId: UUID, referenceId: UUID)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCountries(values: List<TeaCountryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRegions(values: List<TeaRegionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAromas(values: List<TeaAromaEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAromaticProfiles(values: List<TeaAromaticProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMoments(values: List<TeaMomentEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBadges(values: List<TeaBadgeEntity>)
}

data class TeaWithCatalogData(
    @Embedded val tea: TeaEntity,
    @Relation(parentColumn = "categoryRefId", entityColumn = "id")
    val category: ReferenceEntity,
    @Relation(parentColumn = "statusRefId", entityColumn = "id")
    val status: ReferenceEntity?,
    @Relation(parentColumn = "containerRefId", entityColumn = "id")
    val container: ReferenceEntity?,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = TeaCountryEntity::class, parentColumn = "teaId", entityColumn = "countryRefId"),
    )
    val countries: List<ReferenceEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = TeaRegionEntity::class, parentColumn = "teaId", entityColumn = "regionRefId"),
    )
    val regions: List<ReferenceEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = TeaAromaEntity::class, parentColumn = "teaId", entityColumn = "aromaRefId"),
    )
    val aromas: List<ReferenceEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = TeaAromaticProfileEntity::class, parentColumn = "teaId", entityColumn = "profileRefId"),
    )
    val aromaticProfiles: List<ReferenceEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = TeaMomentEntity::class, parentColumn = "teaId", entityColumn = "momentRefId"),
    )
    val moments: List<ReferenceEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = TeaBadgeEntity::class, parentColumn = "teaId", entityColumn = "badgeRefId"),
    )
    val badges: List<ReferenceEntity>,
)

data class TeaWithDetailData(
    @Embedded val tea: TeaEntity,
    @Relation(parentColumn = "categoryRefId", entityColumn = "id")
    val category: ReferenceEntity,
    @Relation(parentColumn = "statusRefId", entityColumn = "id")
    val status: ReferenceEntity?,
    @Relation(parentColumn = "containerRefId", entityColumn = "id")
    val container: ReferenceEntity?,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = TeaCountryEntity::class, parentColumn = "teaId", entityColumn = "countryRefId"),
    )
    val countries: List<ReferenceEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = TeaRegionEntity::class, parentColumn = "teaId", entityColumn = "regionRefId"),
    )
    val regions: List<ReferenceEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = TeaAromaEntity::class, parentColumn = "teaId", entityColumn = "aromaRefId"),
    )
    val aromas: List<ReferenceEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = TeaAromaticProfileEntity::class, parentColumn = "teaId", entityColumn = "profileRefId"),
    )
    val aromaticProfiles: List<ReferenceEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = TeaMomentEntity::class, parentColumn = "teaId", entityColumn = "momentRefId"),
    )
    val moments: List<ReferenceEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = TeaBadgeEntity::class, parentColumn = "teaId", entityColumn = "badgeRefId"),
    )
    val badges: List<ReferenceEntity>,
)

@Dao
interface TastingDao {
    @Query("SELECT * FROM tastings ORDER BY id")
    suspend fun allForBackup(): List<TastingEntity>
    @Query("SELECT COUNT(*) FROM tastings")
    suspend fun count(): Int

    @Query("SELECT * FROM tastings WHERE teaId = :teaId ORDER BY occurredAtEpochMillis DESC, recordedAtEpochMillis DESC, id DESC")
    fun observeForTea(teaId: UUID): Flow<List<TastingEntity>>

    @Query("SELECT * FROM tastings WHERE id = :tastingId AND teaId = :teaId LIMIT 1")
    suspend fun findForTea(tastingId: UUID, teaId: UUID): TastingEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tasting: TastingEntity)

    @Update
    suspend fun update(tasting: TastingEntity)

    /** Call within a transaction that also recalculates any later stock movements for this tea. */
    @Query("DELETE FROM tastings WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("DELETE FROM tastings WHERE id = :tastingId AND teaId = :teaId")
    suspend fun deleteForTea(tastingId: UUID, teaId: UUID): Int
}

@Dao
interface StockMovementDao {
    @Query("SELECT * FROM stock_movements ORDER BY id")
    suspend fun allForBackup(): List<StockMovementEntity>
    @Query("SELECT COUNT(*) FROM stock_movements")
    suspend fun count(): Int

    @Query("SELECT * FROM stock_movements WHERE teaId = :teaId ORDER BY occurredAtEpochMillis, recordedAtEpochMillis")
    fun observeForTea(teaId: UUID): Flow<List<StockMovementEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(movement: StockMovementEntity)

    @Update
    suspend fun update(movement: StockMovementEntity)

    /** Call within a transaction that recalculates the tea's current stock and later balances. */
    @Query("DELETE FROM stock_movements WHERE id = :id")
    suspend fun delete(id: UUID)
}

@Dao
interface TeaPhotoDao {
    @Query("SELECT * FROM tea_photos ORDER BY teaId, position, id")
    suspend fun allForBackup(): List<TeaPhotoEntity>
    @Query("SELECT * FROM tea_photos WHERE teaId = :teaId ORDER BY position")
    fun observeForTea(teaId: UUID): Flow<List<TeaPhotoEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: TeaPhotoEntity)

    @Query("DELETE FROM tea_photos WHERE id = :id")
    suspend fun delete(id: UUID)
}

@Dao
interface ImportedDatasetDao {
    @Query("SELECT * FROM imported_datasets ORDER BY datasetId")
    suspend fun allForBackup(): List<ImportedDatasetEntity>
    @Query("SELECT * FROM imported_datasets WHERE datasetId = :datasetId LIMIT 1")
    suspend fun findByDatasetId(datasetId: UUID): ImportedDatasetEntity?

    @Query("SELECT * FROM imported_datasets WHERE isSuperseded = 0 LIMIT 1")
    suspend fun findAny(): ImportedDatasetEntity?

    @Query("UPDATE imported_datasets SET isSuperseded = 1 WHERE datasetId IN (:datasetIds)")
    suspend fun markSuperseded(datasetIds: List<UUID>): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(dataset: ImportedDatasetEntity)
}
