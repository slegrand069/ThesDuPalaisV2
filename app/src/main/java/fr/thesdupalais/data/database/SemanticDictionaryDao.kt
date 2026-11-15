package fr.thesdupalais.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SemanticDictionaryDao {
    @Query("SELECT * FROM semantic_concepts ORDER BY domain, label COLLATE NOCASE, id")
    fun observeConcepts(): Flow<List<SemanticConceptEntity>>

    @Query("SELECT * FROM semantic_expressions ORDER BY conceptId, isPrimary DESC, normalizedForm, id")
    fun observeExpressions(): Flow<List<SemanticExpressionEntity>>

    @Query("SELECT * FROM semantic_relations ORDER BY sourceConceptId, targetConceptId, id")
    fun observeRelations(): Flow<List<SemanticRelationEntity>>

    @Query("SELECT * FROM semantic_concepts")
    suspend fun allConcepts(): List<SemanticConceptEntity>

    @Query("SELECT * FROM semantic_expressions")
    suspend fun allExpressions(): List<SemanticExpressionEntity>

    @Query("SELECT * FROM semantic_relations")
    suspend fun allRelations(): List<SemanticRelationEntity>

    @Query("SELECT * FROM semantic_concepts WHERE id = :id LIMIT 1")
    suspend fun findConcept(id: String): SemanticConceptEntity?

    @Query("SELECT * FROM semantic_expressions WHERE id = :id LIMIT 1")
    suspend fun findExpression(id: String): SemanticExpressionEntity?

    @Query("SELECT * FROM semantic_expressions WHERE conceptId = :conceptId AND normalizedForm = :normalizedForm LIMIT 1")
    suspend fun findExpressionByForm(conceptId: String, normalizedForm: String): SemanticExpressionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeedConcepts(values: List<SemanticConceptEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeedExpressions(values: List<SemanticExpressionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeedRelations(values: List<SemanticRelationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRelation(value: SemanticRelationEntity)

    @Update
    suspend fun updateConcept(value: SemanticConceptEntity)

    @Update
    suspend fun updateExpression(value: SemanticExpressionEntity)

    @Update
    suspend fun updateRelation(value: SemanticRelationEntity)
}
