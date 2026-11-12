package fr.thesdupalais.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.thesdupalais.data.operation.BackupOperationPendingException
import fr.thesdupalais.data.operation.BackupOperationWriteCoordinator
import fr.thesdupalais.data.operation.RecoveryFinding
import fr.thesdupalais.data.operation.RecoveryInspection
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArticleRepositoryTest {
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun insertsUpdatesAndKeepsSameTitleForDifferentIds() = runBlocking {
        val repository = ArticleRepository(database)
        val first = ArticleEntity(UUID.randomUUID(), "Même titre", "Histoire", "Texte initial")
        val second = ArticleEntity(UUID.randomUUID(), "Même titre", "Culture", "Autre texte")

        repository.insert(first)
        repository.insert(second)
        assertEquals(2, repository.observeAll().first().size)

        val updated = first.copy(category = "Botanique", markdown = "Texte modifié")
        assertTrue(repository.updateById(updated))
        assertEquals(updated, repository.findById(first.id))
        assertFalse(repository.updateById(updated.copy(id = UUID.randomUUID())))
    }

    @Test fun deletesArticleById() = runBlocking {
        val repository = ArticleRepository(database)
        val article = ArticleEntity(UUID.randomUUID(), "À supprimer", "Culture", "")
        repository.insert(article)

        assertTrue(repository.deleteById(article.id))
        assertFalse(repository.deleteById(article.id))
        assertNull(repository.findById(article.id))
    }

    @Test fun refusesWritesWhenRecoveryBlocksTheCoordinator() = runBlocking {
        val blocked = BackupOperationWriteCoordinator {
            RecoveryInspection(RecoveryFinding.UNCOMMITTED_OPERATION_NEEDS_DECISION, operationId = "pending")
        }
        val repository = ArticleRepository(database, blocked)
        val article = ArticleEntity(UUID.randomUUID(), "Savoir", "Culture", "")

        try {
            repository.insert(article)
            throw AssertionError("Write should have been blocked")
        } catch (_: BackupOperationPendingException) {
            // Expected: no row is written while recovery is pending.
        }
        assertNull(database.articleDao().findById(article.id))
    }
}
