package com.wannaphong.hostai

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock as mutexWithLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
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
    @Volatile private var isLoaded = false
    
    // LiteRT components
    @Volatile private var engine: Engine? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // ParcelFileDescriptor kept open for the lifetime of the engine when the model is
    // loaded from a content:// URI. LiteRT requires a file-system path, so we open a
    // ParcelFileDescriptor and pass "/proc/self/fd/{fd}" to EngineConfig. The PFD must
    // stay open as long as the engine uses the underlying file.
    @Volatile private var modelPfd: ParcelFileDescriptor? = null

    // Global Mutex to serialise Engine.createConversation() calls.
    // The LiteRT engine may not safely support concurrent conversation creation.
    private val engineCreationLock = Mutex()

    // Read/Write lock to guard the engine lifecycle.
    // generate*() methods acquire the read lock so they can run concurrently.
    // close() acquires the write lock, which blocks until every in-flight
    // native sendMessage() call has finished – preventing a native crash where
    // the engine is freed while it is still executing inference.
    private val engineLifecycleLock = ReentrantReadWriteLock()

    // Cache SettingsManager to avoid repeated instantiation
    private val settingsManager by lazy { SettingsManager(context) }
    
    companion object {
        private const val TAG = "LlamaModel"
        private const val DEFAULT_MAX_TOKENS = 2048
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

        // Resolve the actual file-system path that LiteRT will use.
        // For content:// URIs we open a ParcelFileDescriptor and use the
        // kernel's /proc/self/fd/<n> symlink so no file copy is needed.
        val enginePath: String
        if (modelPath.startsWith("content://")) {
            return try {
                val uri = Uri.parse(modelPath)
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: run {
                        LogManager.e(TAG, "Failed to open file descriptor for URI: $modelPath")
                        return false
                    }
                modelPfd = pfd
                enginePath = "/proc/self/fd/${pfd.fd}"
                // Derive a display name from the URI path component
                modelName = uri.lastPathSegment?.substringAfterLast('/')
                    ?.substringAfterLast(':')
                    ?: "litert-model"
                LogManager.i(TAG, "Opened URI via fd $enginePath (model: $modelName)")
                loadFromPath(enginePath)
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to open URI: ${e.message}", e)
                false
            }
        } else {
            // It's a plain file path
            val file = File(modelPath)
            if (file.exists()) {
                modelName = file.name
                LogManager.i(TAG, "Model file found: $modelName (${file.length() / 1024 / 1024} MB)")
            } else {
                LogManager.e(TAG, "Model file not found at path: $modelPath")
                return false
            }
            enginePath = modelPath
            return loadFromPath(enginePath)
        }
    }

    /**
     * Initialise the LiteRT engine from a file-system path (or /proc/self/fd/<n> symlink).
     */
    private fun loadFromPath(enginePath: String): Boolean {
        return try {
            LogManager.i(TAG, "Initializing LiteRT with model: $modelName")

            // Get backend preference from settings
            val useGpu = settingsManager.isGpuBackendEnabled()
            val backend = if (useGpu) Backend.GPU() else Backend.CPU()
            
            LogManager.i(TAG, "Using ${if (useGpu) "GPU" else "CPU"} backend for inference")
            
            // Get max context length from settings
            val maxContextLength = settingsManager.getMaxContextLength()
            LogManager.i(TAG, "Using max context length: $maxContextLength tokens")

            // Create engine config with selected backend.
            // Only add vision/audio backends for multimodal models (e.g. Gemma-3N).
            // Text-only models fail with "Unsupported or unknown file format" when
            // these backends are specified.
            val useMultimodal = settingsManager.isMultimodalEnabled()
            val engineConfig = if (useMultimodal) {
                LogManager.i(TAG, "Multimodal mode enabled: adding vision (GPU) and audio (CPU) backends")
                EngineConfig(
                    modelPath = enginePath,
                    backend = backend,
                    maxNumTokens = maxContextLength,
                    visionBackend = Backend.GPU(),
                    audioBackend = Backend.CPU()
                )
            } else {
                EngineConfig(
                    modelPath = enginePath,
                    backend = backend,
                    maxNumTokens = maxContextLength
                )
            }
            
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
            // Close the PFD if we opened one and loading failed
            try { modelPfd?.close() } catch (ex: Exception) {
                LogManager.w(TAG, "Error closing model PFD after load failure: ${ex.message}")
            }
            modelPfd = null
            false
        }
    }
    
    fun isModelLoaded(): Boolean {
        return isLoaded
    }
    
    fun getModelName(): String = modelName
    
    fun getModelPath(): String? = modelPath

    /**
     * Create a new conversation for a single request.
     * A fresh conversation is created for every request and closed after use,
     * preventing stale state from causing failures on subsequent requests.
     * @param config Sampler configuration for the conversation
     * @return The conversation instance, or null if creation fails
     */
    private suspend fun createConversation(config: GenerationConfig): Conversation? {
        // Log extra context if provided (for debugging/future support)
        if (config.extraContext?.isNotEmpty() == true) {
            LogManager.d(TAG, "Extra context provided: ${config.extraContext}")
        }

        return try {
            engineCreationLock.mutexWithLock {
                val currentEngine = engine
                    ?: throw IllegalStateException("Engine is not initialized")

                val samplerConfig = SamplerConfig(
                    topK = config.topK,
                    topP = config.topP,
                    temperature = config.temperature
                )

                val conversationConfig = ConversationConfig(
                    systemInstruction = null,
                    initialMessages = emptyList(),
                    samplerConfig = samplerConfig
                )

                currentEngine.createConversation(conversationConfig)
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to create conversation: ${e.message}")
            null
        }
    }

    /**
     * Generate text with full configuration support.
     * @param prompt The input prompt text
     * @param config Generation configuration with all parameters (optional)
     * @param sessionId Unused – kept for API compatibility
     * @return Generated text
     */
    fun generate(prompt: String, config: GenerationConfig = GenerationConfig(), sessionId: String = ""): String {
        if (!isModelLoaded()) {
            val errorMsg = "Error: Model not loaded. Please load a model first."
            LogManager.e(TAG, errorMsg)
            return errorMsg
        }

        LogManager.i(TAG, "Generating response with prompt (length: ${prompt.length})")
        LogManager.d(TAG, "Config: maxTokens=${config.maxTokens}, temp=${config.temperature}, topK=${config.topK}, topP=${config.topP}")
        
        // For mock model, return a simple response
        if (modelPath == "mock-model") {
            val promptPreview = if (prompt.length > 50) prompt.take(50) + "..." else prompt
            return "This is a mock response from the model. In production, this would be the actual LLM output for prompt: \"$promptPreview\""
        }

        // runBlocking bridges this non-suspend function (called by the Javalin HTTP handler)
        // with the suspend world. Javalin handlers run on dedicated IO threads so blocking is acceptable.
        // The read lock prevents the engine from being closed (write lock in close()) while
        // sendMessage() is executing in native code.
        return engineLifecycleLock.read {
            // Re-check inside the lock: close() sets isLoaded = false while holding the write
            // lock, so if we reach here after close() completed, we see the updated value.
            if (!isLoaded) {
                return@read "Error: Model not loaded. Please load a model first."
            }
            runBlocking {
                var conversation: Conversation? = null
                try {
                    conversation = createConversation(config)

                    if (conversation == null) {
                        val errorMsg = "Error: Failed to create conversation"
                        LogManager.e(TAG, errorMsg)
                        return@runBlocking errorMsg
                    }

                    // Send message and get response synchronously
                    val userMessage = Message.user(prompt)
                    val response = conversation.sendMessage(userMessage)
                    val result = response.toString()
                    LogManager.i(TAG, "Generation completed successfully (length: ${result.length})")
                    result
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to generate response", e)
                    LogManager.e(TAG, "Failed to generate response: ${e.message}", e)
                    "Error: ${e.message}"
                } finally {
                    try { conversation?.close() } catch (e: Exception) {
                        LogManager.w(TAG, "Error closing conversation: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Generate text with multimodal content support (images, audio).
     * @param contents List of Content objects (text, images, audio)
     * @param config Generation configuration with all parameters (optional)
     * @param sessionId Unused – kept for API compatibility
     * @return Generated text
     */
    fun generateWithContents(contents: List<Content>, config: GenerationConfig = GenerationConfig(), sessionId: String = ""): String {
        if (!isModelLoaded()) {
            val errorMsg = "Error: Model not loaded. Please load a model first."
            LogManager.e(TAG, errorMsg)
            return errorMsg
        }

        LogManager.i(TAG, "Generating multimodal response with ${contents.size} content parts")
        LogManager.d(TAG, "Config: maxTokens=${config.maxTokens}, temp=${config.temperature}, topK=${config.topK}, topP=${config.topP}")

        // For mock model, return a simple response
        if (modelPath == "mock-model") {
            return "This is a mock multimodal response from the model with ${contents.size} content parts."
        }

        return engineLifecycleLock.read {
            // Re-check inside the lock: close() sets isLoaded = false while holding the write
            // lock, so if we reach here after close() completed, we see the updated value.
            if (!isLoaded) {
                return@read "Error: Model not loaded. Please load a model first."
            }
            runBlocking {
                var conversation: Conversation? = null
                try {
                    conversation = createConversation(config)

                    if (conversation == null) {
                        val errorMsg = "Error: Failed to create conversation"
                        LogManager.e(TAG, errorMsg)
                        return@runBlocking errorMsg
                    }

                    // Send message with multimodal contents and get response synchronously
                    val userMessage = Message.user(Contents.of(contents))
                    val response = conversation.sendMessage(userMessage)
                    val result = response.toString()
                    LogManager.i(TAG, "Multimodal generation completed successfully (length: ${result.length})")
                    result
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to generate multimodal response", e)
                    LogManager.e(TAG, "Failed to generate multimodal response: ${e.message}", e)
                    "Error: ${e.message}"
                } finally {
                    try { conversation?.close() } catch (e: Exception) {
                        LogManager.w(TAG, "Error closing conversation: ${e.message}")
                    }
                }
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
     * Generate text with streaming and full configuration support.
     * @param prompt The input prompt text
     * @param config Generation configuration with all parameters (optional)
     * @param sessionId Unused – kept for API compatibility
     * @param onToken Callback for each generated token
     * @return Job that can be cancelled, or null on error
     */
    fun generateStream(
        prompt: String,
        config: GenerationConfig = GenerationConfig(),
        sessionId: String = "",
        onToken: (String) -> Unit
    ): Job? {
        if (!isModelLoaded()) {
            onToken("Error: Model not loaded. Please load a model first.")
            return null
        }

        LogManager.d(TAG, "Streaming - config: maxTokens=${config.maxTokens}, temp=${config.temperature}, topK=${config.topK}, topP=${config.topP}")

        // For mock model, simulate streaming
        if (modelPath == "mock-model") {
            return scope.launch {
                val mockResponse = "This is a mock streaming response from the model. "
                val words = mockResponse.split(" ")
                for (word in words) {
                    onToken("$word ")
                    delay(50)
                }
            }
        }

        return scope.launch {
            var conversation: Conversation? = null
            try {
                conversation = createConversation(config)

                if (conversation == null) {
                    LogManager.e(TAG, "Failed to create conversation")
                    onToken("Error: Failed to create conversation")
                    return@launch
                }

                // Use suspendCancellableCoroutine to bridge the async callback with coroutines.
                suspendCancellableCoroutine<Unit> { continuation ->
                    val resumed = AtomicBoolean(false)
                    val streamingCompleted = CompletableDeferred<Unit>()

                    val callback = object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val fullText = message.toString()
                            scope.launch {
                                try {
                                    val parts = fullText.split(" ")
                                    for ((index, part) in parts.withIndex()) {
                                        if (part.isNotEmpty()) {
                                            onToken(part)
                                            delay(TOKEN_EMISSION_DELAY_MS)
                                        }
                                        if (index < parts.size - 1) {
                                            onToken(" ")
                                            delay(TOKEN_EMISSION_DELAY_MS)
                                        }
                                    }
                                } catch (e: Exception) {
                                    LogManager.e(TAG, "Error during chunked streaming", e)
                                } finally {
                                    streamingCompleted.complete(Unit)
                                }
                            }
                        }

                        override fun onDone() {
                            LogManager.i(TAG, "Streaming completed")
                            scope.launch {
                                if (!streamingCompleted.isCompleted) {
                                    streamingCompleted.complete(Unit)
                                }
                                streamingCompleted.await()
                                if (resumed.compareAndSet(false, true)) {
                                    continuation.resume(Unit)
                                }
                            }
                        }

                        override fun onError(throwable: Throwable) {
                            Log.e(TAG, "Streaming error", throwable)
                            LogManager.e(TAG, "Streaming error: ${throwable.message}", throwable)
                            streamingCompleted.complete(Unit)
                            if (resumed.compareAndSet(false, true)) {
                                continuation.resumeWithException(throwable)
                            }
                        }
                    }

                    val userMessage = Message.user(prompt)
                    conversation.sendMessageAsync(userMessage, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming failed", e)
                LogManager.e(TAG, "Streaming failed: ${e.message}", e)
                onToken("Error: ${e.message}")
            } finally {
                try { conversation?.close() } catch (e: Exception) {
                    LogManager.w(TAG, "Error closing conversation: ${e.message}")
                }
            }
        }
    }

    /**
     * Generate text with streaming and multimodal content support (images, audio).
     * @param contents List of Content objects (text, images, audio)
     * @param config Generation configuration with all parameters (optional)
     * @param sessionId Unused – kept for API compatibility
     * @param onToken Callback for each generated token
     * @return Job that can be cancelled, or null on error
     */
    fun generateStreamWithContents(
        contents: List<Content>,
        config: GenerationConfig = GenerationConfig(),
        sessionId: String = "",
        onToken: (String) -> Unit
    ): Job? {
        if (!isModelLoaded()) {
            onToken("Error: Model not loaded. Please load a model first.")
            return null
        }

        LogManager.d(TAG, "Streaming multimodal with ${contents.size} content parts - config: maxTokens=${config.maxTokens}, temp=${config.temperature}, topK=${config.topK}, topP=${config.topP}")

        // For mock model, simulate streaming
        if (modelPath == "mock-model") {
            return scope.launch {
                val mockResponse = "This is a mock multimodal streaming response from the model with ${contents.size} content parts. "
                val words = mockResponse.split(" ")
                for (word in words) {
                    onToken("$word ")
                    delay(50)
                }
            }
        }

        return scope.launch {
            var conversation: Conversation? = null
            try {
                conversation = createConversation(config)

                if (conversation == null) {
                    LogManager.e(TAG, "Failed to create conversation")
                    onToken("Error: Failed to create conversation")
                    return@launch
                }

                suspendCancellableCoroutine<Unit> { continuation ->
                    val resumed = AtomicBoolean(false)
                    val streamingCompleted = CompletableDeferred<Unit>()

                    val callback = object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val fullText = message.toString()
                            scope.launch {
                                try {
                                    val parts = fullText.split(" ")
                                    for ((index, part) in parts.withIndex()) {
                                        if (part.isNotEmpty()) {
                                            onToken(part)
                                            delay(TOKEN_EMISSION_DELAY_MS)
                                        }
                                        if (index < parts.size - 1) {
                                            onToken(" ")
                                            delay(TOKEN_EMISSION_DELAY_MS)
                                        }
                                    }
                                } catch (e: Exception) {
                                    LogManager.e(TAG, "Error during chunked streaming", e)
                                } finally {
                                    streamingCompleted.complete(Unit)
                                }
                            }
                        }

                        override fun onDone() {
                            LogManager.i(TAG, "Multimodal streaming completed")
                            scope.launch {
                                if (!streamingCompleted.isCompleted) {
                                    streamingCompleted.complete(Unit)
                                }
                                streamingCompleted.await()
                                if (resumed.compareAndSet(false, true)) {
                                    continuation.resume(Unit)
                                }
                            }
                        }

                        override fun onError(throwable: Throwable) {
                            Log.e(TAG, "Multimodal streaming error", throwable)
                            LogManager.e(TAG, "Multimodal streaming error: ${throwable.message}", throwable)
                            streamingCompleted.complete(Unit)
                            if (resumed.compareAndSet(false, true)) {
                                continuation.resumeWithException(throwable)
                            }
                        }
                    }

                    val userMessage = Message.user(Contents.of(contents))
                    conversation.sendMessageAsync(userMessage, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Multimodal streaming failed", e)
                LogManager.e(TAG, "Multimodal streaming failed: ${e.message}", e)
                onToken("Error: ${e.message}")
            } finally {
                try { conversation?.close() } catch (e: Exception) {
                    LogManager.w(TAG, "Error closing conversation: ${e.message}")
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
        return generateStream(prompt, GenerationConfig(maxTokens = maxTokens, temperature = temperature.toDouble()), "", onToken)
    }

    /**
     * Cleanup resources, optionally closing the engine.
     * Must be called while holding engineLifecycleLock (write lock) when closeEngine is true.
     */
    private fun cleanup(closeEngine: Boolean = false) {
        try {
            if (closeEngine) {
                // Cancel streaming coroutines BEFORE closing the native engine so that
                // any in-flight sendMessageAsync callbacks see a cancelled scope and do
                // not attempt to use engine resources after they are freed.
                scope.cancel()
                engine?.close()
                engine = null
                // Close the model file descriptor (opened for content:// URI models).
                // This must happen AFTER the engine is closed so the native code
                // is no longer reading from /proc/self/fd/<n>.
                try { modelPfd?.close() } catch (e: Exception) {
                    LogManager.w(TAG, "Error closing model PFD: ${e.message}")
                }
                modelPfd = null
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
     *
     * Acquires the write lock which blocks until every in-flight generate() call
     * (holding the read lock) has returned.  This prevents the engine from being
     * freed while a native sendMessage() is still executing and causing a crash.
     * isLoaded is set to false inside the write lock so that any thread that
     * races through isModelLoaded() and then waits on the read lock will see
     * the updated flag and bail out without touching a closed engine.
     */
    fun close() {
        LogManager.i(TAG, "Closing model and releasing resources")
        // The write lock waits for all current read-lock holders (in-flight
        // generate / generateWithContents calls) to complete before proceeding.
        engineLifecycleLock.write {
            isLoaded = false
            cleanup(closeEngine = true)
        }
    }
}
