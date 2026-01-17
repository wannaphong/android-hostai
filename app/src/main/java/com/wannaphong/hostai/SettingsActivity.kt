package com.wannaphong.hostai

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.wannaphong.hostai.databinding.ActivitySettingsBinding
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager
    private val requestLogger by lazy { RequestLogger.getInstance(this) }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Setup action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)
        
        settingsManager = SettingsManager(this)
        requestLogger = RequestLogger(this)
        
        loadSettings()
        setupUI()
        updateMemoryUsage()
        updateLogsCount()
    }
    
    private fun loadSettings() {
        // Load port setting
        binding.portEditText.setText(settingsManager.getCustomPort().toString())
        
        // Load feature toggles
        binding.webChatSwitch.isChecked = settingsManager.isWebChatEnabled()
        binding.textCompletionsSwitch.isChecked = settingsManager.isTextCompletionsEnabled()
        binding.chatCompletionsSwitch.isChecked = settingsManager.isChatCompletionsEnabled()
        
        // Load logging setting
        binding.loggingSwitch.isChecked = settingsManager.isLoggingEnabled()
    }
    
    private fun setupUI() {
        binding.refreshMemoryButton.setOnClickListener {
            updateMemoryUsage()
        }
        
        binding.exportLogsButton.setOnClickListener {
            exportLogs()
        }
        
        binding.clearLogsButton.setOnClickListener {
            clearLogs()
        }
        
        binding.saveSettingsButton.setOnClickListener {
            saveSettings()
        }
        
        // Update logs count when logging switch is toggled
        binding.loggingSwitch.setOnCheckedChangeListener { _, _ ->
            updateLogsCount()
        }
    }
    
    private fun updateMemoryUsage() {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val totalMemoryMB = memoryInfo.totalMem / (1024 * 1024)
        val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
        val usedMemoryMB = totalMemoryMB - availableMemoryMB
        
        binding.memoryUsageText.text = getString(
            R.string.memory_used,
            usedMemoryMB.toString(),
            totalMemoryMB.toString()
        )
    }
    
    private fun updateLogsCount() {
        val count = requestLogger.getLogsCount()
        if (count > 0) {
            binding.logsCountText.text = getString(R.string.logs_recorded, count)
        } else {
            binding.logsCountText.text = getString(R.string.no_logs_recorded)
        }
    }
    
    private fun saveSettings() {
        // Validate and save port
        val portText = binding.portEditText.text.toString()
        val port = portText.toIntOrNull()
        
        if (port == null || port < 1024 || port > 65535) {
            Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_LONG).show()
            return
        }
        
        settingsManager.setCustomPort(port)
        
        // Save feature toggles
        settingsManager.setWebChatEnabled(binding.webChatSwitch.isChecked)
        settingsManager.setTextCompletionsEnabled(binding.textCompletionsSwitch.isChecked)
        settingsManager.setChatCompletionsEnabled(binding.chatCompletionsSwitch.isChecked)
        
        // Save logging setting
        settingsManager.setLoggingEnabled(binding.loggingSwitch.isChecked)
        
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        
        // Return to main activity
        finish()
    }
    
    private fun exportLogs() {
        val file = requestLogger.exportLogsToJson()
        if (file != null) {
            try {
                // Share the exported file
                val uri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    file
                )
                
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                startActivity(Intent.createChooser(shareIntent, getString(R.string.export_logs_json)))
                
                Toast.makeText(
                    this,
                    getString(R.string.logs_exported, file.name),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                LogManager.e("SettingsActivity", "Failed to share exported logs", e)
                Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
        }
    }
    
    private fun clearLogs() {
        requestLogger.clearLogs()
        updateLogsCount()
        Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
