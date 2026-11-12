package fr.thesdupalais.data.backup

/** One replacement preview, built from the exact validated archive and local snapshot shown to the user. */
data class BackupReplacementPreviewPlan(
    val selectedSets: Set<String>,
    val comparison: BackupArchivePreview,
    val target: BackupTargetResult,
) {
    val blockers: List<String> = (target.blockers + comparison.groups.flatMap { it.blockingConflicts }).distinct()
    val applicable: Boolean get() = target.applicable && blockers.isEmpty()
}

/** Pure preview planning for replacement; no Room, DataStore, filesystem or confirmation side effects. */
object BackupReplacementPreviewPlanner {
    fun plan(
        archiveBytes: ByteArray,
        local: BackupLocalOperationSnapshot,
        selectedSets: Set<String>,
    ): BackupReplacementPreviewPlan {
        val incoming = BackupArchiveValidator.validate(archiveBytes)
        val localArchive = BackupArchiveValidator.validate(local.archiveBytes)
        require(selectedSets.isNotEmpty() && selectedSets.all { it in setOf("teas", "configuration") }) {
            "Sélectionnez les thés, la configuration, ou les deux. Les articles ne sont pas encore restaurables."
        }
        val comparison = BackupArchivePreviewAnalyzer.analyze(archiveBytes, incoming, localArchive, selectedSets)
        val target = BackupTargetStateBuilder.build(BackupTargetRequest(
            archiveBytes = archiveBytes,
            localSnapshotBytes = local.archiveBytes,
            operation = TargetOperation.REPLACE,
            selectedSets = selectedSets,
            replacementConfirmed = true,
            photos = local.photos,
            importHistory = local.importHistory,
        ))
        return BackupReplacementPreviewPlan(selectedSets.toSortedSet(), comparison, target)
    }
}
