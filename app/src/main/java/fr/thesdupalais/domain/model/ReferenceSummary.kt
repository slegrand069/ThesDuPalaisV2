package fr.thesdupalais.domain.model

/** A reference value together with the tea and region relations that currently use it. */
data class ReferenceSummary(
    val value: ReferenceValue,
    val teaUsageCount: Int,
    val activeTeaUsageCount: Int,
    val childRegionCount: Int,
    val parentCountryLabel: String?,
)
