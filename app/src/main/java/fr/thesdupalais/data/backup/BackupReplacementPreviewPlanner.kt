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
        require(selectedSets.isNotEmpty() && selectedSets.all { it in setOf("teas", "configuration", "articles") }) {
            "Sélectionnez un ensemble pris en charge."
        }
        val comparison = BackupArchivePreviewAnalyzer.analyze(archiveBytes, incoming, localArchive, selectedSets, local.articles)
        val target = BackupTargetStateBuilder.build(BackupTargetRequest(
            archiveBytes = archiveBytes,
            localSnapshotBytes = local.archiveBytes,
            operation = TargetOperation.REPLACE,
            selectedSets = selectedSets,
            replacementConfirmed = true,
            photos = local.photos,
            importHistory = local.importHistory,
            localArticles = local.articles,
        ))
        return BackupReplacementPreviewPlan(selectedSets.toSortedSet(), comparison, target)
    }
}
