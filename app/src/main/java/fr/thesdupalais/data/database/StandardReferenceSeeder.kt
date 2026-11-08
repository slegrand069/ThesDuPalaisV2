package fr.thesdupalais.data.database

import androidx.room.withTransaction
import fr.thesdupalais.domain.references.StandardReferenceSeeds

class StandardReferenceSeeder(private val database: AppDatabase) {
    suspend fun seed() = database.withTransaction { seedWithinTransaction() }

    suspend fun seedWithinTransaction() {
        val dao = database.referenceDao()
        StandardReferenceSeeds.all.forEach { reference ->
            val semanticKey = requireNotNull(reference.semanticKey)
            val bySemanticKey = dao.findBySemanticKey(reference.kind.name, semanticKey)
            if (bySemanticKey != null) return@forEach

            val byLabel = dao.findByNormalizedLabel(
                kind = reference.kind.name,
                normalizedLabel = reference.normalizedLabel,
                scopeKey = reference.parentCountryId?.toString().orEmpty(),
            )
            if (byLabel != null) {
                if (byLabel.semanticKey == null) {
                    dao.assignSemanticKeyIfMissing(byLabel.id, semanticKey)
                }
            } else {
                dao.insertIfAbsent(ReferenceEntity.fromDomain(reference))
            }
        }
    }
}
