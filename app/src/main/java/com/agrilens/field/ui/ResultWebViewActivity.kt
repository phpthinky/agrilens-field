package com.agrilens.field.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.agrilens.field.R
import com.agrilens.field.databinding.ActivityResultWebviewBinding
import com.agrilens.field.network.ApiConfig

/**
 * Shows the server's own sample result page inside the app.
 *
 * Sandboxed to the Agrilens web host: no file/content access, and any link to
 * another host is handed to the phone's browser instead of loading here. The
 * session comes from the single-use sign-in link issued by the API, kept in
 * the WebView's own cookie jar.
 */
class ResultWebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultWebviewBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null || !isAllowed(Uri.parse(url))) {
            Toast.makeText(this, R.string.result_open_link_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        intent.getStringExtra(EXTRA_TITLE)?.let { binding.toolbar.subtitle = it }

        CookieManager.getInstance().setAcceptCookie(true)

        binding.webView.settings.apply {
            javaScriptEnabled = true // the result page's charts and Bootstrap UI need it
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isAllowed(request.url)) return false
                openExternally(request.url)
                return true
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) binding.toolbar.title = title
            }
        }

        // The PDF report / Excel export are file downloads, which a WebView can't show.
        binding.webView.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            download(downloadUrl, userAgent, contentDisposition, mimeType)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null) {
            binding.webView.loadUrl(url)
        } else {
            binding.webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        if (::binding.isInitialized) binding.webView.destroy()
        super.onDestroy()
    }

    private fun isAllowed(uri: Uri): Boolean =
        uri.scheme == "https" && uri.host.equals(ApiConfig.WEB_HOST, ignoreCase = true)

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.result_open_link_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun download(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val uri = Uri.parse(url)
        if (!isAllowed(uri)) return

        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(uri).apply {
            setMimeType(mimeType)
            // Downloads are behind the web login, so pass the WebView's session along.
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            addRequestHeader("User-Agent", userAgent)
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(this@ResultWebViewActivity, Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        getSystemService(DownloadManager::class.java)?.enqueue(request)
        Toast.makeText(this, getString(R.string.result_downloading, fileName), Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }
}
