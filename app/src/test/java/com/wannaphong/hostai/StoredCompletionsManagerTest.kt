package com.wannaphong.hostai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StoredCompletionsManagerTest {

    private lateinit var tempDir: File
    private lateinit var manager: StoredCompletionsManager

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("hostai_test_completions").toFile()
        manager = StoredCompletionsManager(tempDir)
    }

    @After
    fun tearDown() {
        manager.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun testSaveAndGetCompletion() {
        val completion = StoredCompletion(
            id = "test-1",
            obj = "chat.completion",
            created = 1000L,
            model = "test-model",
            messages = listOf(mapOf("role" to "user", "content" to "hi")),
            responseContent = "hello",
            metadata = null
        )

        manager.saveCompletion(completion)

        val retrieved = manager.getCompletion("test-1")
        assertNotNull(retrieved)
        assertEquals("test-1", retrieved?.id)
        assertEquals("hello", retrieved?.responseContent)
    }

    @Test
    fun testGetAllCompletionsOrder() {
        val c1 = StoredCompletion("c1", "chat.completion", 100L, "m", emptyList(), "r1", null)
        val c2 = StoredCompletion("c2", "chat.completion", 300L, "m", emptyList(), "r2", null)
        val c3 = StoredCompletion("c3", "chat.completion", 200L, "m", emptyList(), "r3", null)

        manager.saveCompletion(c1)
        manager.saveCompletion(c2)
        manager.saveCompletion(c3)

        val all = manager.getAllCompletions()
        assertEquals(3, all.size)
        // Should be ordered descending by created timestamp
        assertEquals("c2", all[0].id)
        assertEquals("c3", all[1].id)
        assertEquals("c1", all[2].id)
    }

    @Test
    fun testUpdateMetadata() {
        val completion = StoredCompletion(
            id = "test-meta",
            obj = "chat.completion",
            created = 1000L,
            model = "test-model",
            messages = emptyList(),
            responseContent = "hi",
            metadata = null
        )

        manager.saveCompletion(completion)

        val updated = manager.updateMetadata("test-meta", mapOf("flag" to "processed"))
        assertTrue(updated)

        val retrieved = manager.getCompletion("test-meta")
        assertEquals("processed", retrieved?.metadata?.get("flag"))

        val notFound = manager.updateMetadata("non-existent", mapOf("a" to "b"))
        assertFalse(notFound)
    }

    @Test
    fun testDeleteCompletion() {
        val completion = StoredCompletion("test-del", "chat.completion", 1000L, "m", emptyList(), "hi", null)
        manager.saveCompletion(completion)

        assertTrue(manager.deleteCompletion("test-del"))
        assertNull(manager.getCompletion("test-del"))
        assertFalse(manager.deleteCompletion("test-del"))
    }

    @Test
    fun testClearAllCompletions() {
        manager.saveCompletion(StoredCompletion("1", "chat.completion", 100L, "m", emptyList(), "a", null))
        manager.saveCompletion(StoredCompletion("2", "chat.completion", 200L, "m", emptyList(), "b", null))

        assertEquals(2, manager.getAllCompletions().size)
        val cleared = manager.clearAllCompletions()
        assertEquals(2, cleared)
        assertEquals(0, manager.getAllCompletions().size)
    }
}

