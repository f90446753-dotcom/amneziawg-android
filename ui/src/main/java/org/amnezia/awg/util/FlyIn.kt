package org.amnezia.awg.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * "Fly in from depth" effect: the view starts large, blurred, transparent and tilted,
 * then glides onto the screen and settles (fast start, long gentle landing).
 */
object FlyIn {
    private val EXPO = PathInterpolator(0.19f, 1f, 0.22f, 1f)
    private val FADE = PathInterpolator(0.3f, 0.6f, 0.4f, 1f)

    fun play(
        view: View,
        delayMs: Long = 0L,
        totalMs: Long = 2800L,
        scaleFrom: Float = 2.4f,
        rotationFrom: Float = 62f,
        dropDp: Float = 30f,
        blurDp: Float = 14f
    ) {
        val density = view.resources.displayMetrics.density
        val drop = dropDp * density
        val blur = blurDp * density
        view.cameraDistance = 2400f * density

        fun update(t: Float) {
            val e = EXPO.getInterpolation(t)
            val alphaT = FADE.getInterpolation(minOf(1f, t * totalMs / 1300f))
            val blurT = EXPO.getInterpolation(minOf(1f, t * totalMs / 2200f))
            view.alpha = alphaT
            val s = scaleFrom + (1f - scaleFrom) * e
            view.scaleX = s
            view.scaleY = s
            view.rotationX = rotationFrom * (1f - e)
            view.translationY = -drop * (1f - e)
            setBlur(view, blur * (1f - blurT))
        }

        update(0f)
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = totalMs
        animator.startDelay = delayMs
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener { update(it.animatedFraction) }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                update(1f)
                setBlur(view, 0f)
            }
        })
        animator.start()
    }

    private fun setBlur(view: View, px: Float) {
        if (Build.VERSION.SDK_INT >= 31) {
            view.setRenderEffect(
                if (px > 0.5f) RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP) else null
            )
        }
    }
}
