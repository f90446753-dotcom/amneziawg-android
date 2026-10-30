package org.amnezia.awg.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import java.lang.ref.WeakReference
import org.amnezia.awg.R
import org.amnezia.awg.util.FlyIn

/**
 * The brand name shown in the top bar. Every letter is its own view so that, right after the
 * intro screen, the letters can fly in one by one (same effect as the intro).
 */
class BrandTitleView(context: Context) : LinearLayout(context) {
    private val letters = ArrayList<TextView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        val name = context.getString(R.string.brand_name)
        contentDescription = name
        val pad = (12 * resources.displayMetrics.density).toInt()
        val color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        for (ch in name) {
            val tv = TextView(context)
            tv.text = if (ch == ' ') "\u00a0" else ch.toString()
            tv.setTextColor(color)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.letterSpacing = 0.01f
            tv.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            tv.setPadding(pad, pad, pad, pad)
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.setMargins(-pad, -pad, -pad, -pad)
            addView(tv, lp)
            letters.add(tv)
        }
    }

    fun hideForIntro() {
        for (l in letters) l.alpha = 0f
    }

    fun playIntro() {
        var delay = 0L
        for (l in letters) {
            FlyIn.play(l, delayMs = delay, totalMs = 1400L, scaleFrom = 1.7f, rotationFrom = 55f, dropDp = 6f, blurDp = 7f)
            delay += 45L
        }
    }

    companion object {
        private var pending: WeakReference<BrandTitleView>? = null

        fun setPendingIntro(view: BrandTitleView) {
            pending = WeakReference(view)
        }

        fun playPendingIntro() {
            pending?.get()?.playIntro()
            pending = null
        }
    }
}
