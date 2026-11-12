package fr.thesdupalais.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** A single commit marker written in the same Room transaction as future target-state changes. */
@Entity(tableName = "backup_operation_marker")
data class BackupOperationMarkerEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val operationId: String,
    val state: String,
    val archiveSha256: String,
    val targetFingerprint: String,
    val updatedAtEpochMillis: Long,
) {
    companion object { const val SINGLETON_ID = 1 }
}

@Dao
interface BackupOperationMarkerDao {
    @Query("SELECT * FROM backup_operation_marker WHERE singletonId = 1 LIMIT 1")
    suspend fun findActive(): BackupOperationMarkerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(marker: BackupOperationMarkerEntity)

    @Query("DELETE FROM backup_operation_marker WHERE singletonId = 1")
    suspend fun clear()
}
