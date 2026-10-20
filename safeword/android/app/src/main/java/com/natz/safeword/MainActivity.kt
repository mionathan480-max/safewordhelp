package com.natz.safeword

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** Écran de l'app : la même interface que la version web, avec un pont vers le service d'écoute. */
class MainActivity : Activity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF07070B.toInt()
        window.navigationBarColor = 0xFF07070B.toInt()
        web = WebView(this)
        web.setBackgroundColor(0xFF07070B.toInt())
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        WebView.setWebContentsDebuggingEnabled(true)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Native")
        web.loadUrl("file:///android_asset/www/index.html")
    }

    override fun onResume() {
        super.onResume()
        Bus.listener = { json ->
            runOnUiThread {
                web.evaluateJavascript("window.__native&&window.__native($json)", null)
            }
        }
        web.evaluateJavascript("window.syncNative&&window.syncNative()", null)
    }

    override fun onPause() {
        Bus.listener = null
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // L'app reste active en fond : on la met juste de côté.
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Bus.emit("perms")
    }

    private fun granted(p: String): Boolean = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    inner class Bridge {

        @JavascriptInterface
        fun perms(): String {
            val pm = getSystemService(PowerManager::class.java)
            return JSONObject()
                .put("mic", granted(Manifest.permission.RECORD_AUDIO))
                .put("sms", granted(Manifest.permission.SEND_SMS))
                .put(
                    "loc",
                    granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                )
                .put("notif", if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.POST_NOTIFICATIONS) else true)
                .put("battery", pm.isIgnoringBatteryOptimizations(packageName))
                .toString()
        }

        @JavascriptInterface
        fun requestPerms() {
            runOnUiThread {
                val need = ArrayList<String>()
                val wanted = listOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                for (p in wanted) if (!granted(p)) need.add(p)
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    need.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (need.isEmpty()) Bus.emit("perms") else requestPermissions(need.toTypedArray(), 42)
            }
        }

        @JavascriptInterface
        fun requestBattery() {
            runOnUiThread {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                    }
                }
            }
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread {
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun start(json: String): String {
            try {
                Config.parse(json)
            } catch (e: Exception) {
                return "config_invalide"
            }
            if (!granted(Manifest.permission.RECORD_AUDIO)) return "micro_refuse"
            Store.saveConfig(this@MainActivity, json)
            return try {
                startForegroundService(
                    Intent(this@MainActivity, ListenService::class.java).setAction(ListenService.ACTION_START)
                )
                "ok"
            } catch (e: Exception) {
                "erreur: " + (e.message ?: "")
            }
        }

        @JavascriptInterface
        fun stop() {
            Store.setArmed(this@MainActivity, false)
            stopService(Intent(this@MainActivity, ListenService::class.java))
        }

        @JavascriptInterface
        fun cancel() {
            if (ListenService.running) {
                startService(
                    Intent(this@MainActivity, ListenService::class.java).setAction(ListenService.ACTION_CANCEL)
                )
            }
        }

        @JavascriptInterface
        fun saveConfig(json: String) {
            try {
                Config.parse(json)
                Store.saveConfig(this@MainActivity, json)
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun state(): String =
            JSONObject().put("running", ListenService.running).put("state", Bus.state).toString()

        @JavascriptInterface
        fun history(): String = Store.history(this@MainActivity)

        @JavascriptInterface
        fun clearHistory() {
            Store.clearHistory(this@MainActivity)
        }

        @JavascriptInterface
        fun testSms(json: String) {
            val c = try {
                Config.parse(json)
            } catch (e: Exception) {
                return
            }
            val nums = ArrayList<String>()
            for (ct in c.contacts) {
                val n = Phone.norm(ct.tel)
                if (n.isNotEmpty()) nums.add(n)
            }
            SmsSender(this@MainActivity).send(nums, "Test Safeword ✅ : ton alerte par SMS fonctionne.") { ok, failed, err ->
                Bus.emit("sent", "test" to true, "ok" to (ok > 0 && failed == 0), "sent" to ok, "failed" to failed, "err" to err)
            }
        }
    }
}
