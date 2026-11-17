package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingNativeVoiceStart: Boolean = false
    private var nativeMediaRecorder: MediaRecorder? = null
    private var nativeVoiceFile: File? = null
    private var nativeVoiceStartMs: Long = 0L
    private var webViewRef: WebView? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        val results: Array<Uri>? = when {
            result.resultCode != Activity.RESULT_OK -> null
            result.data?.clipData != null -> {
                val clip = result.data!!.clipData!!
                Array(clip.itemCount) { idx -> clip.getItemAt(idx).uri }
            }
            result.data?.data != null -> arrayOf(result.data!!.data!!)
            else -> null
        }
        callback.onReceiveValue(results)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled gracefully */ }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val req = pendingWebPermissionRequest
        pendingWebPermissionRequest = null
        if (req != null) {
            if (granted) req.grant(req.resources) else req.deny()
        }
        if (pendingNativeVoiceStart) {
            pendingNativeVoiceStart = false
            if (granted) {
                beginNativeVoiceCapture()
            } else {
                notifyWebVoiceError("Microphone permission denied")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationChannel()

        setContent {
            MyApplicationTheme {
                JeewanCityWebShell(
                    onWebViewReady = { webView -> webViewRef = webView }
                )
            }
        }

        window.decorView.post {
            requestNotificationPermissionIfNeeded()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jeewan City Alerts & Group Chat",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Power shutdown alerts and Jeewan City Sahiwal resident group messages"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun JeewanCityWebShell(
        onWebViewReady: (WebView) -> Unit
    ) {
        var customFullscreenView by remember { mutableStateOf<View?>(null) }
        var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
        var cachedHtml by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            cachedHtml = withContext(Dispatchers.IO) {
                runCatching {
                    assets.open("index.html").bufferedReader().use { it.readText() }
                }.getOrNull()
            }
        }

        BackHandler(enabled = true) {
            if (customFullscreenView != null) {
                customViewCallback?.onCustomViewHidden()
                customFullscreenView = null
                customViewCallback = null
                return@BackHandler
            }
            val wv = webViewRef
            if (wv != null) {
                wv.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : false") { result ->
                    if (result != "true") {
                        if (wv.canGoBack() && wv.copyBackForwardList().currentIndex > 0) {
                            wv.goBack()
                        } else {
                            finish()
                        }
                    }
                }
            } else {
                finish()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            val loadedHtml = cachedHtml
            if (loadedHtml != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                                allowContentAccess = true
                                mediaPlaybackRequiresUserGesture = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                cacheMode = WebSettings.LOAD_DEFAULT
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(false)
                                userAgentString = userAgentString.replace("; wv", "")
                            }

                        addJavascriptInterface(AndroidBridge(this@MainActivity), "AndroidBridge")

                        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                            saveMediaSource(url, fileName, mimeType ?: "*/*")
                        }

                        webViewClient = object : WebViewClient() {
                            private var recoveredFromError = false

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val uri = request?.url ?: return false
                                val url = uri.toString()
                                val host = uri.host.orEmpty()
                                val path = uri.path.orEmpty()
                                if (host == "jeewan-city.firebaseapp.com" && !path.startsWith("/__/auth/")) {
                                    view?.let { loadLocalAssetHtml(it) }
                                    return true
                                }
                                return handleExternalUri(url)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true && view != null && !recoveredFromError) {
                                    recoveredFromError = true
                                    loadLocalAssetHtml(view)
                                }
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                view?.let { loadLocalAssetHtml(it) }
                                return true
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                this@MainActivity.filePathCallback?.onReceiveValue(null)
                                this@MainActivity.filePathCallback = filePathCallback

                                val rawTypes = fileChooserParams?.acceptTypes
                                    ?.flatMap { it.split(",") }
                                    ?.map { it.trim() }
                                    ?.filter { it.isNotBlank() }
                                    .orEmpty()

                                val mimeTypes = rawTypes.mapNotNull { entry ->
                                    when {
                                        entry == "*/*" -> null
                                        entry.contains("/") -> entry
                                        entry.startsWith(".") -> MimeTypeMap.getSingleton()
                                            .getMimeTypeFromExtension(entry.removePrefix(".").lowercase())
                                        else -> null
                                    }
                                }.distinct().toTypedArray()

                                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
                                    if (mimeTypes.size > 1) {
                                        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                                    }
                                }
                                return try {
                                    fileChooserLauncher.launch(Intent.createChooser(intent, "Select Photo, Video or File"))
                                    true
                                } catch (e: Exception) {
                                    this@MainActivity.filePathCallback = null
                                    false
                                }
                            }

                            override fun onPermissionRequest(request: PermissionRequest?) {
                                if (request == null) return
                                runOnUiThread {
                                    val needsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                                    if (needsAudio && ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            Manifest.permission.RECORD_AUDIO
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        pendingWebPermissionRequest = request
                                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        request.grant(request.resources)
                                    }
                                }
                            }

                            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                customFullscreenView = view
                                customViewCallback = callback
                            }

                            override fun onHideCustomView() {
                                customFullscreenView = null
                                customViewCallback = null
                            }
                        }

                        loadLocalAssetHtml(this, loadedHtml)
                        onWebViewReady(this)
                    }
                }
            )
            }

            customFullscreenView?.let { fsView ->
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            (fsView.parent as? ViewGroup)?.removeView(fsView)
                            addView(fsView)
                        }
                    }
                )
            }
        }
    }

    private fun loadLocalAssetHtml(webView: WebView, preloadedHtml: String? = null) {
        try {
            val html = preloadedHtml ?: assets.open("index.html").bufferedReader().use { it.readText() }
            webView.loadDataWithBaseURL(
                "https://jeewan-city.firebaseapp.com/",
                html,
                "text/html",
                "UTF-8",
                null
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to load app content: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleExternalUri(url: String): Boolean {
        if (url.startsWith("tel:") ||
            url.startsWith("mailto:") ||
            url.startsWith("whatsapp:") ||
            url.startsWith("https://wa.me/") ||
            url.startsWith("http://wa.me/")
        ) {
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            } catch (_: Exception) {
                Toast.makeText(this, "No app found to handle link", Toast.LENGTH_SHORT).show()
                true
            }
        }
        if (url.startsWith("https://jeewan-city.firebaseapp.com") ||
            url.startsWith("https://accounts.google.com") ||
            url.startsWith("https://apis.google.com") ||
            url.contains(".firebaseapp.com") ||
            url.startsWith("https://www.facebook.com") ||
            url.startsWith("https://m.facebook.com")
        ) {
            return false
        }
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
    }

    fun saveMediaSource(source: String, fileName: String, mimeType: String) {
        try {
            if (source.startsWith("data:")) {
                val commaIndex = source.indexOf(',')
                if (commaIndex == -1) return
                val header = source.substring(5, commaIndex)
                val resolvedMime = header.substringBefore(';').ifBlank { mimeType }
                val base64Data = source.substring(commaIndex + 1)
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                saveBytesToDownloads(bytes, fileName, resolvedMime)
            } else if (source.startsWith("http://") || source.startsWith("https://")) {
                val request = DownloadManager.Request(Uri.parse(source)).apply {
                    setTitle(fileName)
                    setDescription("Downloading from Jeewan City Sahiwal")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                runOnUiThread {
                    Toast.makeText(this, "Downloading $fileName...", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Download failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveBytesToDownloads(bytes: ByteArray, fileName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            FileOutputStream(File(dir, fileName)).use { it.write(bytes) }
        }
        runOnUiThread {
            Toast.makeText(this, "Saved $fileName to Downloads", Toast.LENGTH_SHORT).show()
        }
    }

    inner class AndroidBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun copyText(text: String) {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Jeewan City", text))
            activity.runOnUiThread {
                Toast.makeText(activity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun shareText(title: String, text: String) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            activity.startActivity(Intent.createChooser(intent, "Share via"))
        }

        @JavascriptInterface
        fun saveMediaToDownloads(source: String, fileName: String, mimeType: String) {
            activity.saveMediaSource(source, fileName, mimeType)
        }

        @JavascriptInterface
        fun showNativeNotification(title: String, body: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            try {
                val notification = NotificationCompat.Builder(activity, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
                val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(System.currentTimeMillis().toInt(), notification)
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun startNativeVoiceRecording() {
            activity.runOnUiThread {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    activity.pendingNativeVoiceStart = true
                    activity.audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@runOnUiThread
                }
                activity.beginNativeVoiceCapture()
            }
        }

        @JavascriptInterface
        fun stopNativeVoiceRecording(shouldSend: Boolean) {
            activity.runOnUiThread {
                activity.finishNativeVoiceCapture(shouldSend)
            }
        }
    }

    private fun beginNativeVoiceCapture() {
        releaseNativeRecorder()
        try {
            val outFile = File(cacheDir, "jeewan_voice_${System.currentTimeMillis()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(22050)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            nativeMediaRecorder = recorder
            nativeVoiceFile = outFile
            nativeVoiceStartMs = System.currentTimeMillis()
            webViewRef?.evaluateJavascript("window.onNativeVoiceStarted && window.onNativeVoiceStarted();", null)
        } catch (e: Exception) {
            releaseNativeRecorder()
            notifyWebVoiceError(e.localizedMessage ?: "Unable to start microphone")
        }
    }

    private fun finishNativeVoiceCapture(shouldSend: Boolean) {
        val recorder = nativeMediaRecorder
        val file = nativeVoiceFile
        val durSec = ((System.currentTimeMillis() - nativeVoiceStartMs) / 1000L).coerceAtLeast(1L)
        nativeMediaRecorder = null
        nativeVoiceFile = null

        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Very short press or stop error; handled gracefully below
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        if (!shouldSend || file == null || !file.exists() || file.length() < 256L) {
            runCatching { file?.delete() }
            return
        }

        Thread {
            try {
                val bytes = file.readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val dataUrl = "data:audio/mp4;base64,$b64"
                runCatching { file.delete() }
                runOnUiThread {
                    webViewRef?.evaluateJavascript(
                        "window.onNativeVoiceRecorded && window.onNativeVoiceRecorded('$dataUrl', $durSec);",
                        null
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    notifyWebVoiceError(e.localizedMessage ?: "Unable to save voice note")
                }
            }
        }.start()
    }

    private fun releaseNativeRecorder() {
        try {
            nativeMediaRecorder?.reset()
        } catch (_: Exception) {
        }
        try {
            nativeMediaRecorder?.release()
        } catch (_: Exception) {
        }
        nativeMediaRecorder = null
        runCatching { nativeVoiceFile?.delete() }
        nativeVoiceFile = null
    }

    private fun notifyWebVoiceError(message: String) {
        val escaped = message.replace("'", "\\'").replace("\n", " ")
        webViewRef?.post {
            webViewRef?.evaluateJavascript(
                "window.onNativeVoiceError && window.onNativeVoiceError('$escaped');",
                null
            )
        }
    }

    override fun onDestroy() {
        releaseNativeRecorder()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "jeewan_city_alerts"
    }
}
