package com.wannaphong.hostai

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * OpenAI-compatible API server implementation using NanoHTTPD.
 * Implements the following endpoints:
 * - POST /v1/chat/completions - Chat completions (OpenAI format)
 * - POST /v1/completions - Text completions (OpenAI format)
 * - GET /v1/models - List available models
 * - GET /chat - Chat UI interface
 */
class OpenAIApiServer(
    private val port: Int,
    private val model: LlamaModel,
    private val context: Context
) : NanoHTTPD(port) {
    
    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
    
    companion object {
        private const val TAG = "OpenAIApiServer"
        // Maximum request body size (10 MB) to prevent memory exhaustion attacks
        private const val MAX_REQUEST_BODY_SIZE = 10 * 1024 * 1024
    }
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        
        LogManager.d(TAG, "Received ${method.name} request to $uri")
        
        return try {
            when {
                uri == "/health" && method == Method.GET -> handleHealth()
                uri == "/v1/models" && method == Method.GET -> handleModels()
                uri == "/v1/chat/completions" && method == Method.POST -> handleChatCompletions(session)
                uri == "/v1/completions" && method == Method.POST -> handleCompletions(session)
                uri == "/" && method == Method.GET -> handleRoot()
                uri == "/chat" && method == Method.GET -> handleChatUI()
                uri.startsWith("/assets/") && method == Method.GET -> {
                    val fileName = uri.substring(8) // Remove "/assets/"
                    handleAssets(fileName)
                }
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not Found"
                )
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling request", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to mapOf("message" to e.message)))
            )
        }
    }
    
    private fun handleHealth(): Response {
        LogManager.d(TAG, "Handling /health")
        
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
    
    private fun handleModels(): Response {
        LogManager.d(TAG, "Handling /v1/models")
        
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
    
    private fun handleRoot(): Response {
        LogManager.d(TAG, "Handling /")
        
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
    
    private fun handleChatUI(): Response {
        LogManager.d(TAG, "Handling /chat")
        
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
    
    private fun handleAssets(fileName: String): Response {
        LogManager.d(TAG, "Handling /assets/$fileName")
        
        try {
            // Security: Prevent path traversal attacks
            if (fileName.contains("..") || fileName.startsWith("/") || fileName.contains("\\")) {
                LogManager.w(TAG, "Rejected potential path traversal attempt: $fileName")
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
            
            return newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        } catch (e: Exception) {
            LogManager.e(TAG, "Error loading asset: $fileName", e)
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Asset not found"
            )
        }
    }
    
    private fun handleChatCompletions(session: IHTTPSession): Response {
        LogManager.d(TAG, "Handling /v1/chat/completions")
        
        return try {
            val bodyText = getRequestBody(session)
            val request = gson.fromJson(bodyText, JsonObject::class.java)
            
            LogManager.i(TAG, "Chat completion request received")
            
            // Extract parameters
            val messages = request.getAsJsonArray("messages")
            val stream = request.get("stream")?.asBoolean ?: false
            
            // Build generation config from request parameters
            val config = extractGenerationConfig(request)
            
            // Build prompt from messages
            val prompt = buildPromptFromMessages(messages)
            
            // Log a preview of the prompt to verify character encoding
            val promptPreview = if (prompt.length > 100) prompt.take(100) + "..." else prompt
            LogManager.d(TAG, "Prompt preview: $promptPreview")
            LogManager.d(TAG, "Chat completion - stream: $stream, maxTokens: ${config.maxTokens}, temp: ${config.temperature}")
            
            if (stream) {
                handleChatStreamingResponse(prompt, config)
            } else {
                handleChatNonStreamingResponse(prompt, config)
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling chat completions", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to mapOf("message" to e.message)))
            )
        }
    }
    
    private fun handleChatNonStreamingResponse(
        prompt: String,
        config: GenerationConfig
    ): Response {
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
    
    private fun handleChatStreamingResponse(
        prompt: String,
        config: GenerationConfig
    ): Response {
        LogManager.i(TAG, "Starting chat streaming response")
        
        val pipedOutputStream = PipedOutputStream()
        val pipedInputStream = PipedInputStream(pipedOutputStream)
        
        val id = "chatcmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1000
        
        // Start streaming in a coroutine
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val writer = pipedOutputStream.writer(Charsets.UTF_8)
                var tokenCount = 0
                
                val job = model.generateStream(prompt, config) { token ->
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
                    } catch (e: IOException) {
                        // Client disconnected - stop streaming gracefully
                        LogManager.d(TAG, "Client disconnected during streaming (token #$tokenCount)")
                        throw e
                    } catch (e: Exception) {
                        LogManager.e(TAG, "Error writing token to stream", e)
                        throw e
                    }
                }
                
                // Wait for streaming to complete, or handle error if job is null
                if (job != null) {
                    job.join()
                } else {
                    LogManager.e(TAG, "Failed to start streaming: generateStream returned null")
                    // Write error chunk to client
                    val errorChunk = mapOf(
                        "id" to id,
                        "object" to "chat.completion.chunk",
                        "created" to created,
                        "model" to model.getModelName(),
                        "choices" to listOf(
                            mapOf(
                                "index" to 0,
                                "delta" to mapOf(
                                    "content" to "Error: Failed to start streaming"
                                ),
                                "finish_reason" to "error"
                            )
                        )
                    )
                    writer.write("data: ${gson.toJson(errorChunk)}\n\n")
                    writer.write("data: [DONE]\n\n")
                    writer.flush()
                    writer.close()
                    return@launch
                }
                
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
                try {
                    pipedOutputStream.close()
                } catch (ex: Exception) {
                    // Ignore
                }
            }
        }
        
        val response = newChunkedResponse(
            Response.Status.OK,
            "text/event-stream",
            pipedInputStream
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        return response
    }
    
    private fun handleCompletions(session: IHTTPSession): Response {
        LogManager.d(TAG, "Handling /v1/completions")
        
        return try {
            val bodyText = getRequestBody(session)
            val request = gson.fromJson(bodyText, JsonObject::class.java)
            
            // Extract parameters
            val prompt = request.get("prompt")?.asString ?: ""
            val stream = request.get("stream")?.asBoolean ?: false
            
            // Build generation config from request parameters
            val config = extractGenerationConfig(request)
            
            if (stream) {
                handleCompletionStreamingResponse(prompt, config)
            } else {
                handleCompletionNonStreamingResponse(prompt, config)
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling completions", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to mapOf("message" to e.message)))
            )
        }
    }
    
    private fun handleCompletionNonStreamingResponse(
        prompt: String,
        config: GenerationConfig
    ): Response {
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
    
    private fun handleCompletionStreamingResponse(
        prompt: String,
        config: GenerationConfig
    ): Response {
        LogManager.i(TAG, "Starting completion streaming response")
        
        val pipedOutputStream = PipedOutputStream()
        val pipedInputStream = PipedInputStream(pipedOutputStream)
        
        val id = "cmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1000
        
        // Start streaming in a coroutine
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val writer = pipedOutputStream.writer(Charsets.UTF_8)
                var tokenCount = 0
                
                val job = model.generateStream(prompt, config) { token ->
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
                    } catch (e: IOException) {
                        // Client disconnected - stop streaming gracefully
                        LogManager.d(TAG, "Client disconnected during streaming (token #$tokenCount)")
                        throw e
                    } catch (e: Exception) {
                        LogManager.e(TAG, "Error writing token to stream", e)
                        throw e
                    }
                }
                
                // Wait for streaming to complete, or handle error if job is null
                if (job != null) {
                    job.join()
                } else {
                    LogManager.e(TAG, "Failed to start streaming: generateStream returned null")
                    // Write error chunk to client
                    val errorChunk = mapOf(
                        "id" to id,
                        "object" to "text_completion",
                        "created" to created,
                        "model" to model.getModelName(),
                        "choices" to listOf(
                            mapOf(
                                "text" to "Error: Failed to start streaming",
                                "index" to 0,
                                "finish_reason" to "error"
                            )
                        )
                    )
                    writer.write("data: ${gson.toJson(errorChunk)}\n\n")
                    writer.write("data: [DONE]\n\n")
                    writer.flush()
                    writer.close()
                    return@launch
                }
                
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
                try {
                    pipedOutputStream.close()
                } catch (ex: Exception) {
                    // Ignore
                }
            }
        }
        
        val response = newChunkedResponse(
            Response.Status.OK,
            "text/event-stream",
            pipedInputStream
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        return response
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
     * Supports parameters compatible with LiteRT's SamplerConfig.
     */
    private fun extractGenerationConfig(request: JsonObject): GenerationConfig {
        return GenerationConfig(
            maxTokens = request.get("max_tokens")?.asInt ?: 100,
            temperature = request.get("temperature")?.asDouble ?: 0.7,
            topK = request.get("top_k")?.asInt ?: 40,
            topP = request.get("top_p")?.asDouble ?: 0.95,
            seed = request.get("seed")?.asInt ?: -1
        )
    }
    
    /**
     * Safely receive request body with size limits to prevent memory exhaustion attacks.
     */
    private fun getRequestBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        
        // Security: Check content length before reading
        if (contentLength > MAX_REQUEST_BODY_SIZE) {
            LogManager.w(TAG, "Request body too large: $contentLength bytes (max: $MAX_REQUEST_BODY_SIZE)")
            throw IOException("Request body too large")
        }
        
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        
        return files["postData"] ?: ""
    }
}
