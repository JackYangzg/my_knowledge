package com.my.knowledge.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeEntityKindTest {
    @Test
    fun conceptKindsMatchIntermediateDataGrouping() {
        assertTrue(isKnowledgeConceptType("concept"))
        assertTrue(isKnowledgeConceptType("Algorithm"))
        assertTrue(isKnowledgeConceptType(" framework "))
        assertTrue(isKnowledgeConceptType("method"))

        assertFalse(isKnowledgeConceptType("person"))
        assertFalse(isKnowledgeConceptType("organization"))
        assertFalse(isKnowledgeConceptType(""))

        assertEquals("concept", knowledgeEntityTopLevelKind("algorithm"))
        assertEquals("entity", knowledgeEntityTopLevelKind("person"))
        assertEquals("entity", knowledgeEntityTopLevelKind("entity:Algorithm"))
        assertEquals("concept", knowledgeEntityTopLevelKind("concept:Organization"))
        assertEquals("algorithm", normalizeKnowledgeEntityType("entity:Algorithm"))
        assertEquals("organization", normalizeKnowledgeEntityType("concept:Organization"))
    }
}
