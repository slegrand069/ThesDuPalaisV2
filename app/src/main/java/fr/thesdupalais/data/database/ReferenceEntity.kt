package fr.thesdupalais.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.thesdupalais.domain.model.ReferenceValue
import java.util.UUID

@Entity(
    tableName = "reference_values",
    foreignKeys = [
        ForeignKey(
            entity = ReferenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentCountryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["kind", "normalizedLabel", "scopeKey"], unique = true),
        Index(value = ["kind", "semanticKey"], unique = true),
        Index(value = ["parentCountryId"]),
        Index(value = ["id", "parentCountryId"], unique = true),
        Index(value = ["kind", "displayPriority"]),
    ],
)
data class ReferenceEntity(
    @PrimaryKey val id: UUID,
    val kind: String,
    val label: String,
    val normalizedLabel: String,
    val semanticKey: String?,
    val parentCountryId: UUID?,
    val scopeKey: String,
    val archivedAt: Long?,
    val displayPriority: Int,
) {
    fun toDomain() = ReferenceValue(
        id = id,
        kind = fr.thesdupalais.domain.model.ReferenceKind.valueOf(kind),
        label = label,
        normalizedLabel = normalizedLabel,
        semanticKey = semanticKey,
        parentCountryId = parentCountryId,
        archivedAt = archivedAt,
        displayPriority = displayPriority,
    )

    companion object {
        fun fromDomain(value: ReferenceValue) = ReferenceEntity(
            id = value.id,
            kind = value.kind.name,
            label = value.label,
            normalizedLabel = value.normalizedLabel,
            semanticKey = value.semanticKey,
            parentCountryId = value.parentCountryId,
            scopeKey = value.parentCountryId?.toString().orEmpty(),
            archivedAt = value.archivedAt,
            displayPriority = value.displayPriority,
        )
    }
}
