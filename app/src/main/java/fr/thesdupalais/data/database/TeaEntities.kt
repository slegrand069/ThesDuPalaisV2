package fr.thesdupalais.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.util.UUID

@Entity(
    tableName = "teas",
    foreignKeys = [
        ForeignKey(
            entity = ReferenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryRefId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ReferenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["statusRefId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ReferenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["containerRefId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["legacyId"], unique = true),
        Index(value = ["categoryRefId"]),
        Index(value = ["statusRefId"]),
        Index(value = ["containerRefId"]),
        Index(value = ["name"]),
    ],
)
data class TeaEntity(
    @androidx.room.PrimaryKey val id: UUID,
    val legacyId: Long?,
    val name: String,
    val categoryRefId: UUID,
    val description: String?,
    val technicalInformation: String?,
    val personalNotes: String?,
    /** Ratings are nullable values from 0.0 to 10.0; input validation belongs to the domain layer. */
    val olfactoryRating: Double?,
    val tasteRating: Double?,
    val brewTemperatureCelsius: Int?,
    /** Nullable duration in seconds, preserving sub-minute precision without imposing a range. */
    val infusionDurationSeconds: Int?,
    val statusRefId: UUID?,
    val containerRefId: UUID?,
    /** Null means the physical stock has not yet been inventoried. */
    val stockMilliGrams: Long?,
    val archivedAtEpochMillis: Long?,
)

@Entity(
    tableName = "tea_countries",
    primaryKeys = ["teaId", "countryRefId"],
    foreignKeys = [
        ForeignKey(
            entity = TeaEntity::class,
            parentColumns = ["id"],
            childColumns = ["teaId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReferenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["countryRefId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["countryRefId"])],
)
data class TeaCountryEntity(val teaId: UUID, val countryRefId: UUID)

@Entity(
    tableName = "tea_regions",
    primaryKeys = ["teaId", "regionRefId"],
    foreignKeys = [
        ForeignKey(
            entity = TeaCountryEntity::class,
            parentColumns = ["teaId", "countryRefId"],
            childColumns = ["teaId", "countryRefId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReferenceEntity::class,
            parentColumns = ["id", "parentCountryId"],
            childColumns = ["regionRefId", "countryRefId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["teaId", "countryRefId"]),
        Index(value = ["regionRefId", "countryRefId"]),
    ],
)
data class TeaRegionEntity(
    val teaId: UUID,
    val regionRefId: UUID,
    /** A region association requires this country to be associated with the same tea. */
    val countryRefId: UUID,
)

@Entity(
    tableName = "tea_aromas",
    primaryKeys = ["teaId", "aromaRefId"],
    foreignKeys = [
        ForeignKey(entity = TeaEntity::class, parentColumns = ["id"], childColumns = ["teaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ReferenceEntity::class, parentColumns = ["id"], childColumns = ["aromaRefId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["aromaRefId"])],
)
data class TeaAromaEntity(val teaId: UUID, val aromaRefId: UUID)

@Entity(
    tableName = "tea_aromatic_profiles",
    primaryKeys = ["teaId", "profileRefId"],
    foreignKeys = [
        ForeignKey(entity = TeaEntity::class, parentColumns = ["id"], childColumns = ["teaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ReferenceEntity::class, parentColumns = ["id"], childColumns = ["profileRefId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["profileRefId"])],
)
data class TeaAromaticProfileEntity(val teaId: UUID, val profileRefId: UUID)

@Entity(
    tableName = "tea_moments",
    primaryKeys = ["teaId", "momentRefId"],
    foreignKeys = [
        ForeignKey(entity = TeaEntity::class, parentColumns = ["id"], childColumns = ["teaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ReferenceEntity::class, parentColumns = ["id"], childColumns = ["momentRefId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["momentRefId"])],
)
data class TeaMomentEntity(val teaId: UUID, val momentRefId: UUID)

@Entity(
    tableName = "tea_badges",
    primaryKeys = ["teaId", "badgeRefId"],
    foreignKeys = [
        ForeignKey(entity = TeaEntity::class, parentColumns = ["id"], childColumns = ["teaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ReferenceEntity::class, parentColumns = ["id"], childColumns = ["badgeRefId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["badgeRefId"])],
)
data class TeaBadgeEntity(val teaId: UUID, val badgeRefId: UUID)

@Entity(
    tableName = "tastings",
    foreignKeys = [
        ForeignKey(
            entity = TeaEntity::class,
            parentColumns = ["id"],
            childColumns = ["teaId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["teaId", "occurredAtEpochMillis"]),
        Index(value = ["id", "teaId"], unique = true),
    ],
)
data class TastingEntity(
    @androidx.room.PrimaryKey val id: UUID,
    val teaId: UUID,
    val occurredAtEpochMillis: Long,
    val recordedAtEpochMillis: Long,
    /** Null means the amount consumed was not recorded; this event is not a tasting score. */
    val consumedMilliGrams: Long?,
)

/** A movement is written only when stock is known. A null delta denotes an inventory baseline. */
@Entity(
    tableName = "stock_movements",
    foreignKeys = [
        ForeignKey(entity = TeaEntity::class, parentColumns = ["id"], childColumns = ["teaId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(
            entity = TastingEntity::class,
            parentColumns = ["id", "teaId"],
            childColumns = ["tastingId", "teaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["teaId", "occurredAtEpochMillis"]),
        Index(value = ["tastingId", "teaId"], unique = true),
    ],
)
data class StockMovementEntity(
    @androidx.room.PrimaryKey val id: UUID,
    val teaId: UUID,
    val kind: String,
    /** Signed change; null only for an absolute inventory baseline. */
    val deltaMilliGrams: Long?,
    val stockAfterMilliGrams: Long,
    val occurredAtEpochMillis: Long,
    val recordedAtEpochMillis: Long,
    /** One movement at most per tasting prevents a consumption from being counted twice. */
    val tastingId: UUID?,
    val note: String?,
)

@Entity(
    tableName = "tea_photos",
    foreignKeys = [
        ForeignKey(entity = TeaEntity::class, parentColumns = ["id"], childColumns = ["teaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["teaId", "position"], unique = true)],
)
data class TeaPhotoEntity(
    @androidx.room.PrimaryKey val id: UUID,
    val teaId: UUID,
    /** App-private relative file path; no imported photos are created in this step. */
    val relativePath: String,
    val position: Int,
)

@Entity(tableName = "imported_datasets")
data class ImportedDatasetEntity(
    @androidx.room.PrimaryKey val datasetId: UUID,
    val schemaVersion: Int,
    val contentSha256: String,
    val sourceCreatedAtEpochMillis: Long,
    val importedAtEpochMillis: Long,
    val referenceCount: Int,
    val teaCount: Int,
    val tastingCount: Int,
    val stockMovementCount: Int,
    val gramsPerCupMilli: Long,
    val lowStockThresholdMilli: Long,
    val isSuperseded: Boolean = false,
)
