package fr.thesdupalais.domain.model

import java.util.UUID

data class ReferenceValue(
    val id: UUID,
    val kind: ReferenceKind,
    val label: String,
    val normalizedLabel: String,
    val semanticKey: String? = null,
    val parentCountryId: UUID? = null,
    val archivedAt: Long? = null,
)
