package org.amnezia.awg.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import com.google.android.material.color.MaterialColors
import org.amnezia.awg.util.ConnectionNotifier
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Window background for the main screen. While a tunnel is connected, soft matte colour clouds
 * (purple, white, cream, black, dark green) slowly drift and blend over the plain background;
 * when the tunnel disconnects they fade away and the plain background comes back.
 */
class AuroraDrawable(context: Context) : Drawable() {
    private class Blob(
        val color: Int,
        val alpha: Float,
        val fx: Float, val fy: Float,
        val ax: Float, val ay: Float,
        val sx: Float, val sy: Float,
        val px: Float, val py: Float,
        val radius: Float
    ) {
        val shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                Color.argb((255 * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb((255 * alpha * 0.45f).toInt(), Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private val baseColor = MaterialColors.getColor(context, android.R.attr.colorBackground, Color.BLACK)
    private val blobs = listOf(
        Blob(0xFF6D5BFF.toInt(), 0.60f, 0.15f, 0.20f, 0.18f, 0.12f, 1f, 2f, 0.00f, 0.30f, 0.80f), // purple
        Blob(0xFF0E5A43.toInt(), 0.65f, 0.85f, 0.35f, 0.15f, 0.18f, 2f, 1f, 0.40f, 0.10f, 0.85f), // dark green
        Blob(0xFFF2E6CB.toInt(), 0.38f, 0.60f, 0.80f, 0.20f, 0.10f, 1f, 3f, 0.70f, 0.50f, 0.65f), // cream
        Blob(0xFF000000.toInt(), 0.60f, 0.20f, 0.70f, 0.14f, 0.16f, 3f, 1f, 0.20f, 0.80f, 0.75f), // black
        Blob(0xFFFFFFFF.toInt(), 0.30f, 0.80f, 0.10f, 0.12f, 0.12f, 2f, 2f, 0.90f, 0.60f, 0.55f), // white
        Blob(0xFF4338CA.toInt(), 0.50f, 0.50f, 0.50f, 0.25f, 0.20f, 1f, 1f, 0.55f, 0.15f, 0.70f)  // deep purple
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var intensity = 0f
    private var time = 0f
    private var fade: ValueAnimator? = null
    private var drift: ValueAnimator? = null
    private var attached = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val listener = Runnable {
        mainHandler.post {
            if (attached) setConnected(ConnectionNotifier.connected)
        }
    }

    fun attach() {
        if (attached) return
        attached = true
        ConnectionNotifier.addConnectionListener(listener)
        setConnected(ConnectionNotifier.connected)
    }

    fun detach() {
        attached = false
        ConnectionNotifier.removeConnectionListener(listener)
        fade?.cancel()
        drift?.cancel()
        fade = null
        drift = null
    }

    private fun setConnected(connected: Boolean) {
        val target = if (connected) 1f else 0f
        fade?.cancel()
        if (connected) startDrift()
        val animator = ValueAnimator.ofFloat(intensity, target)
        animator.duration = 1600L
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener {
            intensity = it.animatedValue as Float
            invalidateSelf()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (target == 0f && fade === animation) {
                    drift?.cancel()
                    drift = null
                }
            }
        })
        fade = animator
        animator.start()
    }

    private fun startDrift() {
        if (drift?.isRunning == true) return
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 45000L
        animator.repeatCount = ValueAnimator.INFINITE
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener {
            time = it.animatedValue as Float
            invalidateSelf()
        }
        drift = animator
        animator.start()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(baseColor)
        if (intensity <= 0.003f) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val m = max(w, h)
        paint.alpha = (255 * intensity).toInt()
        for (blob in blobs) {
            val cx = w * (blob.fx + blob.ax * sin(2.0 * PI * (time * blob.sx + blob.px)).toFloat())
            val cy = h * (blob.fy + blob.ay * sin(2.0 * PI * (time * blob.sy + blob.py)).toFloat())
            val r = m * blob.radius
            matrix.setScale(r, r)
            matrix.postTranslate(cx, cy)
            blob.shader.setLocalMatrix(matrix)
            paint.shader = blob.shader
            canvas.drawRect(b, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
