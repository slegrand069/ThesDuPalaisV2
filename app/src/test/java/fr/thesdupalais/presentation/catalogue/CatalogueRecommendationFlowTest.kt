package fr.thesdupalais.presentation.catalogue

import fr.thesdupalais.domain.madjo.MadjoRecommendationCriteria
import fr.thesdupalais.domain.madjo.MadjoRecommendationOutcome
import fr.thesdupalais.domain.madjo.MadjoTeaProfile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogueRecommendationFlowTest {
    @Test fun typingQueryDoesNotRecomputeVisibleMadjoRecommendations() = runBlocking {
        val profile = MadjoTeaProfile("tea", "Sencha", "green", "GREEN", "Thé vert")
        val profiles = MutableStateFlow(listOf(profile))
        val request = MadjoResultsRequest(MadjoRecommendationCriteria(emptyList()))
        val requests = MutableStateFlow<MadjoResultsRequest?>(request)
        val query = MutableStateFlow("")
        var calls = 0
        val emissions = Channel<MadjoRecommendationOutcome?>(Channel.UNLIMITED)
        val collection = launch {
            observeMadjoResults(profiles, requests) { _, _ ->
                calls++
                MadjoRecommendationOutcome(emptyList(), emptyList(), emptyList())
            }.take(2).collect(emissions::send)
        }

        withTimeout(1_000) { emissions.receive() }
        query.value = "Madjo thé vert"
        assertEquals(1, calls)
        // Changing the explicit request triggers a rerank; the independent query state above does not.
        requests.value = MadjoResultsRequest(MadjoRecommendationCriteria(listOf(
            fr.thesdupalais.domain.madjo.MadjoCriterion(
                fr.thesdupalais.domain.madjo.MadjoDimension.TYPE_COLOR, "category", "GREEN",
                fr.thesdupalais.domain.madjo.MadjoCriterionKind.ABSOLUTE_CONSTRAINT,
                fr.thesdupalais.domain.madjo.MadjoCriterionSource.INITIAL_REQUEST,
            ),
        )))
        withTimeout(1_000) { emissions.receive() }
        assertEquals(2, calls)
        collection.join()
    }
}
