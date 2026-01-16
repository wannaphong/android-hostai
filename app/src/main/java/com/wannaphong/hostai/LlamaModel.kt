package com.wannaphong.hostai

import android.content.ContentResolver
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        
        // Extract model name from file path
        if (modelPath != "mock-model") {
            val file = File(modelPath)
            if (file.exists()) {
                modelName = file.name
            }
        } else {
            // For mock model, just mark as loaded
            isLoaded = true
            return true
        }
        
        return try {
            val latch = CountDownLatch(1)
            var loadSuccess = false
            
            // Load model using kotlinllamacpp
            llamaHelper.load(modelPath, DEFAULT_CONTEXT_LENGTH) { contextId ->
                Log.d(TAG, "Model loaded successfully with context ID: $contextId")
                isLoaded = true
                loadSuccess = true
                latch.countDown()
            }
            
            // Wait for model to load (with timeout)
            val loaded = latch.await(60, TimeUnit.SECONDS)
            if (!loaded) {
                Log.e(TAG, "Model loading timed out")
                isLoaded = false
                return false
            }
            
            loadSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
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
            return "Error: Model not loaded. Please load a model first."
        }
        
        // For mock model, return a simple response
        if (modelPath == "mock-model") {
            return "This is a mock response from the model. In production, this would be the actual LLM output for prompt: \"$prompt\""
        }
        
        return try {
            val generatedText = StringBuilder()
            val latch = CountDownLatch(1)
            val collectionJob: Job
            
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
            collectionJob.cancel()
            
            if (!completed) {
                Log.e(TAG, "Generation timed out")
                return "Error: Generation timed out"
            }
            
            generatedText.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate response", e)
            "Error: ${e.message}"
        }
    }
    
    fun generateStream(
        prompt: String,
        maxTokens: Int = 100,
        temperature: Float = 0.7f,
        onToken: (String) -> Unit
    ) {
        if (!isModelLoaded()) {
            onToken("Error: Model not loaded. Please load a model first.")
            return
        }
        
        // For streaming, we'll use kotlinllamacpp's token callback
        scope.launch {
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
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming failed", e)
            }
        }
        
        llamaHelper.predict(prompt, partialCompletion = true)
    }
    
    fun unload() {
        try {
            llamaHelper.abort()
            isLoaded = false
            modelPath = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading model", e)
        }
    }
    
    /**
     * Explicitly release native resources.
     * Call this when you're done with the model to free memory immediately.
     */
    fun close() {
        try {
            llamaHelper.abort()
            llamaHelper.release()
            scope.cancel()
            isLoaded = false
        } catch (e: Exception) {
            Log.e(TAG, "Error closing model", e)
        }
    }
}
