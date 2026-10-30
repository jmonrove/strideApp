package dev.stride.lab

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID

/** Bundled console UI over the isolated preview engine. No USB/motor implementation is linked. */
class WorkoutLabActivity : Activity() {
    companion object {
        private const val ENTRY = "https://stride.local/console.html"
        private val ASSETS = mapOf("/console.html" to ("console/index.html" to "text/html"),
            "/console.css" to ("console/console.css" to "text/css"),
            "/console.js" to ("console/console.js" to "application/javascript"))
    }
    private lateinit var session: LabSession
    private lateinit var store: ConsolePreferences
    private var server: PreviewServer? = null
    private var web: WebView? = null
    private var loaded = false
    private var visible = false
    private var networkError: String? = null
    private var lastPayload: String? = null
    private val runId = UUID.randomUUID().toString()
    private val handler = Handler(Looper.getMainLooper())
    private val paint = object : Runnable {
        override fun run() {
            if (!visible || !::session.isInitialized) return
            push()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        store = ConsolePreferences(getSharedPreferences("console", MODE_PRIVATE))
        val cfg = try {
            val saved = store.settings()
            WorkoutEngine.Config(
                baseSpeedMph = extraDouble("baseSpeedMph", minOf(2.0, saved.getDouble("maxSpeedMph"))),
                maxSpeedMph = extraDouble("maxSpeedMph", saved.getDouble("maxSpeedMph")),
                maxIncline = extraDouble("maxInclinePercent", saved.getDouble("maxInclinePercent")),
                targetLowBpm = intent.getIntExtra("targetLowBpm", 110),
                targetHighBpm = intent.getIntExtra("targetHighBpm", 130),
                adaptiveSpeed = intent.getBooleanExtra("adaptiveSpeed", saved.getBoolean("adaptiveSpeed")))
        } catch (_: IllegalArgumentException) { WorkoutEngine.Config() }
        session = LabSession(WorkoutEngine(cfg))
        try {
            server = PreviewServer(session, assets.open("phone.html").bufferedReader().use { it.readText() }).also { it.start() }
        } catch (_: Exception) { networkError = "Phone bridge unavailable; console preview still works." }
        showConsole()
    }

    @SuppressLint("SetJavaScriptEnabled") // Only the three immutable APK assets below can load; no remote frames/content.
    private fun showConsole() {
        loaded = false
        lastPayload = null
        val page = WebView(this)
        page.setBackgroundColor(Color.rgb(7, 14, 32))
        page.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        page.addJavascriptInterface(ConsoleBridge(), "StrideConsole")
        page.webViewClient = object : WebViewClient() {
            @Deprecated("API 22 callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = url != ENTRY
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                val asset = if (request.method == "GET" && uri.scheme == "https" && uri.host == "stride.local" &&
                    uri.port == -1 && uri.query == null && uri.fragment == null) ASSETS[uri.path] else null
                return if (asset != null) {
                    WebResourceResponse(asset.second, "UTF-8", assets.open(asset.first))
                } else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url == ENTRY) { loaded = true; push(force = true) }
            }
            @Deprecated("API 22 callback")
            override fun onReceivedError(view: WebView, code: Int, description: String?, failingUrl: String?) {
                if (failingUrl == ENTRY) fallback()
            }
        }
        web?.destroy()
        web = page
        setContentView(page)
        page.loadUrl(ENTRY)
    }

    private inner class ConsoleBridge {
        @JavascriptInterface fun dispatch(raw: String) {
            if (raw.length > 4096) return
            handler.post {
                if (!visible || !loaded) return@post
                try {
                    val body = JSONObject(raw)
                    when (body.getString("action")) {
                        "presentation" -> store.savePresentation(body)
                        "saveHistory" -> store.saveSession(session.status(), runId)
                        "configure" -> {
                            session.action(body)
                            store.saveConfig(session.engine.config)
                        }
                        else -> session.action(body)
                    }
                    push(force = true)
                } catch (e: IllegalArgumentException) { push(e.message ?: "Invalid action", true) }
                catch (e: IllegalStateException) { push(e.message ?: "Action unavailable", true) }
                catch (_: org.json.JSONException) { push("Invalid action", true) }
            }
        }
    }

    private fun payload(error: String? = null): JSONObject {
        val state = session.status()
        return state.put("routines", session.routines()).put("settings", store.settings())
            .put("history", store.history()).put("recordId", "$runId:${state.optLong("sessionId")}")
            .put("saved", store.hasSession(state, runId))
            .put("bridge", JSONObject().put("addresses", JSONArray(addresses()))
                .put("token", session.token).put("running", server != null)
                .put("error", networkError ?: JSONObject.NULL))
            .put("uiError", error ?: JSONObject.NULL)
            .put("appVersion", "0.2.1-console-preview")
    }
    private fun push(error: String? = null, force: Boolean = false) {
        if (!loaded) return
        val json = payload(error).toString()
        if (force || lastPayload != json) {
            web?.evaluateJavascript("window.StrideUI && window.StrideUI.update($json);", null)
            lastPayload = json
        }
    }
    private fun addresses(): List<String> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .map { "http://${it.hostAddress}:8780/" }
    } catch (_: Exception) { emptyList() }

    private fun fallback() {
        loaded = false
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            addView(TextView(this@WorkoutLabActivity).apply {
                setText(R.string.display_unavailable)
                textSize = 22f
            })
            addView(Button(this@WorkoutLabActivity).apply { setText(R.string.stop_preview); setOnClickListener { session.engine.stop() } })
            addView(Button(this@WorkoutLabActivity).apply { setText(R.string.reload_display); setOnClickListener { showConsole() } })
        })
    }
    @Suppress("DEPRECATION")
    private fun extraDouble(key: String, fallback: Double): Double = (intent.extras?.get(key) as? Number)?.toDouble() ?: fallback
    override fun onResume() {
        super.onResume()
        visible = true
        handler.removeCallbacks(paint)
        handler.post(paint)
    }
    override fun onPause() {
        visible = false
        handler.removeCallbacks(paint)
        if (::session.isInitialized) session.engine.pause()
        super.onPause()
    }
    @Deprecated("API 22 console back handling")
    override fun onBackPressed() { web?.evaluateJavascript("window.StrideUI && window.StrideUI.back();", null) }
    override fun onDestroy() {
        visible = false
        handler.removeCallbacksAndMessages(null)
        server?.close()
        if (::session.isInitialized) session.close()
        web?.removeJavascriptInterface("StrideConsole")
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
