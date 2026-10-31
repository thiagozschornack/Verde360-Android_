package br.com.empreenderthz.verde360

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * VERDE360 Ambiental: navegador dedicado à plataforma web, com navegação nativa,
 * tratamento de falhas de rede, envio e gravação de arquivos e abertura segura de links externos.
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: View
    private lateinit var topBar: View
    private lateinit var contentFrame: View
    private lateinit var bottomBar: View
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var errorPanel: View
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var navBack: TextView
    private lateinit var fileSaver: FileSaver

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val startedAt = SystemClock.elapsedRealtime()
    private var firstPageShown = false
    private var mainFrameFailed = false
    private var webDestroyed = false
    private var bridgeReady = false
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private enum class Status { LOADING, ONLINE, OFFLINE, ERROR }

    /* ─────────────── Seletor de arquivos (input type="file") ─────────────── */
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileCallback
            pendingFileCallback = null
            if (callback == null) return@registerForActivityResult
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }
            val uris = mutableListOf<Uri>()
            val clip: ClipData? = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            } else {
                data.data?.let { uris.add(it) }
            }
            callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        }

    /* ─────────────── Voltar (compatível com o gesto preditivo do Android 13+) ─────────────── */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!webDestroyed && web.canGoBack()) {
                web.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        splash.setKeepOnScreenCondition {
            !firstPageShown && SystemClock.elapsedRealtime() - startedAt < SPLASH_MAX_MS
        }

        bindViews()
        applyWindowInsets()
        fileSaver = FileSaver(applicationContext)
        setupWebView()
        setupNavigation()
        onBackPressedDispatcher.addCallback(this, backCallback)
        registerNetworkCallback()

        val restored = savedInstanceState?.let { web.restoreState(it) } != null
        if (!restored) openFromIntent(intent, fallbackToHome = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent, fallbackToHome = false)
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        topBar = findViewById(R.id.topBar)
        contentFrame = findViewById<View>(R.id.web).parent as View
        bottomBar = findViewById(R.id.bottomBar)
        web = findViewById(R.id.web)
        progress = findViewById(R.id.progress)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        errorPanel = findViewById(R.id.errorPanel)
        errorTitle = findViewById(R.id.errorTitle)
        errorMessage = findViewById(R.id.errorMessage)
        navBack = findViewById(R.id.navBack)
    }

    /** Desenho de borda a borda (obrigatório ao mirar o Android 15+): cada barra recebe o recuo do sistema. */
    private fun applyWindowInsets() {
        val topLeft = topBar.paddingLeft
        val topTop = topBar.paddingTop
        val topRight = topBar.paddingRight
        val bottomLeft = bottomBar.paddingLeft
        val bottomRight = bottomBar.paddingRight
        val bottomBottom = bottomBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            topBar.updatePadding(left = topLeft + bars.left, top = topTop + bars.top, right = topRight + bars.right)
            contentFrame.updatePadding(left = bars.left, right = bars.right)
            bottomBar.visibility = if (imeVisible) View.GONE else View.VISIBLE
            bottomBar.updatePadding(
                left = bottomLeft + bars.left,
                right = bottomRight + bars.right,
                bottom = bottomBottom + bars.bottom
            )
            // Com o teclado aberto, a página termina acima dele (formulário de relato, busca etc.)
            root.updatePadding(bottom = if (imeVisible) ime.bottom else 0)
            WindowInsetsCompat.CONSUMED
        }
    }

    /* ─────────────── WebView ─────────────── */

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
            setGeolocationEnabled(false)
            userAgentString = "$userAgentString Verde360Android/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, false)
        }
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.v360_bg))
        installBridge()
        web.webViewClient = client
        web.webChromeClient = chrome
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    /**
     * Canal de mensagens restrito às origens do VERDE360. Usado para salvar arquivos que a página
     * gera em memória (blob:), que o WebView não baixa sozinho.
     */
    private fun installBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(web, BRIDGE_NAME, ALLOWED_ORIGINS) { _, message, _, isMainFrame, _ ->
            if (isMainFrame) handleBridgeMessage(message.data)
        }
        bridgeReady = true
    }

    private fun handleBridgeMessage(raw: String?) {
        if (raw == null) return
        val json = try { JSONObject(raw) } catch (e: Exception) { return }
        when (json.optString("type")) {
            "download" -> {
                val dataUrl = json.optString("data")
                val name = json.optString("name")
                val mime = json.optString("mime")
                io.execute {
                    val result = fileSaver.saveDataUrl(dataUrl, name, mime)
                    runOnUiThread { onFileSaved(result) }
                }
            }
            "too_large" -> toast(R.string.file_too_large)
            "error" -> toast(R.string.file_error)
        }
    }

    private val client = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (isInternal(uri)) return false
            if (!request.isForMainFrame) return false
            openExternal(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            mainFrameFailed = false
            setStatus(Status.LOADING)
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            firstPageShown = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            firstPageShown = true
            progress.visibility = View.INVISIBLE
            if (bridgeReady && url != null && isInternal(Uri.parse(url))) {
                view.evaluateJavascript(DOWNLOAD_PATCH_JS, null)
            }
            if (!mainFrameFailed) {
                hideError()
                setStatus(if (isOnline()) Status.ONLINE else Status.OFFLINE)
            }
            updateBackState()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            updateBackState()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                mainFrameFailed = true
                showError(offline = !isOnline())
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                mainFrameFailed = true
                showError(offline = false)
            }
        }

        // Se o processo de renderização do WebView cair, recria a tela em vez de fechar o app.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            webDestroyed = true
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            recreate()
            return true
        }
    }

    private val chrome = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.setProgress(newProgress, true)
            progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback
            return try {
                fileChooserLauncher.launch(buildChooserIntent(fileChooserParams))
                true
            } catch (e: ActivityNotFoundException) {
                pendingFileCallback = null
                toast(R.string.chooser_error)
                false
            }
        }
    }

    /** Converte o atributo accept em tipos MIME; extensões sem MIME conhecido (ex.: .geojson) liberam todos os arquivos. */
    private fun buildChooserIntent(params: WebChromeClient.FileChooserParams): Intent {
        val mimes = mutableSetOf<String>()
        var unknown = false
        params.acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .forEach { type ->
                when {
                    type.contains('/') -> mimes.add(type)
                    type.startsWith('.') -> {
                        val m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.drop(1))
                        if (m != null) mimes.add(m) else unknown = true
                    }
                }
            }
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (!unknown && mimes.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
    }

    /* ─────────────── Downloads ─────────────── */

    private fun handleDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            // Arquivos em memória são entregues pelo canal de mensagens (DOWNLOAD_PATCH_JS).
            if (!bridgeReady) toast(R.string.file_unsupported)
            return
        }
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(uri).apply {
                setMimeType(mimeType)
                if (isInternal(uri)) CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                if (userAgent != null) addRequestHeader("User-Agent", userAgent)
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                } else {
                    setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, name)
                }
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast(R.string.download_started)
        } catch (e: Exception) {
            openExternal(uri)
        }
    }

    private fun onFileSaved(result: FileSaver.Result) {
        if (isFinishing || isDestroyed) return
        when (result) {
            is FileSaver.Result.Saved -> {
                val msgRes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) R.string.file_saved_msg else R.string.file_saved_msg_app
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(R.string.file_saved_title)
                    .setMessage(getString(msgRes, result.name))
                    .setPositiveButton(R.string.file_share) { _, _ -> shareFile(result.uri, result.mime) }
                    .setNegativeButton(R.string.close, null)
                    .show()
            }
            is FileSaver.Result.TooLarge -> toast(R.string.file_too_large)
            is FileSaver.Result.Failed -> toast(R.string.file_error)
        }
    }

    private fun shareFile(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startSafely(Intent.createChooser(send, getString(R.string.file_share_chooser)))
    }

    /* ─────────────── Navegação nativa ─────────────── */

    private fun setupNavigation() {
        findViewById<View>(R.id.navHome).setOnClickListener {
            hideError()
            web.loadUrl(BuildConfig.HOME_URL)
        }
        navBack.setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<View>(R.id.navReload).setOnClickListener { retry() }
        findViewById<View>(R.id.navShare).setOnClickListener { sharePage() }
        findViewById<View>(R.id.navMore).setOnClickListener { showMoreMenu(it) }
        findViewById<View>(R.id.retryButton).setOnClickListener { retry() }
        updateBackState()
    }

    private fun updateBackState() {
        if (webDestroyed) return
        val canGoBack = web.canGoBack()
        backCallback.isEnabled = canGoBack
        navBack.isEnabled = canGoBack
        navBack.alpha = if (canGoBack) 1f else 0.4f
    }

    private fun retry() {
        hideError()
        setStatus(Status.LOADING)
        val current = web.url
        if (current.isNullOrEmpty() || current == "about:blank") web.loadUrl(BuildConfig.HOME_URL) else web.reload()
    }

    private fun sharePage() {
        val current = web.url?.takeIf { isInternal(Uri.parse(it)) } ?: BuildConfig.HOME_URL
        val title = web.title?.takeIf { it.isNotBlank() } ?: getString(R.string.share_subject)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n$current")
        }
        startSafely(Intent.createChooser(send, getString(R.string.share_chooser)))
    }

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.more, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_ibama -> openExternal(Uri.parse(URL_IBAMA))
                    R.id.menu_falabr -> openExternal(Uri.parse(URL_FALABR))
                    R.id.menu_linha_verde -> startSafely(Intent(Intent.ACTION_DIAL, Uri.parse(TEL_LINHA_VERDE)))
                    R.id.menu_browser -> startSafely(
                        Intent(Intent.ACTION_VIEW, Uri.parse(web.url ?: BuildConfig.HOME_URL))
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                    )
                    R.id.menu_privacy -> openExternal(Uri.parse(BuildConfig.PRIVACY_URL))
                    R.id.menu_about -> showAbout()
                }
                true
            }
            show()
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_body, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.menu_privacy) { _, _ -> openExternal(Uri.parse(BuildConfig.PRIVACY_URL)) }
            .show()
    }

    private fun openFromIntent(intent: Intent?, fallbackToHome: Boolean) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data != null && isInternal(data)) {
            hideError()
            web.loadUrl(data.toString())
        } else if (fallbackToHome) {
            web.loadUrl(BuildConfig.HOME_URL)
        }
    }

    /* ─────────────── Links externos ─────────────── */

    private fun isInternal(uri: Uri): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            (uri.host?.lowercase()?.let { it in ALLOWED_HOSTS } == true) &&
            (uri.path ?: "").startsWith(BASE_PATH)

    private fun openExternal(uri: Uri) {
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val color = ContextCompat.getColor(this, R.color.v360_panel)
                val tab = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setDefaultColorSchemeParams(
                        CustomTabColorSchemeParams.Builder()
                            .setToolbarColor(color)
                            .setNavigationBarColor(color)
                            .build()
                    )
                    .build()
                try {
                    tab.launchUrl(this, uri)
                } catch (e: ActivityNotFoundException) {
                    startSafely(Intent(Intent.ACTION_VIEW, uri))
                }
            }
            "tel" -> startSafely(Intent(Intent.ACTION_DIAL, uri))
            "mailto" -> startSafely(Intent(Intent.ACTION_SENDTO, uri))
            "intent" -> openIntentScheme(uri)
            else -> startSafely(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    /** Links "intent:" vindos da web: só abre como navegação comum, sem componente explícito. */
    private fun openIntentScheme(uri: Uri) {
        try {
            val parsed = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
            try {
                startActivity(parsed)
            } catch (e: ActivityNotFoundException) {
                val fallback = parsed.getStringExtra("browser_fallback_url")
                if (fallback != null && fallback.startsWith("https://")) openExternal(Uri.parse(fallback)) else toast(R.string.link_error)
            }
        } catch (e: Exception) {
            toast(R.string.link_error)
        }
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.link_error)
        } catch (e: SecurityException) {
            toast(R.string.link_error)
        }
    }

    /* ─────────────── Conectividade e estado ─────────────── */

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { if (mainFrameFailed && !webDestroyed) retry() }
            }

            override fun onLost(network: Network) {
                runOnUiThread { if (!webDestroyed && !isOnline()) setStatus(Status.OFFLINE) }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            networkCallback = null
        }
    }

    private fun setStatus(status: Status) {
        val (textRes, colorRes) = when (status) {
            Status.LOADING -> R.string.status_loading to R.color.v360_amber
            Status.ONLINE -> R.string.status_online to R.color.v360_mint
            Status.OFFLINE -> R.string.status_offline to R.color.v360_red
            Status.ERROR -> R.string.status_error to R.color.v360_red
        }
        statusText.setText(textRes)
        statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun showError(offline: Boolean) {
        firstPageShown = true
        progress.visibility = View.INVISIBLE
        errorTitle.setText(if (offline) R.string.error_offline_title else R.string.error_server_title)
        errorMessage.setText(if (offline) R.string.error_offline_msg else R.string.error_server_msg)
        errorPanel.visibility = View.VISIBLE
        setStatus(if (offline) Status.OFFLINE else Status.ERROR)
    }

    private fun hideError() {
        errorPanel.visibility = View.GONE
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_LONG).show()
    }

    /* ─────────────── Ciclo de vida ─────────────── */

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webDestroyed) web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!webDestroyed) web.onResume()
    }

    override fun onPause() {
        if (!webDestroyed) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        networkCallback?.let { cb ->
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                // já removido
            }
        }
        networkCallback = null
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        io.shutdown()
        if (!webDestroyed) {
            webDestroyed = true
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_MAX_MS = 2500L
        private const val BASE_PATH = "/Ambiental"
        private const val BRIDGE_NAME = "verde360Bridge"
        private const val URL_IBAMA = "https://www.gov.br/pt-br/servicos/apresentar-denuncia-ambiental"
        private const val URL_FALABR = "https://falabr.cgu.gov.br/"
        private const val TEL_LINHA_VERDE = "tel:08000618080"
        private val ALLOWED_HOSTS = setOf("www.empreenderthz.com.br", "empreenderthz.com.br")
        private val ALLOWED_ORIGINS = setOf("https://www.empreenderthz.com.br", "https://empreenderthz.com.br")

        /**
         * Intercepta <a download> com href blob:/data: (inclusive os criados via JS e clicados fora do DOM),
         * lê o conteúdo e envia ao app pelo canal verde360Bridge. Não altera a página quando o canal não existe.
         */
        private val DOWNLOAD_PATCH_JS = """
            (function () {
              if (window.__v360DownloadPatch || !window.verde360Bridge) return;
              window.__v360DownloadPatch = true;
              var MAX = ${FileSaver.MAX_BYTES};
              function post(o) { try { window.verde360Bridge.postMessage(JSON.stringify(o)); } catch (e) {} }
              function send(href, name) {
                fetch(href).then(function (r) { return r.blob(); }).then(function (b) {
                  if (b.size > MAX) { post({ type: 'too_large' }); return; }
                  var fr = new FileReader();
                  fr.onload = function () { post({ type: 'download', name: name || 'verde360-arquivo', mime: b.type || '', data: fr.result }); };
                  fr.onerror = function () { post({ type: 'error' }); };
                  fr.readAsDataURL(b);
                }).catch(function () { post({ type: 'error' }); });
              }
              function intercept(a) {
                var h = a.getAttribute('href') || '';
                if (!a.hasAttribute('download')) return false;
                if (h.indexOf('blob:') !== 0 && h.indexOf('data:') !== 0) return false;
                send(a.href, a.getAttribute('download'));
                return true;
              }
              var nativeClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function () {
                if (intercept(this)) return;
                return nativeClick.apply(this, arguments);
              };
              document.addEventListener('click', function (e) {
                var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
                if (a && intercept(a)) { e.preventDefault(); e.stopPropagation(); }
              }, true);
            })();
        """.trimIndent()
    }
}
