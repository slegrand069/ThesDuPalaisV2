package fr.thesdupalais.data.database

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "semantic_concepts", indices = [Index(value = ["domain", "active"])])
data class SemanticConceptEntity(
    @PrimaryKey val id: String,
    val domain: String,
    val label: String,
    val active: Boolean,
    val businessDimension: String?,
    val businessConcept: String?,
    val businessValue: String?,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Entity(
    tableName = "semantic_expressions",
    foreignKeys = [ForeignKey(entity = SemanticConceptEntity::class, parentColumns = ["id"], childColumns = ["conceptId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["conceptId", "normalizedForm"], unique = true), Index(value = ["normalizedForm", "active"])],
)
data class SemanticExpressionEntity(
    @PrimaryKey val id: String,
    val conceptId: String,
    val expression: String,
    val normalizedForm: String,
    val isPrimary: Boolean,
    val active: Boolean,
)

@Entity(
    tableName = "semantic_relations",
    foreignKeys = [
        ForeignKey(entity = SemanticConceptEntity::class, parentColumns = ["id"], childColumns = ["sourceConceptId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = SemanticConceptEntity::class, parentColumns = ["id"], childColumns = ["targetConceptId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["sourceConceptId", "targetConceptId", "type"], unique = true), Index(value = ["targetConceptId"])],
)
data class SemanticRelationEntity(
    @PrimaryKey val id: String,
    val sourceConceptId: String,
    val targetConceptId: String,
    val type: String,
    val active: Boolean,
)
