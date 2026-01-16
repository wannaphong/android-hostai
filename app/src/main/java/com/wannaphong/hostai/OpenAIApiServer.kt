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
import kotlinx.coroutines.Job
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
        // Maximum request body size (10 MB) to prevent memory exhaustion attacks
        private const val MAX_REQUEST_BODY_SIZE = 10 * 1024 * 1024
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
                "application/json; charset=utf-8",
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
            "application/json; charset=utf-8",
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
            "application/json; charset=utf-8",
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
        
        // Log a preview of the prompt to verify character encoding
        val promptPreview = if (prompt.length > 100) prompt.take(100) + "..." else prompt
        LogManager.d(TAG, "Prompt preview: $promptPreview")
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
            "application/json; charset=utf-8",
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
            "application/json; charset=utf-8",
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
                var streamJob: Job? = null
                try {
                    val writer = pipedOutputStream.bufferedWriter()
                    val id = "chatcmpl-${System.currentTimeMillis()}"
                    val created = System.currentTimeMillis() / 1000
                    var tokenCount = 0
                    var streamClosed = false
                    
                    // Start the stream
                    streamJob = model.generateStream(prompt, config) { token ->
                        // Check if stream is already closed, if so, don't process more tokens
                        if (streamClosed) {
                            return@generateStream
                        }
                        
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
                            // Client disconnected or pipe closed - stop streaming gracefully
                            LogManager.d(TAG, "Client disconnected during streaming (token #$tokenCount)")
                            streamClosed = true
                            // Cancel the streaming job to stop generating more tokens
                            streamJob?.cancel()
                        } catch (e: Exception) {
                            LogManager.e(TAG, "Error writing token to stream", e)
                            streamClosed = true
                            streamJob?.cancel()
                        }
                    }
                    
                    // Wait for streaming to complete (or be cancelled)
                    streamJob?.join()
                    
                    // Only send final chunks if stream is not closed
                    if (!streamClosed) {
                        try {
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
                        } catch (e: IOException) {
                            LogManager.d(TAG, "Client disconnected before final chunk could be sent")
                        }
                    } else {
                        LogManager.i(TAG, "Chat streaming stopped early at $tokenCount tokens (client disconnected)")
                    }
                    
                    writer.close()
                } catch (e: Exception) {
                    LogManager.e(TAG, "Error in chat streaming", e)
                    streamJob?.cancel()
                } finally {
                    try {
                        pipedOutputStream.close()
                    } catch (e: IOException) {
                        // Ignore - already closed
                    }
                }
            }
            
            // Return response with text/event-stream content type
            val response = newChunkedResponse(
                Response.Status.OK,
                "text/event-stream",
                pipedInputStream
            )
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Connection", "keep-alive")
            return response
            
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to start chat streaming", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json; charset=utf-8",
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
                var streamJob: Job? = null
                try {
                    val writer = pipedOutputStream.bufferedWriter()
                    val id = "cmpl-${System.currentTimeMillis()}"
                    val created = System.currentTimeMillis() / 1000
                    var tokenCount = 0
                    var streamClosed = false
                    
                    // Start the stream
                    streamJob = model.generateStream(prompt, config) { token ->
                        // Check if stream is already closed, if so, don't process more tokens
                        if (streamClosed) {
                            return@generateStream
                        }
                        
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
                            // Client disconnected or pipe closed - stop streaming gracefully
                            LogManager.d(TAG, "Client disconnected during streaming (token #$tokenCount)")
                            streamClosed = true
                            // Cancel the streaming job to stop generating more tokens
                            streamJob?.cancel()
                        } catch (e: Exception) {
                            LogManager.e(TAG, "Error writing token to stream", e)
                            streamClosed = true
                            streamJob?.cancel()
                        }
                    }
                    
                    // Wait for streaming to complete (or be cancelled)
                    streamJob?.join()
                    
                    // Only send final chunks if stream is not closed
                    if (!streamClosed) {
                        try {
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
                        } catch (e: IOException) {
                            LogManager.d(TAG, "Client disconnected before final chunk could be sent")
                        }
                    } else {
                        LogManager.i(TAG, "Completion streaming stopped early at $tokenCount tokens (client disconnected)")
                    }
                    
                    writer.close()
                } catch (e: Exception) {
                    LogManager.e(TAG, "Error in completion streaming", e)
                    streamJob?.cancel()
                } finally {
                    try {
                        pipedOutputStream.close()
                    } catch (e: IOException) {
                        // Ignore - already closed
                    }
                }
            }
            
            // Return response with text/event-stream content type
            val response = newChunkedResponse(
                Response.Status.OK,
                "text/event-stream",
                pipedInputStream
            )
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Connection", "keep-alive")
            return response
            
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to start completion streaming", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json; charset=utf-8",
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
    
    private fun getRequestBody(session: IHTTPSession): String {
        return try {
            // Read the input stream directly with UTF-8 encoding to properly handle
            // multibyte characters like Thai, Chinese, Japanese, etc.
            
            // HTTP headers are case-insensitive per RFC, NanoHTTPD normalizes to lowercase
            // But we check both for defensive programming
            val contentLength = (session.headers["content-length"] 
                ?: session.headers["Content-Length"])?.toIntOrNull() ?: 0
            
            if (contentLength > 0) {
                // Security: Prevent memory exhaustion attacks by limiting request body size
                if (contentLength > MAX_REQUEST_BODY_SIZE) {
                    LogManager.w(TAG, "Request body too large: $contentLength bytes (max: $MAX_REQUEST_BODY_SIZE)")
                    throw IOException("Request body too large")
                }
                
                // Read the request body directly from input stream with UTF-8
                val buffer = ByteArray(contentLength)
                var bytesRead = 0
                val inputStream = session.inputStream
                
                // Manual read loop (can't use readNBytes as project targets Java 8)
                // Read until we have all the bytes or reach EOF
                while (bytesRead < contentLength) {
                    val read = inputStream.read(buffer, bytesRead, contentLength - bytesRead)
                    if (read == -1) {
                        // Reached EOF before reading all expected bytes - this is a malformed request
                        val errorMsg = "Incomplete request body: expected $contentLength bytes, got $bytesRead"
                        LogManager.w(TAG, errorMsg)
                        throw IOException(errorMsg)
                    }
                    bytesRead += read
                }
                
                // Decode with UTF-8 to properly handle multibyte characters
                String(buffer, 0, bytesRead, Charsets.UTF_8)
            } else {
                // Fallback to parseBody for empty or unknown content-length
                // NOTE: This fallback will use system default charset and may not properly
                // handle multibyte characters. However, legitimate POST requests from
                // HTTP clients should always include Content-Length header.
                val map = mutableMapOf<String, String>()
                session.parseBody(map)
                map["postData"] ?: ""
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to parse request body", e)
            LogManager.e(TAG, "Failed to parse request body", e)
            ""
        }
    }
}
