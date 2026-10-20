package com.natz.safeword

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipInputStream

const val DEFAULT_MSG = "🚨 Je suis en danger. Appelle-moi ou préviens les secours. Ma position : {position}"

data class Contact(val name: String, val tel: String)

data class Config(
    val words: List<String>,
    val cword: String,
    val contacts: List<Contact>,
    val msg: String,
    val delay: Int,
    val mode: String
) {
    companion object {
        fun parse(json: String): Config {
            val o = JSONObject(json)
            val words = ArrayList<String>()
            val wa = o.optJSONArray("words")
            if (wa != null) {
                for (i in 0 until wa.length()) {
                    val w = wa.optString(i).trim()
                    if (w.isNotEmpty()) words.add(w)
                }
            }
            val contacts = ArrayList<Contact>()
            val ca = o.optJSONArray("contacts")
            if (ca != null) {
                for (i in 0 until ca.length()) {
                    val c = ca.optJSONObject(i)
                    if (c != null) contacts.add(Contact(c.optString("nom"), c.optString("tel")))
                }
            }
            return Config(
                words,
                o.optString("cword", "").trim(),
                contacts,
                o.optString("msg", DEFAULT_MSG),
                o.optInt("delay", 5),
                o.optString("mode", "grammar")
            )
        }
    }
}

