package com.wannaphong.hostai

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * OpenAI-compatible API server implementation using NanoHTTPD.
 * Implements the following endpoints:
 * - POST /v1/chat/completions - Chat completions (OpenAI format)
 * - POST /v1/completions - Text completions (OpenAI format)
 * - GET /v1/models - List available models
 * - GET /chat - Chat UI interface
 */
class OpenAIApiServer(private val port: Int, private val model: LlamaModel, private val context: Context) : NanoHTTPD(port) {
    
    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
    
    companion object {
        private const val TAG = "OpenAIApiServer"
    }
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        
        LogManager.d(TAG, "Received ${method.name} request to $uri")
        
        return try {
            when {
                uri == "/v1/models" && method == Method.GET -> handleModels()
                uri == "/v1/chat/completions" && method == Method.POST -> handleChatCompletions(session)
                uri == "/v1/completions" && method == Method.POST -> handleCompletions(session)
                uri == "/" && method == Method.GET -> handleRoot()
                uri == "/health" && method == Method.GET -> handleHealth()
                uri == "/chat" && method == Method.GET -> handleChatUI()
                uri.startsWith("/assets/") && method == Method.GET -> handleAssets(uri)
                else -> {
                    LogManager.w(TAG, "Endpoint not found: $uri")
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        MIME_PLAINTEXT,
                        "Endpoint not found"
                    )
                }
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling request to $uri", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to mapOf("message" to e.message)))
            )
        }
    }
    
    private fun handleRoot(): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>HostAI - OpenAI Compatible API</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 40px; }
                    h1 { color: #6200EE; }
                    .endpoint { background: #f5f5f5; padding: 10px; margin: 10px 0; border-radius: 5px; }
                    .chat-link { background: #6200EE; color: white; padding: 15px 20px; display: inline-block; margin: 20px 0; text-decoration: none; border-radius: 5px; font-weight: bold; }
                    .chat-link:hover { background: #3700B3; }
                </style>
            </head>
            <body>
                <h1>HostAI - OpenAI Compatible API Server</h1>
                <p>Server is running on port $port</p>
                <a href="/chat" class="chat-link">Open Chat UI</a>
                <h2>Available Endpoints:</h2>
                <div class="endpoint">
                    <strong>GET /v1/models</strong><br>
                    List available models
                </div>
                <div class="endpoint">
                    <strong>POST /v1/chat/completions</strong><br>
                    Chat completion endpoint (OpenAI compatible)
                </div>
                <div class="endpoint">
                    <strong>POST /v1/completions</strong><br>
                    Text completion endpoint (OpenAI compatible)
                </div>
                <div class="endpoint">
                    <strong>GET /health</strong><br>
                    Health check endpoint
                </div>
                <div class="endpoint">
                    <strong>GET /chat</strong><br>
                    Web-based chat interface
                </div>
            </body>
            </html>
        """.trimIndent()
        
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }
    
    private fun handleHealth(): Response {
        val health = mapOf(
            "status" to "ok",
            "model_loaded" to model.isModelLoaded()
        )
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            gson.toJson(health)
        )
    }
    
    private fun handleChatUI(): Response {
        return try {
            val inputStream = context.assets.open("index.html")
            val html = inputStream.bufferedReader().use { it.readText() }
            newFixedLengthResponse(Response.Status.OK, "text/html", html)
        } catch (e: Exception) {
            LogManager.e(TAG, "Error loading chat UI", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/html",
                "<html><body><h1>Error loading chat UI</h1><p>${e.message}</p></body></html>"
            )
        }
    }
    
    private fun handleAssets(uri: String): Response {
        return try {
            // Remove "/assets/" prefix to get the actual file name
            val fileName = uri.removePrefix("/assets/")
            
            // Security: Prevent path traversal attacks
            if (fileName.contains("..") || fileName.startsWith("/") || fileName.contains("\\")) {
                LogManager.w(TAG, "Rejected potential path traversal attempt: $uri")
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    MIME_PLAINTEXT,
                    "Invalid asset path"
                )
            }
            
            // Determine MIME type based on file extension
            val mimeType = when {
                fileName.endsWith(".ico") -> "image/x-icon"
                fileName.endsWith(".json") -> "application/json"
                fileName.endsWith(".html") -> "text/html"
                fileName.endsWith(".css") -> "text/css"
                fileName.endsWith(".js") -> "application/javascript"
                else -> "application/octet-stream"
            }
            
            val inputStream = context.assets.open(fileName)
            val bytes = inputStream.readBytes()
            inputStream.close()
            
            newFixedLengthResponse(Response.Status.OK, mimeType, bytes.inputStream(), bytes.size.toLong())
        } catch (e: Exception) {
            LogManager.e(TAG, "Error loading asset: $uri", e)
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Asset not found"
            )
        }
    }
    
    private fun handleModels(): Response {
        val models = mapOf(
            "object" to "list",
            "data" to listOf(
                mapOf(
                    "id" to model.getModelName(),
                    "object" to "model",
                    "created" to System.currentTimeMillis() / 1000,
                    "owned_by" to "hostai"
                )
            )
        )
        
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            gson.toJson(models)
        )
    }
    
    private fun handleChatCompletions(session: IHTTPSession): Response {
        val body = getRequestBody(session)
        val request = gson.fromJson(body, JsonObject::class.java)
        
        LogManager.i(TAG, "Chat completion request received")
        
        // Extract parameters
        val messages = request.getAsJsonArray("messages")
        val stream = request.get("stream")?.asBoolean ?: false
        
        // Build generation config from request parameters
        val config = extractGenerationConfig(request)
        
        // Build prompt from messages
        val prompt = buildPromptFromMessages(messages)
        
        LogManager.d(TAG, "Chat completion - stream: $stream, maxTokens: ${config.maxTokens}, temp: ${config.temperature}")
        
        if (stream) {
            return handleChatStreamingResponse(prompt, config)
        }
        
        // Generate response
        val completion = model.generate(prompt, config)
        
        val response = mapOf(
            "id" to "chatcmpl-${System.currentTimeMillis()}",
            "object" to "chat.completion",
            "created" to System.currentTimeMillis() / 1000,
            "model" to model.getModelName(),
            "choices" to listOf(
                mapOf(
                    "index" to 0,
                    "message" to mapOf(
                        "role" to "assistant",
                        "content" to completion
                    ),
                    "finish_reason" to "stop"
                )
            ),
            "usage" to mapOf(
                "prompt_tokens" to prompt.split(" ").size,
                "completion_tokens" to completion.split(" ").size,
                "total_tokens" to (prompt.split(" ").size + completion.split(" ").size)
            )
        )
        
        LogManager.i(TAG, "Chat completion completed successfully")
        
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            gson.toJson(response)
        )
    }
    
    private fun handleCompletions(session: IHTTPSession): Response {
        val body = getRequestBody(session)
        val request = gson.fromJson(body, JsonObject::class.java)
        
        // Extract parameters
        val prompt = request.get("prompt")?.asString ?: ""
        val stream = request.get("stream")?.asBoolean ?: false
        
        // Build generation config from request parameters
        val config = extractGenerationConfig(request)
        
        if (stream) {
            return handleCompletionStreamingResponse(prompt, config)
        }
        
        // Generate response
        val completion = model.generate(prompt, config)
        
        val response = mapOf(
            "id" to "cmpl-${System.currentTimeMillis()}",
            "object" to "text_completion",
            "created" to System.currentTimeMillis() / 1000,
            "model" to model.getModelName(),
            "choices" to listOf(
                mapOf(
                    "text" to completion,
                    "index" to 0,
                    "finish_reason" to "stop"
                )
            ),
            "usage" to mapOf(
                "prompt_tokens" to prompt.split(" ").size,
                "completion_tokens" to completion.split(" ").size,
                "total_tokens" to (prompt.split(" ").size + completion.split(" ").size)
            )
        )
        
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            gson.toJson(response)
        )
    }
    
    private fun handleChatStreamingResponse(prompt: String, config: GenerationConfig): Response {
        LogManager.i(TAG, "Starting chat streaming response")
        
        try {
            val pipedOutputStream = PipedOutputStream()
            val pipedInputStream = PipedInputStream(pipedOutputStream)
            
            // Start streaming in a coroutine
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val writer = pipedOutputStream.bufferedWriter()
                    val id = "chatcmpl-${System.currentTimeMillis()}"
                    val created = System.currentTimeMillis() / 1000
                    var tokenCount = 0
                    
                    // Start the stream
                    val streamJob = model.generateStream(prompt, config) { token ->
                        try {
                            tokenCount++
                            
                            // Format according to OpenAI SSE format for chat
                            val chunk = mapOf(
                                "id" to id,
                                "object" to "chat.completion.chunk",
                                "created" to created,
                                "model" to model.getModelName(),
                                "choices" to listOf(
                                    mapOf(
                                        "index" to 0,
                                        "delta" to mapOf(
                                            "content" to token
                                        ),
                                        "finish_reason" to null
                                    )
                                )
                            )
                            
                            // Write SSE format: "data: {json}\n\n"
                            writer.write("data: ${gson.toJson(chunk)}\n\n")
                            writer.flush()
                            
                            LogManager.d(TAG, "Streamed token #$tokenCount")
                        } catch (e: Exception) {
                            LogManager.e(TAG, "Error writing token to stream", e)
                        }
                    }
                    
                    // Wait for streaming to complete
                    streamJob?.join()
                    
                    // Send final chunk with finish_reason
                    val finalChunk = mapOf(
                        "id" to id,
                        "object" to "chat.completion.chunk",
                        "created" to created,
                        "model" to model.getModelName(),
                        "choices" to listOf(
                            mapOf(
                                "index" to 0,
                                "delta" to mapOf<String, String>(),
                                "finish_reason" to "stop"
                            )
                        )
                    )
                    writer.write("data: ${gson.toJson(finalChunk)}\n\n")
                    writer.write("data: [DONE]\n\n")
                    writer.flush()
                    writer.close()
                    
                    LogManager.i(TAG, "Chat streaming completed with $tokenCount tokens")
                } catch (e: Exception) {
                    LogManager.e(TAG, "Error in chat streaming", e)
                    pipedOutputStream.close()
                }
            }
            
            // Return response with text/event-stream content type
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "text/event-stream",
                pipedInputStream,
                -1 // Unknown length for streaming
            )
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Connection", "keep-alive")
            return response
            
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to start chat streaming", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to mapOf("message" to e.message)))
            )
        }
    }
    
    private fun handleCompletionStreamingResponse(prompt: String, config: GenerationConfig): Response {
        LogManager.i(TAG, "Starting completion streaming response")
        
        try {
            val pipedOutputStream = PipedOutputStream()
            val pipedInputStream = PipedInputStream(pipedOutputStream)
            
            // Start streaming in a coroutine
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val writer = pipedOutputStream.bufferedWriter()
                    val id = "cmpl-${System.currentTimeMillis()}"
                    val created = System.currentTimeMillis() / 1000
                    var tokenCount = 0
                    
                    // Start the stream
                    val streamJob = model.generateStream(prompt, config) { token ->
                        try {
                            tokenCount++
                            
                            // Format according to OpenAI SSE format for completions
                            val chunk = mapOf(
                                "id" to id,
                                "object" to "text_completion",
                                "created" to created,
                                "model" to model.getModelName(),
                                "choices" to listOf(
                                    mapOf(
                                        "text" to token,
                                        "index" to 0,
                                        "finish_reason" to null
                                    )
                                )
                            )
                            
                            // Write SSE format: "data: {json}\n\n"
                            writer.write("data: ${gson.toJson(chunk)}\n\n")
                            writer.flush()
                            
                            LogManager.d(TAG, "Streamed token #$tokenCount")
                        } catch (e: Exception) {
                            LogManager.e(TAG, "Error writing token to stream", e)
                        }
                    }
                    
                    // Wait for streaming to complete
                    streamJob?.join()
                    
                    // Send final chunk with finish_reason
                    val finalChunk = mapOf(
                        "id" to id,
                        "object" to "text_completion",
                        "created" to created,
                        "model" to model.getModelName(),
                        "choices" to listOf(
                            mapOf(
                                "text" to "",
                                "index" to 0,
                                "finish_reason" to "stop"
                            )
                        )
                    )
                    writer.write("data: ${gson.toJson(finalChunk)}\n\n")
                    writer.write("data: [DONE]\n\n")
                    writer.flush()
                    writer.close()
                    
                    LogManager.i(TAG, "Completion streaming completed with $tokenCount tokens")
                } catch (e: Exception) {
                    LogManager.e(TAG, "Error in completion streaming", e)
                    pipedOutputStream.close()
                }
            }
            
            // Return response with text/event-stream content type
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "text/event-stream",
                pipedInputStream,
                -1 // Unknown length for streaming
            )
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Connection", "keep-alive")
            return response
            
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to start completion streaming", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to mapOf("message" to e.message)))
            )
        }
    }
    
    private fun buildPromptFromMessages(messages: com.google.gson.JsonArray): String {
        val promptBuilder = StringBuilder()
        for (message in messages) {
            val msgObj = message.asJsonObject
            val role = msgObj.get("role")?.asString ?: ""
            val content = msgObj.get("content")?.asString ?: ""
            promptBuilder.append("$role: $content\n")
        }
        return promptBuilder.toString()
    }
    
    /**
     * Extract generation configuration from OpenAI API request.
     * Supports all parameters from the kotlinllamacpp library.
     */
    private fun extractGenerationConfig(request: JsonObject): GenerationConfig {
        // Extract stop strings array
        val stopStrings = mutableListOf<String>()
        request.get("stop")?.let { stopElement ->
            when {
                stopElement.isJsonArray -> {
                    stopElement.asJsonArray.forEach { 
                        stopStrings.add(it.asString)
                    }
                }
                stopElement.isJsonPrimitive -> {
                    stopStrings.add(stopElement.asString)
                }
            }
        }
        
        // Extract logit_bias if present
        val logitBias = mutableListOf<List<Double>>()
        request.get("logit_bias")?.let { logitBiasElement ->
            if (logitBiasElement.isJsonObject) {
                // OpenAI format: { "token_id": bias_value }
                // Convert to list of [token_id, bias_value] pairs
                logitBiasElement.asJsonObject.entrySet().forEach { entry ->
                    try {
                        val tokenId = entry.key.toDouble()
                        val biasValue = entry.value.asDouble
                        logitBias.add(listOf(tokenId, biasValue))
                    } catch (e: Exception) {
                        LogManager.w(TAG, "Invalid logit_bias entry: ${entry.key}")
                    }
                }
            }
        }
        
        return GenerationConfig(
            maxTokens = request.get("max_tokens")?.asInt ?: 100,
            temperature = request.get("temperature")?.asFloat ?: 0.7f,
            topK = request.get("top_k")?.asInt ?: 40,
            topP = request.get("top_p")?.asFloat ?: 0.95f,
            minP = request.get("min_p")?.asFloat ?: 0.05f,
            tfsZ = request.get("tfs_z")?.asFloat ?: 1.00f,
            typicalP = request.get("typical_p")?.asFloat ?: 1.00f,
            penaltyLastN = request.get("penalty_last_n")?.asInt ?: 64,
            penaltyRepeat = request.get("penalty_repeat")?.asFloat 
                ?: request.get("repetition_penalty")?.asFloat ?: 1.00f,
            penaltyFreq = request.get("penalty_freq")?.asFloat 
                ?: request.get("frequency_penalty")?.asFloat ?: 0.00f,
            penaltyPresent = request.get("penalty_present")?.asFloat 
                ?: request.get("presence_penalty")?.asFloat ?: 0.00f,
            mirostat = request.get("mirostat")?.asFloat ?: 0.00f,
            mirostatTau = request.get("mirostat_tau")?.asFloat ?: 5.00f,
            mirostatEta = request.get("mirostat_eta")?.asFloat ?: 0.10f,
            penalizeNl = request.get("penalize_nl")?.asBoolean ?: false,
            seed = request.get("seed")?.asInt ?: -1,
            nProbs = request.get("n_probs")?.asInt ?: 0,
            grammar = request.get("grammar")?.asString ?: "",
            ignoreEos = request.get("ignore_eos")?.asBoolean ?: false,
            stopStrings = stopStrings,
            logitBias = logitBias
        )
    }
    
    private fun getRequestBody(session: IHTTPSession): String {
        val map = mutableMapOf<String, String>()
        try {
            session.parseBody(map)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to parse request body", e)
            LogManager.e(TAG, "Failed to parse request body", e)
        }
        return map["postData"] ?: ""
    }
}
