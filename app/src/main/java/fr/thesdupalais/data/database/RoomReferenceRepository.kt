package fr.thesdupalais.data.database

import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomReferenceRepository(private val dao: ReferenceDao) {
    fun observe(kind: ReferenceKind): Flow<List<ReferenceValue>> =
        dao.observeByKind(kind.name).map { values -> values.map(ReferenceEntity::toDomain) }
}
