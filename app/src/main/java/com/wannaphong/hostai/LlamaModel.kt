package com.wannaphong.hostai

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Data class to hold all generation/completion parameters.
 * These parameters match the kotlinllamacpp library's doCompletion parameters.
 */
data class GenerationConfig(
    val maxTokens: Int = 100,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val tfsZ: Float = 1.00f,
    val typicalP: Float = 1.00f,
    val penaltyLastN: Int = 64,
    val penaltyRepeat: Float = 1.00f,
    val penaltyFreq: Float = 0.00f,
    val penaltyPresent: Float = 0.00f,
    val mirostat: Float = 0.00f,
    val mirostatTau: Float = 5.00f,
    val mirostatEta: Float = 0.10f,
    val penalizeNl: Boolean = false,
    val seed: Int = -1,
    val nProbs: Int = 0,
    val grammar: String = "",
    val ignoreEos: Boolean = false,
    val stopStrings: List<String> = emptyList(),
    val logitBias: List<List<Double>> = emptyList()
)

/**
 * LLaMA model interface using kotlinllamacpp library.
 * 
 * This implementation uses the kotlinllamacpp library which provides
 * native llama.cpp bindings optimized for Android/ARM devices.
 */
class LlamaModel(private val contentResolver: ContentResolver) {
    private var modelName = "llama-model"
    private var modelPath: String? = null
    private var isLoaded = false
    
    // Internal scope and flow for kotlinllamacpp
    private val scope = CoroutineScope(Dispatchers.IO)
    private val sharedFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    
    private val llamaHelper by lazy {
        LlamaHelper(
            contentResolver = contentResolver,
            scope = scope,
            sharedFlow = sharedFlow
        )
    }
    
