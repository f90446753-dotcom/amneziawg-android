package org.amnezia.awg.widget

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.databinding.Observable
import androidx.databinding.ObservableList
import org.amnezia.awg.R
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.databinding.ObservableKeyedArrayList
import org.amnezia.awg.model.ObservableTunnel
import org.amnezia.awg.util.FlyIn
import org.amnezia.awg.util.PingResults

/**
 * The gradient card on the home screen. Shows the name of the connected tunnel
 * (with a fly-in animation and a pulsing shield) or the default text when nothing is connected.
 */
class HeroStatusView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private var titleView: TextView? = null
    private var badgeView: TextView? = null
    private var shieldView: View? = null
    private var pingButton: ImageView? = null
    private var pingPulse: ObjectAnimator? = null
    private val pingListener = Runnable { updatePingButton() }

    private var observed: ObservableKeyedArrayList<String, ObservableTunnel>? = null
    private val listened = ArrayList<ObservableTunnel>()
    private var listening = false
    private var initialized = false
    private var shownName: String? = null
    private var pulse: ObjectAnimator? = null

    private val tunnelCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            post { refresh() }
        }
    }

    private val listCallback = object : ObservableList.OnListChangedCallback<ObservableList<ObservableTunnel>>() {
        override fun onChanged(sender: ObservableList<ObservableTunnel>) { post { relisten() } }
        override fun onItemRangeChanged(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) { post { relisten() } }
        override fun onItemRangeInserted(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) { post { relisten() } }
        override fun onItemRangeMoved(sender: ObservableList<ObservableTunnel>, fromPosition: Int, toPosition: Int, itemCount: Int) { post { relisten() } }
        override fun onItemRangeRemoved(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) { post { relisten() } }
    }

    init {
        clipChildren = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        titleView = findViewById(R.id.hero_title)
        badgeView = findViewById(R.id.hero_badge)
        shieldView = findViewById(R.id.hero_shield)
        pingButton = findViewById(R.id.hero_ping)
        pingButton?.setOnClickListener {
            PingResults.toggle(context, observed?.toList() ?: emptyList())
        }
    }

    /** Called by data binding (app:tunnels="@{tunnels}"). */
    fun setTunnels(list: ObservableKeyedArrayList<String, ObservableTunnel>?) {
        if (list === observed) return
        stopListening()
        observed = list
        if (isAttachedToWindow) startListening()
        relisten()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startListening()
        PingResults.addListener(pingListener)
        updatePingButton()
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopListening()
        PingResults.removeListener(pingListener)
        pingPulse?.cancel()
        pingPulse = null
        stopPulse()
    }

    /** Bolt icon when idle; stop icon and a gentle blink while the test is running. */
    private fun updatePingButton() {
        val button = pingButton ?: return
        if (PingResults.running) {
            button.setImageResource(R.drawable.ic_ping_stop)
            button.contentDescription = context.getString(R.string.awg_ping_stop_desc)
            if (pingPulse?.isRunning != true) {
                pingPulse = ObjectAnimator.ofFloat(button, View.ALPHA, 1f, 0.45f).apply {
                    duration = 700L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
            }
        } else {
            pingPulse?.cancel()
            pingPulse = null
            button.alpha = 1f
            button.setImageResource(R.drawable.ic_ping_bolt)
            button.contentDescription = context.getString(R.string.awg_ping_start_desc)
        }
    }

    private fun startListening() {
        if (listening) return
        observed?.addOnListChangedCallback(listCallback)
        listening = true
        relisten()
    }

    private fun stopListening() {
        observed?.removeOnListChangedCallback(listCallback)
        for (t in listened) t.removeOnPropertyChangedCallback(tunnelCallback)
        listened.clear()
        listening = false
    }

    private fun relisten() {
        for (t in listened) t.removeOnPropertyChangedCallback(tunnelCallback)
        listened.clear()
        if (listening) {
            observed?.let { list ->
                for (t in list) {
                    t.addOnPropertyChangedCallback(tunnelCallback)
                    listened.add(t)
                }
            }
        }
        refresh()
    }

    private fun refresh() {
        val title = titleView ?: return
        val active = observed?.firstOrNull { it.state == Tunnel.State.UP }
        val name = active?.key

        badgeView?.setText(
            when (active?.connectionStatus) {
                ObservableTunnel.ConnectionStatus.CONNECTED -> R.string.tunnel_status_connected
                ObservableTunnel.ConnectionStatus.CONNECTING -> R.string.tunnel_status_connecting
                else -> R.string.awg_hero_badge
            }
        )

        if (!initialized || name != shownName) {
            val first = !initialized
            initialized = true
            shownName = name
            title.text = name ?: context.getString(R.string.awg_hero_title)
            if (!first && title.height > 0) {
                title.pivotX = title.paddingLeft.toFloat()
                title.pivotY = title.height / 2f
                FlyIn.play(title, totalMs = 1500L, scaleFrom = 1.7f, rotationFrom = 50f, dropDp = 18f, blurDp = 12f)
            }
        }

        if (active != null) startPulse() else stopPulse()
    }

    private fun startPulse() {
        val shield = shieldView ?: return
        if (pulse?.isRunning == true) return
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            shield,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.14f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.14f)
        ).apply {
            duration = 1100L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        shieldView?.let {
            it.scaleX = 1f
            it.scaleY = 1f
        }
    }
}
