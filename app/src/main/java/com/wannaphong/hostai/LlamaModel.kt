package com.wannaphong.hostai

import java.io.File

/**
 * Mock implementation of LLaMA model interface.
 * In a production app, this would interface with actual llama.cpp native code via JNI.
 */
class LlamaModel {
    private var isLoaded = false
    private var modelName = "llama-mock-model"
    private var modelPath: String? = null
    
    fun loadModel(modelPath: String): Boolean {
        // Mock implementation - in real app, this would load the GGUF model via JNI
        this.modelPath = modelPath
        
        // Extract model name from file path
        if (modelPath != "mock-model") {
            val file = File(modelPath)
            if (file.exists()) {
                modelName = file.name
            }
        }
        
        isLoaded = true
        return true
    }
    
    fun isModelLoaded(): Boolean = isLoaded
    
    fun getModelName(): String = modelName
    
    fun getModelPath(): String? = modelPath
    
    fun generate(prompt: String, maxTokens: Int = 100, temperature: Float = 0.7f): String {
        // Mock implementation - returns a simulated response
        // In real app, this would call llama.cpp's generation via JNI
        return "This is a mock response from the LLaMA model. In a production build, " +
               "this would be replaced with actual llama.cpp inference. Your prompt was: \"$prompt\""
    }
    
    fun generateStream(
        prompt: String,
        maxTokens: Int = 100,
        temperature: Float = 0.7f,
        onToken: (String) -> Unit
    ) {
        // Mock implementation - simulates streaming
        val response = generate(prompt, maxTokens, temperature)
        val words = response.split(" ")
        
        for (word in words) {
            onToken("$word ")
            Thread.sleep(50) // Simulate generation time
        }
    }
    
    fun unload() {
        isLoaded = false
        modelPath = null
    }
}
