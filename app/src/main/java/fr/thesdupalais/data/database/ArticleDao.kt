package fr.thesdupalais.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY title COLLATE NOCASE, id")
    fun observeAll(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE id = :id LIMIT 1")
    fun observeById(id: UUID): Flow<ArticleEntity?>

    @Query("SELECT * FROM articles WHERE id = :id LIMIT 1")
    suspend fun findById(id: UUID): ArticleEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(article: ArticleEntity)

    @Query("UPDATE articles SET title = :title, category = :category, markdown = :markdown WHERE id = :id")
    suspend fun updateById(id: UUID, title: String, category: String, markdown: String): Int

    @Query("DELETE FROM articles WHERE id = :id")
    suspend fun deleteById(id: UUID): Int
}
