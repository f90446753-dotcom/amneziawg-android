package org.amnezia.awg.util

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.random.Random
import org.amnezia.awg.R

/**
 * Animated intro: the brand name flies in letter by letter from depth (blur -> sharp),
 * then the whole intro fades and the VPN screen flies in underneath. Tap to skip.
 */
object SplashOverlay {
    fun show(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        // Extra transparent room around animated views so the blur is never cut into a box.
        val pad = dp(36)
        fun roomFor(v: View) = v.setPadding(pad, pad, pad, pad)
        fun wrapParams(w: Int = LinearLayout.LayoutParams.WRAP_CONTENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams {
            val lp = LinearLayout.LayoutParams(w, h)
            lp.setMargins(-pad, -pad, -pad, -pad)
            return lp
        }

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
        roomFor(logo)
        column.addView(logo, wrapParams(dp(84) + 2 * pad, dp(84) + 2 * pad))

        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val letters = ArrayList<TextView>()
        for (ch in activity.getString(R.string.brand_name)) {
            val tv = TextView(activity)
            tv.text = if (ch == ' ') "\u00a0" else ch.toString()
            tv.setTextColor(0xFFFFFFFF.toInt())
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            tv.typeface = Typeface.SERIF
            tv.letterSpacing = 0.01f
            roomFor(tv)
            row.addView(tv, wrapParams())
            letters.add(tv)
        }
        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rowParams.topMargin = dp(26)
        row.clipChildren = false
        column.addView(row, rowParams)

        val sub = TextView(activity)
        sub.text = activity.getString(R.string.awg_hero_badge)
        sub.setTextColor(0xCCFFFFFF.toInt())
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        sub.letterSpacing = 0.2f
        roomFor(sub)
        val subParams = wrapParams()
        subParams.topMargin = dp(8) - pad
        column.addView(sub, subParams)
        column.clipChildren = false
        overlay.clipChildren = false

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

        val handler = Handler(Looper.getMainLooper())
        var dismissed = false
        fun dismiss() {
            if (dismissed) return
            dismissed = true
            handler.removeCallbacksAndMessages(null)
            val content = activity.findViewById<View>(android.R.id.content)
            if (content != null) {
                FlyIn.play(content, totalMs = 1500L, scaleFrom = 1.12f, rotationFrom = 12f, dropDp = 20f, blurDp = 14f)
            }
            overlay.animate().alpha(0f).setDuration(650).withEndAction {
                decor.removeView(overlay)
            }.start()
        }
        overlay.setOnClickListener { dismiss() }

        // Logo first, then the letters one after another, then the small caption.
        FlyIn.play(logo, delayMs = 0L, totalMs = 2200L, scaleFrom = 2.2f, rotationFrom = 55f, dropDp = 30f, blurDp = 16f)
        var t = 300L
        for (letter in letters) {
            FlyIn.play(letter, delayMs = t)
            t += 60L + Random.nextInt(45)
        }
        FlyIn.play(sub, delayMs = t, totalMs = 1600L, scaleFrom = 1.6f, rotationFrom = 40f, dropDp = 12f, blurDp = 8f)
        handler.postDelayed({ dismiss() }, t + 1500L)
    }
}
