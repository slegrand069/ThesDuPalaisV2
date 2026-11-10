package fr.thesdupalais.data.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReferenceDao {
    @Query("SELECT * FROM reference_values WHERE id = :id LIMIT 1")
    suspend fun findById(id: java.util.UUID): ReferenceEntity?

    @Query("SELECT * FROM reference_values WHERE kind = :kind AND normalizedLabel = :normalizedLabel AND scopeKey = :scopeKey AND id != :excludedId LIMIT 1")
    suspend fun findLabelConflict(kind: String, normalizedLabel: String, scopeKey: String, excludedId: java.util.UUID): ReferenceEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(reference: ReferenceEntity)

    @Query("UPDATE reference_values SET label = :label, normalizedLabel = :normalizedLabel WHERE id = :id")
    suspend fun rename(id: java.util.UUID, label: String, normalizedLabel: String): Int

    @Query("""
        SELECT CASE :kind
            WHEN 'CATEGORY' THEN (SELECT COUNT(*) FROM teas WHERE categoryRefId = :id)
            WHEN 'STATUS' THEN (SELECT COUNT(*) FROM teas WHERE statusRefId = :id)
            WHEN 'CONTAINER' THEN (SELECT COUNT(*) FROM teas WHERE containerRefId = :id)
            WHEN 'COUNTRY' THEN (SELECT COUNT(DISTINCT teaId) FROM tea_countries WHERE countryRefId = :id)
            WHEN 'REGION' THEN (SELECT COUNT(DISTINCT teaId) FROM tea_regions WHERE regionRefId = :id)
            WHEN 'AROMA' THEN (SELECT COUNT(DISTINCT teaId) FROM tea_aromas WHERE aromaRefId = :id)
            WHEN 'AROMATIC_PROFILE' THEN (SELECT COUNT(DISTINCT teaId) FROM tea_aromatic_profiles WHERE profileRefId = :id)
            WHEN 'MOMENT' THEN (SELECT COUNT(DISTINCT teaId) FROM tea_moments WHERE momentRefId = :id)
            WHEN 'BADGE' THEN (SELECT COUNT(DISTINCT teaId) FROM tea_badges WHERE badgeRefId = :id)
            ELSE 0
        END
    """)
    suspend fun countTeaUsage(kind: String, id: java.util.UUID): Int

    @Query("SELECT COUNT(*) FROM reference_values WHERE kind = 'REGION' AND parentCountryId = :countryId")
    suspend fun countChildRegions(countryId: java.util.UUID): Int

    @Query("DELETE FROM reference_values WHERE id = :id")
    suspend fun deleteById(id: java.util.UUID): Int

    @Query("SELECT * FROM reference_values WHERE kind = :kind ORDER BY label COLLATE NOCASE")
    fun observeByKind(kind: String): Flow<List<ReferenceEntity>>

    @Query("""
        SELECT r.*,
            CASE r.kind
                WHEN 'CATEGORY' THEN (SELECT COUNT(*) FROM teas t WHERE t.categoryRefId = r.id)
                WHEN 'STATUS' THEN (SELECT COUNT(*) FROM teas t WHERE t.statusRefId = r.id)
                WHEN 'CONTAINER' THEN (SELECT COUNT(*) FROM teas t WHERE t.containerRefId = r.id)
                WHEN 'COUNTRY' THEN (SELECT COUNT(DISTINCT tc.teaId) FROM tea_countries tc WHERE tc.countryRefId = r.id)
                WHEN 'REGION' THEN (SELECT COUNT(DISTINCT tr.teaId) FROM tea_regions tr WHERE tr.regionRefId = r.id)
                WHEN 'AROMA' THEN (SELECT COUNT(DISTINCT ta.teaId) FROM tea_aromas ta WHERE ta.aromaRefId = r.id)
                WHEN 'AROMATIC_PROFILE' THEN (SELECT COUNT(DISTINCT tap.teaId) FROM tea_aromatic_profiles tap WHERE tap.profileRefId = r.id)
                WHEN 'MOMENT' THEN (SELECT COUNT(DISTINCT tm.teaId) FROM tea_moments tm WHERE tm.momentRefId = r.id)
                WHEN 'BADGE' THEN (SELECT COUNT(DISTINCT tb.teaId) FROM tea_badges tb WHERE tb.badgeRefId = r.id)
                ELSE 0
            END AS teaUsageCount,
            CASE r.kind
                WHEN 'COUNTRY' THEN (
                    SELECT COUNT(DISTINCT tc.teaId)
                    FROM tea_countries tc INNER JOIN teas t ON t.id = tc.teaId
                    WHERE tc.countryRefId = r.id AND t.archivedAtEpochMillis IS NULL
                )
                WHEN 'REGION' THEN (
                    SELECT COUNT(DISTINCT tr.teaId)
                    FROM tea_regions tr INNER JOIN teas t ON t.id = tr.teaId
                    WHERE tr.regionRefId = r.id AND t.archivedAtEpochMillis IS NULL
                )
                ELSE 0
            END AS activeTeaUsageCount,
            CASE WHEN r.kind = 'COUNTRY' THEN (
                SELECT COUNT(*) FROM reference_values child
                WHERE child.kind = 'REGION' AND child.parentCountryId = r.id
            ) ELSE 0 END AS childRegionCount,
            (SELECT parent.label FROM reference_values parent
                WHERE parent.id = r.parentCountryId AND parent.kind = 'COUNTRY'
            ) AS parentCountryLabel
        FROM reference_values r
        WHERE r.kind = :kind
        ORDER BY r.displayPriority ASC, r.label COLLATE NOCASE ASC
    """)
    fun observeForManagement(kind: String): Flow<List<ReferenceWithUsageEntity>>

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

data class ReferenceWithUsageEntity(
    @Embedded val reference: ReferenceEntity,
    val teaUsageCount: Int,
    val activeTeaUsageCount: Int,
    val childRegionCount: Int,
    val parentCountryLabel: String?,
)
