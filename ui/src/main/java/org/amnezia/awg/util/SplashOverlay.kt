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
 * Animated intro, in this order:
 *   1. "design and development" (letter by letter)
 *   2. the brand name (letter by letter)
 *   3. the signature logo
 * Everything flies in from depth (blur -> sharp). Tap anywhere to skip.
 * The texts are far apart vertically so a flying letter never lands on top of another text.
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

        fun makeRow(text: String, sizeSp: Float, color: Int, spacing: Float, list: ArrayList<TextView>): LinearLayout {
            val row = LinearLayout(activity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER
            row.clipChildren = false
            for (ch in text) {
                val tv = TextView(activity)
                tv.text = if (ch == ' ') "\u00a0" else ch.toString()
                tv.setTextColor(color)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                tv.typeface = Typeface.SERIF
                tv.letterSpacing = spacing
                roomFor(tv)
                row.addView(tv, wrapParams())
                list.add(tv)
            }
            return row
        }

        // 1) tagline
        val tagLetters = ArrayList<TextView>()
        val tagRow = makeRow(activity.getString(R.string.awg_splash_tagline), 15f, 0xCCFFFFFF.toInt(), 0.12f, tagLetters)
        column.addView(tagRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 2) brand name (big gap above so the two animations never overlap)
        val nameLetters = ArrayList<TextView>()
        val nameRow = makeRow(activity.getString(R.string.brand_name), 28f, 0xFFFFFFFF.toInt(), 0.01f, nameLetters)
        val nameParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        nameParams.topMargin = dp(26)
        column.addView(nameRow, nameParams)

        // 3) signature logo
        val logo = ImageView(activity)
        logo.setImageResource(R.drawable.splash_signature)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        roomFor(logo)
        val logoParams = wrapParams(dp(100) + 2 * pad, dp(126) + 2 * pad)
        logoParams.topMargin = dp(34) - pad
        column.addView(logo, logoParams)

        column.clipChildren = false
        overlay.clipChildren = false
        val columnParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        columnParams.topMargin = dp(110) // pushes the whole group lower on the screen
        overlay.addView(column, columnParams)
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
            handler.postDelayed({ org.amnezia.awg.widget.BrandTitleView.playPendingIntro() }, 250L)
            overlay.animate().alpha(0f).setDuration(650).withEndAction {
                decor.removeView(overlay)
            }.start()
        }
        overlay.setOnClickListener { dismiss() }

        // Timeline
        var t = 250L
        // tagline: fast
        for (letter in tagLetters) {
            FlyIn.play(letter, delayMs = t, totalMs = 1300L, scaleFrom = 1.8f, rotationFrom = 55f, dropDp = 14f, blurDp = 8f)
            t += 16L
        }
        t += 220L
        // name: medium
        for (letter in nameLetters) {
            FlyIn.play(letter, delayMs = t, totalMs = 2300L, scaleFrom = 2.2f, rotationFrom = 60f, dropDp = 24f, blurDp = 12f)
            t += 55L + Random.nextInt(35)
        }
        t += 300L
        // signature logo: the star of the show (longest, biggest, blurriest start)
        FlyIn.play(logo, delayMs = t, totalMs = 3600L, scaleFrom = 2.8f, rotationFrom = 70f, dropDp = 44f, blurDp = 22f)
        handler.postDelayed({ dismiss() }, t + 2200L)
    }
}