/** Stockage local : config, état armé, journal des alertes. */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("safeword", Context.MODE_PRIVATE)

    fun saveConfig(c: Context, json: String) {
        p(c).edit().putString("cfg", json).apply()
    }

    fun loadConfig(c: Context): Config? {
        val j = p(c).getString("cfg", null) ?: return null
        return try {
            Config.parse(j)
        } catch (e: Exception) {
            null
        }
    }

    fun setArmed(c: Context, v: Boolean) {
        p(c).edit().putBoolean("armed", v).apply()
    }

    fun armed(c: Context): Boolean = p(c).getBoolean("armed", false)

    fun addHistory(c: Context, ok: Boolean, label: String) {
        val old = try {
            JSONArray(p(c).getString("hist", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        val out = JSONArray()
        out.put(JSONObject().put("t", System.currentTimeMillis()).put("ok", ok).put("label", label))
        for (i in 0 until minOf(old.length(), 11)) out.put(old.get(i))
        p(c).edit().putString("hist", out.toString()).apply()
    }

    fun history(c: Context): String = p(c).getString("hist", "[]") ?: "[]"

    fun clearHistory(c: Context) {
        p(c).edit().remove("hist").apply()
    }
}

/** Canal de messages entre le service d'écoute et l'écran (quand il est ouvert). */
object Bus {
    @Volatile
    var listener: ((String) -> Unit)? = null

    @Volatile
    var state: String = "stopped"

    fun emit(type: String, vararg kv: Pair<String, Any?>) {
        val o = JSONObject()
        o.put("type", type)
        for (pair in kv) o.put(pair.first, pair.second)
        listener?.invoke(o.toString())
    }
}

object Phone {
    fun norm(s: String): String {
        var t = s.replace(Regex("[\\s.\\-()]"), "")
        if (t.startsWith("00")) t = "+" + t.substring(2)
        if (Regex("^0\\d{9}\$").matches(t)) t = "+33" + t.substring(1)
        return t
    }
}

/** Reconnaissance floue du mot-clé : accents, pluriels et sons proches (même logique que l'interface). */
object Match {
    private val marks = Regex("\\p{M}+")
    private val other = Regex("[^a-z0-9 ]")
    private val spaces = Regex("\\s+")
    private val softC = Regex("c(?=[eiy])")
    private val doubles = Regex("(.)\\1+")
    private val tail = Regex("[sxtdze]+(?=\\s|\$)")

    fun norm(s: String): String {
        val t = Normalizer.normalize(s.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
        return t.replace(marks, "").replace(other, " ").replace(spaces, " ").trim()
    }

    fun phon(s: String): String {
        var t = norm(s)
        t = t.replace("ph", "f").replace("qu", "k").replace("ck", "k").replace("ch", "x")
        t = t.replace("eau", "o").replace("au", "o").replace("ou", "u")
        t = t.replace(softC, "s").replace("c", "k")
        t = t.replace(doubles, "\$1")
        t = t.replace(tail, "")
        return t.replace(spaces, " ").trim()
    }

    fun sim(a: String, b: String): Double {
        if (a == b) return 1.0
        val m = a.length
        val n = b.length
        if (m == 0 || n == 0) return 0.0
        var prev = IntArray(n + 1) { it }
        for (i in 1..m) {
            val cur = IntArray(n + 1)
            cur[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return 1.0 - prev[n].toDouble() / maxOf(m, n).toDouble()
    }

    fun matches(heard: String, target: String): Boolean {
        val h = norm(heard)
        val t = norm(target)
        if (h.isEmpty() || t.isEmpty()) return false
        if (" $h ".contains(" $t ")) return true
        val ph = phon(heard)
        val pt = phon(target)
        if (pt.isEmpty()) return false
        if (" $ph ".contains(" $pt ")) return true
        if (pt.length < 5) return false
        val hw = ph.split(" ")
        val n = pt.split(" ").size
        var i = 0
        while (i + n <= hw.size) {
            if (sim(hw.subList(i, i + n).joinToString(" "), pt) >= 0.8) return true
            i++
        }
        return false
    }
}

/** Décompresse le modèle vocal français (embarqué dans l'APK) au premier lancement. */
object ModelLoader {
    fun ensure(ctx: Context): File {
        val root = File(ctx.filesDir, "vosk")
        val done = File(root, ".ok")
        if (!done.exists()) {
            root.deleteRecursively()
            root.mkdirs()
            val rootPath = root.canonicalPath
            ZipInputStream(BufferedInputStream(ctx.assets.open("vosk-model-small-fr.zip"))).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val out = File(root, e.name)
                    if (!out.canonicalPath.startsWith(rootPath)) throw SecurityException("archive invalide")
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zin.copyTo(it) }
                    }
                    zin.closeEntry()
                    e = zin.nextEntry
                }
            }
            done.writeText("1")
        }
        return findRoot(root) ?: throw IllegalStateException("modèle vocal introuvable")
    }

    private fun findRoot(d: File): File? {
        if (File(d, "am").isDirectory && File(d, "conf").isDirectory) return d
        val kids = d.listFiles() ?: return null
        for (k in kids) {
            if (k.isDirectory) {
                val r = findRoot(k)
                if (r != null) return r
            }
        }
        return null
    }
}

/** Envoi de SMS directement depuis la carte SIM, avec confirmation de l'opérateur. */
class SmsSender(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var seq = 0

    fun send(numbers: List<String>, body: String, cb: (Int, Int, String?) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { send(numbers, body, cb) }
            return
        }
        Job(numbers, body, cb).start()
    }

    @Suppress("DEPRECATION")
    private fun manager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    private fun reason(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "échec de l'envoi (crédit ou réseau)"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "pas de réseau"
        SmsManager.RESULT_ERROR_NULL_PDU -> "message invalide"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "mode avion activé"
        else -> "erreur $code"
    }

    private inner class Job(
        val numbers: List<String>,
        val body: String,
        val cb: (Int, Int, String?) -> Unit
    ) : BroadcastReceiver() {
        val action = "com.natz.safeword.SMS_SENT." + System.nanoTime()
        var pending = 0
        var dispatched = false
        var finished = false
        var err: String? = null
        val failedIdx = HashSet<Int>()
        val timeout = Runnable { finish() }

        override fun onReceive(c: Context?, i: Intent?) {
            val idx = i?.getIntExtra("idx", -1) ?: -1
            if (resultCode != Activity.RESULT_OK) {
                if (idx >= 0) failedIdx.add(idx)
                if (err == null) err = reason(resultCode)
            }
            pending--
            if (dispatched && pending <= 0) finish()
        }

        fun start() {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(this, filter)
            }
            val sm: SmsManager? = try {
                manager()
            } catch (e: Exception) {
                null
            }
            for ((idx, num) in numbers.withIndex()) {
                try {
                    if (sm == null) throw IllegalStateException("SMS indisponible sur cet appareil")
                    val parts = sm.divideMessage(body)
                    val pis = ArrayList<PendingIntent>()
                    for (part in parts) {
                        seq++
                        val intent = Intent(action).setPackage(ctx.packageName).putExtra("idx", idx)
                        pis.add(
                            PendingIntent.getBroadcast(
                                ctx, seq, intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                    }
                    if (parts.size > 1) {
                        sm.sendMultipartTextMessage(num, null, parts, pis, null)
                    } else {
                        sm.sendTextMessage(num, null, body, pis[0], null)
                    }
                    pending += parts.size
                } catch (e: Exception) {
                    failedIdx.add(idx)
                    if (err == null) {
                        err = if (e is SecurityException) "permission SMS refusée" else (e.message ?: "erreur d'envoi")
                    }
                }
            }
            dispatched = true
            if (pending <= 0) finish() else handler.postDelayed(timeout, 10000)
        }

        fun finish() {
            if (finished) return
            finished = true
            handler.removeCallbacks(timeout)
            try {
                ctx.unregisterReceiver(this)
            } catch (e: Exception) {
            }
            val failed = failedIdx.size
            cb(numbers.size - failed, failed, err)
        }
    }
}
