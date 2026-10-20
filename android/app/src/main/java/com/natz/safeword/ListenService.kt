package com.natz.safeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.Locale

/**
 * Service d'écoute en arrière-plan : tourne même écran éteint ou dans un jeu.
 * Moteur vocal Vosk 100 % hors ligne, envoi des SMS directement depuis la SIM.
 */
class ListenService : Service() {

    companion object {
        const val ACTION_START = "com.natz.safeword.START"
        const val ACTION_CANCEL = "com.natz.safeword.CANCEL"
        const val CH_LISTEN = "listen"
        const val CH_ALERT = "alert"
        const val ID_FG = 1
        const val ID_ALERT = 2

        @Volatile
        var running = false

        // Le modèle est gardé en mémoire d'un service à l'autre : relance instantanée.
        @Volatile
        private var cachedModel: Model? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var speech: SpeechService? = null
    private var cfg = Config(emptyList(), "", emptyList(), DEFAULT_MSG, 5, "grammar")
    private var wake: PowerManager.WakeLock? = null
    private var loading = false
    private var phase = 0 // 0 = repos, 1 = compte à rebours, 2 = envoi en cours
    private var cooldownUntil = 0L
    private var countdownLeft = 0
    private var lastLoc: Location? = null
    private var locWaits = 0
    private var lastPartialEmit = 0L
    private var restartFails = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LISTEN, "Écoute en arrière-plan", NotificationManager.IMPORTANCE_LOW)
        )
        val alert = NotificationChannel(CH_ALERT, "Alertes", NotificationManager.IMPORTANCE_HIGH)
        alert.enableVibration(true)
        nm.createNotificationChannel(alert)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_CANCEL) {
            if (!running) {
                stopSelf()
                return START_NOT_STICKY
            }
            cancelCountdown("bouton")
            return START_STICKY
        }
        // Relance automatique par Android après un arrêt : seulement si l'écoute était armée.
        if (intent == null && !Store.armed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val c = Store.loadConfig(this)
        if (c == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        cfg = c
        try {
            goForeground("Chargement du moteur vocal…")
        } catch (e: Exception) {
            Store.setArmed(this, false)
            Bus.emit("state", "state" to "error", "msg" to ("Android refuse l'écoute en arrière-plan : " + (e.message ?: "")))
            stopSelf()
            return START_NOT_STICKY
        }
        Store.setArmed(this, true)
        running = true
        acquireWake()
        handler.removeCallbacks(locRun)
        handler.post(locRun)
        startRecognizer()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            speech?.stop()
            speech?.shutdown()
        } catch (e: Exception) {
        }
        speech = null
        try {
            wake?.release()
        } catch (e: Exception) {
        }
        Bus.state = "stopped"
        Bus.emit("state", "state" to "stopped")
        super.onDestroy()
    }

    // ---------- notifications ----------

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun serviceIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, ListenService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun listenNotif(text: String): Notification =
        Notification.Builder(this, CH_LISTEN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Safeword")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent())
            .build()

    private fun updateListenNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(ID_FG, listenNotif(text))
    }

    private fun notifyAlert(title: String, text: String, ongoing: Boolean, cancelBtn: Boolean, timeoutMs: Long) {
        val b = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent())
        if (cancelBtn) b.addAction(0, "Annuler", serviceIntent(ACTION_CANCEL, 2))
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs)
        getSystemService(NotificationManager::class.java).notify(ID_ALERT, b.build())
    }

    private fun goForeground(text: String) {
        val n = listenNotif(text)
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (hasLoc()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(ID_FG, n, type)
        } else {
            startForeground(ID_FG, n)
        }
    }

    private fun acquireWake() {
        if (wake == null) {
            val pm = getSystemService(PowerManager::class.java)
            val w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "safeword:listen")
            w.setReferenceCounted(false)
            w.acquire()
            wake = w
        }
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 120, 300, 120, 300), -1))
        } catch (e: Exception) {
        }
    }

    // ---------- moteur vocal ----------

    private fun grammar(): String {
        val a = JSONArray()
        for (w in cfg.words) a.put(w.lowercase(Locale.FRENCH))
        if (cfg.cword.isNotEmpty()) a.put(cfg.cword.lowercase(Locale.FRENCH))
        a.put("[unk]")
        return a.toString()
    }

    private fun startRecognizer() {
        if (loading) return
        val m = cachedModel
        if (m != null) {
            rebuild(m)
            return
        }
        loading = true
        Bus.state = "loading"
        Bus.emit("state", "state" to "loading")
        updateListenNotif("Chargement du moteur vocal…")
        Thread {
            try {
                val dir = ModelLoader.ensure(this)
                val loaded = Model(dir.absolutePath)
                handler.post {
                    loading = false
                    cachedModel = loaded
                    if (running) rebuild(loaded)
                }
            } catch (t: Throwable) {
                handler.post {
                    loading = false
                    fail("Moteur vocal : " + (t.message ?: t.javaClass.simpleName))
                }
            }
        }.start()
    }

    private fun rebuild(m: Model) {
        try {
            try {
                speech?.stop()
                speech?.shutdown()
            } catch (e: Exception) {
            }
            speech = null
            val rec = if (cfg.mode == "free") Recognizer(m, 16000.0f) else Recognizer(m, 16000.0f, grammar())
            val s = SpeechService(rec, 16000.0f)
            if (!s.startListening(listener)) {
                fail("Micro indisponible. Une autre app l'utilise peut-être.")
                return
            }
            speech = s
            restartFails = 0
            Bus.state = "listening"
            Bus.emit("state", "state" to "listening")
            val first = if (cfg.words.isNotEmpty()) cfg.words[0] else ""
            updateListenNotif("À l'écoute. Dis « $first » pour alerter.")
        } catch (t: Throwable) {
            fail("Moteur vocal : " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun restartLater() {
        if (!running) return
        restartFails++
        if (restartFails > 6) {
            fail("Le micro ne répond plus. Rouvre Safeword et réarme l'écoute.")
            return
        }
        handler.postDelayed({
            val m = cachedModel
            if (running && !loading && m != null) rebuild(m)
        }, 1500)
    }

    private fun fail(msg: String) {
        Bus.state = "error"
        Bus.emit("state", "state" to "error", "msg" to msg)
        Store.setArmed(this, false)
        notifyAlert("Safeword s'est arrêté", msg, false, false, 0)
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun text(h: String?, key: String): String {
        if (h == null) return ""
        return try {
            JSONObject(h).optString(key, "")
        } catch (e: Exception) {
            ""
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            handleText(text(hypothesis, "partial"), true)
        }

        override fun onResult(hypothesis: String?) {
            handleText(text(hypothesis, "text"), false)
        }

        override fun onFinalResult(hypothesis: String?) {
            handleText(text(hypothesis, "text"), false)
        }

        override fun onError(exception: Exception?) {
            restartLater()
        }

        override fun onTimeout() {
            restartLater()
        }
    }

    private fun handleText(raw: String, partial: Boolean) {
        val t = raw.replace("[unk]", "").trim()
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!partial || now - lastPartialEmit > 250) {
            Bus.emit("heard", "text" to t)
            if (partial) lastPartialEmit = now
        }
        if (phase != 0) {
            if (phase == 1 && cfg.cword.isNotEmpty() && Match.matches(t, cfg.cword)) cancelCountdown("mot d'annulation")
            return
        }
        if (now < cooldownUntil) return
        for (w in cfg.words) {
            if (Match.matches(t, w)) {
                trigger()
                return
            }
        }
    }

    // ---------- alerte ----------

    private fun trigger() {
        if (phase != 0) return
        phase = 1
        locWaits = 0
        refreshLocation()
        vibrate()
        if (cfg.delay <= 0) {
            phase = 2
            sendWhenReady()
        } else {
            countdownLeft = cfg.delay
            handler.post(tickRun)
        }
    }

    private val tickRun = object : Runnable {
        override fun run() {
            if (phase != 1) return
            Bus.emit("countdown", "n" to countdownLeft, "total" to cfg.delay)
            if (countdownLeft <= 0) {
                phase = 2
                sendWhenReady()
                return
            }
            val hint = if (cfg.cword.isNotEmpty()) " Dis « ${cfg.cword} » pour annuler." else ""
            notifyAlert("Alerte dans $countdownLeft s", "Appuie sur Annuler si c'est une fausse alerte.$hint", true, true, 0)
            countdownLeft--
            handler.postDelayed(this, 1000)
        }
    }

    private fun cancelCountdown(why: String) {
        if (phase != 1) return
        handler.removeCallbacks(tickRun)
        phase = 0
        cooldownUntil = System.currentTimeMillis() + 4000
        Bus.emit("cancelled", "why" to why)
        notifyAlert("Alerte annulée", "Aucun message envoyé.", false, false, 4000)
    }

    private fun sendWhenReady() {
        if (lastLoc == null && hasLoc() && locWaits < 12) {
            locWaits++
            handler.postDelayed({ sendWhenReady() }, 250)
            return
        }
        doSend()
    }

    private fun doSend() {
        val loc = lastLoc
        var link = if (loc != null) "https://maps.google.com/?q=${loc.latitude},${loc.longitude}" else "(position indisponible)"
        if (loc != null && System.currentTimeMillis() - loc.time > 10 * 60 * 1000) link += " (position ancienne)"
        val body = cfg.msg.replace("{position}", link)
        val nums = ArrayList<String>()
        for (c in cfg.contacts) {
            val n = Phone.norm(c.tel)
            if (n.isNotEmpty()) nums.add(n)
        }
        notifyAlert("Envoi en cours…", "Les SMS partent vers tes proches.", true, false, 0)
        if (nums.isEmpty()) {
            finishSend(0, 0, "aucun proche enregistré", body)
            return
        }
        SmsSender(this).send(nums, body) { ok, failed, err -> finishSend(ok, failed, err, body) }
    }

    private fun finishSend(ok: Int, failed: Int, err: String?, body: String) {
        phase = 0
        cooldownUntil = System.currentTimeMillis() + 20000
        val success = ok > 0 && failed == 0
        val label = if (success) {
            "$ok SMS envoyé" + (if (ok > 1) "s" else "")
        } else if (ok > 0) {
            "$ok SMS sur ${ok + failed}"
        } else {
            "Envoi échoué"
        }
        Store.addHistory(this, success, label)
        Bus.emit("sent", "ok" to success, "sent" to ok, "failed" to failed, "err" to err, "body" to body)
        vibrate()
        val title = if (success) "Alerte envoyée" else "Alerte : envoi incomplet"
        val text = if (success) {
            "$ok proche(s) prévenu(s) avec ta position."
        } else {
            (err ?: "Échec de l'envoi") + (if (ok > 0) " ($ok prévenu(s))" else "")
        }
        notifyAlert(title, text, false, false, 0)
    }

    // ---------- position ----------

    private fun hasLoc(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // On rafraîchit la position toutes les 3 minutes pour en avoir toujours une récente.
    private val locRun = object : Runnable {
        override fun run() {
            if (!running) return
            refreshLocation()
            handler.postDelayed(this, 180000)
        }
    }

    @Suppress("DEPRECATION")
    private fun refreshLocation() {
        if (!hasLoc()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(p)
                val cur = lastLoc
                if (l != null && (cur == null || l.time > cur.time)) lastLoc = l
            } catch (e: Exception) {
            }
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestSingleUpdate(p, object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            lastLoc = location
                        }

                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }, Looper.getMainLooper())
                }
            } catch (e: Exception) {
            }
        }
    }
}
