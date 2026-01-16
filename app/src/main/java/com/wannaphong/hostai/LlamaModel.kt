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
                        modelName = cursor.getString(nameIndex)
                        val fileSize = cursor.getLong(sizeIndex)
                        LogManager.i(TAG, "Model file info: $modelName (${fileSize / 1024 / 1024} MB)")
                    }
                }
            } catch (e: Exception) {
                LogManager.w(TAG, "Could not query content URI for file info: ${e.message}")
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
    
    fun generate(prompt: String, maxTokens: Int = 100, temperature: Float = 0.7f): String {
        if (!isModelLoaded()) {
            val errorMsg = "Error: Model not loaded. Please load a model first."
            LogManager.e(TAG, errorMsg)
            return errorMsg
        }
        
        LogManager.i(TAG, "Generating response for prompt (length: ${prompt.length})")
        
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
    
    fun generateStream(
        prompt: String,
        maxTokens: Int = 100,
        temperature: Float = 0.7f,
        onToken: (String) -> Unit
    ): Job? {
        if (!isModelLoaded()) {
            onToken("Error: Model not loaded. Please load a model first.")
            return null
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
        
        llamaHelper.predict(prompt, partialCompletion = true)
        return streamJob
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
