package com.wannaphong.hostai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wannaphong.hostai.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private var apiServerService: ApiServerService? = null
    private var isBound = false
    
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
            
            binding.modelStatusText.text = getString(R.string.model_loaded, "Mock LLaMA Model")
        } else {
            binding.serverStatusText.text = getString(R.string.server_stopped)
            binding.serverStatusText.setTextColor(Color.RED)
            binding.startStopButton.text = getString(R.string.start_server)
            
            binding.serverUrlLabel.visibility = View.GONE
            binding.serverUrlText.visibility = View.GONE
            binding.copyUrlButton.visibility = View.GONE
            binding.testServerButton.visibility = View.GONE
            
            binding.modelStatusText.text = getString(R.string.model_not_loaded)
        }
    }
    
    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipAddress = wifiManager.connectionInfo.ipAddress
            return Formatter.formatIpAddress(ipAddress)
        } catch (e: Exception) {
            return "localhost"
        }
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
    
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
