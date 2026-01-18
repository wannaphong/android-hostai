package com.wannaphong.hostai

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wannaphong.hostai.databinding.ActivityLicensesBinding

class LicensesActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityLicensesBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Setup action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.licenses)
        
        setupLicenseText()
    }
    
    private fun setupLicenseText() {
        val licenseText = buildString {
            appendLine("HostAI uses the following open source libraries:\n")
            
            // AndroidX Libraries
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("AndroidX Core & AppCompat")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Copyright (C) The Android Open Source Project")
            appendLine("Licensed under the Apache License 2.0")
            appendLine("https://developer.android.com/jetpack\n")
            
            // Material Components
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Material Components for Android")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Copyright (C) Google Inc.")
            appendLine("Licensed under the Apache License 2.0")
            appendLine("https://github.com/material-components/material-components-android\n")
            
            // Javalin
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Javalin")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Copyright (C) David Åse and contributors")
            appendLine("Licensed under the Apache License 2.0")
            appendLine("https://javalin.io/\n")
            
            // SLF4J
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("SLF4J Android")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Copyright (C) QOS.ch")
            appendLine("Licensed under the MIT License")
            appendLine("https://www.slf4j.org/\n")
            
            // Gson
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Gson")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Copyright (C) Google Inc.")
            appendLine("Licensed under the Apache License 2.0")
            appendLine("https://github.com/google/gson\n")
            
            // Kotlin Coroutines
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Kotlin Coroutines")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Copyright (C) JetBrains s.r.o.")
            appendLine("Licensed under the Apache License 2.0")
            appendLine("https://github.com/Kotlin/kotlinx.coroutines\n")
            
            // LiteRT-LM
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("LiteRT-LM (LiteRT Language Models)")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Copyright (C) Google LLC")
            appendLine("Licensed under the Apache License 2.0")
            appendLine("https://github.com/google-ai-edge/LiteRT-LM\n")
            
            // Apache License 2.0 Full Text
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Apache License 2.0")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Licensed under the Apache License, Version 2.0 (the \"License\");")
            appendLine("you may not use this file except in compliance with the License.")
            appendLine("You may obtain a copy of the License at")
            appendLine()
            appendLine("    http://www.apache.org/licenses/LICENSE-2.0")
            appendLine()
            appendLine("Unless required by applicable law or agreed to in writing, software")
            appendLine("distributed under the License is distributed on an \"AS IS\" BASIS,")
            appendLine("WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.")
            appendLine("See the License for the specific language governing permissions and")
            appendLine("limitations under the License.\n")
            
            // MIT License Full Text
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("MIT License")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Permission is hereby granted, free of charge, to any person obtaining")
            appendLine("a copy of this software and associated documentation files (the")
            appendLine("\"Software\"), to deal in the Software without restriction, including")
            appendLine("without limitation the rights to use, copy, modify, merge, publish,")
            appendLine("distribute, sublicense, and/or sell copies of the Software, and to")
            appendLine("permit persons to whom the Software is furnished to do so, subject to")
            appendLine("the following conditions:")
            appendLine()
            appendLine("The above copyright notice and this permission notice shall be")
            appendLine("included in all copies or substantial portions of the Software.")
            appendLine()
            appendLine("THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND,")
            appendLine("EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF")
            appendLine("MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.")
            appendLine("IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY")
            appendLine("CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,")
            appendLine("TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE")
            appendLine("SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.")
        }
        
        binding.licenseTextView.text = licenseText
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
