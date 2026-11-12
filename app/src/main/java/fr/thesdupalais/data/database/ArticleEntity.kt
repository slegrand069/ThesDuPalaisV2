package fr.thesdupalais.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: UUID,
    val title: String,
    val category: String,
    val markdown: String,
)
