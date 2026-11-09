package fr.thesdupalais.presentation.references

import fr.thesdupalais.domain.model.ReferenceKind

data class ReferenceFamily(val kind: ReferenceKind, val label: String)

val referenceFamilies = listOf(
    ReferenceFamily(ReferenceKind.CATEGORY, "Catégories"),
    ReferenceFamily(ReferenceKind.STATUS, "Statuts"),
    ReferenceFamily(ReferenceKind.COUNTRY, "Pays"),
    ReferenceFamily(ReferenceKind.REGION, "Régions"),
    ReferenceFamily(ReferenceKind.AROMA, "Arômes"),
    ReferenceFamily(ReferenceKind.AROMATIC_PROFILE, "Profils aromatiques"),
    ReferenceFamily(ReferenceKind.MOMENT, "Moments"),
    ReferenceFamily(ReferenceKind.BADGE, "Badges"),
    ReferenceFamily(ReferenceKind.CONTAINER, "Contenants"),
)

fun ReferenceKind.displayName(): String =
    referenceFamilies.single { it.kind == this }.label
