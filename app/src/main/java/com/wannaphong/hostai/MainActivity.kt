package com.wannaphong.hostai

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.wannaphong.hostai.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private var apiServerService: ApiServerService? = null
    private var isBound = false
    private var selectedModelPath: String? = null
    private var selectedModelName: String? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ApiServerService.LocalBinder
            apiServerService = binder.getService()
            isBound = true
            updateUI()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            apiServerService = null
            isBound = false
            updateUI()
        }
    }
    
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFile(uri)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        bindToService()
    }
    
    private fun setupUI() {
        binding.startStopButton.setOnClickListener {
            if (isServerRunning()) {
                stopServer()
            } else {
                startServer()
            }
        }
        
        binding.copyUrlButton.setOnClickListener {
            copyUrlToClipboard()
        }
        
        binding.testServerButton.setOnClickListener {
            testServer()
        }
        
        binding.selectModelButton.setOnClickListener {
            selectModelFile()
        }
        
        updateUI()
    }
    
    private fun bindToService() {
        val intent = Intent(this, ApiServerService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun startServer() {
        val intent = Intent(this, ApiServerService::class.java).apply {
            action = ApiServerService.ACTION_START
            putExtra(ApiServerService.EXTRA_PORT, ApiServerService.DEFAULT_PORT)
            selectedModelPath?.let { 
                putExtra(ApiServerService.EXTRA_MODEL_PATH, it)
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        // Wait a moment for service to start, then update UI
        binding.root.postDelayed({
            updateUI()
        }, 500)
    }
    
    private fun stopServer() {
        val intent = Intent(this, ApiServerService::class.java).apply {
            action = ApiServerService.ACTION_STOP
        }
        startService(intent)
        
        binding.root.postDelayed({
            updateUI()
        }, 500)
    }
    
    private fun isServerRunning(): Boolean {
        return apiServerService?.isServerRunning() ?: false
    }
    
    private fun updateUI() {
        val isRunning = isServerRunning()
        
        if (isRunning) {
            binding.serverStatusText.text = getString(R.string.server_running)
            binding.serverStatusText.setTextColor(Color.GREEN)
            binding.startStopButton.text = getString(R.string.stop_server)
            
            val ipAddress = getLocalIpAddress()
            val port = apiServerService?.getServerPort() ?: ApiServerService.DEFAULT_PORT
            val serverUrl = "http://$ipAddress:$port"
            
            binding.serverUrlLabel.visibility = View.VISIBLE
            binding.serverUrlText.visibility = View.VISIBLE
            binding.serverUrlText.text = serverUrl
            binding.copyUrlButton.visibility = View.VISIBLE
            binding.testServerButton.visibility = View.VISIBLE
            
            val model = apiServerService?.getLoadedModel()
            val modelName = model?.getModelName() ?: "Unknown"
            binding.modelStatusText.text = getString(R.string.model_loaded, modelName)
            binding.selectModelButton.isEnabled = false
        } else {
            binding.serverStatusText.text = getString(R.string.server_stopped)
            binding.serverStatusText.setTextColor(Color.RED)
            binding.startStopButton.text = getString(R.string.start_server)
            
            binding.serverUrlLabel.visibility = View.GONE
            binding.serverUrlText.visibility = View.GONE
            binding.copyUrlButton.visibility = View.GONE
            binding.testServerButton.visibility = View.GONE
            
            if (selectedModelName != null) {
                binding.modelStatusText.text = getString(R.string.model_selected, selectedModelName)
            } else {
                binding.modelStatusText.text = getString(R.string.no_model_selected)
            }
            binding.selectModelButton.isEnabled = true
        }
    }
    
    private fun getLocalIpAddress(): String {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For Android 12+, use ConnectivityManager
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = connectivityManager.activeNetwork
                val linkProperties = connectivityManager.getLinkProperties(network)
                linkProperties?.linkAddresses?.forEach { linkAddress ->
                    val address = linkAddress.address
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress ?: "localhost"
                    }
                }
            } else {
                // For older Android versions, use WifiManager
                @Suppress("DEPRECATION")
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ipAddress = wifiManager.connectionInfo.ipAddress
                return Formatter.formatIpAddress(ipAddress)
            }
        } catch (e: Exception) {
            return "localhost"
        }
        return "localhost"
    }
    
    private fun copyUrlToClipboard() {
        val url = binding.serverUrlText.text.toString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Server URL", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show()
    }
    
    private fun testServer() {
        val ipAddress = getLocalIpAddress()
        val port = apiServerService?.getServerPort() ?: ApiServerService.DEFAULT_PORT
        val testUrl = "http://$ipAddress:$port/health"
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val url = URL(testUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    
                    val responseCode = connection.responseCode
                    val response = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()
                    
                    "Response Code: $responseCode\n$response"
                }
                
                Toast.makeText(this@MainActivity, "Server test successful!\n$result", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Server test failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun selectModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // Filter for .gguf files
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("*/*"))
        }
        filePickerLauncher.launch(intent)
    }
    
    private fun handleSelectedFile(uri: Uri) {
        try {
            // Get file name and size
            var fileName: String? = null
            var fileSize: Long = 0
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                cursor.moveToFirst()
                fileName = cursor.getString(nameIndex)
                fileSize = cursor.getLong(sizeIndex)
            }
            
            if (fileName == null || !fileName!!.endsWith(".gguf", ignoreCase = true)) {
                Toast.makeText(this, "Please select a GGUF model file", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Check file size (limit to 2GB to avoid OOM)
            val maxFileSize = 2L * 1024 * 1024 * 1024 // 2GB
            if (fileSize > maxFileSize) {
                Toast.makeText(this, "File too large. Maximum size is 2GB", Toast.LENGTH_LONG).show()
                return
            }
            
            // Copy file to internal storage
            val internalFile = File(filesDir, fileName!!)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(internalFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            selectedModelPath = internalFile.absolutePath
            selectedModelName = fileName
            
            Toast.makeText(this, "Model selected: $fileName", Toast.LENGTH_SHORT).show()
            updateUI()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load model: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
