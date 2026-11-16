package fr.thesdupalais.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SemanticDictionaryDao {
    @Query("SELECT * FROM semantic_concepts WHERE deleted = 0 ORDER BY domain, label COLLATE NOCASE, id")
    fun observeConcepts(): Flow<List<SemanticConceptEntity>>

    @Query("SELECT * FROM semantic_expressions ORDER BY conceptId, isPrimary DESC, normalizedForm, id")
    fun observeExpressions(): Flow<List<SemanticExpressionEntity>>

    @Query("SELECT * FROM semantic_relations ORDER BY sourceConceptId, targetConceptId, id")
    fun observeRelations(): Flow<List<SemanticRelationEntity>>

    @Query("SELECT * FROM semantic_associations ORDER BY sourceConceptId, targetConceptId, id")
    fun observeAssociations(): Flow<List<SemanticAssociationEntity>>

    @Query("SELECT * FROM semantic_concepts WHERE deleted = 0")
    suspend fun allConcepts(): List<SemanticConceptEntity>

    @Query("SELECT * FROM semantic_concepts ORDER BY id")
    suspend fun allConceptRowsForBackup(): List<SemanticConceptEntity>

    @Query("SELECT * FROM semantic_expressions")
    suspend fun allExpressions(): List<SemanticExpressionEntity>

    @Query("SELECT * FROM semantic_relations")
    suspend fun allRelations(): List<SemanticRelationEntity>

    @Query("SELECT * FROM semantic_associations ORDER BY sourceConceptId, targetConceptId, id")
    suspend fun allAssociations(): List<SemanticAssociationEntity>

    @Query("SELECT * FROM semantic_associations WHERE sourceConceptId = :conceptId OR targetConceptId = :conceptId ORDER BY sourceConceptId, targetConceptId")
    suspend fun associationsForConcept(conceptId: String): List<SemanticAssociationEntity>

    @Query("SELECT * FROM semantic_associations WHERE id = :id LIMIT 1")
    suspend fun findAssociation(id: String): SemanticAssociationEntity?

    @Query("SELECT * FROM semantic_associations WHERE sourceConceptId = :sourceId AND targetConceptId = :targetId LIMIT 1")
    suspend fun findAssociation(sourceId: String, targetId: String): SemanticAssociationEntity?

    @Query("DELETE FROM semantic_relations")
    suspend fun clearRelationsForReplacement()

    @Query("DELETE FROM semantic_associations")
    suspend fun clearAssociationsForReplacement()

    @Query("DELETE FROM semantic_expressions")
    suspend fun clearExpressionsForReplacement()

    @Query("DELETE FROM semantic_concepts")
    suspend fun clearConceptsForReplacement()

    @Query("SELECT * FROM semantic_concepts WHERE id = :id AND deleted = 0 LIMIT 1")
    suspend fun findConcept(id: String): SemanticConceptEntity?

    @Query("SELECT * FROM semantic_expressions WHERE id = :id LIMIT 1")
    suspend fun findExpression(id: String): SemanticExpressionEntity?

    @Query("SELECT * FROM semantic_expressions WHERE conceptId = :conceptId AND normalizedForm = :normalizedForm LIMIT 1")
    suspend fun findExpressionByForm(conceptId: String, normalizedForm: String): SemanticExpressionEntity?

    @Query("SELECT c.id, c.label, e.expression FROM semantic_expressions e INNER JOIN semantic_concepts c ON c.id = e.conceptId WHERE c.domain = :domain AND e.normalizedForm = :normalizedForm AND e.expression != '' AND c.deleted = 0 AND c.id != :excludedConceptId LIMIT 1")
    suspend fun findExpressionConflict(domain: String, normalizedForm: String, excludedConceptId: String): SemanticExpressionConflict?

    @Query("SELECT * FROM semantic_expressions WHERE conceptId = :conceptId ORDER BY isPrimary DESC, normalizedForm, id")
    suspend fun expressionsForConcept(conceptId: String): List<SemanticExpressionEntity>

    @Query("SELECT * FROM semantic_relations WHERE sourceConceptId = :conceptId OR targetConceptId = :conceptId ORDER BY sourceConceptId, targetConceptId")
    suspend fun relationsForConcept(conceptId: String): List<SemanticRelationEntity>

    @Query("SELECT * FROM semantic_relations WHERE sourceConceptId = :sourceId AND targetConceptId = :targetId AND type = :type LIMIT 1")
    suspend fun findRelation(sourceId: String, targetId: String, type: String): SemanticRelationEntity?

    @Query("SELECT COUNT(*) FROM semantic_expressions WHERE conceptId = :conceptId AND isPrimary = 0 AND expression != ''")
    suspend fun countExpressions(conceptId: String): Int

    @Query("SELECT COUNT(*) FROM semantic_relations WHERE (sourceConceptId = :conceptId OR targetConceptId = :conceptId) AND active = 1")
    suspend fun countRelations(conceptId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeedConcepts(values: List<SemanticConceptEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeedExpressions(values: List<SemanticExpressionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeedRelations(values: List<SemanticRelationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRelation(value: SemanticRelationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRelations(values: List<SemanticRelationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAssociations(values: List<SemanticAssociationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConcept(value: SemanticConceptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExpressions(values: List<SemanticExpressionEntity>)

    @Query("DELETE FROM semantic_expressions WHERE conceptId = :conceptId")
    suspend fun deleteExpressionsForConcept(conceptId: String)

    @Query("UPDATE semantic_expressions SET expression = '', active = 0 WHERE id = :id")
    suspend fun tombstoneExpression(id: String): Int

    @Query("DELETE FROM semantic_expressions WHERE id = :id")
    suspend fun deleteExpression(id: String): Int

    @Query("DELETE FROM semantic_relations WHERE id = :id")
    suspend fun deleteRelation(id: String): Int

    @Query("UPDATE semantic_relations SET active = 0 WHERE id = :id")
    suspend fun deactivateRelation(id: String): Int

    @Query("DELETE FROM semantic_associations WHERE id = :id")
    suspend fun deleteAssociation(id: String): Int

    @Query("UPDATE semantic_associations SET active = :active WHERE id = :id")
    suspend fun updateAssociationActive(id: String, active: Boolean): Int

    @Update
    suspend fun updateAssociation(value: SemanticAssociationEntity)

    @Query("DELETE FROM semantic_associations WHERE sourceConceptId = :conceptId OR targetConceptId = :conceptId")
    suspend fun deleteAssociationsForConcept(conceptId: String)

    @Query("DELETE FROM semantic_relations WHERE sourceConceptId = :conceptId OR targetConceptId = :conceptId")
    suspend fun deleteRelationsForConcept(conceptId: String)

    @Query("DELETE FROM semantic_concepts WHERE id = :id")
    suspend fun deleteConcept(id: String): Int

    @Query("UPDATE semantic_concepts SET active = 0, deleted = 1 WHERE id = :id AND deleted = 0")
    suspend fun markConceptDeleted(id: String): Int

    @Update
    suspend fun updateConcept(value: SemanticConceptEntity)

    @Update
    suspend fun updateExpression(value: SemanticExpressionEntity)

    @Update
    suspend fun updateRelation(value: SemanticRelationEntity)
}

data class SemanticExpressionConflict(val id: String, val label: String, val expression: String)
