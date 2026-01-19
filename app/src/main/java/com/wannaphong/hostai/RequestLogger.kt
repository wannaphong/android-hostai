package com.wannaphong.hostai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Data class for logged request
 */
data class LoggedRequest(
    val timestamp: Long,
    val date: String,
    val ipAddress: String,
    val endpoint: String,
    val requestBody: String,
    val responseBody: String
)

/**
 * Manages logging of chat and completion requests
 * Singleton to ensure logs are shared across all activities
 * Logs are persisted to disk and automatically cleaned up after 90 days
 */
class RequestLogger private constructor(private val context: Context) {
    private val logs = ConcurrentLinkedQueue<LoggedRequest>()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val logsFile: File
    private val saveExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var pendingSave = false
    
    companion object {
        private const val TAG = "RequestLogger"
        private const val MAX_LOGS = 1000 // Limit to prevent excessive memory usage
        private const val LOG_RETENTION_DAYS = 90 // Keep logs for 90 days
        private const val LOGS_FILE_NAME = "request_logs.json"
        private const val SAVE_DELAY_MS = 5000L // Batch saves every 5 seconds
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L // Milliseconds in a day
        
        @Volatile
        private var instance: RequestLogger? = null
        
        fun getInstance(context: Context): RequestLogger {
            return instance ?: synchronized(this) {
                instance ?: RequestLogger(context.applicationContext).also { instance = it }
            }
        }
    }
    
    init {
        logsFile = File(context.filesDir, LOGS_FILE_NAME)
        // Load and clean logs asynchronously to avoid blocking
        saveExecutor.execute {
            loadLogsFromDisk()
            cleanOldLogs()
        }
    }
    
    /**
     * Load logs from disk
     */
    private fun loadLogsFromDisk() {
        try {
            if (logsFile.exists()) {
                val json = logsFile.readText()
                if (json.isNotEmpty()) {
                    try {
                        val logsList = gson.fromJson(json, Array<LoggedRequest>::class.java).toList()
                        logs.addAll(logsList)
                        LogManager.i(TAG, "Loaded ${logs.size} logs from disk")
                    } catch (e: JsonSyntaxException) {
                        LogManager.e(TAG, "Malformed JSON in logs file, skipping load", e)
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to load logs from disk", e)
        }
    }
    
    /**
     * Save logs to disk
     */
    private fun saveLogsToDisk() {
        try {
            val logsList = logs.toList()
            val json = gson.toJson(logsList)
            logsFile.writeText(json)
            LogManager.d(TAG, "Saved ${logsList.size} logs to disk")
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to save logs to disk", e)
        }
    }
    
    /**
     * Schedule a delayed save to disk (batches multiple saves)
     */
    private fun scheduleSaveToDisk() {
        synchronized(this) {
            if (!pendingSave) {
                pendingSave = true
                saveExecutor.schedule({
                    saveLogsToDisk()
                    synchronized(this) {
                        pendingSave = false
                    }
                }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        }
    }
    
    /**
     * Save logs to disk immediately (used for critical operations like clearing logs)
     */
    private fun saveLogsToDiskImmediate() {
        synchronized(this) {
            pendingSave = false
        }
        saveLogsToDisk()
    }
    
    /**
     * Clean logs older than 90 days
     */
    private fun cleanOldLogs() {
        try {
            val cutoffTime = System.currentTimeMillis() - (LOG_RETENTION_DAYS * MILLIS_PER_DAY)
            val sizeBefore = logs.size
            
            // Remove logs older than the cutoff time
            logs.removeIf { it.timestamp < cutoffTime }
            
            val sizeAfter = logs.size
            val removed = sizeBefore - sizeAfter
            
            if (removed > 0) {
                LogManager.i(TAG, "Cleaned $removed old logs (older than $LOG_RETENTION_DAYS days)")
                saveLogsToDiskImmediate()
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to clean old logs", e)
        }
    }
    
    /**
     * Log a request
     */
    fun logRequest(
        ipAddress: String,
        endpoint: String,
        requestBody: String,
        responseBody: String
    ) {
        val timestamp = System.currentTimeMillis()
        val date = dateFormat.format(Date(timestamp))
        
        val logEntry = LoggedRequest(
            timestamp = timestamp,
            date = date,
            ipAddress = ipAddress,
            endpoint = endpoint,
            requestBody = requestBody,
            responseBody = responseBody
        )
        
        logs.add(logEntry)
        
        // Remove oldest entries if we exceed the limit
        while (logs.size > MAX_LOGS) {
            logs.poll()
        }
        
        // Schedule a save to disk (batched)
        scheduleSaveToDisk()
        
        LogManager.d(TAG, "Logged request from $ipAddress to $endpoint at $date")
    }
    
    /**
     * Get all logged requests
     */
    fun getAllLogs(): List<LoggedRequest> {
        return logs.toList()
    }
    
    /**
     * Get logs count
     */
    fun getLogsCount(): Int {
        return logs.size
    }
    
    /**
     * Clear all logs
     */
    fun clearLogs() {
        val count = logs.size
        logs.clear()
        saveLogsToDiskImmediate()
        LogManager.i(TAG, "Cleared $count logged requests")
    }
    
    /**
     * Export logs to JSON file
     * Returns the file path on success, null on failure
     */
    fun exportLogsToJson(): File? {
        return try {
            val logsList = logs.toList()
            val json = gson.toJson(logsList)
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "request_logs_$timestamp.json"
            val file = File(context.getExternalFilesDir(null), fileName)
            
            file.writeText(json)
            
            LogManager.i(TAG, "Exported ${logsList.size} logs to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to export logs to JSON", e)
            null
        }
    }
    
    /**
     * Export logs as JSON string
     */
    fun exportLogsToJsonString(): String {
        val logsList = logs.toList()
        return gson.toJson(logsList)
    }
    
    /**
     * Shutdown the logger and clean up resources
     * Should be called when the application is terminating
     */
    fun shutdown() {
        try {
            // Save any pending logs immediately
            saveLogsToDiskImmediate()
            // Shutdown the executor
            saveExecutor.shutdown()
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                saveExecutor.shutdownNow()
            }
            LogManager.i(TAG, "RequestLogger shutdown complete")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error during shutdown", e)
            saveExecutor.shutdownNow()
        }
    }
}
