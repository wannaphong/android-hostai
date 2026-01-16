package com.wannaphong.hostai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wannaphong.hostai.databinding.ActivityLogViewerBinding

/**
 * Activity to view and manage application logs.
 */
class LogViewerActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityLogViewerBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        updateLogs()
    }
    
    override fun onResume() {
        super.onResume()
        updateLogs()
    }
    
    private fun setupUI() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Application Logs"
        
        binding.refreshButton.setOnClickListener {
            updateLogs()
            Toast.makeText(this, "Logs refreshed", Toast.LENGTH_SHORT).show()
        }
        
        binding.copyLogsButton.setOnClickListener {
            copyLogsToClipboard()
        }
        
        binding.clearLogsButton.setOnClickListener {
            clearLogs()
        }
    }
    
    private fun updateLogs() {
        val logs = LogManager.getAllLogs()
        binding.logsTextView.text = if (logs.isEmpty()) {
            "No logs available"
        } else {
            logs
        }
        
        // Auto-scroll to bottom
        binding.scrollView.post {
            binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
    
    private fun copyLogsToClipboard() {
        val logs = LogManager.getAllLogs()
        if (logs.isEmpty()) {
            Toast.makeText(this, "No logs to copy", Toast.LENGTH_SHORT).show()
            return
        }
        
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Application Logs", logs)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
    }
    
    private fun clearLogs() {
        LogManager.clearLogs()
        updateLogs()
        Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
