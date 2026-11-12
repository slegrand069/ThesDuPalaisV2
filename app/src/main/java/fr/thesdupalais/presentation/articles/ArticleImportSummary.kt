package fr.thesdupalais.presentation.articles

import fr.thesdupalais.data.importer.ArticleImportResult

enum class ArticleImportOutcome { COMPLETE, WITH_REJECTIONS, INTERRUPTED, PARTIAL }

fun ArticleImportResult.outcome(): ArticleImportOutcome = when {
    globalError != null && added + updated > 0 -> ArticleImportOutcome.PARTIAL
    globalError != null -> ArticleImportOutcome.INTERRUPTED
    rejected > 0 -> ArticleImportOutcome.WITH_REJECTIONS
    else -> ArticleImportOutcome.COMPLETE
}
