package com.example.tubex

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SslSupport.installDefault(applicationContext)
        aplicarOrientacionInicial()

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.databaseEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                activarSonido()
                inyectarSenalTv()
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                Toast.makeText(this@MainActivity, "Error de red: $description", Toast.LENGTH_LONG).show()
            }
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                } else {
                    progressBar.visibility = View.GONE
                }
            }
        }

        webView.loadUrl(TUBEX_BASE_URL)

        onBackPressedDispatcher.addCallback(this) {
            salirDePantallaCompletaSiAplica { enFullscreen ->
                if (!enFullscreen && webView.canGoBack()) {
                    webView.goBack()
                } else if (!enFullscreen) {
                    finish()
                }
            }
        }

        AppUpdater.checkForUpdate(this)
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.retryPendingInstall(this)
    }

    private fun salirDePantallaCompletaSiAplica(continuar: (Boolean) -> Unit) {
        webView.evaluateJavascript(
            """
            (function(){
              try {
                if (window.__tubexIsFs && window.__tubexIsFs()) {
                  if (window.__tubexExitFs) window.__tubexExitFs();
                  return "fs";
                }
              } catch(e) {}
              return "no";
            })();
            """.trimIndent()
        ) { resultado ->
            val enFullscreen = resultado?.trim()?.quotedEquals("fs") == true
            continuar(enFullscreen)
        }
    }

    private fun String?.quotedEquals(valor: String): Boolean {
        val t = this?.trim()
        return t == valor || t == "\"$valor\"" || t == "'$valor'"
    }

    private fun esAndroidTv(): Boolean {
        return try {
            val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (e: Exception) {
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        }
    }

    private fun aplicarOrientacionInicial() {
        requestedOrientation = if (esAndroidTv()) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun inyectarSenalTv() {
        if (!esAndroidTv()) return
        runOnUiThread {
            webView.evaluateJavascript(
                """
                (function(){
                  window.__tubexIsTV = true;
                  return true;
                })();
                """.trimIndent(),
                null
            )
        }
    }

    private fun activarSonido() {
        runOnUiThread {
            webView.evaluateJavascript(
                """
                (function(){
                    function unmute(){
                        var media = Array.prototype.slice.call(document.querySelectorAll('video,audio'));
                        media.forEach(function(v){
                            try {
                                v.muted = false;
                                v.defaultMuted = false;
                                v.volume = 1.0;
                                v.removeAttribute('muted');
                                v.setAttribute('playsinline', '');
                                var p = v.play();
                                if (p && p.catch) p.catch(function(){});
                            } catch(e) {}
                        });
                    }
                    unmute();
                    if (!window.__tubexUnmuteTimer) {
                        window.__tubexUnmuteTimer = setInterval(unmute, 1000);
                    }
                    return true;
                })();
                """.trimIndent(),
                null
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> activarSonido()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val TUBEX_BASE_URL = "https://poseidon.tail7e7844.ts.net/freetube/acceso"
    }
}