package org.amnezia.awg.widget

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import org.amnezia.awg.R
import org.amnezia.awg.util.PingResults

/** Small coloured ping label shown on the right side of every config in the list. */
class PingBadgeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatTextView(context, attrs) {
    private var tunnelName: String? = null
    private val listener = Runnable { render() }

    init {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 16f
        visibility = View.GONE
    }

    /** Called by data binding (app:tunnelName="@{key}"). */
    fun setTunnelName(name: String?) {
        tunnelName = name
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PingResults.addListener(listener)
        render()
    }

    override fun onDetachedFromWindow() {
        PingResults.removeListener(listener)
        super.onDetachedFromWindow()
    }

    private fun render() {
        val entry = tunnelName?.let { PingResults.get(it) }
        if (entry == null) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        when (entry.state) {
            PingResults.State.TESTING -> {
                text = "\u2026"
                setTextColor(ContextCompat.getColor(context, R.color.tunnel_status_idle))
            }
            PingResults.State.FAILED -> {
                text = context.getString(R.string.awg_ping_timeout)
                setTextColor(ContextCompat.getColor(context, R.color.ping_bad))
            }
            PingResults.State.DONE -> {
                text = context.getString(R.string.awg_ping_ms, entry.ms)
                setTextColor(ContextCompat.getColor(context, PingResults.colorRes(entry.ms)))
            }
        }
    }
}
