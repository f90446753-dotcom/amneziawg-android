package org.amnezia.awg.util

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real latency test: opens TCP connections to Google (8.8.8.8:443) and times them.
 * When a tunnel is up, the app's own traffic goes through it, so this measures the tunnel.
 */
object PingTest {
    private const val HOST = "8.8.8.8"
    private const val PORT = 443

    class Result(val pingMs: Int?, val replies: Int, val total: Int)

    private fun connectOnce(timeoutMs: Int): Long? {
        val socket = Socket()
        return try {
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(HOST, PORT), timeoutMs)
            (System.nanoTime() - start) / 1_000_000L
        } catch (e: Exception) {
            null
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
            }
        }
    }

    /** Waits until Google answers through the current connection (handshake can take a few seconds). */
    suspend fun waitUntilReachable(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (connectOnce(2000) != null) return@withContext true
            delay(500)
        }
        false
    }

    /** Several samples, median result. */
    suspend fun measure(samples: Int = 5): Result = withContext(Dispatchers.IO) {
        val times = ArrayList<Long>()
        repeat(samples) {
            val t = connectOnce(3000)
            if (t != null) times.add(t)
            delay(120)
        }
        if (times.isEmpty()) {
            Result(null, 0, samples)
        } else {
            times.sort()
            Result(maxOf(1L, times[times.size / 2]).toInt(), times.size, samples)
        }
    }
}
