package fr.thesdupalais.data.database

interface ArticleImportStore {
    /** Holds the shared writer permit for the full import, preventing a restore from interleaving. */
    suspend fun <T> withImportPermit(block: suspend () -> T): T
    suspend fun upsertForImport(article: ArticleEntity): ArticleWriteDisposition
}
