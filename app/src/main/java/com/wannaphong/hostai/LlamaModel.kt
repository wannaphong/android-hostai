package com.wannaphong.hostai

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock as mutexWithLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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

    // Global Mutex to serialise the entire conversation lifecycle.
    // LiteRT supports only one active session at a time: a second
    // createConversation() call while a session is open throws
    // FAILED_PRECONDITION.  This mutex is held from createConversation()
    // until the conversation is closed, ensuring only one request uses
    // the engine at a time even when concurrency > 1 is configured.
    //
    // close() also acquires this mutex before freeing the native engine,
    // guaranteeing that no in-flight inference (streaming or non-streaming)
    // is running when the engine is destroyed.
    private val inferenceMutex = Mutex()

    // Cache SettingsManager to avoid repeated instantiation
    private val settingsManager by lazy { SettingsManager(context) }
    
    companion object {
        private const val TAG = "LlamaModel"
        private const val DEFAULT_MAX_TOKENS = 2048
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

        val enginePath: String
        if (modelPath.startsWith("content://")) {
            // LiteRT's native engine requires a real file-system path with the
            // correct file extension – it cannot follow /proc/self/fd/<n> symlinks.
            // Copy the model from the content URI to the app's internal model cache
            // directory, keeping the original filename (and therefore the .litertlm
            // extension).  Subsequent starts reuse the cached copy so no extra I/O
            // is needed after the first load.
            val uri = Uri.parse(modelPath)
            val fileName = getFileNameFromUri(uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                ?: "model.litertlm"
            modelName = fileName

            val fileSize = getFileSizeFromUri(uri)

            val cachedFile = getCachedModelFile(fileName, fileSize)
            enginePath = if (cachedFile != null) {
                LogManager.i(TAG, "Using cached model file: ${cachedFile.absolutePath}")
                cachedFile.absolutePath
            } else {
                val sizeDisplay = if (fileSize > 0) "${fileSize / 1024 / 1024} MB" else "unknown size"
                LogManager.i(TAG, "Copying model from URI to internal cache ($sizeDisplay)…")
                val destFile = File(getModelCacheDir(), fileName)
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: run {
                        LogManager.e(TAG, "Failed to open input stream for URI: $modelPath")
                        return false
                    }
                } catch (e: Exception) {
                    LogManager.e(TAG, "Failed to copy model from URI: ${e.message}", e)
                    destFile.delete()
                    return false
                }
                LogManager.i(TAG, "Model cached at: ${destFile.absolutePath}")
                destFile.absolutePath
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
        }
        return loadFromPath(enginePath)
    }

    /** Returns the cache directory used to store model copies from content URIs. */
    private fun getModelCacheDir(): File {
        val dir = File(context.filesDir, "model_cache")
        if (!dir.exists() && !dir.mkdirs()) {
            LogManager.w(TAG, "Failed to create model cache directory: ${dir.absolutePath}")
        }
        return dir
    }

    /**
     * Returns the display filename reported by ContentResolver for [uri],
     * or null if the query fails.
     */
    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the file size (bytes) reported by ContentResolver for [uri],
     * or -1 if unknown.
     */
    private fun getFileSizeFromUri(uri: Uri): Long {
        return try {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Returns the cached file if it exists, has the right name, and (when
     * [expectedSize] is positive) its size matches.  Returns null otherwise.
     */
    private fun getCachedModelFile(fileName: String, expectedSize: Long): File? {
        val file = File(getModelCacheDir(), fileName)
        if (!file.exists()) return null
        if (expectedSize > 0 && file.length() != expectedSize) return null
        return file
    }

    /**
     * Initialise the LiteRT engine from a real file-system path.
     */
    private fun loadFromPath(enginePath: String): Boolean {
        return try {
            LogManager.i(TAG, "Initializing LiteRT with model: $modelName")

            // Get backend preference from settings
            val backend = when (settingsManager.getBackend()) {
                SettingsManager.BACKEND_NPU -> {
                    LogManager.i(TAG, "Using NPU backend for inference")
                    Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
                }
                SettingsManager.BACKEND_GPU -> {
                    LogManager.i(TAG, "Using GPU backend for inference")
                    Backend.GPU()
                }
                else -> {
                    LogManager.i(TAG, "Using CPU backend for inference")
                    Backend.CPU()
                }
            }

            // Get max context length from settings
            val maxContextLength = settingsManager.getMaxContextLength()
            LogManager.i(TAG, "Using max context length: $maxContextLength tokens")

            // Compiled-kernel cache directory: speeds up subsequent model loads by reusing
            // pre-compiled GPU/NPU kernels instead of recompiling them on every launch.
            val cacheDirFile = File(context.cacheDir, "litert_cache")
            if (!cacheDirFile.exists() && !cacheDirFile.mkdirs()) {
                LogManager.w(TAG, "Failed to create LiteRT cache directory; compiled kernels will not be cached")
            }
            val cacheDir = cacheDirFile.absolutePath

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
                    cacheDir = cacheDir,
                    visionBackend = Backend.GPU(),
                    audioBackend = Backend.CPU()
                )
            } else {
                EngineConfig(
                    modelPath = enginePath,
                    backend = backend,
                    maxNumTokens = maxContextLength,
                    cacheDir = cacheDir
                )
            }
            
            // Initialize engine (this can take time, already on IO thread)
            val newEngine = Engine(engineConfig)
            newEngine.initialize()
            
            // Only set engine and isLoaded if initialization succeeds
            engine = newEngine
            isLoaded = true
            
            LogManager.i(TAG, "LiteRT engine initialized successfully with ${settingsManager.getBackend().uppercase()} backend")
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
     * Create a new conversation for a single request.
     * A fresh conversation is created for every request and closed after use,
     * preventing stale state from causing failures on subsequent requests.
     *
     * **Must be called while [inferenceMutex] is held** – the LiteRT engine
     * only supports one live session at a time.
     *
     * @param config Sampler configuration for the conversation
     * @return The conversation instance, or null if creation fails
     */
    private fun createConversation(config: GenerationConfig): Conversation? {
        // Log extra context if provided (for debugging/future support)
        if (config.extraContext?.isNotEmpty() == true) {
            LogManager.d(TAG, "Extra context provided: ${config.extraContext}")
        }

        return try {
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
        // inferenceMutex is held for the full conversation lifetime so that the next request
        // cannot call createConversation() before this one has called conversation.close().
        // close() also acquires inferenceMutex before freeing the engine, so we are guaranteed
        // that the engine is alive for the entire duration of this block.
        return runBlocking {
            inferenceMutex.mutexWithLock {
                // Re-check inside the mutex: close() sets isLoaded = false before acquiring
                // inferenceMutex, so this check detects a close() that raced ahead of us.
                if (!isLoaded) {
                    return@mutexWithLock "Error: Model not loaded. Please load a model first."
                }
                var conversation: Conversation? = null
                try {
                    conversation = createConversation(config)

                    if (conversation == null) {
                        val errorMsg = "Error: Failed to create conversation"
                        LogManager.e(TAG, errorMsg)
                        return@mutexWithLock errorMsg
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

        return runBlocking {
            inferenceMutex.mutexWithLock {
                // Re-check inside the mutex: close() sets isLoaded = false before acquiring
                // inferenceMutex, so this check detects a close() that raced ahead of us.
                if (!isLoaded) {
                    return@mutexWithLock "Error: Model not loaded. Please load a model first."
                }
                var conversation: Conversation? = null
                try {
                    conversation = createConversation(config)

                    if (conversation == null) {
                        val errorMsg = "Error: Failed to create conversation"
                        LogManager.e(TAG, errorMsg)
                        return@mutexWithLock errorMsg
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
                onToken(mockResponse)
            }
        }

        return scope.launch {
            // inferenceMutex serialises the full conversation lifetime so that
            // createConversation() is never called while another session is open.
            // close() also acquires this mutex before freeing the engine, so we are
            // guaranteed the engine remains alive throughout sendMessageAsync.
            //
            // NOTE: engineLifecycleLock.read {} is intentionally NOT used here.
            // ReentrantReadWriteLock has thread affinity: unlock() must be called from
            // the thread that called lock().  Kotlin coroutines on Dispatchers.IO can
            // resume on a different thread after each suspension point
            // (inferenceMutex.mutexWithLock and suspendCancellableCoroutine both
            // suspend), so holding a ReentrantReadWriteLock.readLock across those
            // suspension points causes IllegalMonitorStateException and breaks
            // multi-concurrency streaming.
            inferenceMutex.mutexWithLock {
                // Re-check inside the mutex: close() sets isLoaded = false before
                // acquiring inferenceMutex, so this detects a close() that won the race.
                if (!isLoaded) {
                    onToken("Error: Model not loaded. Please load a model first.")
                    return@mutexWithLock
                }
                var conversation: Conversation? = null
                try {
                    conversation = createConversation(config)

                    if (conversation == null) {
                        LogManager.e(TAG, "Failed to create conversation")
                        onToken("Error: Failed to create conversation")
                        return@mutexWithLock
                    }

                    // Use suspendCancellableCoroutine to bridge the async callback with coroutines.
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val resumed = AtomicBoolean(false)

                        val callback = object : MessageCallback {
                            override fun onMessage(message: Message) {
                                // If the continuation was already resumed (e.g. the client
                                // disconnected), skip further token delivery immediately.
                                // This avoids redundant IOException throws and keeps the
                                // native callback thread free.
                                if (resumed.get()) return

                                // Emit each token chunk directly as it arrives from the engine.
                                // No buffering or artificial delays — let the native engine pace output.
                                // Wrap in try-catch: exceptions must never escape a JNI callback or
                                // they will crash the native engine / the Android process.
                                try {
                                    onToken(message.toString())
                                } catch (e: Exception) {
                                    LogManager.w(TAG, "Token callback error (client may have disconnected): ${e.message}")
                                    if (resumed.compareAndSet(false, true)) {
                                        // Close the conversation immediately from the callback
                                        // thread to send a stop signal to the native engine
                                        // right away.  Without this, the engine keeps generating
                                        // tokens while inferenceMutex is still held, blocking
                                        // any new request until the stream naturally ends.
                                        try { conversation?.close() } catch (ignored: Exception) { }
                                        continuation.resumeWithException(e)
                                    }
                                }
                            }

                            override fun onDone() {
                                LogManager.i(TAG, "Streaming completed")
                                if (resumed.compareAndSet(false, true)) {
                                    continuation.resume(Unit)
                                }
                            }

                            override fun onError(throwable: Throwable) {
                                Log.e(TAG, "Streaming error", throwable)
                                LogManager.e(TAG, "Streaming error: ${throwable.message}", throwable)
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
                    try { onToken("Error: ${e.message}") } catch (ignored: Exception) {
                        // Client may have already disconnected; nothing to do.
                    }
                } finally {
                    try { conversation?.close() } catch (e: Exception) {
                        LogManager.w(TAG, "Error closing conversation: ${e.message}")
                    }
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
                onToken(mockResponse)
            }
        }

        return scope.launch {
            // Same approach as generateStream(): use inferenceMutex only.
            // See generateStream() for the detailed explanation of why
            // engineLifecycleLock.read {} must NOT be used in streaming coroutines.
            inferenceMutex.mutexWithLock {
                // Re-check inside the mutex: close() sets isLoaded = false before
                // acquiring inferenceMutex, so this detects a close() that won the race.
                if (!isLoaded) {
                    onToken("Error: Model not loaded. Please load a model first.")
                    return@mutexWithLock
                }
                var conversation: Conversation? = null
                try {
                    conversation = createConversation(config)

                    if (conversation == null) {
                        LogManager.e(TAG, "Failed to create conversation")
                        onToken("Error: Failed to create conversation")
                        return@mutexWithLock
                    }

                    suspendCancellableCoroutine<Unit> { continuation ->
                        val resumed = AtomicBoolean(false)

                        val callback = object : MessageCallback {
                            override fun onMessage(message: Message) {
                                // If the continuation was already resumed (e.g. the client
                                // disconnected), skip further token delivery immediately.
                                // This avoids redundant IOException throws and keeps the
                                // native callback thread free.
                                if (resumed.get()) return

                                // Emit each token chunk directly as it arrives from the engine.
                                // Wrap in try-catch: exceptions must never escape a JNI callback or
                                // they will crash the native engine / the Android process.
                                try {
                                    onToken(message.toString())
                                } catch (e: Exception) {
                                    LogManager.w(TAG, "Multimodal token callback error (client may have disconnected): ${e.message}")
                                    if (resumed.compareAndSet(false, true)) {
                                        // Close the conversation immediately from the callback
                                        // thread to send a stop signal to the native engine
                                        // right away.  Without this, the engine keeps generating
                                        // tokens while inferenceMutex is still held, blocking
                                        // any new request until the stream naturally ends.
                                        try { conversation?.close() } catch (ignored: Exception) { }
                                        continuation.resumeWithException(e)
                                    }
                                }
                            }

                            override fun onDone() {
                                LogManager.i(TAG, "Multimodal streaming completed")
                                if (resumed.compareAndSet(false, true)) {
                                    continuation.resume(Unit)
                                }
                            }

                            override fun onError(throwable: Throwable) {
                                Log.e(TAG, "Multimodal streaming error", throwable)
                                LogManager.e(TAG, "Multimodal streaming error: ${throwable.message}", throwable)
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
                    try { onToken("Error: ${e.message}") } catch (ignored: Exception) {
                        // Client may have already disconnected; nothing to do.
                    }
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
     * Must only be called while inferenceMutex is held when closeEngine is true.
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
     * Sets isLoaded = false first to prevent new requests from starting, then
     * acquires inferenceMutex to wait for any active inference (streaming or
     * non-streaming) to finish before closing the native engine.  This guarantees
     * that engine?.close() is never called while sendMessage / sendMessageAsync is
     * still executing in native code.
     */
    fun close() {
        LogManager.i(TAG, "Closing model and releasing resources")
        // Signal to all generate*() / generateStream*() methods that the model is
        // going away.  They re-check isLoaded inside inferenceMutex and will
        // return an error instead of starting a new conversation.
        isLoaded = false
        // Acquire the inference mutex to wait for the currently active inference
        // (if any) to complete and release the mutex before we destroy the engine.
        runBlocking {
            inferenceMutex.mutexWithLock {
                cleanup(closeEngine = true)
            }
        }
    }
}
