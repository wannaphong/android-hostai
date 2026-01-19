package com.wannaphong.hostai

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
    val extraContext: Map<String, Any>? = null  // Extra context for prompt template (from extra_body)
)

/**
 * LLM model interface using LiteRT (LLM) library.
 * 
 * This implementation uses the LiteRT library which provides
 * native LLM inference optimized for Android/ARM devices with GPU acceleration.
 */
class LlamaModel(
    private val contentResolver: ContentResolver, 
    private val context: Context
) {
    private var modelName = "litert-model"
    private var modelPath: String? = null
    private var isLoaded = false
    
    // LiteRT components
    private var engine: Engine? = null
    private val conversations = ConcurrentHashMap<String, Conversation>()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Per-session locks for thread-safe concurrent request handling
    // Ensures requests to the same session are processed sequentially
    // while requests to different sessions can run in parallel
    //
    // Implementation note: Locks are used inside coroutines on the IO dispatcher.
    // This is acceptable because:
    // 1. The IO dispatcher is designed for blocking operations
    // 2. We're only blocking during actual model inference (the primary work)
    // 3. Each coroutine runs on a separate thread from the IO thread pool
    // 4. The alternative (channels/mutexes) would add complexity without benefit
    private val sessionLocks = ConcurrentHashMap<String, ReentrantLock>()
    
    // Cache SettingsManager to avoid repeated instantiation
    private val settingsManager by lazy { SettingsManager(context) }
    
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
            
            // Get backend preference from settings
            val useGpu = settingsManager.isGpuBackendEnabled()
            val backend = if (useGpu) Backend.GPU else Backend.CPU
            
            LogManager.i(TAG, "Using ${if (useGpu) "GPU" else "CPU"} backend for inference")
            
            // Create engine config with selected backend and multimodal support
            // Vision backend: GPU for better performance (Gemma-3N requires GPU for vision)
            // Audio backend: CPU (Gemma-3N requires CPU for audio)
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = DEFAULT_MAX_TOKENS,
                visionBackend = Backend.GPU,  // Enable vision processing
                audioBackend = Backend.CPU     // Enable audio processing
            )
            
            // Initialize engine (this can take time, already on IO thread)
            val newEngine = Engine(engineConfig)
            newEngine.initialize()
            
            // Only set engine and isLoaded if initialization succeeds
            engine = newEngine
            isLoaded = true
            
            LogManager.i(TAG, "LiteRT engine initialized successfully with ${if (useGpu) "GPU" else "CPU"} backend")
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
     * Get or create a lock for the given session ID.
     * Ensures thread-safe concurrent access per session.
     * @param sessionId Unique identifier for the session
     * @return A ReentrantLock for the session
     */
    private fun getSessionLock(sessionId: String): ReentrantLock {
        return sessionLocks.computeIfAbsent(sessionId) { ReentrantLock() }
    }
    
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
                
                // Log extra context if provided (for debugging/future support)
                if (config.extraContext?.isNotEmpty() == true) {
                    LogManager.d(TAG, "Extra context provided: ${config.extraContext}")
                }
                
                // Build conversation config
                // Note: extraContext support in ConversationConfig depends on LiteRT-LM version
                val conversationConfig = ConversationConfig(null, emptyList(), samplerConfig)
                
                val newConversation = currentEngine.createConversation(conversationConfig)
                    ?: throw IllegalStateException("createConversation returned null")
                
                LogManager.i(TAG, "Created new conversation for session: $sessionId")
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
     * Thread-safe: Acquires the session lock to ensure no concurrent operations.
     * @param sessionId The session ID to clear
     * @return true if the session was found and cleared, false otherwise
     */
    fun clearSession(sessionId: String): Boolean {
        // Get the lock for this session (create if doesn't exist)
        val lock = getSessionLock(sessionId)
        
        // Acquire lock to prevent concurrent access during cleanup
        return lock.withLock {
            // Remove both conversation and lock while holding the lock
            val conversation = conversations.remove(sessionId)
            sessionLocks.remove(sessionId)
            
            if (conversation != null) {
                LogManager.i(TAG, "Clearing conversation session: $sessionId")
                try {
                    conversation.close()
                } catch (e: Exception) {
                    LogManager.e(TAG, "Error closing conversation for session $sessionId", e)
                }
                true
            } else {
                false
            }
        }
    }
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
        // Clear all session locks
        sessionLocks.clear()
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
     * Thread-safe: Multiple concurrent requests to the same session are serialized,
     * while requests to different sessions can run in parallel.
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
        
        // Use per-session lock to ensure thread-safe access
        val lock = getSessionLock(sessionId)
        return lock.withLock {
            try {
                // Get or create conversation for this session
                val sessionConversation = getOrCreateConversation(sessionId, config)
                
                if (sessionConversation == null) {
                    val errorMsg = "Error: Failed to create conversation for session '$sessionId'"
                    LogManager.e(TAG, errorMsg)
                    return@withLock errorMsg
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
    }
    
    /**
     * Generate text with multimodal content support (images, audio).
     * Thread-safe: Multiple concurrent requests to the same session are serialized,
     * while requests to different sessions can run in parallel.
     * @param contents List of Content objects (text, images, audio)
     * @param config Generation configuration with all parameters (optional)
     * @param sessionId Session identifier for conversation context (optional)
     * @return Generated text
     */
    fun generateWithContents(contents: List<Content>, config: GenerationConfig = GenerationConfig(), sessionId: String = DEFAULT_SESSION_ID): String {
        if (!isModelLoaded()) {
            val errorMsg = "Error: Model not loaded. Please load a model first."
            LogManager.e(TAG, errorMsg)
            return errorMsg
        }
        
        LogManager.i(TAG, "Generating multimodal response for session '$sessionId' with ${contents.size} content parts")
        LogManager.d(TAG, "Config: maxTokens=${config.maxTokens}, temp=${config.temperature}, topK=${config.topK}, topP=${config.topP}")
        
        // For mock model, return a simple response
        if (modelPath == "mock-model") {
            return "This is a mock multimodal response from the model (session: $sessionId) with ${contents.size} content parts."
        }
        
        // Use per-session lock to ensure thread-safe access
        val lock = getSessionLock(sessionId)
        return lock.withLock {
            try {
                // Get or create conversation for this session
                val sessionConversation = getOrCreateConversation(sessionId, config)
                
                if (sessionConversation == null) {
                    val errorMsg = "Error: Failed to create conversation for session '$sessionId'"
                    LogManager.e(TAG, errorMsg)
                    return@withLock errorMsg
                }
                
                // Send message with multimodal contents and get response synchronously
                val userMessage = Message.of(*contents.toTypedArray())
                val response = sessionConversation.sendMessage(userMessage)
                
                val result = response?.toString() ?: ""
                LogManager.i(TAG, "Multimodal generation completed successfully for session '$sessionId' (length: ${result.length})")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate multimodal response for session '$sessionId'", e)
                LogManager.e(TAG, "Failed to generate multimodal response: ${e.message}", e)
                "Error: ${e.message}"
            }
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
     * Thread-safe: Multiple concurrent streaming requests to the same session are serialized,
     * while requests to different sessions can run in parallel.
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
            // Use per-session lock to ensure thread-safe access
            val lock = getSessionLock(sessionId)
            lock.withLock {
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
    }
    
    /**
     * Generate text with streaming and multimodal content support (images, audio).
     * Thread-safe: Multiple concurrent streaming requests to the same session are serialized,
     * while requests to different sessions can run in parallel.
     * @param contents List of Content objects (text, images, audio)
     * @param config Generation configuration with all parameters (optional)
     * @param sessionId Session identifier for conversation context (optional)
     * @param onToken Callback for each generated token
     * @return Job that can be cancelled, or null on error
     */
    fun generateStreamWithContents(
        contents: List<Content>,
        config: GenerationConfig = GenerationConfig(),
        sessionId: String = DEFAULT_SESSION_ID,
        onToken: (String) -> Unit
    ): Job? {
        if (!isModelLoaded()) {
            onToken("Error: Model not loaded. Please load a model first.")
            return null
        }
        
        LogManager.d(TAG, "Streaming multimodal for session '$sessionId' with ${contents.size} content parts - config: maxTokens=${config.maxTokens}, temp=${config.temperature}, topK=${config.topK}, topP=${config.topP}")
        
        // For mock model, simulate streaming
        if (modelPath == "mock-model") {
            return scope.launch {
                val mockResponse = "This is a mock multimodal streaming response from the model for session $sessionId with ${contents.size} content parts. "
                val words = mockResponse.split(" ")
                for (word in words) {
                    onToken("$word ")
                    delay(50) // Small delay to simulate streaming
                }
            }
        }
        
        return scope.launch {
            // Use per-session lock to ensure thread-safe access
            val lock = getSessionLock(sessionId)
            lock.withLock {
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
                                scope.launch {
                                    try {
                                        // Split by whitespace while preserving the spaces as separate tokens
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
                                LogManager.i(TAG, "Multimodal streaming completed for session '$sessionId'")
                                // Wait for streaming job to complete before resuming
                                scope.launch {
                                    // Await the streaming job completion
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
                                Log.e(TAG, "Multimodal streaming error for session '$sessionId'", throwable)
                                LogManager.e(TAG, "Multimodal streaming error: ${throwable.message}", throwable)
                                // Complete the deferred to unblock any waiters
                                streamingCompleted.complete(Unit)
                                // Resume with exception on error
                                if (resumed.compareAndSet(false, true)) {
                                    continuation.resumeWithException(throwable)
                                }
                            }
                        }
                        
                        val userMessage = Message.of(*contents.toTypedArray())
                        sessionConversation.sendMessageAsync(userMessage, callback)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Multimodal streaming failed for session '$sessionId'", e)
                    LogManager.e(TAG, "Multimodal streaming failed: ${e.message}", e)
                    onToken("Error: ${e.message}")
                }
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
