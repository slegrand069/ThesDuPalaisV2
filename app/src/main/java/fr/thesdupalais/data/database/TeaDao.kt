package fr.thesdupalais.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface TeaDao {
    @Query("SELECT * FROM teas WHERE id = :id")
    suspend fun findById(id: UUID): TeaEntity?

    @Query("SELECT * FROM teas WHERE archivedAtEpochMillis IS NULL ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<TeaEntity>>

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

@Dao
interface TastingDao {
    @Query("SELECT * FROM tastings WHERE teaId = :teaId ORDER BY occurredAtEpochMillis")
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
