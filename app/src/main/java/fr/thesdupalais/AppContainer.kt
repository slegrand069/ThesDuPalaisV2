package fr.thesdupalais

import android.content.Context
import androidx.room.Room
import fr.thesdupalais.data.database.AppDatabase
import fr.thesdupalais.data.database.MIGRATION_1_2
import fr.thesdupalais.data.database.MIGRATION_2_3
import fr.thesdupalais.data.importer.CollectionImporter
import fr.thesdupalais.data.database.RoomReferenceRepository
import fr.thesdupalais.data.database.StandardReferenceSeeder
import fr.thesdupalais.data.database.TeaRepository
import fr.thesdupalais.data.preferences.LocalPreferencesRepository
import fr.thesdupalais.data.importer.CollectionImportResult
import fr.thesdupalais.FirstRunOperations

class AppContainer(context: Context) : FirstRunOperations {
    val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "thes-du-palais.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

    val preferences = LocalPreferencesRepository(context)
    val referenceRepository = RoomReferenceRepository(database)
    val teaRepository = TeaRepository(database)
    val collectionImporter = CollectionImporter(database, preferences)

    override suspend fun initialize() {
        StandardReferenceSeeder(database).seed()
    }

    override suspend fun isInstallationInitialized(): Boolean = preferences.isInstallationInitialized()

    override suspend fun markInstallationInitialized() = preferences.markInstallationInitialized()

    override suspend fun importCollection(json: String): CollectionImportResult = collectionImporter.importJson(json)
}