    companion object {
        private const val TAG = "LlamaModel"
        private const val DEFAULT_CONTEXT_LENGTH = 2048
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
        
        // Check if it's a content URI or file path
        if (modelPath.startsWith("content://")) {
            // It's a content URI - query for file info
            try {
                val uri = Uri.parse(modelPath)
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst() && nameIndex >= 0 && sizeIndex >= 0) {
                        val name = cursor.getString(nameIndex)
                        if (name != null) {
                            modelName = name
                        }
                        val fileSize = cursor.getLong(sizeIndex)
                        LogManager.i(TAG, "Model file info: $modelName (${fileSize / 1024 / 1024} MB)")
                    }
                }
            } catch (e: Exception) {
                LogManager.w(TAG, "Could not query content URI for file info: ${e.message}")
            }
        } else {
            // It's a file path
            val file = File(modelPath)
            if (file.exists()) {
                modelName = file.name
                LogManager.i(TAG, "Model file found: $modelName (${file.length() / 1024 / 1024} MB)")
            } else {
                LogManager.e(TAG, "Model file not found at path: $modelPath")
            }
        }
        
        return try {
            val latch = CountDownLatch(1)
            var loadSuccess = false
            
            LogManager.i(TAG, "Initializing kotlinllamacpp with context length: $DEFAULT_CONTEXT_LENGTH")
            
            // Load model using kotlinllamacpp
            llamaHelper.load(modelPath, DEFAULT_CONTEXT_LENGTH) { contextId ->
                Log.d(TAG, "Model loaded successfully with context ID: $contextId")
                LogManager.i(TAG, "Model loaded successfully with context ID: $contextId")
                isLoaded = true
                loadSuccess = true
                latch.countDown()
            }
            
            // Wait for model to load (with timeout)
            val loaded = latch.await(60, TimeUnit.SECONDS)
            if (!loaded) {
                Log.e(TAG, "Model loading timed out")
                LogManager.e(TAG, "Model loading timed out after 60 seconds")
                isLoaded = false
                return false
            }
            
            if (loadSuccess) {
                LogManager.i(TAG, "Model loading completed successfully")
            } else {
                LogManager.e(TAG, "Model loading failed")
            }
            
            loadSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            LogManager.e(TAG, "Failed to load model", e)
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
     * Generate text with full configuration support.
     * @param prompt The input prompt text
     * @param config Generation configuration with all parameters (optional)
     * @return Generated text
     * 
     * Note: The underlying kotlinllamacpp LlamaHelper currently has limited parameter support
     * through its predict() method. This implementation prepares the full parameter set for
     * when the library extends its API. Currently, only prompt and streaming are directly supported.
     */
    fun generate(prompt: String, config: GenerationConfig = GenerationConfig()): String {
        if (!isModelLoaded()) {
            val errorMsg = "Error: Model not loaded. Please load a model first."
            LogManager.e(TAG, errorMsg)
            return errorMsg
        }
        
        LogManager.i(TAG, "Generating response for prompt (length: ${prompt.length})")
        LogManager.d(TAG, "Config: maxTokens=${config.maxTokens}, temp=${config.temperature}, topK=${config.topK}, topP=${config.topP}")
        
        // For mock model, return a simple response
        if (modelPath == "mock-model") {
            return "This is a mock response from the model. In production, this would be the actual LLM output for prompt: \"$prompt\""
        }
        
        return try {
            val generatedText = StringBuilder()
            val latch = CountDownLatch(1)
            var collectionJob: Job? = null
            
            try {
                // Start collecting events
                collectionJob = scope.launch {
                    sharedFlow.collect { event ->
                        when (event) {
                            is LlamaHelper.LLMEvent.Ongoing -> {
                                generatedText.append(event.word)
                            }
                            is LlamaHelper.LLMEvent.Done -> {
                                latch.countDown()
                            }
                            is LlamaHelper.LLMEvent.Error -> {
                                Log.e(TAG, "Generation error: ${event.message}")
                                LogManager.e(TAG, "Generation error: ${event.message}")
                                latch.countDown()
                            }
                            else -> {}
                        }
                    }
                }
                
                // Start generation
                // TODO: When kotlinllamacpp's LlamaHelper supports additional parameters,
                // pass the full config through the predict method's params parameter
                llamaHelper.predict(prompt, partialCompletion = true)
                
                // Wait for completion (with timeout)
                val completed = latch.await(120, TimeUnit.SECONDS)
                
                if (!completed) {
                    Log.e(TAG, "Generation timed out")
                    LogManager.e(TAG, "Generation timed out after 120 seconds")
                    return "Error: Generation timed out"
                }
                
                val result = generatedText.toString()
                LogManager.i(TAG, "Generation completed successfully (length: ${result.length})")
                result
            } finally {
                collectionJob?.cancel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate response", e)
            LogManager.e(TAG, "Failed to generate response", e)
            "Error: ${e.message}"
        }
    }
    
    /**
     * Legacy method for backward compatibility.
     * @deprecated Use generate(prompt, GenerationConfig) instead
     */
    @Deprecated("Use generate(prompt, GenerationConfig) for full parameter control")
    fun generate(prompt: String, maxTokens: Int = 100, temperature: Float = 0.7f): String {
        return generate(prompt, GenerationConfig(maxTokens = maxTokens, temperature = temperature))
    }
    
    /**
     * Generate text with streaming and full configuration support.
     * @param prompt The input prompt text
     * @param config Generation configuration with all parameters (optional)
     * @param onToken Callback for each generated token
     * @return Job that can be cancelled, or null on error
     * 
     * Note: The underlying kotlinllamacpp LlamaHelper currently has limited parameter support
     * through its predict() method. This implementation prepares the full parameter set for
     * when the library extends its API. Currently, only prompt and streaming are directly supported.
     */
    fun generateStream(
        prompt: String,
        config: GenerationConfig = GenerationConfig(),
        onToken: (String) -> Unit
    ): Job? {
        if (!isModelLoaded()) {
            onToken("Error: Model not loaded. Please load a model first.")
            return null
        }
        
        LogManager.d(TAG, "Streaming config: maxTokens=${config.maxTokens}, temp=${config.temperature}, topK=${config.topK}, topP=${config.topP}")
        
        // For mock model, simulate streaming
        if (modelPath == "mock-model") {
            return scope.launch {
                val mockResponse = "This is a mock streaming response from the model. "
                val words = mockResponse.split(" ")
                for (word in words) {
                    onToken("$word ")
                    kotlinx.coroutines.delay(50) // Small delay to simulate streaming
                }
            }
        }
        
        // For streaming, we'll use kotlinllamacpp's token callback
        val streamJob = scope.launch {
            try {
                sharedFlow.collect { event ->
                    when (event) {
                        is LlamaHelper.LLMEvent.Ongoing -> {
                            onToken(event.word)
                        }
                        is LlamaHelper.LLMEvent.Done -> {
                            // Stream complete
                        }
                        is LlamaHelper.LLMEvent.Error -> {
                            Log.e(TAG, "Streaming error: ${event.message}")
                            LogManager.e(TAG, "Streaming error: ${event.message}")
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming failed", e)
                LogManager.e(TAG, "Streaming failed", e)
            }
        }
        
        // TODO: When kotlinllamacpp's LlamaHelper supports additional parameters,
        // pass the full config through the predict method's params parameter
        llamaHelper.predict(prompt, partialCompletion = true)
        return streamJob
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
        return generateStream(prompt, GenerationConfig(maxTokens = maxTokens, temperature = temperature), onToken)
    }
    
    fun unload() {
        try {
            LogManager.i(TAG, "Unloading model")
            llamaHelper.abort()
            isLoaded = false
            modelPath = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading model", e)
            LogManager.e(TAG, "Error unloading model", e)
        }
    }
    
    /**
     * Explicitly release native resources.
     * Call this when you're done with the model to free memory immediately.
     */
    fun close() {
        try {
            LogManager.i(TAG, "Closing model and releasing resources")
            llamaHelper.abort()
            llamaHelper.release()
            scope.cancel()
            isLoaded = false
        } catch (e: Exception) {
            Log.e(TAG, "Error closing model", e)
            LogManager.e(TAG, "Error closing model", e)
        }
    }
}
