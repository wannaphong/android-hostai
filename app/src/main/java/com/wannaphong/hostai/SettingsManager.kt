package com.wannaphong.hostai

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app settings using SharedPreferences
 */
class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    companion object {
        private const val PREFS_NAME = "hostai_settings"
        private const val KEY_CUSTOM_PORT = "custom_port"
        private const val KEY_WEB_CHAT_ENABLED = "web_chat_enabled"
        private const val KEY_TEXT_COMPLETIONS_ENABLED = "text_completions_enabled"
        private const val KEY_CHAT_COMPLETIONS_ENABLED = "chat_completions_enabled"
        private const val KEY_LOGGING_ENABLED = "logging_enabled"
        private const val KEY_USE_GPU_BACKEND = "use_gpu_backend"
        private const val KEY_BACKEND = "backend"
        private const val KEY_MAX_CONCURRENCY = "max_concurrency"
        private const val KEY_MAX_CONTEXT_LENGTH = "max_context_length"
        private const val KEY_MULTIMODAL_ENABLED = "multimodal_enabled"

        const val BACKEND_CPU = "cpu"
        const val BACKEND_GPU = "gpu"
        const val BACKEND_NPU = "npu"

        const val DEFAULT_PORT = 8080
        const val DEFAULT_MAX_CONCURRENCY = 1
        const val DEFAULT_MAX_CONTEXT_LENGTH = 2048
    }
    
    /**
     * Get custom port setting (default: 8080)
     */
    fun getCustomPort(): Int {
        return prefs.getInt(KEY_CUSTOM_PORT, DEFAULT_PORT)
    }
    
    /**
     * Set custom port
     */
    fun setCustomPort(port: Int) {
        prefs.edit().putInt(KEY_CUSTOM_PORT, port).apply()
    }
    
    /**
     * Check if web-based chat UI is enabled (default: true)
     */
    fun isWebChatEnabled(): Boolean {
        return prefs.getBoolean(KEY_WEB_CHAT_ENABLED, true)
    }
    
    /**
     * Set web-based chat UI enabled state
     */
    fun setWebChatEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEB_CHAT_ENABLED, enabled).apply()
    }
    
    /**
     * Check if text completions endpoint is enabled (default: true)
     */
    fun isTextCompletionsEnabled(): Boolean {
        return prefs.getBoolean(KEY_TEXT_COMPLETIONS_ENABLED, true)
    }
    
    /**
     * Set text completions endpoint enabled state
     */
    fun setTextCompletionsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TEXT_COMPLETIONS_ENABLED, enabled).apply()
    }
    
    /**
     * Check if chat completions endpoint is enabled (default: true)
     */
    fun isChatCompletionsEnabled(): Boolean {
        return prefs.getBoolean(KEY_CHAT_COMPLETIONS_ENABLED, true)
    }
    
    /**
     * Set chat completions endpoint enabled state
     */
    fun setChatCompletionsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHAT_COMPLETIONS_ENABLED, enabled).apply()
    }
    
    /**
     * Check if request logging is enabled (default: false)
     */
    fun isLoggingEnabled(): Boolean {
        return prefs.getBoolean(KEY_LOGGING_ENABLED, false)
    }
    
    /**
     * Set request logging enabled state
     */
    fun setLoggingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOGGING_ENABLED, enabled).apply()
    }
    
    /**
     * Get the selected inference backend ("cpu", "gpu", or "npu").
     * Migrates the legacy boolean GPU setting on first read.
     */
    fun getBackend(): String {
        val stored = prefs.getString(KEY_BACKEND, null)
        if (stored != null) return stored
        // Migrate legacy boolean setting: if GPU was enabled, default to GPU.
        val legacyGpu = prefs.getBoolean(KEY_USE_GPU_BACKEND, false)
        return if (legacyGpu) BACKEND_GPU else BACKEND_CPU
    }

    /**
     * Set the inference backend ("cpu", "gpu", or "npu").
     */
    fun setBackend(backend: String) {
        prefs.edit().putString(KEY_BACKEND, backend).apply()
    }

    /**
     * Check if GPU backend is enabled.
     * @deprecated Use [getBackend] instead.
     */
    @Deprecated("Use getBackend() instead", ReplaceWith("getBackend() == BACKEND_GPU"))
    fun isGpuBackendEnabled(): Boolean = getBackend() == BACKEND_GPU

    /**
     * Set GPU backend enabled state.
     * @deprecated Use [setBackend] instead.
     */
    @Deprecated("Use setBackend() instead")
    fun setGpuBackendEnabled(enabled: Boolean) {
        setBackend(if (enabled) BACKEND_GPU else BACKEND_CPU)
    }
    
    /**
     * Get max concurrency setting (default: 1)
     */
    fun getMaxConcurrency(): Int {
        return prefs.getInt(KEY_MAX_CONCURRENCY, DEFAULT_MAX_CONCURRENCY)
    }
    
    /**
     * Set max concurrency
     */
    fun setMaxConcurrency(concurrency: Int) {
        prefs.edit().putInt(KEY_MAX_CONCURRENCY, concurrency).apply()
    }

    /**
     * Get max context length (number of tokens) for the LLM engine (default: 2048)
     */
    fun getMaxContextLength(): Int {
        return prefs.getInt(KEY_MAX_CONTEXT_LENGTH, DEFAULT_MAX_CONTEXT_LENGTH)
    }

    /**
     * Set max context length (number of tokens) for the LLM engine
     */
    fun setMaxContextLength(length: Int) {
        prefs.edit().putInt(KEY_MAX_CONTEXT_LENGTH, length).apply()
    }

    /**
     * Check if multimodal mode is enabled (default: false).
     * Enable only for multimodal models (e.g. Gemma-3N) that include vision/audio components.
     * Text-only models will fail to load when this is enabled.
     */
    fun isMultimodalEnabled(): Boolean {
        return prefs.getBoolean(KEY_MULTIMODAL_ENABLED, false)
    }

    /**
     * Set multimodal mode enabled state
     */
    fun setMultimodalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MULTIMODAL_ENABLED, enabled).apply()
    }
}
