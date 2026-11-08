package fr.thesdupalais.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReferenceDao {
    @Query("SELECT * FROM reference_values WHERE kind = :kind ORDER BY label COLLATE NOCASE")
    fun observeByKind(kind: String): Flow<List<ReferenceEntity>>

    @Query("SELECT * FROM reference_values WHERE kind = :kind AND semanticKey = :semanticKey LIMIT 1")
    suspend fun findBySemanticKey(kind: String, semanticKey: String): ReferenceEntity?

    @Query("""
        SELECT * FROM reference_values
        WHERE kind = :kind AND normalizedLabel = :normalizedLabel AND scopeKey = :scopeKey
        LIMIT 1
    """)
    suspend fun findByNormalizedLabel(
        kind: String,
        normalizedLabel: String,
        scopeKey: String,
    ): ReferenceEntity?

    @Query("UPDATE reference_values SET semanticKey = :semanticKey WHERE id = :id AND semanticKey IS NULL")
    suspend fun assignSemanticKeyIfMissing(id: java.util.UUID, semanticKey: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(reference: ReferenceEntity): Long
}
