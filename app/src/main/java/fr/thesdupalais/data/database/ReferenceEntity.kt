package fr.thesdupalais.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.thesdupalais.domain.model.ReferenceValue
import java.util.UUID

@Entity(
    tableName = "reference_values",
    indices = [
        Index(value = ["kind", "normalizedLabel", "scopeKey"], unique = true),
        Index(value = ["kind", "semanticKey"], unique = true),
        Index(value = ["parentCountryId"]),
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
) {
    fun toDomain() = ReferenceValue(
        id = id,
        kind = fr.thesdupalais.domain.model.ReferenceKind.valueOf(kind),
        label = label,
        normalizedLabel = normalizedLabel,
        semanticKey = semanticKey,
        parentCountryId = parentCountryId,
        archivedAt = archivedAt,
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
        )
    }
}
