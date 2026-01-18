package com.wannaphong.hostai

import android.content.ContentResolver
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Data class to hold all generation/completion parameters.
 * These parameters are compatible with LiteRT's SamplerConfig.
 */
data class GenerationConfig(
    val maxTokens: Int = 100,
    val temperature: Double = 0.7,
    val topK: Int = 40,
    val topP: Double = 0.95,
    val seed: Int = -1,
    val tools: List<Any>? = null,  // Tool instances for function calling
    val extraContext: Map<String, Any>? = null  // Extra context for prompt template (from extra_body)
)

/**
 * LLM model interface using LiteRT (LLM) library.
 * 
 * This implementation uses the LiteRT library which provides
 * native LLM inference optimized for Android/ARM devices with GPU acceleration.
 */
class LlamaModel(private val contentResolver: ContentResolver) {
    private var modelName = "litert-model"
    private var modelPath: String? = null
    private var isLoaded = false
    
    // LiteRT components
    private var engine: Engine? = null
    private val conversations = ConcurrentHashMap<String, Conversation>()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    companion object {
        private const val TAG = "LlamaModel"
        private const val DEFAULT_MAX_TOKENS = 2048
        private const val DEFAULT_SESSION_ID = "default"
        // Delay between streaming token emissions (in milliseconds)
        private const val TOKEN_EMISSION_DELAY_MS = 10L
    }
    
    fun loadModel(modelPath: String): Boolean {
        this.modelPath = modelPath
        
        LogManager.i(TAG, "Loading model from path: $modelPath")
        
        // Handle different path types
        if (modelPath == "mock-model") {
            // For mock model, just mark as loaded
            LogManager.i(TAG, "Using mock model")
            isLoaded = true
            return true
        }
        
        // It's a file path
        val file = File(modelPath)
        if (file.exists()) {
            modelName = file.name
            LogManager.i(TAG, "Model file found: $modelName (${file.length() / 1024 / 1024} MB)")
        } else {
            LogManager.e(TAG, "Model file not found at path: $modelPath")
            return false
        }
        
        return try {
            LogManager.i(TAG, "Initializing LiteRT with model: $modelName")
            
            // Create engine config with CPU backend by default for compatibility
            // GPU backend can provide better performance on supported devices
            // To enable GPU: change Backend.CPU to Backend.GPU
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU,  // Start with CPU for universal compatibility
                maxNumTokens = DEFAULT_MAX_TOKENS
            )
            
            // Initialize engine (this can take time, already on IO thread)
            val newEngine = Engine(engineConfig)
            newEngine.initialize()
            
            // Only set engine and isLoaded if initialization succeeds
            engine = newEngine
            isLoaded = true
            
            LogManager.i(TAG, "LiteRT engine initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            LogManager.e(TAG, "Failed to load model: ${e.message}", e)
            engine = null
            isLoaded = false
            false
        }
    }
    
    fun isModelLoaded(): Boolean {
        return isLoaded
    }
    
    fun getModelName(): String = modelName
    
    fun getModelPath(): String? = modelPath
    
    /**
     * Get or create a conversation for the given session ID.
     * Thread-safe atomic operation using computeIfAbsent.
     * @param sessionId Unique identifier for the conversation session
     * @param config Sampler configuration for the conversation
     * @return The conversation instance, or null if creation fails
     */
    private fun getOrCreateConversation(sessionId: String, config: GenerationConfig): Conversation? {
        return try {
            conversations.computeIfAbsent(sessionId) { _ ->
                val currentEngine = engine ?: throw IllegalStateException("Engine is not initialized")
                
                val samplerConfig = SamplerConfig(
                    topK = config.topK,
                    topP = config.topP,
                    temperature = config.temperature
                )
                
                // Helper properties for cleaner condition checks
                val hasTools = config.tools?.isNotEmpty() == true
                val hasExtraContext = config.extraContext?.isNotEmpty() == true
                
                // Log extra context if provided (for debugging/future support)
                if (hasExtraContext) {
                    LogManager.d(TAG, "Extra context provided: ${config.extraContext}")
                }
                
                // Build conversation config with tools if provided
                // Note: extraContext support in ConversationConfig depends on LiteRT-LM version
                val conversationConfig = if (hasTools) {
                    // Pass null for system message, samplerConfig, and tools as positional parameters
                    ConversationConfig(null, samplerConfig, config.tools)
                } else {
                    ConversationConfig(null, samplerConfig)
                }
                
                val newConversation = currentEngine.createConversation(conversationConfig)
                    ?: throw IllegalStateException("createConversation returned null")
                
                LogManager.i(TAG, "Created new conversation for session: $sessionId with ${config.tools?.size ?: 0} tools")
                newConversation
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to create conversation for session $sessionId", e)
            // Note: computeIfAbsent won't insert the key if the function throws an exception
            // So we don't need to remove anything - the map remains unchanged
            null
        }
    }
    
    /**
     * Clear a specific conversation session.
     * @param sessionId The session ID to clear
     * @return true if the session was found and cleared, false otherwise
     */
    fun clearSession(sessionId: String): Boolean {
        val conversation = conversations.remove(sessionId)
        if (conversation != null) {
            LogManager.i(TAG, "Clearing conversation session: $sessionId")
            try {
                conversation.close()
            } catch (e: Exception) {
                LogManager.e(TAG, "Error closing conversation for session $sessionId", e)
            }
            return true
        }
        return false
    }
    
    /**
     * Clear all conversation sessions.
     */
    fun clearAllSessions() {
        LogManager.i(TAG, "Clearing all conversation sessions (${conversations.size} sessions)")
        // Use iterator to safely remove all entries while closing conversations
        val iterator = conversations.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            try {
                entry.value.close()
            } catch (e: Exception) {
                LogManager.e(TAG, "Error closing conversation for session ${entry.key}", e)
            }
            iterator.remove()
        }
    }
    
