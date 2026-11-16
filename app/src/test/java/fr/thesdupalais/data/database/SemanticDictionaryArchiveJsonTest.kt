package fr.thesdupalais.data.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticDictionaryArchiveJsonTest {
    private fun sample() = SemanticDictionaryArchive(
        concepts = listOf(
            SemanticConceptEntity("00000000-0000-0000-0000-000000000002", "AROMA", "Inactif", false, null, null, null),
            SemanticConceptEntity("00000000-0000-0000-0000-000000000001", "AROMA", "Fruité", true, "AROMA", "aroma", "FRUITY"),
            SemanticConceptEntity("00000000-0000-0000-0000-000000000003", "AROMA", "Supprimé", false, null, null, null, deleted = true),
        ),
        expressions = listOf(
            SemanticExpressionEntity("e2", "00000000-0000-0000-0000-000000000001", "fruits rouges", "fruits rouges", false, true),
            SemanticExpressionEntity("e1", "00000000-0000-0000-0000-000000000001", "Fruité", "fruite", true, true),
            SemanticExpressionEntity("e5", "00000000-0000-0000-0000-000000000002", "Inactif", "inactif", true, false),
            SemanticExpressionEntity("e3", "00000000-0000-0000-0000-000000000003", "", "supprime", false, false),
        ),
        relations = listOf(SemanticRelationEntity("r1", "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", "SPECIALIZATION", false)),
    )

    @Test fun exportIsDeterministicAndSorted() {
        val archive = sample()
        val reversed = archive.copy(concepts = archive.concepts.reversed(), expressions = archive.expressions.reversed(), relations = archive.relations.reversed())
        assertEquals(SemanticDictionaryArchiveJson.encode(archive), SemanticDictionaryArchiveJson.encode(reversed))
    }

    @Test fun exportImportRoundTripPreservesIdsMappingsStatesAndTombstones() {
        val original = sample()
        val decoded = SemanticDictionaryArchiveJson.decode(SemanticDictionaryArchiveJson.encode(original))
        assertEquals(original.concepts.sortedBy { it.id }, decoded.concepts)
        assertEquals(original.expressions.sortedWith(compareBy<SemanticExpressionEntity> { it.conceptId }.thenBy { it.id }), decoded.expressions)
        assertEquals(original.relations, decoded.relations)
        assertEquals("FRUITY", decoded.concepts.first { it.label == "Fruité" }.businessValue)
        assertFalse(decoded.concepts.first { it.label == "Inactif" }.active)
        assertTrue(decoded.concepts.first { it.deleted }.deleted)
    }

    @Test fun rejectsInvalidJsonAndUnsupportedFutureVersion() {
        assertThrows(Exception::class.java) { SemanticDictionaryArchiveJson.decode("{") }
        val future = SemanticDictionaryArchiveJson.encode(sample()).replace("\"version\": 1", "\"version\": 99")
        assertThrows(IllegalArgumentException::class.java) { SemanticDictionaryArchiveJson.decode(future) }
    }

    @Test fun rejectsCyclesAndUnknownReferencesBeforeReplacement() {
        val cycle = sample().copy(relations = listOf(
            SemanticRelationEntity("r1", "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", "SPECIALIZATION", true),
            SemanticRelationEntity("r2", "00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000001", "SPECIALIZATION", true),
        ))
        assertThrows(IllegalArgumentException::class.java) { SemanticDictionaryArchiveJson.decode(SemanticDictionaryArchiveJson.encode(cycle)) }

        val refId = "10000000-0000-0000-0000-000000000001"
        val added = SemanticConceptEntity("00000000-0000-0000-0000-000000000004", "AROMA", "Arôme personnalisé", true, "AROMA", "aroma", "ref:$refId")
        val withReference = BuiltInSemanticDictionary.conceptRows + added
        val fullArchive = SemanticDictionaryArchive(
            withReference,
            BuiltInSemanticDictionary.expressionRows + SemanticExpressionEntity("custom-expression", added.id, "arôme personnalisé", "arome personnalise", true, true),
            BuiltInSemanticDictionary.relationRows,
        )
        val decoded = SemanticDictionaryArchiveJson.decode(SemanticDictionaryArchiveJson.encode(fullArchive))
        assertThrows(IllegalArgumentException::class.java) { SemanticDictionaryArchiveRules.validateBusinessLinks(decoded, emptyMap()) }
        SemanticDictionaryArchiveRules.validateBusinessLinks(decoded, mapOf(refId to "AROMA"))
    }

    @Test fun refusesConflictAcrossConceptsAfterAccentNormalization() {
        val added = SemanticConceptEntity("00000000-0000-0000-0000-000000000004", "AROMA", "Autre", true, null, null, null)
        val conflicting = sample().copy(concepts = sample().concepts + added, expressions = sample().expressions + listOf(
            SemanticExpressionEntity("e4", added.id, "Autre", "autre", true, true),
            SemanticExpressionEntity("e6", added.id, "FRUITÉ", "fruite", false, true),
        ))
        assertThrows(IllegalArgumentException::class.java) { SemanticDictionaryArchiveJson.decode(SemanticDictionaryArchiveJson.encode(conflicting)) }
    }
}
