package fr.thesdupalais.data.database

import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomReferenceRepository(private val dao: ReferenceDao) {
    fun observe(kind: ReferenceKind): Flow<List<ReferenceSummary>> =
        dao.observeForManagement(kind.name).map { values ->
            values.map { item ->
                ReferenceSummary(
                    value = item.reference.toDomain(),
                    teaUsageCount = item.teaUsageCount,
                    activeTeaUsageCount = item.activeTeaUsageCount,
                    childRegionCount = item.childRegionCount,
                    parentCountryLabel = item.parentCountryLabel,
                )
            }
        }
}
