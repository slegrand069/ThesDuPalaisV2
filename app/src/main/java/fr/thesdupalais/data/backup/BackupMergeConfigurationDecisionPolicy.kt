package fr.thesdupalais.data.backup

/** Pure selection rules for optional configuration updates in a merge plan. */
object BackupMergeConfigurationDecisionPolicy {
    fun candidates(plan: BackupArchiveMergePlan): Set<ConfirmedMergeChange> = plan.items
        .asSequence()
        .filter { isConfigurationItem(it) }
        .filter { it.disposition in setOf(MergeDisposition.UPDATE_PROPOSED, MergeDisposition.PRESERVED) }
        .flatMap { item -> item.changedFields.asSequence().map { field -> ConfirmedMergeChange(item.entityType, item.identity, field) } }
        .toSet()

    fun acceptAll(plan: BackupArchiveMergePlan, current: Set<ConfirmedMergeChange>): Set<ConfirmedMergeChange> =
        current + candidates(plan)

    fun refuseAll(plan: BackupArchiveMergePlan, current: Set<ConfirmedMergeChange>): Set<ConfirmedMergeChange> =
        current - candidates(plan)

    fun toggle(plan: BackupArchiveMergePlan, current: Set<ConfirmedMergeChange>, change: ConfirmedMergeChange): Set<ConfirmedMergeChange> {
        if (change !in candidates(plan)) return current
        return if (change in current) current - change else current + change
    }

    private fun isConfigurationItem(item: MergeItem): Boolean =
        item.entityType.startsWith("Référence") || item.entityType == "Préférences métier"
}
