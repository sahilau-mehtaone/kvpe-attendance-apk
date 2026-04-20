package `in`.co.kvpe.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var offlineView: View
    private lateinit var retryButton: Button
    private lateinit var offlineMsg: TextView

    private var pendingPermissionRequest: PermissionRequest? = null

    companion object {
        const val APP_URL   = "https://projects.kvpe.co.in/app-login"
        const val BASE_HOST = "projects.kvpe.co.in"
    }

    // Runtime permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val req = pendingPermissionRequest ?: return@registerForActivityResult
        val granted = grants.entries
            .filter { it.value }
            .map { permToAndroidResource(it.key) }
            .toTypedArray()
        if (granted.isNotEmpty()) req.grant(granted) else req.deny()
        pendingPermissionRequest = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView       = findViewById(R.id.webview)
        swipeRefresh  = findViewById(R.id.swipeRefresh)
        offlineView   = findViewById(R.id.offlineView)
        retryButton   = findViewById(R.id.retryButton)
        offlineMsg    = findViewById(R.id.offlineMsg)

        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()

        retryButton.setOnClickListener { reloadOrLoad() }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            reloadOrLoad()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled        = true
            domStorageEnabled        = true
            databaseEnabled          = true
            cacheMode                = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false    // allow camera stream
            useWideViewPort          = true
            loadWithOverviewMode     = true
            setSupportZoom(false)
            builtInZoomControls      = false
            displayZoomControls      = false
            allowFileAccess          = false
            allowContentAccess       = false
        }

        // Enable cookies (needed for session after login)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
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
                    showOffline("No connection.\n${error.description}")
                }
            }

            // Keep navigation inside the app host; open external links in browser
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                return if (host.endsWith(BASE_HOST)) {
                    false   // navigate inside WebView
                } else {
                    // Open in external browser
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW, request.url
                    )
                    startActivity(intent)
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Camera + Location permissions forwarded from WebView to Android
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
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                            grant.add(res)  // not needed; grant silently
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
                val fineGranted  = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                val coarseGranted = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (fineGranted || coarseGranted) {
                    callback.invoke(origin, true, false)
                } else {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                    callback.invoke(origin, false, false)
                }
            }
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.brand_blue)
        swipeRefresh.setOnRefreshListener { reloadOrLoad() }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
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
        webView.visibility   = View.VISIBLE
        offlineView.visibility = View.GONE
    }

    private fun showOffline(msg: String) {
        offlineMsg.text        = msg
        webView.visibility     = View.GONE
        offlineView.visibility = View.VISIBLE
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun permToAndroidResource(androidPerm: String) = when (androidPerm) {
        Manifest.permission.CAMERA -> PermissionRequest.RESOURCE_VIDEO_CAPTURE
        else -> androidPerm
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }
}
