package app.larpcord

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "Larpcord"
        private const val HOME = "https://discord.com/app"
        private const val ORIGIN = "https://discord.com"
        private const val CHANNEL = "messages"
        private const val EXTRA_TAG = "larpcord.notificationTag"
    }

    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private var replyProxy: JavaScriptReplyProxy? = null
    private var injectedAtDocumentStart = false
    private lateinit var script: String
    private val debuggable by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var pendingNotifyAnswer: ((String) -> Unit)? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    private val askMediaPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingPermission?.let { grantWhatWeCan(it) }
        pendingPermission = null
    }

    private val askNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingNotifyAnswer?.invoke(notificationState())
        pendingNotifyAnswer = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.app_bg)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        createNotificationChannel()
        script = buildScript()
        createWebView(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = web ?: return finish()
                // Let the page close an open drawer or popup first
                view.evaluateJavascript("(window.__larpcordBack && window.__larpcordBack()) ? 1 : 0") { handled ->
                    if (handled == "1") return@evaluateJavascript
                    if (view.canGoBack()) view.goBack() else moveTaskToBack(true)
                }
            }
        })

        handleIntent(intent)
    }

    /** Prelude + userscript, wrapped so Larpcord's variables stay out of Discord's globals. */
    private fun buildScript(): String {
        val prelude = assets.open("prelude.js").bufferedReader().use { it.readText() }
            .replace("__APP_VERSION__", packageManager.getPackageInfo(packageName, 0).versionName ?: "")
        val userscript = assets.open("larpcord.user.js").bufferedReader().use { it.readText() }
        return "(function () {\n\"use strict\";\n" +
            "if (window.top !== window || window.__larpcordLoaded) return;\n" +
            "window.__larpcordLoaded = true;\n" +
            "try {\n" + prelude + "\n" + userscript + "\n} catch (e) {\n" +
            "console.error(\"[LarpcordAndroid] startup error: \" + (e && e.stack || e));\n}\n})();\n"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(savedInstanceState: Bundle?) {
        WebView.setWebContentsDebuggingEnabled(debuggable)

        val view = WebView(this)
        web = view
        view.setBackgroundColor(getColor(R.color.app_bg))
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // captcha widgets run in third-party iframes
            setAcceptThirdPartyCookies(view, true)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, "LarpcordNative", setOf(ORIGIN)) { _, message, _, isMainFrame, proxy ->
                if (isMainFrame) onBridgeMessage(message.data ?: return@addWebMessageListener, proxy)
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, script, setOf(ORIGIN))
            injectedAtDocumentStart = true
        } else {
            Log.w(TAG, "WebView too old for document-start scripts; injecting on page start instead")
        }

        view.webViewClient = client
        view.webChromeClient = chrome
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }

        if (savedInstanceState == null || view.restoreState(savedInstanceState) == null) {
            view.loadUrl(HOME)
        }
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url
            val host = uri.host?.lowercase() ?: return true
            if (uri.scheme == "https" && (host == "discord.com" || host.endsWith(".discord.com"))) return false
            // Invite links open inside the app
            if (host == "discord.gg" && !uri.path.isNullOrBlank()) {
                view.loadUrl("https://discord.com/invite" + uri.path)
                return true
            }
            openOutside(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (!injectedAtDocumentStart && url?.startsWith(ORIGIN) == true) {
                view.evaluateJavascript(script, null)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's renderer died (usually memory pressure). Start over instead of crashing.
            Log.w(TAG, "WebView renderer gone, crashed=${detail.didCrash()}")
            root.removeView(view)
            view.destroy()
            web = null
            replyProxy = null
            createWebView(null)
            return true
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                pickFiles.launch(params.createIntent().apply {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                })
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            if (request.origin.toString().trimEnd('/') != ORIGIN) return request.deny()
            val needed = request.resources.mapNotNull {
                when (it) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                    else -> null
                }
            }.filter { !granted(it) }
            if (needed.isEmpty()) return grantWhatWeCan(request)
            pendingPermission?.deny()
            pendingPermission = request
            askMediaPermissions.launch(needed.toTypedArray())
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingPermission == request) pendingPermission = null
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val text = message.message()
            if (text.startsWith("[LarpcordAndroid]")) {
                Log.i(TAG, text)
            } else if (debuggable && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                Log.w(TAG, "console: ${text.take(500)} @ ${message.sourceId()}:${message.lineNumber()}")
            }
            return false
        }
    }

    private fun grantWhatWeCan(request: PermissionRequest) {
        val allowed = request.resources.filter {
            when (it) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> granted(Manifest.permission.RECORD_AUDIO)
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> granted(Manifest.permission.CAMERA)
                else -> false
            }
        }
        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ---- messages from prelude.js ----

    private fun onBridgeMessage(data: String, proxy: JavaScriptReplyProxy) {
        replyProxy = proxy
        val msg = try { JSONObject(data) } catch (e: Exception) { return }
        when (msg.optString("type")) {
            "fetch" -> NativeFetch.run(msg) { result -> runOnUiThread { proxy.postMessage(result.toString()) } }
            "notify" -> showNotification(msg.optString("title"), msg.optString("body"), msg.optString("tag"))
            "notifyClose" -> NotificationManagerCompat.from(this).cancel(msg.optString("tag"), 0)
            "notifyPermission" -> {
                val answer: (String) -> Unit = { state ->
                    proxy.postMessage(JSONObject().put("id", msg.optInt("id")).put("state", state).toString())
                }
                if (msg.optBoolean("ask") && Build.VERSION.SDK_INT >= 33 && notificationState() == "default") {
                    pendingNotifyAnswer = answer
                    askNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    answer(notificationState())
                }
            }
        }
    }

    private fun notificationState(): String = when {
        !NotificationManagerCompat.from(this).areNotificationsEnabled() ->
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) "default" else "denied"
        else -> "granted"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL, getString(R.string.notif_channel_messages), NotificationManager.IMPORTANCE_HIGH)
        channel.description = getString(R.string.notif_channel_messages_desc)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission") // areNotificationsEnabled() is checked right before
    private fun showNotification(title: String, body: String, tag: String) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TAG, tag)
        val pending = PendingIntent.getActivity(
            this, tag.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        manager.notify(tag, 0, notification)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val tag = intent?.getStringExtra(EXTRA_TAG) ?: return
        intent.removeExtra(EXTRA_TAG)
        replyProxy?.postMessage(JSONObject().put("type", "notificationClick").put("tag", tag).toString())
    }

    // ---- links and downloads ----

    private fun openOutside(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun download(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return openOutside(uri)
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(uri)
            .setMimeType(mimeType)
            .addRequestHeader("User-Agent", userAgent)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
    }

    // ---- lifecycle ----

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
