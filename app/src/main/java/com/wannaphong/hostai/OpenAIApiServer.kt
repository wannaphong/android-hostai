package com.wannaphong.hostai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import io.javalin.Javalin
import io.javalin.http.Context as JavalinContext
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Data class to store chat completion information.
 */
data class StoredCompletion(
    val id: String,
    val obj: String,
    val created: Long,
    val model: String,
    val messages: List<Map<String, String>>,
    val responseContent: String,
    var metadata: Map<String, Any>?
)

/**
 * OpenAI-compatible API server implementation using Javalin.
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
) {
    
    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
    
    // Coroutine scope for streaming responses
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var app: Javalin? = null
    
    // Storage for chat completions with store=true
    private val storedCompletions = ConcurrentHashMap<String, StoredCompletion>()
    
    companion object {
        private const val TAG = "OpenAIApiServer"
        // Maximum request body size (10 MB) to prevent memory exhaustion attacks
        private const val MAX_REQUEST_BODY_SIZE = 10 * 1024 * 1024
    }
    
    fun start() {
        try {
            app = Javalin.create { config ->
                // Configure Javalin
                config.http.maxRequestSize = MAX_REQUEST_BODY_SIZE.toLong()
                config.showJavalinBanner = false
                config.http.asyncTimeout = 300000L // 5 minutes for streaming
            }.apply {
                // Health check
                get("/health") { ctx -> handleHealth(ctx) }
                
                // Model endpoints
                get("/v1/models") { ctx -> handleModels(ctx) }
                
                // Completion endpoints
                post("/v1/chat/completions") { ctx -> handleChatCompletions(ctx) }
                post("/v1/completions") { ctx -> handleCompletions(ctx) }
                
                // Stored chat completions endpoints
                get("/v1/chat/completions/{completion_id}") { ctx -> handleGetStoredCompletion(ctx) }
                get("/v1/chat/completions/{completion_id}/messages") { ctx -> handleGetStoredCompletionMessages(ctx) }
                post("/v1/chat/completions/{completion_id}") { ctx -> handleUpdateStoredCompletion(ctx) }
                
                // Session management endpoints
                get("/v1/sessions") { ctx -> handleListSessions(ctx) }
                delete("/v1/sessions/{sessionId}") { ctx -> handleDeleteSession(ctx) }
                delete("/v1/sessions") { ctx -> handleClearAllSessions(ctx) }
                
                // UI endpoints
                get("/") { ctx -> handleRoot(ctx) }
                get("/chat") { ctx -> handleChatUI(ctx) }
                get("/assets/{fileName}") { ctx -> handleAssets(ctx) }
                
                // Exception handler
                exception(Exception::class.java) { e, ctx ->
                    LogManager.e(TAG, "Error handling request", e)
                    val errorResponse = mapOf(
                        "error" to mapOf("message" to (e.message ?: "Internal server error"))
                    )
                    ctx.status(500).contentType("application/json").result(gson.toJson(errorResponse))
                }
            }.start(port)
            
            LogManager.i(TAG, "Javalin server started on port $port")
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to start Javalin server", e)
            throw e
        }
    }
    
    fun stop() {
        try {
            app?.stop()
            serverScope.cancel() // Cancel all streaming coroutines
            LogManager.i(TAG, "Javalin server stopped")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error stopping server", e)
        }
    }
    
    private fun handleHealth(ctx: JavalinContext) {
        LogManager.d(TAG, "Handling /health")
        
        val health = mapOf(
            "status" to "ok",
            "model_loaded" to model.isModelLoaded()
        )
        
        ctx.contentType("application/json").result(gson.toJson(health))
    }
    
    private fun handleModels(ctx: JavalinContext) {
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
        
        ctx.contentType("application/json").result(gson.toJson(models))
    }
    
    private fun handleRoot(ctx: JavalinContext) {
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
                    Chat completion endpoint (OpenAI compatible)<br>
                    <em>Supports multi-session via: conversation_id, user, session_id fields or X-Session-ID header</em><br>
                    <em>Set store=true to persist completion for later retrieval</em>
                </div>
                <div class="endpoint">
                    <strong>GET /v1/chat/completions/{completion_id}</strong><br>
                    Get a stored chat completion (only for completions with store=true)
                </div>
                <div class="endpoint">
                    <strong>GET /v1/chat/completions/{completion_id}/messages</strong><br>
                    Get messages from a stored chat completion
                </div>
                <div class="endpoint">
                    <strong>POST /v1/chat/completions/{completion_id}</strong><br>
                    Update metadata for a stored chat completion
                </div>
                <div class="endpoint">
                    <strong>POST /v1/completions</strong><br>
                    Text completion endpoint (OpenAI compatible)<br>
                    <em>Supports multi-session via: conversation_id, user, session_id fields or X-Session-ID header</em>
                </div>
                <div class="endpoint">
                    <strong>GET /v1/sessions</strong><br>
                    List active conversation sessions
                </div>
                <div class="endpoint">
                    <strong>DELETE /v1/sessions/{sessionId}</strong><br>
                    Clear a specific conversation session
                </div>
                <div class="endpoint">
                    <strong>DELETE /v1/sessions</strong><br>
                    Clear all conversation sessions
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
        
        ctx.html(html)
    }
    
    private fun handleChatUI(ctx: JavalinContext) {
        LogManager.d(TAG, "Handling /chat")
        
        try {
            val inputStream = context.assets.open("index.html")
            val html = inputStream.bufferedReader().use { it.readText() }
            ctx.html(html)
        } catch (e: Exception) {
            LogManager.e(TAG, "Error loading chat UI", e)
            ctx.status(500).html(
                "<html><body><h1>Error loading chat UI</h1><p>${e.message}</p></body></html>"
            )
        }
    }
    
    private fun handleAssets(ctx: JavalinContext) {
        val fileName = ctx.pathParam("fileName")
        LogManager.d(TAG, "Handling /assets/$fileName")
        
        try {
            // Security: Prevent path traversal attacks
            if (fileName.contains("..") || fileName.startsWith("/") || fileName.contains("\\")) {
                LogManager.w(TAG, "Rejected potential path traversal attempt: $fileName")
                ctx.status(403).result("Invalid asset path")
                return
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
            
            ctx.contentType(mimeType).result(bytes)
        } catch (e: Exception) {
            LogManager.e(TAG, "Error loading asset: $fileName", e)
            ctx.status(404).result("Asset not found")
        }
    }
    
    private fun handleChatCompletions(ctx: JavalinContext) {
        LogManager.d(TAG, "Handling /v1/chat/completions")
        
        try {
            val bodyText = ctx.body()
            
            // Security: Check content length
            if (bodyText.length > MAX_REQUEST_BODY_SIZE) {
                LogManager.w(TAG, "Request body too large: ${bodyText.length} bytes")
                val errorResponse = mapOf(
                    "error" to mapOf("message" to "Request body too large")
                )
                ctx.status(413).contentType("application/json").result(gson.toJson(errorResponse))
                return
            }
            
            val request = gson.fromJson(bodyText, JsonObject::class.java)
            
            LogManager.i(TAG, "Chat completion request received")
            
            // Extract parameters
            val messages = request.getAsJsonArray("messages")
            val stream = request.get("stream")?.asBoolean ?: false
            val store = request.get("store")?.asBoolean ?: false
            val metadata = request.get("metadata")?.asJsonObject?.let { meta ->
                meta.entrySet().associate { it.key to it.value }
            }
            
            // Extract session ID using helper method
            val sessionId = extractSessionId(ctx, request)
            
            LogManager.d(TAG, "Using session ID: $sessionId, store: $store")
            
            // Build generation config from request parameters
            val config = extractGenerationConfig(request)
            
            // Build prompt from messages
            val prompt = buildPromptFromMessages(messages)
            
            // Log a preview of the prompt to verify character encoding
            val promptPreview = if (prompt.length > 100) prompt.take(100) + "..." else prompt
            LogManager.d(TAG, "Prompt preview: $promptPreview")
            LogManager.d(TAG, "Chat completion - stream: $stream, maxTokens: ${config.maxTokens}, temp: ${config.temperature}")
            
            if (stream) {
                handleChatStreamingResponse(ctx, prompt, config, sessionId, messages, store, metadata)
            } else {
                handleChatNonStreamingResponse(ctx, prompt, config, sessionId, messages, store, metadata)
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling chat completions", e)
            val errorResponse = mapOf(
                "error" to mapOf("message" to (e.message ?: "Internal server error"))
            )
            ctx.status(500).contentType("application/json").result(gson.toJson(errorResponse))
        }
    }
    
    private fun handleChatNonStreamingResponse(
        ctx: JavalinContext,
        prompt: String,
        config: GenerationConfig,
        sessionId: String,
        messages: com.google.gson.JsonArray,
        store: Boolean,
        metadata: Map<String, Any>?
    ) {
        // Generate response with session ID
        val completion = model.generate(prompt, config, sessionId)
        
        val promptTokens = prompt.split(" ").size
        val completionTokens = completion.split(" ").size
        
        val id = "chatcmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1000
        
        // Store completion if store parameter is true
        if (store) {
            val messagesList = messages.map { element ->
                val msgObj = element.asJsonObject
                mapOf(
                    "role" to (msgObj.get("role")?.asString ?: ""),
                    "content" to (msgObj.get("content")?.asString ?: "")
                )
            }
            
            val storedCompletion = StoredCompletion(
                id = id,
                obj = "chat.completion",
                created = created,
                model = model.getModelName(),
                messages = messagesList,
                responseContent = completion,
                metadata = metadata
            )
            
            storedCompletions[id] = storedCompletion
            LogManager.i(TAG, "Stored completion with ID: $id")
        }
        
        val response = mapOf(
            "id" to id,
            "object" to "chat.completion",
            "created" to created,
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
                "prompt_tokens" to promptTokens,
                "completion_tokens" to completionTokens,
                "total_tokens" to (promptTokens + completionTokens)
            )
        )
        
        LogManager.i(TAG, "Chat completion completed successfully for session: $sessionId")
        
        ctx.contentType("application/json").result(gson.toJson(response))
    }
    
    private fun handleChatStreamingResponse(
        ctx: JavalinContext,
        prompt: String,
        config: GenerationConfig,
        sessionId: String,
        messages: com.google.gson.JsonArray,
        store: Boolean,
        metadata: Map<String, Any>?
    ) {
        LogManager.i(TAG, "Starting chat streaming response for session: $sessionId")
        
        // Note: Storing streaming completions is not supported in this implementation
        // as it requires buffering the entire response before streaming
        if (store) {
            LogManager.w(TAG, "Store parameter is not supported with streaming responses")
        }
        
        val id = "chatcmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1000
        
        // Use Javalin's SSE support for streaming
        ctx.contentType("text/event-stream")
        ctx.header("Cache-Control", "no-cache")
        ctx.header("Connection", "keep-alive")
        
        // Get the response output stream
        val outputStream = ctx.res().outputStream
        
        try {
            var tokenCount = 0
            
            val job = model.generateStream(prompt, config, sessionId) { token ->
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
                    val sseData = "data: ${gson.toJson(chunk)}\n\n"
                    outputStream.write(sseData.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                    
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
            
            // Wait for streaming to complete
            // Using runBlocking is necessary here because Javalin handlers are not suspend functions
            // Blocking is acceptable for streaming responses as we need to keep the connection open
            if (job != null) {
                runBlocking { job.join() }
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
                val errorData = "data: ${gson.toJson(errorChunk)}\n\ndata: [DONE]\n\n"
                outputStream.write(errorData.toByteArray(Charsets.UTF_8))
                outputStream.flush()
                return
            }
            
            // Send final chunk with finish_reason
            try {
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
                val finalData = "data: ${gson.toJson(finalChunk)}\n\ndata: [DONE]\n\n"
                outputStream.write(finalData.toByteArray(Charsets.UTF_8))
                outputStream.flush()
                
                LogManager.i(TAG, "Chat streaming completed with $tokenCount tokens")
            } catch (e: IllegalStateException) {
                // Handle Jetty output stream state errors gracefully
                // This can happen if the client disconnected or the stream is already closed
                LogManager.d(TAG, "Output stream no longer writable (client may have disconnected): ${e.message}")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error in chat streaming", e)
        }
        // Note: Javalin manages the output stream lifecycle; don't close it manually
    }
    
    private fun handleCompletions(ctx: JavalinContext) {
        LogManager.d(TAG, "Handling /v1/completions")
        
        try {
            val bodyText = ctx.body()
            
            // Security: Check content length
            if (bodyText.length > MAX_REQUEST_BODY_SIZE) {
                LogManager.w(TAG, "Request body too large: ${bodyText.length} bytes")
                val errorResponse = mapOf(
                    "error" to mapOf("message" to "Request body too large")
                )
                ctx.status(413).contentType("application/json").result(gson.toJson(errorResponse))
                return
            }
            
            val request = gson.fromJson(bodyText, JsonObject::class.java)
            
            // Extract parameters
            val prompt = request.get("prompt")?.asString ?: ""
            val stream = request.get("stream")?.asBoolean ?: false
            
            // Extract session ID using helper method
            val sessionId = extractSessionId(ctx, request)
            
            LogManager.d(TAG, "Text completion - Using session ID: $sessionId")
            
            // Build generation config from request parameters
            val config = extractGenerationConfig(request)
            
            if (stream) {
                handleCompletionStreamingResponse(ctx, prompt, config, sessionId)
            } else {
                handleCompletionNonStreamingResponse(ctx, prompt, config, sessionId)
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling completions", e)
            val errorResponse = mapOf(
                "error" to mapOf("message" to (e.message ?: "Internal server error"))
            )
            ctx.status(500).contentType("application/json").result(gson.toJson(errorResponse))
        }
    }
    
    private fun handleCompletionNonStreamingResponse(
        ctx: JavalinContext,
        prompt: String,
        config: GenerationConfig,
        sessionId: String
    ) {
        // Generate response with session ID
        val completion = model.generate(prompt, config, sessionId)
        
        val promptTokens = prompt.split(" ").size
        val completionTokens = completion.split(" ").size
        
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
                "prompt_tokens" to promptTokens,
                "completion_tokens" to completionTokens,
                "total_tokens" to (promptTokens + completionTokens)
            )
        )
        
        ctx.contentType("application/json").result(gson.toJson(response))
    }
    
    private fun handleCompletionStreamingResponse(
        ctx: JavalinContext,
        prompt: String,
        config: GenerationConfig,
        sessionId: String
    ) {
        LogManager.i(TAG, "Starting completion streaming response for session: $sessionId")
        
        val id = "cmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1000
        
        // Use Javalin's SSE support for streaming
        ctx.contentType("text/event-stream")
        ctx.header("Cache-Control", "no-cache")
        ctx.header("Connection", "keep-alive")
        
        // Get the response output stream
        val outputStream = ctx.res().outputStream
        
        try {
            var tokenCount = 0
            
            val job = model.generateStream(prompt, config, sessionId) { token ->
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
                    val sseData = "data: ${gson.toJson(chunk)}\n\n"
                    outputStream.write(sseData.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                    
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
            
            // Wait for streaming to complete
            // Using runBlocking is necessary here because Javalin handlers are not suspend functions
            // Blocking is acceptable for streaming responses as we need to keep the connection open
            if (job != null) {
                runBlocking { job.join() }
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
                val errorData = "data: ${gson.toJson(errorChunk)}\n\ndata: [DONE]\n\n"
                outputStream.write(errorData.toByteArray(Charsets.UTF_8))
                outputStream.flush()
                return
            }
            
            // Send final chunk with finish_reason
            try {
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
                val finalData = "data: ${gson.toJson(finalChunk)}\n\ndata: [DONE]\n\n"
                outputStream.write(finalData.toByteArray(Charsets.UTF_8))
                outputStream.flush()
                
                LogManager.i(TAG, "Completion streaming completed with $tokenCount tokens")
            } catch (e: IllegalStateException) {
                // Handle Jetty output stream state errors gracefully
                // This can happen if the client disconnected or the stream is already closed
                LogManager.d(TAG, "Output stream no longer writable (client may have disconnected): ${e.message}")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error in completion streaming", e)
        }
        // Note: Javalin manages the output stream lifecycle; don't close it manually
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
    
    private fun handleListSessions(ctx: JavalinContext) {
        LogManager.d(TAG, "Handling GET /v1/sessions")
        
        try {
            val activeSessions = model.getActiveSessions()
            val sessionCount = model.getActiveSessionCount()
            
            val response = mapOf(
                "object" to "list",
                "data" to activeSessions.map { sessionId ->
                    mapOf(
                        "id" to sessionId,
                        "object" to "session"
                    )
                },
                "count" to sessionCount
            )
            
            ctx.contentType("application/json").result(gson.toJson(response))
        } catch (e: Exception) {
            LogManager.e(TAG, "Error listing sessions", e)
            val errorResponse = mapOf(
                "error" to mapOf("message" to (e.message ?: "Failed to list sessions"))
            )
            ctx.status(500).contentType("application/json").result(gson.toJson(errorResponse))
        }
    }
    
    private fun handleDeleteSession(ctx: JavalinContext) {
        val sessionId = ctx.pathParam("sessionId")
        LogManager.d(TAG, "Handling DELETE /v1/sessions/$sessionId")
        
        try {
            val cleared = model.clearSession(sessionId)
            
            if (cleared) {
                val response = mapOf(
                    "deleted" to true,
                    "id" to sessionId,
                    "object" to "session"
                )
                ctx.contentType("application/json").result(gson.toJson(response))
            } else {
                val errorResponse = mapOf(
                    "error" to mapOf("message" to "Session not found: $sessionId")
                )
                ctx.status(404).contentType("application/json").result(gson.toJson(errorResponse))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error deleting session $sessionId", e)
            val errorResponse = mapOf(
                "error" to mapOf("message" to (e.message ?: "Failed to delete session"))
            )
            ctx.status(500).contentType("application/json").result(gson.toJson(errorResponse))
        }
    }
    
    private fun handleClearAllSessions(ctx: JavalinContext) {
        LogManager.d(TAG, "Handling DELETE /v1/sessions (clear all)")
        
        try {
            val countBefore = model.getActiveSessionCount()
            model.clearAllSessions()
            
            val response = mapOf(
                "deleted" to true,
                "count" to countBefore,
                "object" to "sessions"
            )
            ctx.contentType("application/json").result(gson.toJson(response))
        } catch (e: Exception) {
            LogManager.e(TAG, "Error clearing all sessions", e)
            val errorResponse = mapOf(
                "error" to mapOf("message" to (e.message ?: "Failed to clear sessions"))
            )
            ctx.status(500).contentType("application/json").result(gson.toJson(errorResponse))
        }
    }
    
    /**
     * Handle GET /v1/chat/completions/{completion_id}
     * Get a stored chat completion. Only completions created with store=true are returned.
     */
    private fun handleGetStoredCompletion(ctx: JavalinContext) {
        val completionId = ctx.pathParam("completion_id")
        LogManager.d(TAG, "Handling GET /v1/chat/completions/$completionId")
        
        try {
            val storedCompletion = storedCompletions[completionId]
            
            if (storedCompletion == null) {
                val errorResponse = mapOf(
                    "error" to mapOf(
                        "message" to "Completion not found: $completionId",
                        "type" to "invalid_request_error"
                    )
                )
                ctx.status(404).contentType("application/json").result(gson.toJson(errorResponse))
                return
            }
            
            // Build response with all messages including the assistant response
            val allMessages = storedCompletion.messages.toMutableList()
            allMessages.add(mapOf(
                "role" to "assistant",
                "content" to storedCompletion.responseContent
            ))
            
            val response = mutableMapOf<String, Any>(
                "id" to storedCompletion.id,
                "object" to storedCompletion.obj,
                "created" to storedCompletion.created,
                "model" to storedCompletion.model,
                "choices" to listOf(
                    mapOf(
                        "index" to 0,
                        "message" to mapOf(
                            "role" to "assistant",
                            "content" to storedCompletion.responseContent
                        ),
                        "finish_reason" to "stop"
                    )
                )
            )
            
            // Add metadata if present
            if (storedCompletion.metadata != null) {
                response["metadata"] = storedCompletion.metadata!!
            }
            
            LogManager.i(TAG, "Retrieved stored completion: $completionId")
            ctx.contentType("application/json").result(gson.toJson(response))
        } catch (e: Exception) {
            LogManager.e(TAG, "Error retrieving stored completion $completionId", e)
            val errorResponse = mapOf(
                "error" to mapOf("message" to (e.message ?: "Failed to retrieve completion"))
            )
            ctx.status(500).contentType("application/json").result(gson.toJson(errorResponse))
        }
    }
    
    /**
     * Handle GET /v1/chat/completions/{completion_id}/messages
     * Get the messages in a stored chat completion.
     */
    private fun handleGetStoredCompletionMessages(ctx: JavalinContext) {
        val completionId = ctx.pathParam("completion_id")
        LogManager.d(TAG, "Handling GET /v1/chat/completions/$completionId/messages")
        
        try {
            val storedCompletion = storedCompletions[completionId]
            
            if (storedCompletion == null) {
                val errorResponse = mapOf(
                    "error" to mapOf(
                        "message" to "Completion not found: $completionId",
                        "type" to "invalid_request_error"
                    )
                )
                ctx.status(404).contentType("application/json").result(gson.toJson(errorResponse))
                return
            }
            
            // Include all messages: original messages + assistant response
            val allMessages = storedCompletion.messages.toMutableList()
            allMessages.add(mapOf(
                "role" to "assistant",
                "content" to storedCompletion.responseContent
            ))
            
            val response = mapOf(
                "object" to "list",
                "data" to allMessages.mapIndexed { index, msg ->
                    mapOf(
                        "id" to "$completionId-msg-$index",
                        "object" to "chat.completion.message",
                        "created" to storedCompletion.created,
                        "role" to msg["role"],
                        "content" to msg["content"]
                    )
                }
            )
            
            LogManager.i(TAG, "Retrieved messages for completion: $completionId")
            ctx.contentType("application/json").result(gson.toJson(response))
        } catch (e: Exception) {
            LogManager.e(TAG, "Error retrieving messages for completion $completionId", e)
            val errorResponse = mapOf(
                "error" to mapOf("message" to (e.message ?: "Failed to retrieve messages"))
            )
            ctx.status(500).contentType("application/json").result(gson.toJson(errorResponse))
        }
    }
    
    /**
     * Handle POST /v1/chat/completions/{completion_id}
     * Update a stored chat completion. Only metadata updates are supported.
     */
    private fun handleUpdateStoredCompletion(ctx: JavalinContext) {
        val completionId = ctx.pathParam("completion_id")
        LogManager.d(TAG, "Handling POST /v1/chat/completions/$completionId")
        
        try {
            val storedCompletion = storedCompletions[completionId]
            
            if (storedCompletion == null) {
                val errorResponse = mapOf(
                    "error" to mapOf(
                        "message" to "Completion not found: $completionId",
                        "type" to "invalid_request_error"
                    )
                )
                ctx.status(404).contentType("application/json").result(gson.toJson(errorResponse))
                return
            }
            
            val bodyText = ctx.body()
            val request = gson.fromJson(bodyText, JsonObject::class.java)
            
            // Extract metadata from request
            val newMetadata = request.get("metadata")?.asJsonObject?.let { meta ->
                meta.entrySet().associate { it.key to it.value }
            }
            
            if (newMetadata == null) {
                val errorResponse = mapOf(
                    "error" to mapOf(
                        "message" to "metadata field is required",
                        "type" to "invalid_request_error"
                    )
                )
                ctx.status(400).contentType("application/json").result(gson.toJson(errorResponse))
                return
            }
            
            // Update metadata
            storedCompletion.metadata = newMetadata
            
            val response = mutableMapOf<String, Any>(
                "id" to storedCompletion.id,
                "object" to storedCompletion.obj,
                "created" to storedCompletion.created,
                "model" to storedCompletion.model,
                "choices" to listOf(
                    mapOf(
                        "index" to 0,
                        "message" to mapOf(
                            "role" to "assistant",
                            "content" to storedCompletion.responseContent
                        ),
                        "finish_reason" to "stop"
                    )
                ),
                "metadata" to newMetadata
            )
            
            LogManager.i(TAG, "Updated metadata for completion: $completionId")
            ctx.contentType("application/json").result(gson.toJson(response))
        } catch (e: Exception) {
            LogManager.e(TAG, "Error updating completion $completionId", e)
            val errorResponse = mapOf(
                "error" to mapOf("message" to (e.message ?: "Failed to update completion"))
            )
            ctx.status(500).contentType("application/json").result(gson.toJson(errorResponse))
        }
    }
    
    /**
     * Extract session ID from request using multiple methods in priority order:
     * 1. conversation_id field (OpenAI Conversations API standard)
     * 2. user field (OpenAI standard)
     * 3. session_id field
     * 4. X-Session-ID header
     * 5. default session
     * 
     * Validates and sanitizes session IDs to prevent injection attacks.
     */
    private fun extractSessionId(ctx: JavalinContext, request: JsonObject): String {
        val rawSessionId = request.get("conversation_id")?.asString
            ?: request.get("user")?.asString
            ?: request.get("session_id")?.asString
            ?: ctx.header("X-Session-ID")
            ?: "default"
        
        // Sanitize session ID: allow only alphanumeric, dash, underscore, dot, and @
        // This prevents potential injection attacks and ensures safe usage
        return rawSessionId.filter { it.isLetterOrDigit() || it in "-_.@" }
            .take(128) // Limit length to prevent excessive memory usage
            .ifEmpty { "default" } // Fall back to default if sanitized ID is empty
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
}
