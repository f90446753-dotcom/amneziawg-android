package org.amnezia.awg.util

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.R
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.model.ObservableTunnel

/**
 * Bottom sheet that really tests every config: connects it, measures the latency to Google
 * through it, disconnects it, and finally restores whatever was connected before.
 */
object PingSheet {
    private class Row(val tunnel: ObservableTunnel, val view: View) {
        val dot: TextView = view.findViewById(R.id.ping_row_dot)
        val name: TextView = view.findViewById(R.id.ping_row_name)
        val status: TextView = view.findViewById(R.id.ping_row_status)
        val ms: TextView = view.findViewById(R.id.ping_row_ms)
    }

    fun show(context: Context, tunnels: List<ObservableTunnel>) {
        val dialog = BottomSheetDialog(context)
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.ping_sheet, null, false)
        dialog.setContentView(root)

        val rowsBox = root.findViewById<LinearLayout>(R.id.ping_rows)
        val scroll = root.findViewById<ScrollView>(R.id.ping_scroll)
        val summary = root.findViewById<TextView>(R.id.ping_summary)
        val action = root.findViewById<MaterialButton>(R.id.ping_action)

        val rows = tunnels.map { t ->
            val rv = inflater.inflate(R.layout.ping_row, rowsBox, false)
            rowsBox.addView(rv)
            val row = Row(t, rv)
            row.name.text = t.key
            paint(context, row, context.getString(R.string.awg_ping_idle), "\u2013", R.color.tunnel_status_idle)
            row
        }
        if (rows.size > 4) {
            scroll.layoutParams.height = (context.resources.displayMetrics.heightPixels * 0.42f).toInt()
        }
        if (rows.isEmpty()) {
            summary.setText(R.string.awg_ping_empty)
            action.isEnabled = false
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var job: Job? = null
        dialog.setOnDismissListener { scope.cancel() }

        action.setOnClickListener {
            if (job?.isActive == true) {
                job?.cancel()
                return@setOnClickListener
            }
            action.setText(R.string.awg_ping_stop)
            job = scope.launch {
                try {
                    runTest(context, rows, summary)
                } finally {
                    action.setText(R.string.awg_ping_again)
                }
            }
        }

        dialog.show()
    }

    private fun paint(context: Context, row: Row, status: String, ms: String, colorRes: Int) {
        val color = ContextCompat.getColor(context, colorRes)
        row.dot.setTextColor(color)
        row.status.text = status
        row.ms.text = ms
        row.ms.setTextColor(color)
    }

    private fun qualityColor(ms: Int): Int = when {
        ms < 150 -> R.color.tunnel_status_connected
        ms < 350 -> R.color.tunnel_status_connecting
        else -> R.color.tunnel_status_disconnected
    }

    private suspend fun runTest(context: Context, rows: List<Row>, summary: TextView) {
        val all = rows.map { it.tunnel }
        val wasUp = all.filter { it.state == Tunnel.State.UP }
        // The connection that is already up goes first, so it is tested without any interruption.
        val order = rows.sortedBy { if (wasUp.contains(it.tunnel)) 0 else 1 }
        var bestName: String? = null
        var bestMs = Int.MAX_VALUE

        for (row in rows) {
            paint(context, row, context.getString(R.string.awg_ping_waiting), "\u2013", R.color.tunnel_status_idle)
        }
        summary.setText(R.string.awg_ping_running)

        try {
            for (row in order) {
                val t = row.tunnel
                paint(context, row, context.getString(R.string.awg_ping_connecting), "\u2026", R.color.tunnel_status_connecting)
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
                    paint(context, row, context.getString(R.string.awg_ping_error), "\u2716", R.color.tunnel_status_disconnected)
                    continue
                }

                paint(context, row, context.getString(R.string.awg_ping_measuring), "\u2026", R.color.tunnel_status_connecting)
                val reachable = PingTest.waitUntilReachable(12000L)
                if (!reachable) {
                    paint(context, row, context.getString(R.string.awg_ping_failed), "\u2716", R.color.tunnel_status_disconnected)
                } else {
                    val r = PingTest.measure()
                    val ping = r.pingMs
                    if (ping == null) {
                        paint(context, row, context.getString(R.string.awg_ping_failed), "\u2716", R.color.tunnel_status_disconnected)
                    } else {
                        paint(
                            context, row,
                            context.getString(R.string.awg_ping_replies, r.replies, r.total),
                            context.getString(R.string.awg_ping_ms, ping),
                            qualityColor(ping)
                        )
                        if (ping < bestMs) {
                            bestMs = ping
                            bestName = t.key
                        }
                    }
                }

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
            summary.setText(R.string.awg_ping_restoring)
            withContext(NonCancellable) {
                for (t in all) {
                    if (!wasUp.contains(t) && t.state == Tunnel.State.UP) runCatching { t.setStateAsync(Tunnel.State.DOWN) }
                }
                for (t in all) {
                    if (wasUp.contains(t) && t.state != Tunnel.State.UP) runCatching { t.setStateAsync(Tunnel.State.UP) }
                }
            }
            val best = bestName
            if (best != null) {
                summary.text = context.getString(R.string.awg_ping_best, best, bestMs)
            } else {
                summary.setText(R.string.awg_ping_no_best)
            }
        }
    }
}