    /**
     * Get the number of active conversation sessions.
     */
    fun getActiveSessionCount(): Int = conversations.size
    
    /**
     * Get list of active session IDs.
     */
    fun getActiveSessions(): List<String> = conversations.keys.toList()
    
    /**
     * Generate text with full configuration support and session management.
     * @param prompt The input prompt text
     * @param config Generation configuration with all parameters (optional)
     * @param sessionId Session identifier for conversation context (optional)
     * @return Generated text
     */
    fun generate(prompt: String, config: GenerationConfig = GenerationConfig(), sessionId: String = DEFAULT_SESSION_ID): String {
        if (!isModelLoaded()) {
            val errorMsg = "Error: Model not loaded. Please load a model first."
            LogManager.e(TAG, errorMsg)
            return errorMsg
        }
        
        LogManager.i(TAG, "Generating response for session '$sessionId' with prompt (length: ${prompt.length})")
        LogManager.d(TAG, "Config: maxTokens=${config.maxTokens}, temp=${config.temperature}, topK=${config.topK}, topP=${config.topP}")
        
        // For mock model, return a simple response
        if (modelPath == "mock-model") {
            val promptPreview = if (prompt.length > 50) prompt.take(50) + "..." else prompt
            return "This is a mock response from the model (session: $sessionId). In production, this would be the actual LLM output for prompt: \"$promptPreview\""
        }
        
        return try {
            // Get or create conversation for this session
            val sessionConversation = getOrCreateConversation(sessionId, config)
            
            if (sessionConversation == null) {
                val errorMsg = "Error: Failed to create conversation for session '$sessionId'"
                LogManager.e(TAG, errorMsg)
                return errorMsg
            }
            
            // Send message and get response synchronously
            val userMessage = Message.of(prompt)
            val response = sessionConversation.sendMessage(userMessage)
            
            val result = response?.toString() ?: ""
            LogManager.i(TAG, "Generation completed successfully for session '$sessionId' (length: ${result.length})")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate response for session '$sessionId'", e)
            LogManager.e(TAG, "Failed to generate response: ${e.message}", e)
            "Error: ${e.message}"
        }
    }
    
    /**
     * Legacy method for backward compatibility.
     * @deprecated Use generate(prompt, GenerationConfig) instead
     */
    @Deprecated("Use generate(prompt, GenerationConfig) for full parameter control")
    fun generate(prompt: String, maxTokens: Int = 100, temperature: Float = 0.7f): String {
        return generate(prompt, GenerationConfig(maxTokens = maxTokens, temperature = temperature.toDouble()))
    }
    
