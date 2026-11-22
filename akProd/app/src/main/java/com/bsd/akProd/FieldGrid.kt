package com.bsd.akProd

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Lays numeric input cells side by side so many fields fit on one screen.
 * Values are at most ~7 characters (e.g. 1234.5), so each cell only needs a narrow width;
 * the column count is derived from the screen width and every row shares the same column widths.
 */
class FieldGrid(
    private val context: Context,
    private val parent: LinearLayout,
    cellDp: Int = 96,
    horizontalPaddingPx: Int = 40
) {
    private val density = context.resources.displayMetrics.density
    val columns: Int = ((context.resources.displayMetrics.widthPixels - horizontalPaddingPx) /
            (cellDp * density)).toInt().coerceIn(1, 8)
    private var row: LinearLayout? = null
    private var used = 0

    /** Returns a new vertical cell with [title] on top; the caller adds the input (and checkbox) to it. */
    fun cell(title: String): LinearLayout {
        if (row == null || used >= columns) {
            row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; weightSum = columns.toFloat()
            }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2))
            used = 0
        }
        val pad = (3 * density).toInt()
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, 0, pad, pad)
        }
        cell.addView(TextView(context).apply {
            text = title; textSize = 13f; gravity = Gravity.CENTER_HORIZONTAL; isSingleLine = true
        }, LinearLayout.LayoutParams(-1, -2))
        row!!.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        used++
        return cell
    }
}
