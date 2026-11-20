package com.bsd.akProd

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** The 조회 condition of every history screen: both dates on one line, then a row of compact buttons. */
internal object HistoryFilterBar {
    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun Context.box(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(6).toFloat(); setColor(fill); setStroke(dp(1), stroke)
    }

    /** "기간 [시작일] ~ [종료일]" on one line; returns the start and end fields. */
    fun dates(context: Context, parent: LinearLayout, from: String, to: String): Pair<EditText, EditText> = with(context) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        row.addView(TextView(this).apply { text = "기간"; textSize = 14f; setTypeface(typeface, Typeface.BOLD) },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        fun field(value: String) = EditText(this).apply {
            setSingleLine(); setText(value); hint = "yyyy-MM-dd"; textSize = 15f
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
            gravity = Gravity.CENTER; background = box(0xFFFFFFFF.toInt(), 0xFFC5CDD8.toInt())
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val start = field(from); val end = field(to)
        row.addView(start, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply { text = "~"; textSize = 16f; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(24), -2))
        row.addView(end, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row)
        start to end
    }

    /** Equal-width compact buttons on one line; the first one is the primary action. */
    fun buttons(context: Context, parent: LinearLayout, items: List<Pair<String, () -> Unit>>, idle: () -> Boolean) = with(context) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(6)) }
        items.forEachIndexed { index, (title, action) ->
            val primary = index == 0
            row.addView(Button(this).apply {
                text = title; isAllCaps = false; textSize = 14f
                minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
                setPadding(dp(4), dp(8), dp(4), dp(8)); stateListAnimator = null
                setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF2B3A4F.toInt())
                background = if (primary) box(0xFF2F6FDE.toInt(), 0xFF2F6FDE.toInt()) else box(0xFFF2F4F7.toInt(), 0xFFC5CDD8.toInt())
                setOnClickListener { if (idle()) action() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        parent.addView(row)
    }
}
