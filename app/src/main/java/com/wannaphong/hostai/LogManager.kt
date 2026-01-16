package com.wannaphong.hostai

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralized logging manager that collects logs in memory for display in the app.
 * Thread-safe implementation using ArrayDeque with synchronized access.
 * Automatically maintains a maximum of MAX_LOGS entries by removing oldest logs.
 */
object LogManager {
    private const val MAX_LOGS = 1000
    private val logs = ArrayDeque<LogEntry>(MAX_LOGS)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    // Synchronize access to logs for thread safety
    private val lock = Any()
    
    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            val time = dateFormat.format(Date(timestamp))
            return "[$time] [${level.name}] [$tag] $message"
        }
    }
    
    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR
    }
    
    /**
     * Log a debug message
     */
    fun d(tag: String, message: String) {
        addLog(LogLevel.DEBUG, tag, message)
        Log.d(tag, message)
    }
    
    /**
     * Log an info message
     */
    fun i(tag: String, message: String) {
        addLog(LogLevel.INFO, tag, message)
        Log.i(tag, message)
    }
    
    /**
     * Log a warning message
     */
    fun w(tag: String, message: String) {
        addLog(LogLevel.WARN, tag, message)
        Log.w(tag, message)
    }
    
    /**
     * Log an error message
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        addLog(LogLevel.ERROR, tag, fullMessage)
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
    
    private fun addLog(level: LogLevel, tag: String, message: String) {
        synchronized(lock) {
            val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
            logs.add(entry)
            
            // Keep only the last MAX_LOGS entries
            if (logs.size > MAX_LOGS) {
                logs.removeFirst()
            }
        }
    }
    
    /**
     * Get all logs as a formatted string
     */
    fun getAllLogs(): String {
        synchronized(lock) {
            return logs.joinToString("\n") { it.format() }
        }
    }
    
    /**
     * Get all log entries
     */
    fun getLogEntries(): List<LogEntry> {
        synchronized(lock) {
            return logs.toList()
        }
    }
    
    /**
     * Clear all logs
     */
    fun clearLogs() {
        synchronized(lock) {
            logs.clear()
        }
    }
}
