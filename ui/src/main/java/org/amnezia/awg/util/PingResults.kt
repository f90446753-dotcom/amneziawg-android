package org.amnezia.awg.util

import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.R
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.model.ObservableTunnel

/**
 * Runs the real ping test (connect each config, time TCP connects to Google through it,
 * disconnect, finally restore the previous connection) and keeps the latest result per config.
 */
object PingResults {
    enum class State { TESTING, DONE, FAILED }

    class Entry(val state: State, val ms: Int = 0)

    private val entries = HashMap<String, Entry>()
    private val listeners = ArrayList<Runnable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    var running = false
        private set

    fun get(name: String): Entry? = entries[name]

    fun addListener(r: Runnable) {
        if (!listeners.contains(r)) listeners.add(r)
    }

    fun removeListener(r: Runnable) {
        listeners.remove(r)
    }

    private fun changed() {
        for (l in ArrayList(listeners)) l.run()
    }

    /** Colour scale: <=100 great, <=200 good, <=350 fair, <=600 poor, above that bad. */
    fun colorRes(ms: Int): Int = when {
        ms <= 100 -> R.color.ping_great
        ms <= 200 -> R.color.ping_good
        ms <= 350 -> R.color.ping_fair
        ms <= 600 -> R.color.ping_poor
        else -> R.color.ping_bad
    }

    fun toggle(context: Context, tunnels: List<ObservableTunnel>) {
        if (running) {
            job?.cancel()
            return
        }
        if (tunnels.isEmpty()) {
            Toast.makeText(context, R.string.awg_ping_no_tunnels, Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = context.getSharedPreferences("awg_ping", Context.MODE_PRIVATE)
        if (prefs.getBoolean("confirmed", false)) {
            start(tunnels)
        } else {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.awg_ping_title)
                .setMessage(R.string.awg_ping_warning)
                .setPositiveButton(R.string.awg_ping_start) { _, _ ->
                    prefs.edit().putBoolean("confirmed", true).apply()
                    start(tunnels)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun start(tunnels: List<ObservableTunnel>) {
        if (running) return
        running = true
        entries.clear()
        changed()
        job = scope.launch {
            try {
                runTest(tunnels)
            } finally {
                entries.entries.removeAll { it.value.state == State.TESTING }
                running = false
                changed()
            }
        }
    }

    private suspend fun runTest(all: List<ObservableTunnel>) {
        val wasUp = all.filter { it.state == Tunnel.State.UP }
        // The connection that is already up goes first, so it is tested without any interruption.
        val order = all.sortedBy { if (wasUp.contains(it)) 0 else 1 }
        try {
            for (t in order) {
                val name = t.key
                entries[name] = Entry(State.TESTING)
                changed()
                try {
                    if (t.state != Tunnel.State.UP) {
                        for (other in all) {
                            if (other !== t && other.state == Tunnel.State.UP) other.setStateAsync(Tunnel.State.DOWN)
                        }
                        t.setStateAsync(Tunnel.State.UP)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    entries[name] = Entry(State.FAILED)
                    changed()
                    continue
                }

                var result: Entry = Entry(State.FAILED)
                if (PingTest.waitUntilReachable(12000L)) {
                    val ping = PingTest.measure().pingMs
                    if (ping != null) result = Entry(State.DONE, ping)
                }
                entries[name] = result
                changed()

                if (!wasUp.contains(t)) {
                    try {
                        t.setStateAsync(Tunnel.State.DOWN)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                for (t in all) {
                    if (!wasUp.contains(t) && t.state == Tunnel.State.UP) runCatching { t.setStateAsync(Tunnel.State.DOWN) }
                }
                for (t in all) {
                    if (wasUp.contains(t) && t.state != Tunnel.State.UP) runCatching { t.setStateAsync(Tunnel.State.UP) }
                }
            }
        }
    }
}
