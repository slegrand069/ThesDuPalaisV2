package fr.thesdupalais

import fr.thesdupalais.data.importer.CollectionImportResult

interface FirstRunOperations {
    suspend fun initialize()
    suspend fun isInstallationInitialized(): Boolean
    suspend fun markInstallationInitialized()
    suspend fun importCollection(json: String): CollectionImportResult
}
