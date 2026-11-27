package com.bsd.akProd

/**
 * The HYUNDAI LCD draws the field under the cursor inverted: dark digits on a lit (yellow) box, e.g. IHT 030.0 on
 * the MIRACON-I 사출 및 보압 screen. Every reader expects lit digits on the dark screen, so such a cell is cut
 * to its lit box and inverted; afterwards it reads like any other cell.
 */
object CursorCell {
    /** Lit box that fills at least this share of the cell crop's area. */
    private const val MIN_BOX_SHARE = .2f

    /** Brightness used throughout: red + green carry the yellow LCD ink. */
    private fun gray(c: Int) = ((c shr 16 and 255) + (c shr 8 and 255)) / 2

    /**
     * The lit box of a cursor cell as [left, top, right, bottom) in the crop, or null when the crop holds no such box
     * (an ordinary cell: thin lit strokes cover far less of any row than a box does).
     */
    fun box(width: Int, height: Int, pixels: IntArray): IntArray? {
        if (width < 8 || height < 8 || pixels.size != width * height) return null
        val g = IntArray(pixels.size) { gray(pixels[it]) }
        val lit = (g.maxOrNull() ?: return null).let { max -> (g.minOrNull()!! + max) / 2 }
        // Rows and columns where the box (lit almost wall to wall, digit strokes aside) runs.
        val rows = (0 until height).filter { y -> (0 until width).count { g[y * width + it] > lit } > width * .45f }
        if (rows.size < height * .25f) return null
        val top = rows.first(); val bottom = rows.last() + 1
        val cols = (0 until width).filter { x -> (top until bottom).count { g[it * width + x] > lit } > (bottom - top) * .45f }
        if (cols.isEmpty()) return null
        val left = cols.first(); val right = cols.last() + 1
        if ((right - left) * (bottom - top) < width * height * MIN_BOX_SHARE || right - left < (bottom - top)) return null
        // A box holds one value of a few digits: about 1.4 to 3.6 times as wide as tall. A separator line or a
        // glare strip across the cell is far wider (seen 4.5:1 and 10:1 on CP photos).
        val aspect = (right - left).toFloat() / (bottom - top)
        if (aspect !in 1.4f..3.6f) return null
        // Inside the box most pixels are lit, yet its dark digits leave a clear share unlit; a plain lit patch has none.
        val inside = (top until bottom).sumOf { y -> (left until right).count { x -> g[y * width + x] > lit } }
        val area = (right - left) * (bottom - top)
        if (inside < area * .55f || inside > area * .92f) return null
        return intArrayOf(left, top, right, bottom)
    }

    /**
     * The cursor box of the crop cut out and inverted to lit grey digits on black, with a dark border of [margin]
     * pixels so no digit touches the edge; null for an ordinary cell. Returns (width, height, ARGB pixels).
     */
    fun normalize(width: Int, height: Int, pixels: IntArray, margin: Int = 4): Triple<Int, Int, IntArray>? {
        val (l, t, r, b) = box(width, height, pixels)?.toList() ?: return null
        // Inverted box: the lit background becomes dark, the dark digits lit. Stretch between the background
        // (20th percentile) and the strokes (97th) so it has the contrast of an ordinary cell.
        val inverted = (t until b).flatMap { y -> (l until r).map { x -> 255 - gray(pixels[y * width + x]) } }
        val sorted = inverted.sorted()
        val low = sorted[(sorted.size * .2f).toInt()]; val high = sorted[(sorted.size * .97f).toInt()]
        if (high - low < 20) return null
        fun stretch(v: Int) = ((v - low) * 255 / (high - low)).coerceIn(0, 255)
        val w = r - l + margin * 2; val h = b - t + margin * 2
        val out = IntArray(w * h) { 0xFF000000.toInt() }
        for (y in t until b) for (x in l until r) {
            val v = stretch(255 - gray(pixels[y * width + x]))
            out[(y - t + margin) * w + x - l + margin] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Triple(w, h, out)
    }
}
