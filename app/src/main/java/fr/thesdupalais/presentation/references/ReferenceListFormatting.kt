package fr.thesdupalais.presentation.references

import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceSummary
import fr.thesdupalais.domain.model.frenchLabelComparator

data class ReferenceListSection(
    val title: String,
    val emptyMessage: String,
    val references: List<ReferenceSummary>,
)

fun geographicReferenceSections(
    kind: ReferenceKind,
    references: List<ReferenceSummary>,
): List<ReferenceListSection>? {
    if (kind != ReferenceKind.COUNTRY && kind != ReferenceKind.REGION) return null

    val alphabetical: (List<ReferenceSummary>) -> List<ReferenceSummary> = { values ->
        values.sortedWith(frenchLabelComparator({ it.value.label }, { it.value.id.toString() }))
    }
    val used = alphabetical(references.filter { it.activeTeaUsageCount > 0 })
    val other = alphabetical(references.filter { it.activeTeaUsageCount == 0 })
    val usedTitle = if (kind == ReferenceKind.COUNTRY) "Utilisés dans ma collection" else "Utilisées dans ma collection"
    val noun = if (kind == ReferenceKind.COUNTRY) "pays" else "régions"

    return listOf(
        ReferenceListSection(usedTitle, "Aucun $noun associé à un thé actif", used),
        ReferenceListSection("Autres $noun", "Aucun autre $noun", other),
    )
}

fun ReferenceSummary.displayLabel(): String = when (value.kind) {
    ReferenceKind.REGION -> "${value.label} (${parentCountryLabel ?: "Pays indisponible"})"
    else -> value.label
}
