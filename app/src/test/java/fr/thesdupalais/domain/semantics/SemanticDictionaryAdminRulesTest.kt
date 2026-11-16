package fr.thesdupalais.domain.semantics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticDictionaryAdminRulesTest {
    @Test fun conceptCanBeRenamedWithoutChangingItsStableId() {
        val original = SemanticConcept("stable-id", SemanticDomain.AROMA, "Fruité")
        val renamed = original.copy(label = "Profil fruité")
        assertEquals(original.id, renamed.id)
        assertEquals("Profil fruité", renamed.label)
    }

    @Test fun requiredLabelAndNormalizedDuplicateSynonymsAreRejected() {
        assertTrue(runCatching { SemanticDictionaryValidator.normalizedExpressions("  ", emptyList()) }.isFailure)
        assertTrue(runCatching { SemanticDictionaryValidator.normalizedExpressions("Fruité", listOf("FRUITÉ")) }.isFailure)
        assertEquals(listOf("fruit", "fruits"), SemanticDictionaryValidator.normalizedExpressions("fruit", listOf("fruits")))
    }

    @Test fun synonymConflictsAcrossConceptsInSameDomainAreReported() {
        val owner = SemanticConcept("owner", SemanticDomain.AROMA, "Agrumes")
        val duplicate = SemanticExpression("e1", owner.id, "zeste", "zeste", false)
        val snapshot = SemanticDictionarySnapshot(listOf(owner), listOf(duplicate))
        val result = runCatching {
            SemanticDictionaryValidator.checkSynonymConflicts(SemanticDomain.AROMA, listOf("ZÉSTE"), snapshot, "new-concept")
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Agrumes"))
    }

    @Test fun businessLinksAreDomainSpecificAndIntentLinksRemainProtected() {
        SemanticDictionaryValidator.validateConceptMapping(
            SemanticConcept("aroma", SemanticDomain.AROMA, "Baies", businessDimension = "AROMA", businessConcept = "aroma", businessValue = "ref:123"),
        )
        assertTrue(runCatching {
            SemanticDictionaryValidator.validateConceptMapping(
                SemanticConcept("bad", SemanticDomain.CATEGORY, "Baies", businessDimension = "AROMA", businessConcept = "aroma", businessValue = "ref:123"),
            )
        }.isFailure)
        assertTrue(runCatching {
            SemanticDictionaryValidator.validateConceptMapping(
                SemanticConcept("bad-stock", SemanticDomain.STOCK, "Un autre stock", businessDimension = "STOCK", businessConcept = "stock", businessValue = "available"),
            )
        }.isFailure)
    }

    @Test fun requiredInternalConceptsAreProtectedByStableIds() {
        val required = listOf(
            "top-rated" to SemanticDomain.TASTE_RATING,
            "available-stock" to SemanticDomain.STOCK,
            "abundant-stock" to SemanticDomain.STOCK,
            "frequent-tasting" to SemanticDomain.TASTING_HISTORY,
            "forgotten-tasting" to SemanticDomain.TASTING_HISTORY,
        ).map { (key, domain) ->
            val id = java.util.UUID.nameUUIDFromBytes("semantic-concept-${domain.name.lowercase()}.$key".toByteArray(Charsets.UTF_8)).toString()
            SemanticConcept(id, domain, key)
        }
        assertTrue(required.all(SemanticDictionaryValidator::isProtectedSystemConcept))
        assertFalse(SemanticDictionaryValidator.isProtectedSystemConcept(SemanticConcept("user", SemanticDomain.STOCK, "Stock libre")))
    }

    @Test fun aNewSnapshotAffectsTheNextResolutionImmediately() {
        val concept = SemanticConcept("user-aroma", SemanticDomain.AROMA, "Groseille", businessDimension = "AROMA", businessConcept = "aroma", businessValue = "BERRY")
        val before = SemanticDictionarySnapshot()
        val after = before.copy(
            concepts = listOf(concept),
            expressions = listOf(SemanticExpression("user-expression", concept.id, "petit fruit rouge", "petit fruit rouge", false)),
        )
        assertTrue(MadjoSemanticResolver.resolve("petit fruit rouge", before).matches.isEmpty())
        assertEquals(concept.id, MadjoSemanticResolver.resolve("PETIT FRUIT ROUGE", after).matches.single().concept.id)
    }

    @Test fun deactivatedConceptStopsResolvingAndCanBeReactivated() {
        val activeConcept = SemanticConcept("floral-id", SemanticDomain.AROMA, "Floral")
        val activeExpression = SemanticExpression("floral-expression", activeConcept.id, "fleurs", "fleurs", false)
        val active = SemanticDictionarySnapshot(listOf(activeConcept), listOf(activeExpression))
        val inactive = active.copy(
            concepts = listOf(activeConcept.copy(active = false)),
            expressions = listOf(activeExpression.copy(active = false)),
        )
        assertEquals(1, MadjoSemanticResolver.resolve("fleurs", active).matches.size)
        assertTrue(MadjoSemanticResolver.resolve("fleurs", inactive).matches.isEmpty())
        val reactivated = inactive.copy(
            concepts = listOf(activeConcept.copy(active = true)),
            expressions = listOf(activeExpression.copy(active = true)),
        )
        assertEquals(activeConcept.id, MadjoSemanticResolver.resolve("fleurs", reactivated).matches.single().concept.id)
    }
}
