package com.wannaphong.hostai

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.io.IOException

/**
 * OpenAI-compatible API server implementation using Ktor.
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
    
    private var server: ApplicationEngine? = null
    
    companion object {
        private const val TAG = "OpenAIApiServer"
        // Maximum request body size (10 MB) to prevent memory exhaustion attacks
        private const val MAX_REQUEST_BODY_SIZE = 10 * 1024 * 1024
    }
    
    fun start() {
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            configureRouting()
        }.start(wait = false)
        
        LogManager.i(TAG, "Ktor server started on port $port")
    }
    
    fun stop() {
        server?.stop(1000, 2000)
        server = null
        LogManager.i(TAG, "Ktor server stopped")
    }
    
    private fun Application.configureRouting() {
        routing {
            // Health check
            get("/health") {
                handleHealth(call)
            }
            
            // Models endpoint
            get("/v1/models") {
                handleModels(call)
            }
            
            // Chat completions
            post("/v1/chat/completions") {
                handleChatCompletions(call)
            }
            
            // Text completions
            post("/v1/completions") {
                handleCompletions(call)
            }
            
            // Root endpoint
            get("/") {
                handleRoot(call)
            }
            
            // Chat UI
            get("/chat") {
                handleChatUI(call)
            }
            
            // Assets
            get("/assets/{file}") {
                val fileName = call.parameters["file"] ?: ""
                handleAssets(call, fileName)
            }
        }
    }
    
    private suspend fun handleHealth(call: ApplicationCall) {
        LogManager.d(TAG, "Received GET request to /health")
        
        val health = mapOf(
            "status" to "ok",
            "model_loaded" to model.isModelLoaded()
        )
        
        call.respondText(
            gson.toJson(health),
            ContentType.Application.Json,
            HttpStatusCode.OK
        )
    }
    
    private suspend fun handleModels(call: ApplicationCall) {
        LogManager.d(TAG, "Received GET request to /v1/models")
        
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
        
        call.respondText(
            gson.toJson(models),
            ContentType.Application.Json,
            HttpStatusCode.OK
        )
    }
    
    private suspend fun handleRoot(call: ApplicationCall) {
        LogManager.d(TAG, "Received GET request to /")
        
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
        
        call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
    }
    
    private suspend fun handleChatUI(call: ApplicationCall) {
        LogManager.d(TAG, "Received GET request to /chat")
        
        try {
            val inputStream = context.assets.open("index.html")
            val html = inputStream.bufferedReader().use { it.readText() }
            call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
        } catch (e: Exception) {
            LogManager.e(TAG, "Error loading chat UI", e)
            call.respondText(
                "<html><body><h1>Error loading chat UI</h1><p>${e.message}</p></body></html>",
                ContentType.Text.Html,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    private suspend fun handleAssets(call: ApplicationCall, fileName: String) {
        LogManager.d(TAG, "Received GET request to /assets/$fileName")
        
        try {
            // Security: Prevent path traversal attacks
            if (fileName.contains("..") || fileName.startsWith("/") || fileName.contains("\\")) {
                LogManager.w(TAG, "Rejected potential path traversal attempt: $fileName")
                call.respondText(
                    "Invalid asset path",
                    ContentType.Text.Plain,
                    HttpStatusCode.Forbidden
                )
                return
            }
            
            // Determine MIME type based on file extension
            val contentType = when {
                fileName.endsWith(".ico") -> ContentType.Image.XIcon
                fileName.endsWith(".json") -> ContentType.Application.Json
                fileName.endsWith(".html") -> ContentType.Text.Html
                fileName.endsWith(".css") -> ContentType.Text.CSS
                fileName.endsWith(".js") -> ContentType.Application.JavaScript
                else -> ContentType.Application.OctetStream
            }
            
            val inputStream = context.assets.open(fileName)
            val bytes = inputStream.readBytes()
            inputStream.close()
            
            call.respondBytes(bytes, contentType, HttpStatusCode.OK)
        } catch (e: Exception) {
            LogManager.e(TAG, "Error loading asset: $fileName", e)
            call.respondText(
                "Asset not found",
                ContentType.Text.Plain,
                HttpStatusCode.NotFound
            )
        }
    }
    
    private suspend fun handleChatCompletions(call: ApplicationCall) {
        LogManager.d(TAG, "Received POST request to /v1/chat/completions")
        
        try {
            val bodyText = call.receiveText()
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
                handleChatStreamingResponse(call, prompt, config)
            } else {
                handleChatNonStreamingResponse(call, prompt, config)
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling chat completions", e)
            call.respondText(
                gson.toJson(mapOf("error" to mapOf("message" to e.message))),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    private suspend fun handleChatNonStreamingResponse(
        call: ApplicationCall,
        prompt: String,
        config: GenerationConfig
    ) {
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
        
        call.respondText(
            gson.toJson(response),
            ContentType.Application.Json,
            HttpStatusCode.OK
        )
    }
    
    private suspend fun handleChatStreamingResponse(
        call: ApplicationCall,
        prompt: String,
        config: GenerationConfig
    ) {
        LogManager.i(TAG, "Starting chat streaming response")
        
        call.response.headers.append(HttpHeaders.ContentType, "text/event-stream")
        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.response.headers.append(HttpHeaders.Connection, "keep-alive")
        
        call.respondOutputStream {
            val writer = this.writer(Charsets.UTF_8)
            val id = "chatcmpl-${System.currentTimeMillis()}"
            val created = System.currentTimeMillis() / 1000
            var tokenCount = 0
            
            try {
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
                
                // Wait for streaming to complete
                job?.join()
                
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
                
                LogManager.i(TAG, "Chat streaming completed with $tokenCount tokens")
            } catch (e: Exception) {
                LogManager.e(TAG, "Error in chat streaming", e)
            }
        }
    }
    
    private suspend fun handleCompletions(call: ApplicationCall) {
        LogManager.d(TAG, "Received POST request to /v1/completions")
        
        try {
            val bodyText = call.receiveText()
            val request = gson.fromJson(bodyText, JsonObject::class.java)
            
            // Extract parameters
            val prompt = request.get("prompt")?.asString ?: ""
            val stream = request.get("stream")?.asBoolean ?: false
            
            // Build generation config from request parameters
            val config = extractGenerationConfig(request)
            
            if (stream) {
                handleCompletionStreamingResponse(call, prompt, config)
            } else {
                handleCompletionNonStreamingResponse(call, prompt, config)
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling completions", e)
            call.respondText(
                gson.toJson(mapOf("error" to mapOf("message" to e.message))),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    private suspend fun handleCompletionNonStreamingResponse(
        call: ApplicationCall,
        prompt: String,
        config: GenerationConfig
    ) {
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
        
        call.respondText(
            gson.toJson(response),
            ContentType.Application.Json,
            HttpStatusCode.OK
        )
    }
    
    private suspend fun handleCompletionStreamingResponse(
        call: ApplicationCall,
        prompt: String,
        config: GenerationConfig
    ) {
        LogManager.i(TAG, "Starting completion streaming response")
        
        call.response.headers.append(HttpHeaders.ContentType, "text/event-stream")
        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.response.headers.append(HttpHeaders.Connection, "keep-alive")
        
        call.respondOutputStream {
            val writer = this.writer(Charsets.UTF_8)
            val id = "cmpl-${System.currentTimeMillis()}"
            val created = System.currentTimeMillis() / 1000
            var tokenCount = 0
            
            try {
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
                
                // Wait for streaming to complete
                job?.join()
                
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
                
                LogManager.i(TAG, "Completion streaming completed with $tokenCount tokens")
            } catch (e: Exception) {
                LogManager.e(TAG, "Error in completion streaming", e)
            }
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
