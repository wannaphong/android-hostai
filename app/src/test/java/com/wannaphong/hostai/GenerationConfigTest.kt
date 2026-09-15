package com.wannaphong.hostai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationConfigTest {

    @Test
    fun testDefaultValues() {
        val config = GenerationConfig()
        assertEquals(100, config.maxTokens)
        assertEquals(0.7, config.temperature, 0.001)
        assertEquals(40, config.topK)
        assertEquals(0.95, config.topP, 0.001)
        assertEquals(-1, config.seed)
        assertNull(config.extraContext)
    }

    @Test
    fun testCustomValues() {
        val extra = mapOf<String, Any>("thinking_mode" to true, "budget" to 500)
        val config = GenerationConfig(
            maxTokens = 2048,
            temperature = 0.2,
            topK = 50,
            topP = 0.9,
            seed = 42,
            extraContext = extra
        )
        assertEquals(2048, config.maxTokens)
        assertEquals(0.2, config.temperature, 0.001)
        assertEquals(50, config.topK)
        assertEquals(0.9, config.topP, 0.001)
        assertEquals(42, config.seed)
        assertEquals(extra, config.extraContext)
    }
}

