package org.amnezia.awg.util

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.amnezia.awg.R

/**
 * Short animated intro that shows the brand name on top of the whole window,
 * then fades away and reveals the VPN screen underneath.
 */
object SplashOverlay {
    private const val HOLD_MS = 1700L

    fun show(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val overlay = FrameLayout(activity)
        overlay.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                ContextCompat.getColor(activity, R.color.brand_start),
                ContextCompat.getColor(activity, R.color.brand_mid),
                ContextCompat.getColor(activity, R.color.brand_end)
            )
        )
        overlay.isClickable = true

        val column = LinearLayout(activity)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL

        val logo = ImageView(activity)
        logo.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_hero_shield))
        logo.alpha = 0f
        logo.scaleX = 0.5f
        logo.scaleY = 0.5f
        column.addView(logo, LinearLayout.LayoutParams(dp(88), dp(88)))

        val name = TextView(activity)
        name.text = activity.getString(R.string.brand_name)
        name.setTextColor(0xFFFFFFFF.toInt())
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        name.typeface = Typeface.DEFAULT_BOLD
        name.letterSpacing = 0.04f
        name.gravity = Gravity.CENTER
        name.alpha = 0f
        name.translationY = dp(16).toFloat()
        val nameParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        nameParams.topMargin = dp(22)
        column.addView(name, nameParams)

        val sub = TextView(activity)
        sub.text = activity.getString(R.string.awg_hero_badge)
        sub.setTextColor(0xCCFFFFFF.toInt())
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        sub.letterSpacing = 0.2f
        sub.alpha = 0f
        val subParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        subParams.topMargin = dp(6)
        column.addView(sub, subParams)

        overlay.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        decor.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(650).setInterpolator(OvershootInterpolator()).start()
        name.animate().alpha(1f).translationY(0f)
            .setStartDelay(250).setDuration(550).start()
        sub.animate().alpha(1f)
            .setStartDelay(550).setDuration(450).start()

        Handler(Looper.getMainLooper()).postDelayed({
            overlay.animate().alpha(0f).setDuration(380).withEndAction {
                decor.removeView(overlay)
            }.start()
        }, HOLD_MS)
    }
}
