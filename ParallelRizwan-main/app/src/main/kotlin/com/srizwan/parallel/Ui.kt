package com.srizwan.parallel

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny helpers so the whole UI can be built in code (no XML, no drawable files). */
object Palette {
    val BG = Color.parseColor("#F4F6FB")
    val CARD = Color.WHITE
    val PRIMARY = Color.parseColor("#5B4BFF")
    val PRIMARY_DARK = Color.parseColor("#3A2FD6")
    val TEXT = Color.parseColor("#1D2233")
    val MUTED = Color.parseColor("#6B7285")
    val GREEN = Color.parseColor("#E3F7EA")
    val AMBER = Color.parseColor("#FFF1D6")
    val RED = Color.parseColor("#FDE4E4")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.roundBg(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}

fun Context.tv(value: String, sp: Float, color: Int = Palette.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

/** Square emoji button, e.g. "▶️", "🧹", "🗑️". */
fun Context.emojiButton(emoji: String, bg: Int, onClick: () -> Unit): TextView =
    tv(emoji, 18f).apply {
        gravity = Gravity.CENTER
        background = roundBg(bg, 12)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(6) }
        setOnClickListener { onClick() }
    }

fun TextView.oneLine() {
    setSingleLine()
    ellipsize = TextUtils.TruncateAt.END
}
