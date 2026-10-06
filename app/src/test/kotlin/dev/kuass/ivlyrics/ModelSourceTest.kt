package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test

class ModelSourceTest {
    private val source = Ai.Config("https://example.invalid/v1", "first-example-key", "selected")

    @Test fun `model lists belong to the endpoint and credential used for the request`() {
        assertTrue(source.sameModelSource(source.copy()))
        assertFalse(source.sameModelSource(source.copy(baseUrl = "https://other.invalid/v1")))
        assertFalse(source.sameModelSource(source.copy(apiKey = "second-example-key")))
        assertFalse(source.sameModelSource(source.copy(apiKey = "")))
    }

    @Test fun `selecting or clearing a model does not change the model-list source`() {
        assertTrue(source.sameModelSource(source.copy(model = "new-selection")))
        assertTrue(source.sameModelSource(source.copy(model = "")))
    }

    @Test fun `model-source comparison is symmetric across endpoint key and model changes`() {
        val variants = listOf(source, source.copy(model = "other"), source.copy(apiKey = "other"), source.copy(baseUrl = "other"))
        for (first in variants) for (second in variants) {
            assertEquals(first.sameModelSource(second), second.sameModelSource(first))
        }
    }
}
