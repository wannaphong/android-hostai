package com.wannaphong.hostai

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LoggedRequestTest {

    private val gson = Gson()

    @Test
    fun testLoggedRequestCreationAndSerialization() {
        val request = LoggedRequest(
            timestamp = 1700000000000L,
            date = "2023-11-14 22:13:20",
            ipAddress = "192.168.1.50",
            endpoint = "/v1/chat/completions",
            requestBody = """{"model":"test","messages":[{"role":"user","content":"hi"}]}""",
            responseBody = """{"id":"chatcmpl-1","choices":[{"message":{"role":"assistant","content":"hello"}}]}"""
        )

        val json = gson.toJson(request)
        assertNotNull(json)

        val deserialized = gson.fromJson(json, LoggedRequest::class.java)
        assertEquals(request.timestamp, deserialized.timestamp)
        assertEquals(request.date, deserialized.date)
        assertEquals(request.ipAddress, deserialized.ipAddress)
        assertEquals(request.endpoint, deserialized.endpoint)
        assertEquals(request.requestBody, deserialized.requestBody)
        assertEquals(request.responseBody, deserialized.responseBody)
    }

    @Test
    fun testLoggedRequestArraySerialization() {
        val list = listOf(
            LoggedRequest(1L, "2023-01-01", "127.0.0.1", "/health", "", "ok"),
            LoggedRequest(2L, "2023-01-02", "127.0.0.1", "/v1/models", "", "list")
        )

        val json = gson.toJson(list)
        val deserialized = gson.fromJson(json, Array<LoggedRequest>::class.java).toList()

        assertEquals(2, deserialized.size)
        assertEquals("/health", deserialized[0].endpoint)
        assertEquals("/v1/models", deserialized[1].endpoint)
    }
}

