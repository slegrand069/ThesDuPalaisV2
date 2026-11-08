package fr.thesdupalais.data.database

import androidx.room.TypeConverter
import java.util.UUID

class DatabaseConverters {
    @TypeConverter
    fun uuidToString(value: UUID?): String? = value?.toString()

    @TypeConverter
    fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)
}
