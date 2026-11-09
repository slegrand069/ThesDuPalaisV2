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
    @Query("SELECT COUNT(*) FROM teas")
    suspend fun count(): Int

    @Query("SELECT * FROM teas WHERE id = :id")
    suspend fun findById(id: UUID): TeaEntity?

    @Query("SELECT * FROM teas WHERE archivedAtEpochMillis IS NULL ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<TeaEntity>>

    @Transaction
    @Query("SELECT * FROM teas WHERE archivedAtEpochMillis IS NULL ORDER BY name COLLATE NOCASE")
    fun observeActiveCatalog(): Flow<List<TeaWithCatalogData>>

    @Transaction
    @Query("SELECT * FROM teas WHERE id = :teaId AND archivedAtEpochMillis IS NULL LIMIT 1")
    fun observeActiveDetail(teaId: UUID): Flow<TeaWithDetailData?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tea: TeaEntity)

    @Update
    suspend fun update(tea: TeaEntity)

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
    @Query("SELECT COUNT(*) FROM tastings")
    suspend fun count(): Int

    @Query("SELECT * FROM tastings WHERE teaId = :teaId ORDER BY occurredAtEpochMillis DESC, recordedAtEpochMillis DESC, id DESC")
    fun observeForTea(teaId: UUID): Flow<List<TastingEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tasting: TastingEntity)

    @Update
    suspend fun update(tasting: TastingEntity)

    /** Call within a transaction that also recalculates any later stock movements for this tea. */
    @Query("DELETE FROM tastings WHERE id = :id")
    suspend fun delete(id: UUID)
}

@Dao
interface StockMovementDao {
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
    @Query("SELECT * FROM tea_photos WHERE teaId = :teaId ORDER BY position")
    fun observeForTea(teaId: UUID): Flow<List<TeaPhotoEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: TeaPhotoEntity)

    @Query("DELETE FROM tea_photos WHERE id = :id")
    suspend fun delete(id: UUID)
}

@Dao
interface ImportedDatasetDao {
    @Query("SELECT * FROM imported_datasets WHERE datasetId = :datasetId LIMIT 1")
    suspend fun findByDatasetId(datasetId: UUID): ImportedDatasetEntity?

    @Query("SELECT * FROM imported_datasets LIMIT 1")
    suspend fun findAny(): ImportedDatasetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(dataset: ImportedDatasetEntity)
}
