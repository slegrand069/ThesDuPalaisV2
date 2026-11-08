package fr.thesdupalais

import android.content.Context
import androidx.room.Room
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.MIGRATION_1_2
import fr.thesdupalais.data.database.RoomReferenceRepository
import fr.thesdupalais.data.database.StandardReferenceSeeder
import fr.thesdupalais.data.preferences.LocalPreferencesRepository

class AppContainer(context: Context) {
    val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "thes-du-palais.db",
    ).addMigrations(MIGRATION_1_2).build()

    val preferences = LocalPreferencesRepository(context)
    val referenceRepository = RoomReferenceRepository(database.referenceDao())

    suspend fun initialize() {
        StandardReferenceSeeder(database).seed()
    }
}
