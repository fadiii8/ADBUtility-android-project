package com.adbutility.app

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.adbutility.app.usb.UsbSessionManager

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var usbSessions: UsbSessionManager

    private var pendingFilePickCallback: ((Uri?) -> Unit)? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cb = pendingFilePickCallback
        pendingFilePickCallback = null
        cb?.invoke(uri)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbSessions = UsbSessionManager(applicationContext)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()

        val filePicker = object : NativeBridge.FilePicker {
            override fun pickFile(onPicked: (Uri?) -> Unit) {
                pendingFilePickCallback = onPicked
                // "*/*" - .img files don't always report a recognised MIME type.
                filePickerLauncher.launch(arrayOf("*/*"))
            }
        }

        webView.addJavascriptInterface(NativeBridge(webView, usbSessions, filePicker), "NativeBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        usbSessions.disconnect()
        super.onDestroy()
    }
}
