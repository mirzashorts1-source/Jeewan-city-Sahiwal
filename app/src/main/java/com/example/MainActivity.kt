package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingNativeVoiceStart: Boolean = false
    private var nativeMediaRecorder: MediaRecorder? = null
    private var nativeVoiceFile: File? = null
    private var nativeVoiceStartMs: Long = 0L
    private var webViewRef: WebView? = null
    private var pendingNotificationPostId: String? = null

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
    ) { granted ->
        if (granted) {
            createNotificationChannel(this)
            JeewanNotificationScheduler.schedulePeriodic(this)
        }
        webViewRef?.post {
            webViewRef?.evaluateJavascript(
                "window.refreshNotificationStatusUI && window.refreshNotificationStatusUI();",
                null
            )
        }
    }

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
        isActivityInForeground = true
        createNotificationChannel(this)
        extractPostIdFromIntent(intent)
        syncNativeFcmSubscription(this)
        JeewanNotificationScheduler.schedulePeriodic(this)

        setContent {
            MyApplicationTheme {
                JeewanCityWebShell(
                    onWebViewReady = { webView ->
                        webViewRef = webView
                        activeWebViewRef = WeakReference(webView)
                        dispatchPendingNotificationPost()
                        syncNativeFcmSubscription(this@MainActivity) { nativeToken ->
                            val escaped = nativeToken
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")
                            webView.post {
                                webView.evaluateJavascript(
                                    "window.onNativeFcmToken && window.onNativeFcmToken('$escaped');",
                                    null
                                )
                            }
                        }
                    }
                )
            }
        }

        window.decorView.post {
            requestNotificationPermissionIfNeeded()
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityInForeground = true
        webViewRef?.let { wv ->
            activeWebViewRef = WeakReference(wv)
            wv.post {
                wv.evaluateJavascript(
                    "window.flushPendingNotificationActions && window.flushPendingNotificationActions();",
                    null
                )
            }
        }
    }

    override fun onPause() {
        isActivityInForeground = false
        super.onPause()
    }

    override fun onStop() {
        isActivityInForeground = false
        JeewanNotificationScheduler.schedulePeriodic(this)
        JeewanNotificationScheduler.scheduleQuickCatchUp(this, 60_000L)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractPostIdFromIntent(intent)
        dispatchPendingNotificationPost()
    }

    private fun extractPostIdFromIntent(intent: Intent?) {
        if (intent == null) return
        val extraPostId = intent.getStringExtra(EXTRA_POST_ID)?.trim().orEmpty()
        val queryPostId = intent.data?.getQueryParameter("postId")?.trim().orEmpty()
        val resolved = extraPostId.ifEmpty { queryPostId }
        if (resolved.isNotEmpty()) {
            pendingNotificationPostId = resolved
        }
    }

    private fun dispatchPendingNotificationPost() {
        val postId = pendingNotificationPostId ?: return
        val wv = webViewRef ?: return
        val escapedId = postId
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        wv.post {
            wv.evaluateJavascript(
                "window.openPostFromNotification && window.openPostFromNotification('$escapedId');",
                null
            )
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
        createNotificationChannel(this)
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
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                dispatchPendingNotificationPost()
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
                }
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
        val normalized = normalizeExternalUrl(url)
        if (normalized.isEmpty()) return false

        if (normalized.startsWith("tel:") ||
            normalized.startsWith("mailto:") ||
            normalized.startsWith("whatsapp:") ||
            normalized.startsWith("https://wa.me/") ||
            normalized.startsWith("http://wa.me/")
        ) {
            return openUriExternally(normalized)
        }
        if (isOAuthFlowUrl(normalized)) {
            return false
        }
        return if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            openUriExternally(normalized)
        } else {
            false
        }
    }

    private fun openUriExternally(normalizedUrl: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl))
            startActivity(intent)
            true
        } catch (_: Exception) {
            runOnUiThread {
                Toast.makeText(this, "No app found to handle link", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    fun saveMediaSource(source: String, fileName: String, mimeType: String, subFolder: String = "") {
        try {
            var resolvedSubFolder = subFolder.trim()
            var cleanFileName = fileName.trim()
            if (resolvedSubFolder.isEmpty() && cleanFileName.contains("|")) {
                val parts = cleanFileName.split("|", limit = 2)
                resolvedSubFolder = parts[0].trim()
                cleanFileName = parts[1].trim()
            }
            cleanFileName = sanitizeMediaFileName(cleanFileName)

            if (source.startsWith("data:")) {
                val commaIndex = source.indexOf(',')
                if (commaIndex == -1) return
                val header = source.substring(5, commaIndex)
                val resolvedMime = header.substringBefore(';').ifBlank { mimeType }.ifBlank { "*/*" }
                val base64Data = source.substring(commaIndex + 1)
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val dest = resolveMediaDestination(resolvedMime, cleanFileName, resolvedSubFolder)
                writeStreamToMediaStore(bytes.inputStream(), cleanFileName, resolvedMime, dest)
                runOnUiThread {
                    Toast.makeText(this, "Saved to ${dest.albumLabel}", Toast.LENGTH_SHORT).show()
                }
            } else if (source.startsWith("http://") || source.startsWith("https://")) {
                val dest = resolveMediaDestination(mimeType, cleanFileName, resolvedSubFolder)
                runOnUiThread {
                    Toast.makeText(this, "Saving to ${dest.albumLabel}...", Toast.LENGTH_SHORT).show()
                }
                Thread {
                    val savedViaStream = downloadHttpMediaToStore(source, cleanFileName, mimeType, resolvedSubFolder)
                    if (!savedViaStream) {
                        runOnUiThread {
                            fallbackDownloadManager(source, cleanFileName, dest)
                        }
                    }
                }.start()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Download failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadHttpMediaToStore(
        urlStr: String,
        fileName: String,
        fallbackMime: String,
        explicitSubFolder: String
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            var currentUrl = urlStr
            var hops = 0
            while (hops < 5) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 60000
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) break
                    currentUrl = loc
                    hops++
                } else {
                    break
                }
            }
            val activeConn = conn ?: return false
            if (activeConn.responseCode !in 200..299) {
                activeConn.disconnect()
                return false
            }
            val contentType = activeConn.contentType?.substringBefore(';')?.trim().orEmpty()
            val resolvedMime = when {
                contentType.isNotBlank() && contentType != "application/octet-stream" -> contentType
                fallbackMime.isNotBlank() -> fallbackMime
                else -> "*/*"
            }
            val dest = resolveMediaDestination(resolvedMime, fileName, explicitSubFolder)
            activeConn.inputStream.use { input ->
                writeStreamToMediaStore(input, fileName, resolvedMime, dest)
            }
            runOnUiThread {
                Toast.makeText(this, "Saved to ${dest.albumLabel}", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun fallbackDownloadManager(source: String, fileName: String, dest: MediaDestination) {
        try {
            val dmSubPath = "${dest.subFolder}/$fileName".trimStart('/')
            val request = DownloadManager.Request(Uri.parse(source)).apply {
                setTitle(fileName)
                setDescription("Saving to ${dest.albumLabel}")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                @Suppress("DEPRECATION")
                allowScanningByMediaScanner()
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, dmSubPath)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeStreamToMediaStore(
        input: InputStream,
        fileName: String,
        mimeType: String,
        dest: MediaDestination
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collectionUri = when {
                dest.isGalleryImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                dest.isGalleryVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dest.relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(collectionUri, values)
                ?: throw IllegalStateException("Unable to create MediaStore entry")
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    input.copyTo(out)
                    out.flush()
                } ?: throw IllegalStateException("Unable to open output stream")
                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(uri, doneValues, null, null)
            } catch (e: Exception) {
                runCatching { contentResolver.delete(uri, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val baseDir = Environment.getExternalStoragePublicDirectory(dest.publicDir)
            val targetDir = File(baseDir, dest.subFolder)
            if (!targetDir.exists()) targetDir.mkdirs()
            val outFile = File(targetDir, fileName)
            FileOutputStream(outFile).use { out ->
                input.copyTo(out)
                out.flush()
            }
            MediaScannerConnection.scanFile(
                this,
                arrayOf(outFile.absolutePath),
                arrayOf(mimeType),
                null
            )
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
        fun openExternalUrl(rawUrl: String) {
            val normalized = normalizeExternalUrl(rawUrl)
            if (normalized.isEmpty()) return
            activity.runOnUiThread {
                activity.openUriExternally(normalized)
            }
        }

        @JavascriptInterface
        fun saveMediaToDownloads(source: String, fileName: String, mimeType: String) {
            activity.saveMediaSource(source, fileName, mimeType, "")
        }

        @JavascriptInterface
        fun saveMediaToFolder(source: String, fileName: String, mimeType: String, subFolder: String) {
            activity.saveMediaSource(source, fileName, mimeType, subFolder)
        }

        @JavascriptInterface
        fun showInteractiveMessageNotification(postId: String, title: String, body: String) {
            showInteractiveMessageNotificationCompat(activity, postId, title, body)
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
                val notifId = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
                val openAppIntent = Intent(activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val contentPi = PendingIntent.getActivity(
                    activity,
                    notifId,
                    openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(activity, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_jeewan)
                    .setColor(0xFF008F39.toInt())
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(contentPi)
                    .setAutoCancel(true)
                    .build()
                val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(notifId, notification)
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun consumePendingNotificationPostId(): String {
            val current = activity.pendingNotificationPostId.orEmpty()
            activity.pendingNotificationPostId = null
            return current
        }

        @JavascriptInterface
        fun isAppInForeground(): Boolean = isActivityInForeground

        @JavascriptInterface
        fun syncNotificationState(
            lastNotifiedTs: Long,
            uid: String,
            userName: String,
            idToken: String,
            alertsEnabled: Boolean,
            isMuted: Boolean
        ) {
            runCatching {
                val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val existingTs = prefs.getLong("last_notified_ts", 0L)
                val nextTs = maxOf(existingTs, lastNotifiedTs)
                prefs.edit().apply {
                    if (nextTs > 0L) putLong("last_notified_ts", nextTs)
                    if (uid.isNotBlank()) putString("current_uid", uid.trim())
                    if (userName.isNotBlank()) putString("current_user_name", userName.trim())
                    if (idToken.isNotBlank()) putString("firebase_id_token", idToken.trim())
                    putBoolean("alert_notifs_enabled", alertsEnabled)
                    putBoolean("push_muted", isMuted)
                }.apply()
                if (!alertsEnabled || isMuted) {
                    JeewanNotificationScheduler.cancelAll(activity)
                } else {
                    JeewanNotificationScheduler.schedulePeriodic(activity)
                }
            }
        }

        @JavascriptInterface
        fun markPostNotified(postId: String, postTs: Long) {
            val cleanId = postId.trim()
            if (cleanId.isEmpty() && postTs <= 0L) return
            runCatching {
                val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val existingIds = prefs.getStringSet("notified_post_ids", emptySet()).orEmpty().toMutableSet()
                if (cleanId.isNotEmpty()) existingIds.add(cleanId)
                val existingTs = prefs.getLong("last_notified_ts", 0L)
                val nextTs = maxOf(existingTs, postTs)
                prefs.edit().apply {
                    putStringSet("notified_post_ids", existingIds.toList().takeLast(120).toSet())
                    if (nextTs > 0L) putLong("last_notified_ts", nextTs)
                }.apply()
            }
        }

        @JavascriptInterface
        fun consumePendingNotificationActionsJson(): String {
            return runCatching {
                val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val readIds = prefs.getStringSet("pending_read_ids", emptySet()).orEmpty().toList()
                val rawReplies = prefs.getString("pending_notif_replies", "[]").orEmpty()
                val repliesArr = runCatching { JSONArray(rawReplies) }.getOrElse { JSONArray() }
                if (readIds.isNotEmpty() || repliesArr.length() > 0) {
                    prefs.edit()
                        .remove("pending_read_ids")
                        .remove("pending_notif_replies")
                        .apply()
                }
                JSONObject().apply {
                    put("readIds", JSONArray(readIds))
                    put("replies", repliesArr)
                }.toString()
            }.getOrElse { """{"readIds":[],"replies":[]}""" }
        }

        @JavascriptInterface
        fun syncFcmToken(token: String, uid: String) {
            val currentMuted = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("push_muted", false)
            syncPushConfig(uid, token, currentMuted)
        }

        @JavascriptInterface
        fun syncPushConfig(uid: String, fcmToken: String, isMuted: Boolean) {
            runCatching {
                val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().apply {
                    if (fcmToken.isNotBlank()) {
                        putString("fcm_token", fcmToken.trim())
                    }
                    if (uid.isNotBlank()) {
                        putString("fcm_uid", uid.trim())
                        putString("current_uid", uid.trim())
                    }
                    putBoolean("push_muted", isMuted)
                    putString("fcm_topic", FCM_CHAT_TOPIC)
                }.apply()
                if (isMuted) {
                    JeewanNotificationScheduler.cancelAll(activity)
                } else {
                    JeewanNotificationScheduler.schedulePeriodic(activity)
                }
            }
            syncNativeFcmSubscription(activity, isMuted) { nativeToken ->
                val escaped = nativeToken
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                activity.webViewRef?.post {
                    activity.webViewRef?.evaluateJavascript(
                        "window.onNativeFcmToken && window.onNativeFcmToken('$escaped');",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun getNotificationStatus(): String {
            return runCatching {
                val notifEnabled = NotificationManagerCompat.from(activity).areNotificationsEnabled()
                val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val ignoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) {
                    pm.isIgnoringBatteryOptimizations(activity.packageName)
                } else {
                    true
                }
                JSONObject().apply {
                    put("notificationsEnabled", notifEnabled)
                    put("ignoringBatteryOptimizations", ignoringBattery)
                    put("androidSdk", Build.VERSION.SDK_INT)
                }.toString()
            }.getOrElse {
                """{"notificationsEnabled":true,"ignoringBatteryOptimizations":true,"androidSdk":0}"""
            }
        }

        @JavascriptInterface
        fun requestSystemNotificationPermission() {
            activity.runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    activity.notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings()
                }
            }
        }

        @JavascriptInterface
        fun openNotificationSettings() {
            activity.runOnUiThread {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", activity.packageName, null)
                    }
                }
                runCatching { activity.startActivity(intent) }.onFailure {
                    runCatching {
                        activity.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", activity.packageName, null)
                            }
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun openBatteryOptimizationSettings() {
            activity.runOnUiThread {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
                runCatching { activity.startActivity(intent) }.onFailure {
                    runCatching {
                        activity.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", activity.packageName, null)
                            }
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun showUploadProgressNotification(
            uploadKey: String,
            title: String,
            body: String,
            progressPct: Int,
            isFinished: Boolean
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            try {
                val notifId = 20000 + (uploadKey.hashCode() and 0x7FFF)
                val clampedPct = progressPct.coerceIn(0, 100)
                val builder = NotificationCompat.Builder(activity, UPLOAD_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setOngoing(!isFinished)
                    .setAutoCancel(isFinished)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                if (!isFinished) {
                    builder.setProgress(100, clampedPct, false)
                } else {
                    builder.setProgress(0, 0, false)
                }
                val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(notifId, builder.build())
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun cancelUploadNotification(uploadKey: String) {
            try {
                val notifId = 20000 + (uploadKey.hashCode() and 0x7FFF)
                val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(notifId)
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
        isActivityInForeground = false
        if (activeWebViewRef?.get() === webViewRef) {
            activeWebViewRef = null
        }
        releaseNativeRecorder()
        super.onDestroy()
    }

    internal data class MediaDestination(
        val publicDir: String,
        val subFolder: String,
        val relativePath: String,
        val albumLabel: String,
        val isGalleryImage: Boolean = false,
        val isGalleryVideo: Boolean = false
    )

    companion object {
        @Volatile
        internal var isActivityInForeground: Boolean = false
        private var activeWebViewRef: WeakReference<WebView>? = null

        internal const val PREFS_NAME = "jeewan_prefs"
        internal const val FCM_CHAT_TOPIC = "jeewan_city_chat"
        internal const val CHANNEL_ID = "jeewan_city_alerts"
        private const val UPLOAD_CHANNEL_ID = "jeewan_city_uploads"
        internal const val ACTION_NOTIF_MARK_READ = "com.example.ACTION_NOTIF_MARK_READ"
        internal const val ACTION_NOTIF_REPLY = "com.example.ACTION_NOTIF_REPLY"
        internal const val EXTRA_POST_ID = "extra_post_id"
        internal const val EXTRA_NOTIF_ID = "extra_notif_id"
        internal const val KEY_TEXT_REPLY = "key_text_reply"

        internal fun dispatchMarkReadToActiveWebView(postId: String): Boolean {
            val wv = activeWebViewRef?.get() ?: return false
            val escapedId = postId.replace("\\", "\\\\").replace("'", "\\'")
            wv.post {
                wv.evaluateJavascript(
                    "window.markPostAsReadFromNotification && window.markPostAsReadFromNotification('$escapedId');",
                    null
                )
            }
            return true
        }

        internal fun dispatchReplyToActiveWebView(postId: String, replyText: String): Boolean {
            val wv = activeWebViewRef?.get() ?: return false
            val escapedId = postId.replace("\\", "\\\\").replace("'", "\\'")
            val escapedReply = replyText
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
            wv.post {
                wv.evaluateJavascript(
                    "window.sendInlineReplyFromNotification && window.sendInlineReplyFromNotification('$escapedId', '$escapedReply');",
                    null
                )
            }
            return true
        }

        internal fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Jeewan City Alerts & Group Chat",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Power shutdown alerts and Jeewan City Sahiwal resident group messages"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                    enableLights(true)
                }
                manager.createNotificationChannel(channel)

                val uploadChannel = NotificationChannel(
                    UPLOAD_CHANNEL_ID,
                    "Media Uploads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Background photo, video, and document upload progress"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(uploadChannel)
            }
        }

        internal fun showInteractiveMessageNotificationCompat(
            context: Context,
            postId: String,
            title: String,
            body: String
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean("push_muted", false) || !prefs.getBoolean("alert_notifs_enabled", true)) {
                return
            }
            createNotificationChannel(context)
            try {
                val safePostId = postId.ifBlank { System.currentTimeMillis().toString() }
                val notifId = 30000 + (safePostId.hashCode() and 0x7FFF)
                val markReadIntent = Intent(context, JeewanNotificationActionReceiver::class.java).apply {
                    action = ACTION_NOTIF_MARK_READ
                    putExtra(EXTRA_POST_ID, safePostId)
                    putExtra(EXTRA_NOTIF_ID, notifId)
                }
                val markReadPi = PendingIntent.getBroadcast(
                    context,
                    notifId + 1,
                    markReadIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
                    .setLabel("Type quick reply...")
                    .build()
                val replyIntent = Intent(context, JeewanNotificationActionReceiver::class.java).apply {
                    action = ACTION_NOTIF_REPLY
                    putExtra(EXTRA_POST_ID, safePostId)
                    putExtra(EXTRA_NOTIF_ID, notifId)
                }
                val replyFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val replyPi = PendingIntent.getBroadcast(
                    context,
                    notifId + 2,
                    replyIntent,
                    replyFlags
                )
                val replyAction = NotificationCompat.Action.Builder(
                    R.drawable.ic_stat_jeewan,
                    "Reply",
                    replyPi
                ).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build()

                val openAppIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_POST_ID, safePostId)
                }
                val contentPi = PendingIntent.getActivity(
                    context,
                    notifId,
                    openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_jeewan)
                    .setColor(0xFF008F39.toInt())
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(contentPi)
                    .setAutoCancel(true)
                    .addAction(R.drawable.ic_stat_jeewan, "Mark as Read", markReadPi)
                    .addAction(replyAction)
                    .build()
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(notifId, notification)
            } catch (_: Exception) {
            }
        }

        internal fun syncNativeFcmSubscription(
            context: Context,
            isMuted: Boolean? = null,
            onNativeToken: ((String) -> Unit)? = null
        ) {
            runCatching {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val muted = isMuted ?: prefs.getBoolean("push_muted", false)
                if (FirebaseApp.getApps(context).isEmpty()) {
                    return
                }
                val messaging = FirebaseMessaging.getInstance()
                if (muted) {
                    messaging.unsubscribeFromTopic(FCM_CHAT_TOPIC)
                } else {
                    messaging.subscribeToTopic(FCM_CHAT_TOPIC)
                }
                @Suppress("DEPRECATION")
                messaging.token.addOnSuccessListener { token ->
                    val clean = token?.trim().orEmpty()
                    if (clean.isNotEmpty()) {
                        prefs.edit()
                            .putString("native_fcm_token", clean)
                            .putString("fcm_token", clean)
                            .apply()
                        onNativeToken?.invoke(clean)
                    }
                }
            }
        }

        internal fun isOAuthFlowUrl(url: String): Boolean {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            val host = uri.host.orEmpty().lowercase()
            val path = uri.path.orEmpty().lowercase()
            val lower = url.lowercase()

            if (path.startsWith("/__/auth/") || lower.contains("/__/auth/")) return true
            if (host == "accounts.google.com" || host == "apis.google.com") return true
            if (host.endsWith(".firebaseapp.com") && host != "jeewan-city.firebaseapp.com") return true

            val isFacebookHost = host == "facebook.com" || host.endsWith(".facebook.com")
            if (isFacebookHost) {
                return path.startsWith("/login") ||
                    path.contains("/dialog/oauth") ||
                    path.startsWith("/checkpoint") ||
                    path.startsWith("/cookie/consent") ||
                    path.startsWith("/privacy/consent") ||
                    path.startsWith("/two_step_verification") ||
                    path.startsWith("/recover") ||
                    path.startsWith("/device-based/login") ||
                    path.startsWith("/oauth")
            }
            return false
        }

        internal fun normalizeExternalUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return ""
            val lower = trimmed.lowercase()
            if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("vbscript:")) {
                return ""
            }
            if (lower.startsWith("www.")) {
                return "https://$trimmed"
            }
            return if (
                lower.startsWith("https://") ||
                lower.startsWith("http://") ||
                lower.startsWith("tel:") ||
                lower.startsWith("mailto:") ||
                lower.startsWith("whatsapp:")
            ) {
                trimmed
            } else {
                ""
            }
        }

        internal fun sanitizeMediaFileName(rawName: String): String {
            val cleaned = rawName.trim()
                .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
                .trim('.')
            return cleaned.ifEmpty { "JeewanCity_Media_${System.currentTimeMillis()}" }
        }

        internal fun sanitizeSubFolder(rawSubFolder: String): String {
            return rawSubFolder.split('/', '\\')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .joinToString("/")
        }

        internal fun resolveMediaDestination(
            mimeType: String,
            fileName: String,
            explicitSubFolder: String = ""
        ): MediaDestination {
            val mime = mimeType.trim().lowercase()
            val lowerName = fileName.trim().lowercase()
            val ext = lowerName.substringAfterLast('.', "")
            val cleanExplicit = sanitizeSubFolder(explicitSubFolder)

            val isImage = mime.startsWith("image/") ||
                ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp")
            if (isImage) {
                val sub = cleanExplicit.ifEmpty { "Jeewan City Sahiwal/Jeewan City Images" }
                return MediaDestination(
                    publicDir = Environment.DIRECTORY_PICTURES,
                    subFolder = sub,
                    relativePath = "${Environment.DIRECTORY_PICTURES}/$sub",
                    albumLabel = "Jeewan City Images",
                    isGalleryImage = true
                )
            }

            val isAudio = mime.startsWith("audio/") ||
                ext in setOf("m4a", "mp3", "ogg", "aac", "wav", "opus") ||
                (ext == "webm" && (lowerName.contains("voice") || lowerName.contains("audio")))
            if (isAudio) {
                val sub = cleanExplicit.ifEmpty { "Jeewan City Sahiwal/Voice Notes" }
                return MediaDestination(
                    publicDir = Environment.DIRECTORY_DOWNLOADS,
                    subFolder = sub,
                    relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$sub",
                    albumLabel = "Jeewan City Sahiwal/Voice Notes"
                )
            }

            val isVideo = mime.startsWith("video/") ||
                ext in setOf("mp4", "mov", "mkv", "avi", "3gp", "m4v", "webm")
            if (isVideo) {
                val sub = cleanExplicit.ifEmpty { "Jeewan City Sahiwal/Jeewan City Videos" }
                return MediaDestination(
                    publicDir = Environment.DIRECTORY_MOVIES,
                    subFolder = sub,
                    relativePath = "${Environment.DIRECTORY_MOVIES}/$sub",
                    albumLabel = "Jeewan City Videos",
                    isGalleryVideo = true
                )
            }

            val sub = cleanExplicit.ifEmpty { "Jeewan City Sahiwal/Documents" }
            return MediaDestination(
                publicDir = Environment.DIRECTORY_DOWNLOADS,
                subFolder = sub,
                relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$sub",
                albumLabel = "Jeewan City Sahiwal/Documents"
            )
        }
    }
}
