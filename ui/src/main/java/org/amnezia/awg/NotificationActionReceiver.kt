package org.amnezia.awg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.util.ConnectionNotifier

/** Handles the "Disconnect" button of the connection notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConnectionNotifier.ACTION_DISCONNECT) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                for (t in Application.getTunnelManager().getTunnels()) {
                    if (t.state == Tunnel.State.UP) t.setStateAsync(Tunnel.State.DOWN)
                }
            } catch (e: Throwable) {
                Log.e("AWG", "Disconnect from notification failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
