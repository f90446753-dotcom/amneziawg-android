package org.amnezia.awg.util

import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.text.InputType
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.Application
import org.amnezia.awg.R
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Imports AmneziaWG / WireGuard configs from a subscription ("SUB") link.
 * It understands: a plain .conf, base64 text, and the panel's subscription page (HTML or JSON)
 * that contains links to one or more ".../config.conf" files.
 */
object SubImporter {
    private const val TAG = "AmneziaWG/SubImporter"
    private const val MAX_BYTES = 2_000_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private class Fetched(val body: String, val url: URL, val fileName: String?)
    private class Found(val name: String?, val text: String)

    fun start(activity: FragmentActivity) {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val input = EditText(activity)
        input.setHint(R.string.awg_sub_hint)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.maxLines = 4
        val box = FrameLayout(activity)
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.awg_sub_dialog_title)
            .setMessage(R.string.awg_sub_dialog_message)
            .setView(box)
            .setPositiveButton(R.string.awg_sub_import) { _, _ -> run(activity, input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun snack(activity: FragmentActivity, message: CharSequence) {
        val root = activity.findViewById<android.view.View>(android.R.id.content) ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun run(activity: FragmentActivity, link: String) {
        if (!(link.startsWith("http://", true) || link.startsWith("https://", true))) {
            snack(activity, activity.getString(R.string.awg_sub_bad_url))
            return
        }
        snack(activity, activity.getString(R.string.awg_sub_working))
        activity.lifecycleScope.launch {
            val message: String = try {
                val found = withContext(Dispatchers.IO) { collect(link) }
                if (found.isEmpty()) throw IllegalStateException(activity.getString(R.string.awg_sub_none))
                val manager = Application.getTunnelManager()
                val taken = HashSet<String>()
                for (t in manager.getTunnels()) taken.add(t.key)
                val host = try { URL(link).host } catch (e: Exception) { "sub" }
                var ok = 0
                var failed = 0
                var lastError = ""
                found.forEachIndexed { index, item ->
                    try {
                        val name = pickName(item.name, index, host, taken)
                        val config = Config.parse(ByteArrayInputStream(item.text.toByteArray(StandardCharsets.UTF_8)))
                        manager.create(name, config)
                        ok++
                    } catch (e: Throwable) {
                        failed++
                        lastError = ErrorMessages[e]
                        Log.e(TAG, "Could not create tunnel", e)
                    }
                }
                when {
                    ok > 0 && failed == 0 -> activity.resources.getQuantityString(R.plurals.awg_sub_done, ok, ok)
                    ok > 0 -> activity.getString(R.string.awg_sub_partial, ok, failed, lastError)
                    else -> activity.getString(R.string.awg_sub_error, lastError)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Subscription import failed", e)
                activity.getString(R.string.awg_sub_error, e.message ?: e.javaClass.simpleName)
            }
            snack(activity, message)
        }
    }

    // ------------------------------------------------------------------ network + parsing

    private fun fetch(urlString: String): Fetched {
        var url = URL(urlString)
        var redirects = 0
        while (true) {
            val c = url.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*;q=0.8")
                val code = c.responseCode
                if (code in 300..399 && redirects < 5) {
                    val location = c.getHeaderField("Location") ?: throw IllegalStateException("Redirect without location")
                    url = URL(url, location)
                    redirects++
                    continue
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val out = java.io.ByteArrayOutputStream()
                c.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_BYTES) break
                    }
                }
                return Fetched(String(out.toByteArray(), StandardCharsets.UTF_8), url, fileNameOf(c.getHeaderField("Content-Disposition")))
            } finally {
                c.disconnect()
            }
        }
    }

    private fun fileNameOf(header: String?): String? {
        if (header == null) return null
        val m = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header) ?: return null
        val raw = try { URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (e: Exception) { m.groupValues[1] }
        return raw.removeSuffix(".conf").removeSuffix(".CONF").trim().ifEmpty { null }
    }

    private fun configsIn(text: String, name: String?): List<Found> {
        if (!text.contains("[Interface]")) return emptyList()
        val parts = text.split(Regex("(?=\\[Interface\\])")).filter { it.contains("[Interface]") }
        return parts.map { Found(if (parts.size == 1) name else null, it.trim() + "\n") }
    }

    private fun decodeBase64(body: String): String? {
        val cleaned = body.trim().replace(Regex("\\s"), "")
        if (cleaned.length < 16) return null
        for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE)) {
            try {
                return String(Base64.decode(cleaned, flags), StandardCharsets.UTF_8)
            } catch (e: Exception) {
            }
        }
        return null
    }

    private fun linksIn(body: String, base: URL): List<URL> {
        val text = body.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        val regex = Regex("""(?:https?://[^\s"'<>\\]+?|/[^\s"'<>\\]+?)\.conf(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
        val seen = LinkedHashSet<String>()
        val urls = ArrayList<URL>()
        for (m in regex.findAll(text)) {
            try {
                val u = URL(base, m.value)
                if (seen.add(u.toString())) urls.add(u)
            } catch (e: Exception) {
            }
        }
        return urls
    }

    private fun extract(f: Fetched, followLinks: Boolean): List<Found> {
        configsIn(f.body, f.fileName).let { if (it.isNotEmpty()) return it }
        decodeBase64(f.body)?.let { decoded -> configsIn(decoded, f.fileName).let { if (it.isNotEmpty()) return it } }
        if (!followLinks) return emptyList()
        val result = ArrayList<Found>()
        for (u in linksIn(f.body, f.url).take(20)) {
            try {
                val sub = fetch(u.toString())
                result.addAll(configsIn(sub.body, sub.fileName))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping $u", e)
            }
        }
        return result
    }

    private fun collect(link: String): List<Found> {
        val first = fetch(link)
        extract(first, true).let { if (it.isNotEmpty()) return it }
        // Some panels expose the same data under a few other endpoints.
        for (suffix in listOf("/info", "?format=json", "/json")) {
            try {
                val u = if (suffix.startsWith("?") && link.contains("?")) link + "&" + suffix.substring(1) else link.trimEnd('/') + suffix
                val other = fetch(u)
                extract(other, true).let { if (it.isNotEmpty()) return it }
            } catch (e: Exception) {
            }
        }
        return emptyList()
    }

    private fun pickName(preferred: String?, index: Int, host: String, taken: MutableSet<String>): String {
        var base = preferred ?: (host.substringBefore('.') + "-" + (index + 1))
        base = base.replace(Regex("[^a-zA-Z0-9_=+.-]"), "")
        if (base.isEmpty()) base = "sub-" + (index + 1)
        base = base.take(15)
        var name = base
        var n = 2
        while (taken.contains(name)) {
            val suffix = "-$n"
            name = base.take(15 - suffix.length) + suffix
            n++
        }
        taken.add(name)
        return name
    }
}
