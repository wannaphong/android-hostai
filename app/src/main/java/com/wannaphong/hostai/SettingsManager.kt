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
        
        const val DEFAULT_PORT = 8080
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
     * Check if GPU backend is enabled (default: false, uses CPU for compatibility)
     */
    fun isGpuBackendEnabled(): Boolean {
        return prefs.getBoolean(KEY_USE_GPU_BACKEND, false)
    }
    
    /**
     * Set GPU backend enabled state
     */
    fun setGpuBackendEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_GPU_BACKEND, enabled).apply()
    }
}
