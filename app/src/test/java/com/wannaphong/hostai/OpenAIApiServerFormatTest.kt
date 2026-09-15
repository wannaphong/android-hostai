package com.wannaphong.hostai

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIApiServerFormatTest {

    private val gson = Gson()

    @Test
    fun testModelListResponseFormat() {
        val modelName = "gemma-3-4b"
        val models = mapOf(
            "object" to "list",
            "data" to listOf(
                mapOf(
                    "id" to modelName,
                    "object" to "model",
                    "created" to 1700000000L,
                    "owned_by" to "hostai"
                )
            )
        )

        val json = gson.toJson(models)
        val obj = gson.fromJson(json, JsonObject::class.java)

        assertEquals("list", obj.get("object").asString)
        val data = obj.getAsJsonArray("data")
        assertEquals(1, data.size())

        val first = data[0].asJsonObject
        assertEquals(modelName, first.get("id").asString)
        assertEquals("model", first.get("object").asString)
        assertEquals("hostai", first.get("owned_by").asString)
    }

    @Test
    fun testSingleModelResponseFormat() {
        val modelName = "phi-4-mini"
        val modelData = mapOf(
            "id" to modelName,
            "object" to "model",
            "created" to 1700000000L,
            "owned_by" to "hostai"
        )

        val json = gson.toJson(modelData)
        val obj = gson.fromJson(json, JsonObject::class.java)

        assertEquals(modelName, obj.get("id").asString)
        assertEquals("model", obj.get("object").asString)
        assertEquals("hostai", obj.get("owned_by").asString)
    }

    @Test
    fun testDeleteStoredCompletionResponseFormat() {
        val completionId = "chatcmpl-12345"
        val response = mapOf(
            "id" to completionId,
            "object" to "chat.completion.deleted",
            "deleted" to true
        )

        val json = gson.toJson(response)
        val obj = gson.fromJson(json, JsonObject::class.java)

        assertEquals(completionId, obj.get("id").asString)
        assertEquals("chat.completion.deleted", obj.get("object").asString)
        assertTrue(obj.get("deleted").asBoolean)
    }

    @Test
    fun testStreamingChunkFormatWithUsage() {
        val id = "chatcmpl-stream-1"
        val created = 1700000000L
        val model = "gemma-3-1b"

        val chunk = mutableMapOf<String, Any>(
            "id" to id,
            "object" to "chat.completion.chunk",
            "created" to created,
            "model" to model,
            "choices" to listOf(
                mapOf(
                    "index" to 0,
                    "delta" to emptyMap<String, String>(),
                    "finish_reason" to "stop"
                )
            ),
            "usage" to mapOf(
                "prompt_tokens" to 15,
                "completion_tokens" to 42,
                "total_tokens" to 57
            )
        )

        val json = gson.toJson(chunk)
        val obj = gson.fromJson(json, JsonObject::class.java)

        assertEquals("chat.completion.chunk", obj.get("object").asString)
        val usage = obj.getAsJsonObject("usage")
        assertNotNull(usage)
        assertEquals(15, usage.get("prompt_tokens").asInt)
        assertEquals(42, usage.get("completion_tokens").asInt)
        assertEquals(57, usage.get("total_tokens").asInt)
    }

    @Test
    fun testStoredCompletionDataClass() {
        val completion = StoredCompletion(
            id = "chatcmpl-test",
            obj = "chat.completion",
            created = 1700000000L,
            model = "test-model",
            messages = listOf(
                mapOf("role" to "user", "content" to "Hello"),
                mapOf("role" to "assistant", "content" to "Hi there!")
            ),
            responseContent = "Hi there!",
            metadata = mapOf("custom_tag" to "v1")
        )

        val json = gson.toJson(completion)
        val deserialized = gson.fromJson(json, StoredCompletion::class.java)

        assertEquals(completion.id, deserialized.id)
        assertEquals(completion.obj, deserialized.obj)
        assertEquals(completion.created, deserialized.created)
        assertEquals(completion.model, deserialized.model)
        assertEquals(completion.responseContent, deserialized.responseContent)
        assertEquals(2, deserialized.messages.size)
        assertEquals("v1", deserialized.metadata?.get("custom_tag"))
    }
}

