package fr.thesdupalais.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ReferenceEntity::class,
        TeaEntity::class,
        TeaCountryEntity::class,
        TeaRegionEntity::class,
        TeaAromaEntity::class,
        TeaAromaticProfileEntity::class,
        TeaMomentEntity::class,
        TeaBadgeEntity::class,
        TastingEntity::class,
        StockMovementEntity::class,
        TeaPhotoEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun referenceDao(): ReferenceDao
    abstract fun teaDao(): TeaDao
    abstract fun tastingDao(): TastingDao
    abstract fun stockMovementDao(): StockMovementDao
    abstract fun teaPhotoDao(): TeaPhotoDao
}