    /**
     * Generate text with streaming and full configuration support with session management.
     * @param prompt The input prompt text
     * @param config Generation configuration with all parameters (optional)
     * @param sessionId Session identifier for conversation context (optional)
     * @param onToken Callback for each generated token
     * @return Job that can be cancelled, or null on error
     */
    fun generateStream(
        prompt: String,
        config: GenerationConfig = GenerationConfig(),
        sessionId: String = DEFAULT_SESSION_ID,
        onToken: (String) -> Unit
    ): Job? {
        if (!isModelLoaded()) {
            onToken("Error: Model not loaded. Please load a model first.")
            return null
        }
        
        LogManager.d(TAG, "Streaming for session '$sessionId' - config: maxTokens=${config.maxTokens}, temp=${config.temperature}, topK=${config.topK}, topP=${config.topP}")
        
        // For mock model, simulate streaming
        if (modelPath == "mock-model") {
            return scope.launch {
                val mockResponse = "This is a mock streaming response from the model for session $sessionId. "
                val words = mockResponse.split(" ")
                for (word in words) {
                    onToken("$word ")
                    delay(50) // Small delay to simulate streaming
                }
            }
        }
        
        return scope.launch {
            try {
                // Get or create conversation for this session
                val sessionConversation = getOrCreateConversation(sessionId, config)
                
                if (sessionConversation == null) {
                    LogManager.e(TAG, "Failed to create conversation for session '$sessionId'")
                    onToken("Error: Failed to create conversation for session '$sessionId'")
                    return@launch
                }
                
                // Use suspendCancellableCoroutine to wait for async callback to complete
                suspendCancellableCoroutine<Unit> { continuation ->
                    val resumed = AtomicBoolean(false)
                    // Use CompletableDeferred to signal when streaming job completes
                    val streamingCompleted = CompletableDeferred<Unit>()
                    
                    // Use MessageCallback for streaming
                    val callback = object : MessageCallback {
                        override fun onMessage(message: Message) {
                            // LiteRT's MessageCallback.onMessage is called once with the complete response
                            // To provide proper streaming, we need to chunk the response and emit it progressively
                            val fullText = message.toString()
                            
                            // Stream the response in chunks (word by word for better UX)
                            // This simulates token-level streaming when the library provides complete responses
                            scope.launch {
                                try {
                                    // Split by whitespace while preserving the spaces as separate tokens
                                    // This provides natural word-by-word streaming
                                    val parts = fullText.split(" ")
                                    
                                    for ((index, part) in parts.withIndex()) {
                                        // Emit the word
                                        if (part.isNotEmpty()) {
                                            onToken(part)
                                            delay(TOKEN_EMISSION_DELAY_MS)
                                        }
                                        // Emit the space after the word (except after the last word)
                                        if (index < parts.size - 1) {
                                            onToken(" ")
                                            delay(TOKEN_EMISSION_DELAY_MS)
                                        }
                                    }
                                } catch (e: Exception) {
                                    LogManager.e(TAG, "Error during chunked streaming", e)
                                } finally {
                                    // Signal that streaming is complete
                                    streamingCompleted.complete(Unit)
                                }
                            }
                        }
                        
                        override fun onDone() {
                            LogManager.i(TAG, "Streaming completed for session '$sessionId'")
                            // Wait for streaming job to complete before resuming
                            scope.launch {
                                // Await the streaming job completion
                                // Note: If onMessage was never called, we complete the deferred here
                                if (!streamingCompleted.isCompleted) {
                                    streamingCompleted.complete(Unit)
                                }
                                streamingCompleted.await()
                                // Resume the coroutine when streaming is done
                                if (resumed.compareAndSet(false, true)) {
                                    continuation.resume(Unit)
                                }
                            }
                        }
                        
                        override fun onError(throwable: Throwable) {
                            Log.e(TAG, "Streaming error for session '$sessionId'", throwable)
                            LogManager.e(TAG, "Streaming error: ${throwable.message}", throwable)
                            // Complete the deferred to unblock any waiters
                            streamingCompleted.complete(Unit)
                            // Resume with exception on error
                            if (resumed.compareAndSet(false, true)) {
                                continuation.resumeWithException(throwable)
                            }
                        }
                    }
                    
                    val userMessage = Message.of(prompt)
                    sessionConversation.sendMessageAsync(userMessage, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming failed for session '$sessionId'", e)
                LogManager.e(TAG, "Streaming failed: ${e.message}", e)
                onToken("Error: ${e.message}")
            }
        }
    }
    
    /**
     * Legacy method for backward compatibility.
     * @deprecated Use generateStream(prompt, GenerationConfig, onToken) instead
     */
    @Deprecated("Use generateStream(prompt, GenerationConfig, onToken) for full parameter control")
    fun generateStream(
        prompt: String,
        maxTokens: Int = 100,
        temperature: Float = 0.7f,
        onToken: (String) -> Unit
    ): Job? {
        return generateStream(prompt, GenerationConfig(maxTokens = maxTokens, temperature = temperature.toDouble()), DEFAULT_SESSION_ID, onToken)
    }
    
    /**
     * Cleanup resources by closing conversations and optionally engine.
     */
    private fun cleanup(closeEngine: Boolean = false) {
        try {
            // Clean up all session conversations
            clearAllSessions()
            
            if (closeEngine) {
                engine?.close()
                engine = null
                scope.cancel()
            }
            
            isLoaded = false
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
            LogManager.e(TAG, "Error during cleanup: ${e.message}", e)
        }
    }
    
    fun unload() {
        LogManager.i(TAG, "Unloading model")
        cleanup(closeEngine = false)
        modelPath = null
    }
    
    /**
     * Explicitly release native resources.
     * Call this when you're done with the model to free memory immediately.
     */
    fun close() {
        LogManager.i(TAG, "Closing model and releasing resources")
        cleanup(closeEngine = true)
    }
}
