package fr.thesdupalais.data.database

import androidx.room.withTransaction
import fr.thesdupalais.domain.references.StandardReferenceSeeds

class StandardReferenceSeeder(private val database: AppDatabase) {
    suspend fun seed() = database.withTransaction {
        val dao = database.referenceDao()
        StandardReferenceSeeds.colors.forEach { color ->
            val exists = dao.findBySemanticKey(color.kind.name, requireNotNull(color.semanticKey))
            if (exists == null) {
                dao.insertIfAbsent(ReferenceEntity.fromDomain(color))
            }
        }
    }
}
