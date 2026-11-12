package fr.thesdupalais.presentation.articles

import fr.thesdupalais.data.database.ArticleEntity
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleDeletionControllerTest {
    @Test fun confirmationDeletesOnlyTheSelectedArticleUuid() = runBlocking {
        val selected = article("Même titre")
        val other = article("Même titre")
        val rows = mutableMapOf(selected.id to selected, other.id to other)
        val controller = ArticleDeletionController { id -> rows.remove(id) != null }

        controller.request(selected)
        controller.confirm()

        assertFalse(rows.containsKey(selected.id))
        assertTrue(rows.containsKey(other.id))
        assertNull(controller.state.value.pendingArticle)
        assertNull(controller.state.value.errorMessage)
    }

    @Test fun cancellingConfirmationDoesNotCallTheRepository() = runBlocking {
        val selected = article("À conserver")
        var calls = 0
        val controller = ArticleDeletionController { calls++; true }

        controller.request(selected)
        controller.cancel()
        controller.confirm()

        assertEquals(0, calls)
        assertNull(controller.state.value.pendingArticle)
    }

    @Test fun reportsDeletionErrorsWithoutClaimingSuccess() = runBlocking {
        val selected = article("Article protégé")
        val controller = ArticleDeletionController { error("Base indisponible") }

        controller.request(selected)
        controller.confirm()

        assertNull(controller.state.value.pendingArticle)
        assertNull(controller.state.value.deletingId)
        assertTrue(controller.state.value.errorMessage.orEmpty().contains("Impossible de confirmer"))
        assertTrue(controller.state.value.errorMessage.orEmpty().contains("Base indisponible"))
    }

    @Test fun reportsWhenTheArticleDisappearsWhileConfirmationIsOpen() = runBlocking {
        val selected = article("Déjà supprimé ailleurs")
        val controller = ArticleDeletionController { false }

        controller.request(selected)
        controller.onArticlesChanged(emptyList())

        assertNull(controller.state.value.pendingArticle)
        assertTrue(controller.state.value.errorMessage.orEmpty().contains("demande de suppression a été annulée"))
    }

    @Test fun reportsWhenRepositoryFindsNoMatchingArticleAtConfirmationTime() = runBlocking {
        val controller = ArticleDeletionController { false }
        controller.request(article("Absent au dernier moment"))
        controller.confirm()
        assertTrue(controller.state.value.errorMessage.orEmpty().contains("Aucune suppression n'a été effectuée"))
    }

    @Test fun refreshesConfirmationTitleIfTheSelectedArticleChangesInTheList() {
        val selected = article("Ancien titre")
        val renamed = selected.copy(title = "Titre actualisé")
        val controller = ArticleDeletionController { true }

        controller.request(selected)
        controller.onArticlesChanged(listOf(renamed))

        assertEquals("Titre actualisé", controller.state.value.pendingArticle?.title)
    }

    @Test fun ignoresRepeatedConfirmationWhileDeletionIsInFlight() = runBlocking {
        val selected = article("Suppression en cours")
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Boolean>()
        var calls = 0
        val controller = ArticleDeletionController {
            calls++
            started.complete(Unit)
            finish.await()
        }

        controller.request(selected)
        val first = launch { controller.confirm() }
        started.await()
        controller.confirm()
        assertEquals(1, calls)
        assertEquals(selected.id, controller.state.value.deletingId)

        finish.complete(true)
        first.join()
        assertNull(controller.state.value.deletingId)
        assertNull(controller.state.value.pendingArticle)
    }

    private fun article(title: String) = ArticleEntity(UUID.randomUUID(), title, "Culture", "")
}
