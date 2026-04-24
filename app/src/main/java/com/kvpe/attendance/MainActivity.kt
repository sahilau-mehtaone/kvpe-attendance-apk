package com.kvpe.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var offlineView: View
    private lateinit var retryButton: Button
    private lateinit var offlineMsg: TextView
    private lateinit var updateBanner: LinearLayout
    private lateinit var updateBtn: Button
    private lateinit var dismissBtn: Button

    private var pendingPermissionRequest: PermissionRequest? = null

    // FIX (v2): Store geo callback so it's invoked AFTER user responds, not before
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null

    private var downloadId: Long = -1L
    private var latestApkUrl: String? = null
    private var lastBackPress: Long = 0L
    private var receiverRegistered = false

    private val scope = MainScope()

    companion object {
        const val APP_URL      = "https://projects.kvpe.co.in/app-login"
        const val BASE_HOST    = "projects.kvpe.co.in"
        const val VERSION_URL  = "https://projects.kvpe.co.in/api/app-version"
        const val APK_FILENAME = "kvpe-attendance-update.apk"
    }

    // Handles both camera and location permission results
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Camera → forward result to pending WebView permission request
        val req = pendingPermissionRequest
        if (req != null) {
            val cameraGranted = grants[Manifest.permission.CAMERA] == true
            if (cameraGranted) {
                req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            } else {
                req.deny()
            }
            pendingPermissionRequest = null
        }

        // Location → forward result to pending geolocation callback (FIX: was always denied in v1)
        val geoCallback = pendingGeoCallback
        val geoOrigin   = pendingGeoOrigin
        if (geoCallback != null && geoOrigin != null) {
            val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                                  grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            geoCallback.invoke(geoOrigin, locationGranted, false)
            pendingGeoCallback = null
            pendingGeoOrigin   = null
        }
    }

    // Triggered when DownloadManager finishes downloading the APK
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId) installApk()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView      = findViewById(R.id.webview)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        offlineView  = findViewById(R.id.offlineView)
        retryButton  = findViewById(R.id.retryButton)
        offlineMsg   = findViewById(R.id.offlineMsg)
        updateBanner = findViewById(R.id.updateBanner)
        updateBtn    = findViewById(R.id.updateBtn)
        dismissBtn   = findViewById(R.id.dismissBtn)

        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()

        retryButton.setOnClickListener { reloadOrLoad() }
        updateBtn.setOnClickListener   { startDownload() }
        dismissBtn.setOnClickListener  { updateBanner.visibility = View.GONE }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            reloadOrLoad()
        }

        // Check for updates 3 seconds after launch — non-blocking
        scope.launch {
            delay(3_000)
            checkForUpdate()
        }
    }

    // ─── In-app update ────────────────────────────────────────────────────────

    private suspend fun checkForUpdate() {
        try {
            val json = withContext(Dispatchers.IO) {
                val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout    = 5_000
                conn.requestMethod  = "GET"
                conn.connect()
                if (conn.responseCode == 200)
                    JSONObject(conn.inputStream.bufferedReader().readText())
                else null
            } ?: return

            val serverVersionCode = json.getInt("version_code")
            latestApkUrl = json.optString("apk_url").takeIf { it.isNotBlank() }

            if (serverVersionCode > BuildConfig.VERSION_CODE) {
                withContext(Dispatchers.Main) {
                    updateBanner.visibility = View.VISIBLE
                }
            }
        } catch (_: Exception) {
            // Silent — update check is non-critical
        }
    }

    private fun startDownload() {
        val apkUrl = latestApkUrl
        if (apkUrl.isNullOrBlank()) {
            Toast.makeText(this, "Update URL not available. Try again later.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isOnline()) {
            Toast.makeText(this, "No internet connection.", Toast.LENGTH_SHORT).show()
            return
        }

        // Delete any previous download
        val prev = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILENAME)
        if (prev.exists()) prev.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("KVPE Attendance Update")
            setDescription("Downloading v${BuildConfig.VERSION_NAME} update…")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                this@MainActivity, Environment.DIRECTORY_DOWNLOADS, APK_FILENAME
            )
            setMimeType("application/vnd.android.package-archive")
        }

        val dm = getSystemService(DownloadManager::class.java)
        downloadId = dm.enqueue(request)

        updateBtn.isEnabled = false
        updateBtn.text      = "Downloading…"
        Toast.makeText(this, "Downloading update. You'll be prompted to install when ready.", Toast.LENGTH_LONG).show()
    }

    private fun installApk() {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILENAME)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            this, "${BuildConfig.APPLICATION_ID}.provider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ─── WebView setup ────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled               = true
            domStorageEnabled               = true
            databaseEnabled                 = true
            // LOAD_NO_CACHE — always fetch HTML/JS fresh from the network.
            // Fixes the "APK stuck on old version" issue where WebView's
            // HTTP cache served stale /app-login before the service worker
            // could intercept. SW still handles offline queueing.
            cacheMode                       = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort                 = true
            loadWithOverviewMode            = true
            setSupportZoom(false)
            builtInZoomControls             = false
            displayZoomControls             = false
            allowFileAccess                 = false
            allowContentAccess              = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false) // FIX (v2): was true — unnecessary for same-domain app
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                swipeRefresh.isRefreshing = false
                showWebView()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    swipeRefresh.isRefreshing = false
                    showOffline("Could not load page.\n${error.description}")
                }
            }

            // Keep navigation inside the app host; open external links in browser
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                return if (host.endsWith(BASE_HOST)) {
                    false
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val needed = mutableListOf<String>()
                val grant  = mutableListOf<String>()

                for (res in request.resources) {
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                            if (hasPermission(Manifest.permission.CAMERA))
                                grant.add(res)
                            else
                                needed.add(Manifest.permission.CAMERA)
                        }
                        else -> grant.add(res)
                    }
                }

                if (needed.isNotEmpty()) {
                    pendingPermissionRequest = request
                    permissionLauncher.launch(needed.toTypedArray())
                } else {
                    request.grant(grant.toTypedArray())
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                val fineGranted   = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                val coarseGranted = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

                if (fineGranted || coarseGranted) {
                    callback.invoke(origin, true, false)
                } else {
                    // FIX (v2): Store callback — invoke it AFTER user responds to the permission prompt.
                    // v1 called callback(false) here immediately, making GPS always fail on first try.
                    pendingGeoCallback = callback
                    pendingGeoOrigin   = origin
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }
    }

    // ─── UI helpers ──────────────────────────────────────────────────────────

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.brand_blue)
        swipeRefresh.setOnRefreshListener { reloadOrLoad() }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPress < 2_000) {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        } else {
                            lastBackPress = now
                            Toast.makeText(
                                this@MainActivity,
                                "Press back again to exit",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })
    }

    private fun reloadOrLoad() {
        if (!isOnline()) {
            showOffline("No internet connection.\nPlease check your Wi-Fi or mobile data.")
            return
        }
        showWebView()
        val current = webView.url
        if (current.isNullOrEmpty() || current == "about:blank") {
            webView.loadUrl(APP_URL)
        } else {
            webView.reload()
        }
        swipeRefresh.isRefreshing = true
    }

    private fun showWebView() {
        webView.visibility    = View.VISIBLE
        offlineView.visibility = View.GONE
    }

    private fun showOffline(msg: String) {
        offlineMsg.text        = msg
        webView.visibility     = View.GONE
        offlineView.visibility = View.VISIBLE
    }

    // FIX (v2): Added NET_CAPABILITY_VALIDATED — v1 only checked NET_CAPABILITY_INTERNET
    // which is self-reported by the network and can be true even when there's no real connectivity.
    private fun isOnline(): Boolean {
        val cm   = getSystemService(ConnectivityManager::class.java) ?: return false
        val net  = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        if (receiverRegistered) {
            unregisterReceiver(downloadReceiver)
            receiverRegistered = false
        }
    }

    // FIX (v2): Free WebView memory when system is under pressure
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            webView.freeMemory()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        webView.destroy()
        super.onDestroy()
    }
}
