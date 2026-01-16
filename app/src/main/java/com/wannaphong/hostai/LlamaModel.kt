package com.wannaphong.hostai

import java.io.File

/**
 * LLaMA model interface with JNI bindings to native llama.cpp code.
 * 
 * This implementation uses native code (C++ via JNI) to interface with llama.cpp.
 * The native library provides actual model loading and text generation capabilities.
 */
class LlamaModel {
    private var nativeContext: Long = 0
    private var modelName = "llama-model"
    private var modelPath: String? = null
    
    companion object {
        init {
            // Load the native library
            System.loadLibrary("hostai")
        }
    }
    
    // Native method declarations
    private external fun nativeInit(): Long
    private external fun nativeLoadModel(contextPtr: Long, modelPath: String): Boolean
    private external fun nativeGenerate(
        contextPtr: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String
    private external fun nativeIsLoaded(contextPtr: Long): Boolean
    private external fun nativeUnload(contextPtr: Long)
    private external fun nativeFree(contextPtr: Long)
    
    init {
        // Initialize native context
        nativeContext = nativeInit()
    }
    
    fun loadModel(modelPath: String): Boolean {
        this.modelPath = modelPath
        
        // Extract model name from file path
        if (modelPath != "mock-model") {
            val file = File(modelPath)
            if (file.exists()) {
                modelName = file.name
            }
        }
        
        // Load model via JNI
        val success = nativeLoadModel(nativeContext, modelPath)
        
        return success
    }
    
    fun isModelLoaded(): Boolean {
        return nativeIsLoaded(nativeContext)
    }
    
    fun getModelName(): String = modelName
    
    fun getModelPath(): String? = modelPath
    
    fun generate(prompt: String, maxTokens: Int = 100, temperature: Float = 0.7f): String {
        if (!isModelLoaded()) {
            return "Error: Model not loaded. Please load a model first."
        }
        
        // Call native generation method
        return nativeGenerate(nativeContext, prompt, maxTokens, temperature)
    }
    
    fun generateStream(
        prompt: String,
        maxTokens: Int = 100,
        temperature: Float = 0.7f,
        onToken: (String) -> Unit
    ) {
        // For streaming, we'll simulate it by calling generate and splitting the response
        // A full implementation would require callback support in JNI
        val response = generate(prompt, maxTokens, temperature)
        val words = response.split(" ")
        
        for (word in words) {
            onToken("$word ")
            Thread.sleep(50) // Simulate generation time
        }
    }
    
    fun unload() {
        nativeUnload(nativeContext)
        modelPath = null
    }
    
    protected fun finalize() {
        // Clean up native resources when object is garbage collected
        if (nativeContext != 0L) {
            nativeFree(nativeContext)
            nativeContext = 0
        }
    }
}
