package com.wannaphong.hostai

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Data class to hold all generation/completion parameters.
 * These parameters are compatible with LiteRT's SamplerConfig.
 */
data class GenerationConfig(
    val maxTokens: Int = 100,
    val temperature: Double = 0.7,
    val topK: Int = 40,
    val topP: Double = 0.95,
    val seed: Int = -1
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
    private var conversation: Conversation? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
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
            LogManager.i(TAG, "Initializing LiteRT with model: $modelName")
            
            // Convert content:// URI to file path if needed
            val actualModelPath = if (modelPath.startsWith("content://")) {
                // For content URIs, we need to use the file path directly
                // The file was already copied to internal storage by MainActivity
                modelPath
            } else {
                modelPath
            }
            
            // Create engine config with GPU backend for better performance
            val engineConfig = EngineConfig(
                modelPath = actualModelPath,
                backend = Backend.CPU,  // Start with CPU, can be changed to GPU if needed
                maxNumTokens = DEFAULT_MAX_TOKENS
            )
            
            // Initialize engine (this can take time, already on IO thread)
            engine = Engine(engineConfig)
            engine?.initialize()
            
            LogManager.i(TAG, "LiteRT engine initialized successfully")
            isLoaded = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            LogManager.e(TAG, "Failed to load model: ${e.message}", e)
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
            // Create or reuse conversation
            if (conversation == null) {
                val samplerConfig = SamplerConfig(
                    topK = config.topK,
                    topP = config.topP,
                    temperature = config.temperature
                )
                conversation = engine?.createConversation(
                    ConversationConfig(samplerConfig = samplerConfig)
                )
            }
            
            // Send message and get response synchronously
            val userMessage = Message.of(prompt)
            val response = conversation?.sendMessage(userMessage)
            
            val result = response?.toString() ?: ""
            LogManager.i(TAG, "Generation completed successfully (length: ${result.length})")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate response", e)
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
     * Generate text with streaming and full configuration support.
     * @param prompt The input prompt text
     * @param config Generation configuration with all parameters (optional)
     * @param onToken Callback for each generated token
     * @return Job that can be cancelled, or null on error
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
        
        return scope.launch {
            try {
                // Create or reuse conversation
                if (conversation == null) {
                    val samplerConfig = SamplerConfig(
                        topK = config.topK,
                        topP = config.topP,
                        temperature = config.temperature
                    )
                    conversation = engine?.createConversation(
                        ConversationConfig(samplerConfig = samplerConfig)
                    )
                }
                
                // Use MessageCallback for streaming
                val callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        // Extract text content from message
                        onToken(message.toString())
                    }
                    
                    override fun onDone() {
                        LogManager.i(TAG, "Streaming completed")
                    }
                    
                    override fun onError(throwable: Throwable) {
                        Log.e(TAG, "Streaming error", throwable)
                        LogManager.e(TAG, "Streaming error: ${throwable.message}", throwable)
                    }
                }
                
                val userMessage = Message.of(prompt)
                conversation?.sendMessageAsync(userMessage, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Streaming failed", e)
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
        return generateStream(prompt, GenerationConfig(maxTokens = maxTokens, temperature = temperature.toDouble()), onToken)
    }
    
    fun unload() {
        try {
            LogManager.i(TAG, "Unloading model")
            conversation?.close()
            conversation = null
            isLoaded = false
            modelPath = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading model", e)
            LogManager.e(TAG, "Error unloading model: ${e.message}", e)
        }
    }
    
    /**
     * Explicitly release native resources.
     * Call this when you're done with the model to free memory immediately.
     */
    fun close() {
        try {
            LogManager.i(TAG, "Closing model and releasing resources")
            conversation?.close()
            conversation = null
            engine?.close()
            engine = null
            scope.cancel()
            isLoaded = false
        } catch (e: Exception) {
            Log.e(TAG, "Error closing model", e)
            LogManager.e(TAG, "Error closing model: ${e.message}", e)
        }
    }
}
