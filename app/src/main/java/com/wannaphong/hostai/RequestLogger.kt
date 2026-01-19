package com.wannaphong.hostai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

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
    
    companion object {
        private const val TAG = "RequestLogger"
        private const val MAX_LOGS = 1000 // Limit to prevent excessive memory usage
        private const val LOG_RETENTION_DAYS = 90 // Keep logs for 90 days
        private const val LOGS_FILE_NAME = "request_logs.json"
        
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
        loadLogsFromDisk()
        cleanOldLogs()
    }
    
    /**
     * Load logs from disk
     */
    private fun loadLogsFromDisk() {
        try {
            if (logsFile.exists()) {
                val json = logsFile.readText()
                if (json.isNotEmpty()) {
                    val logsList = gson.fromJson(json, Array<LoggedRequest>::class.java).toList()
                    logs.addAll(logsList)
                    LogManager.i(TAG, "Loaded ${logs.size} logs from disk")
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
     * Clean logs older than 90 days
     */
    private fun cleanOldLogs() {
        try {
            val cutoffTime = System.currentTimeMillis() - (LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
            val sizeBefore = logs.size
            
            // Remove logs older than the cutoff time
            logs.removeIf { it.timestamp < cutoffTime }
            
            val sizeAfter = logs.size
            val removed = sizeBefore - sizeAfter
            
            if (removed > 0) {
                LogManager.i(TAG, "Cleaned $removed old logs (older than $LOG_RETENTION_DAYS days)")
                saveLogsToDisk()
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
        
        // Save to disk after adding new log
        saveLogsToDisk()
        
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
        saveLogsToDisk()
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
}
