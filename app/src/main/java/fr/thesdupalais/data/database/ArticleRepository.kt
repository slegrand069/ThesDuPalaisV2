package fr.thesdupalais.data.database

import androidx.room.withTransaction
import fr.thesdupalais.data.operation.BackupOperationWriteCoordinator
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Persistence API for read-only knowledge articles and the future JSON importer. */
class ArticleRepository(
    private val database: AppDatabase,
    private val writeCoordinator: BackupOperationWriteCoordinator = BackupOperationWriteCoordinator(),
) : ArticleImportStore {
    fun observeAll(): Flow<List<ArticleEntity>> = database.articleDao().observeAll()

    fun observeById(id: UUID): Flow<ArticleEntity?> = database.articleDao().observeById(id)

    suspend fun findById(id: UUID): ArticleEntity? = database.articleDao().findById(id)

    suspend fun insert(article: ArticleEntity) = writeCoordinator.withWritePermit {
        database.articleDao().insert(article)
    }

    /** Atomically inserts or updates an imported article and reports which operation occurred. */
    override suspend fun <T> withImportPermit(block: suspend () -> T): T = writeCoordinator.withWritePermit(block)

    override suspend fun upsertForImport(article: ArticleEntity): ArticleWriteDisposition = writeCoordinator.withWritePermit {
        database.withTransaction {
            val dao = database.articleDao()
            if (dao.findById(article.id) == null) {
                dao.insert(article)
                ArticleWriteDisposition.ADDED
            } else {
                check(dao.updateById(article.id, article.title, article.category, article.markdown) == 1) {
                    "L'article n'est plus disponible pour mise à jour."
                }
                ArticleWriteDisposition.UPDATED
            }
        }
    }

    /** Returns false when the UUID does not exist. */
    suspend fun updateById(article: ArticleEntity): Boolean = writeCoordinator.withWritePermit {
        database.articleDao().updateById(article.id, article.title, article.category, article.markdown) == 1
    }

    /** Returns false when the UUID does not exist. */
    suspend fun deleteById(id: UUID): Boolean = writeCoordinator.withWritePermit {
        database.articleDao().deleteById(id) == 1
    }
}

enum class ArticleWriteDisposition { ADDED, UPDATED }
