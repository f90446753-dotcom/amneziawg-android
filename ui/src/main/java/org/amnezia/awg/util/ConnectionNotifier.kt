package org.amnezia.awg.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.databinding.Observable
import androidx.databinding.ObservableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.amnezia.awg.Application
import org.amnezia.awg.NotificationActionReceiver
import org.amnezia.awg.R
import org.amnezia.awg.activity.MainActivity
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.model.ObservableTunnel

/**
 * Persistent notification while a tunnel is up: shows the config name, live download and
 * upload speed, and has a "Disconnect" button.
 */
object ConnectionNotifier {
    const val ACTION_DISCONNECT = "org.amnezia.awg.action.NOTIFICATION_DISCONNECT"
    private const val CHANNEL_ID = "vpn_status"
    private const val NOTIFICATION_ID = 4711

    private lateinit var app: Context
    private lateinit var scope: CoroutineScope
    private var list: ObservableList<ObservableTunnel>? = null
    private val listened = ArrayList<ObservableTunnel>()
    private var loop: Job? = null
    private var loopFor: ObservableTunnel? = null

    private val tunnelCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            sync()
        }
    }

    private val listCallback = object : ObservableList.OnListChangedCallback<ObservableList<ObservableTunnel>>() {
        override fun onChanged(sender: ObservableList<ObservableTunnel>) { relisten() }
        override fun onItemRangeChanged(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) { relisten() }
        override fun onItemRangeInserted(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) { relisten() }
        override fun onItemRangeMoved(sender: ObservableList<ObservableTunnel>, fromPosition: Int, toPosition: Int, itemCount: Int) { relisten() }
        override fun onItemRangeRemoved(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) { relisten() }
    }

    fun start(context: Context, coroutineScope: CoroutineScope) {
        app = context.applicationContext
        scope = coroutineScope
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, app.getString(R.string.awg_notif_channel), NotificationManager.IMPORTANCE_LOW)
            channel.description = app.getString(R.string.awg_notif_channel_desc)
            channel.setShowBadge(false)
            app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        scope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            list = tunnels
            tunnels.addOnListChangedCallback(listCallback)
            relisten()
        }
    }

    private fun relisten() {
        for (t in listened) t.removeOnPropertyChangedCallback(tunnelCallback)
        listened.clear()
        list?.let { l ->
            for (t in l) {
                t.addOnPropertyChangedCallback(tunnelCallback)
                listened.add(t)
            }
        }
        sync()
    }

    /** Starts or stops the speed loop depending on whether a tunnel is up. */
    private fun sync() {
        val active = list?.firstOrNull { it.state == Tunnel.State.UP }
        if (active == null) {
            loop?.cancel()
            loop = null
            loopFor = null
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
            return
        }
        if (loop?.isActive == true && loopFor === active) return
        loop?.cancel()
        loopFor = active
        loop = scope.launch { run(active) }
    }

    private suspend fun run(tunnel: ObservableTunnel) {
        var lastRx = -1L
        var lastTx = -1L
        var lastTime = 0L
        while (true) {
            var rxRate = 0L
            var txRate = 0L
            val stats = try {
                tunnel.getStatisticsAsync()
            } catch (e: Exception) {
                null
            }
            val now = SystemClock.elapsedRealtime()
            if (stats != null) {
                val rx = stats.totalRx()
                val tx = stats.totalTx()
                if (lastRx >= 0 && now > lastTime) {
                    val seconds = (now - lastTime) / 1000.0
                    rxRate = maxOf(0L, ((rx - lastRx) / seconds).toLong())
                    txRate = maxOf(0L, ((tx - lastTx) / seconds).toLong())
                }
                lastRx = rx
                lastTx = tx
                lastTime = now
            }
            show(tunnel, rxRate, txRate)
            delay(1000)
        }
    }

    private fun show(tunnel: ObservableTunnel, rxRate: Long, txRate: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnect = PendingIntent.getBroadcast(
            app, 1,
            Intent(app, NotificationActionReceiver::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val connected = tunnel.connectionStatus == ObservableTunnel.ConnectionStatus.CONNECTED
        val title = app.getString(if (connected) R.string.awg_notif_connected else R.string.awg_notif_connecting) + " \u00b7 " + tunnel.key
        val text = "\u2193 " + formatRate(rxRate) + "     \u2191 " + formatRate(txRate)

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(ContextCompat.getColor(app, R.color.seed))
            .addAction(0, app.getString(R.string.awg_notif_disconnect), disconnect)
            .build()
        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
    }

    private fun formatRate(bytesPerSecond: Long): String {
        val b = bytesPerSecond.toDouble()
        return when {
            b < 1024 -> "${bytesPerSecond} B/s"
            b < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB/s", b / 1024)
            b < 1024.0 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f MB/s", b / (1024 * 1024))
            else -> String.format(java.util.Locale.US, "%.2f GB/s", b / (1024.0 * 1024 * 1024))
        }
    }
}
