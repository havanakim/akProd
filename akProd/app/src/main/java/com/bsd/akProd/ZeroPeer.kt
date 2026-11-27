package com.bsd.akProd

/**
 * An HP 000 cell the source-pixel zero check cannot settle alone (glare on one glyph) compared with another cell of
 * the same row that it did verify as 000 (same photo, same LCD font): every glyph must match that verified 0 closely.
 * A cell with any other digit (HP3 043) does not.
 */
object ZeroPeer {
    /** Similarity every glyph needs with the verified 0 … */
    internal const val MIN_MATCH = .78f

    /**
     * True when the first [digits] glyphs of the [cell] crop all match a glyph of the [verified] crop (a cell verified
     * as all zeros); with the reason for the OCR log. Crops are ARGB pixels of lit LCD digits on the dark screen.
     */
    fun allZeroLike(cell: Triple<Int, Int, IntArray>, verified: Triple<Int, Int, IntArray>, digits: Int = 3): Pair<Boolean, String> {
        val a = glyphs(cell, digits) ?: return false to "글자 나누기 실패"
        val b = glyphs(verified, digits) ?: return false to "기준 칸 글자 나누기 실패"
        val scores = a.map { g -> b.maxOf { similarity(g, it) } }
        val text = scores.joinToString(" ") { "%.2f".format(it) }
        return scores.all { it >= MIN_MATCH } to "0 유사도 $text"
    }

    private const val GRID_W = 10
    private const val GRID_H = 16

    /** Each glyph resampled to a [GRID_W]×[GRID_H] ink grid. */
    private fun glyphs(crop: Triple<Int, Int, IntArray>, digits: Int): List<BooleanArray>? {
        val (w, h, px) = crop
        if (w < 6 || h < 8 || px.size != w * h) return null
        val g = IntArray(px.size) { ((px[it] shr 16 and 255) + (px[it] shr 8 and 255)) / 2 }
        // Otsu between screen and ink.
        val hist = IntArray(256); g.forEach { hist[it]++ }
        val total = g.sumOf { it.toLong() }.toDouble()
        var count = 0; var sum = 0.0; var best = -1.0; var threshold = 127
        for (v in 0..254) {
            count += hist[v]; sum += v.toDouble() * hist[v]
            val rest = g.size - count
            if (count == 0 || rest == 0) continue
            val d = sum / count - (total - sum) / rest
            val s = count.toDouble() * rest * d * d
            if (s > best) { best = s; threshold = v }
        }
        val ink = BooleanArray(g.size) { g[it] > threshold }
        // The value row: the band of rows holding the most ink (a header remnant above or a line below is dropped).
        val rowInk = IntArray(h) { y -> (0 until w).count { ink[y * w + it] } }
        val rows = (0 until h).filter { rowInk[it] > 0 }
        if (rows.isEmpty()) return null
        val bands = mutableListOf<IntRange>(); var start = rows.first(); var last = start
        for (y in rows.drop(1)) { if (y - last > 1) { bands.add(start..last); start = y }; last = y }
        bands.add(start..last)
        val band = bands.maxBy { r -> r.sumOf { rowInk[it] } }
        if (band.last - band.first < h * .2f) return null
        // Column runs inside the band; tall ones are digit strokes.
        val runs = mutableListOf<IntArray>(); var from = -1
        for (x in 0..w) {
            val on = x < w && band.any { ink[it * w + x] }
            if (on && from < 0) from = x
            if (!on && from >= 0) { runs.add(intArrayOf(from, x)); from = -1 }
        }
        val bandHeight = band.last - band.first + 1
        // The value is the first group of runs: its digits are a stroke apart, the unit after it (Kg/cm²) a few
        // characters away.
        val firstGroup = runs.filter { it[1] - it[0] >= 2 }.let { list ->
            val end = list.indices.drop(1).firstOrNull { list[it][0] - list[it - 1][1] > bandHeight * .4f } ?: list.size
            list.take(end)
        }
        runs.clear(); runs.addAll(firstGroup)
        // Touching digits (000 as one run) are cut at the font's pitch: a digit is about .6–.8 of its height wide.
        // The pitch is taken from the leftmost runs only: the value comes first, a unit (Kg/cm²) after it.
        val unit = runs.map { it[1] - it[0] }.filter { it in (bandHeight * .45f).toInt()..(bandHeight * .9f).toInt() }
            .take(digits).sorted().let { if (it.isEmpty()) bandHeight * .68f else it[it.size / 2].toFloat() }
        val boxes = runs.filter { it[1] - it[0] >= unit * .45f }.flatMap { run ->
            val n = Math.round((run[1] - run[0]) / unit).coerceAtLeast(1)
            val step = (run[1] - run[0]).toFloat() / n
            (0 until n).map { i -> intArrayOf(run[0] + Math.round(step * i), run[0] + Math.round(step * (i + 1))) }
        }.take(digits)
        if (boxes.size < digits) return null
        return boxes.map { (l, r) ->
            BooleanArray(GRID_W * GRID_H) { i ->
                val gx = i % GRID_W; val gy = i / GRID_W
                val x0 = l + gx * (r - l) / GRID_W; val x1 = maxOf(x0 + 1, l + (gx + 1) * (r - l) / GRID_W)
                val y0 = band.first + gy * bandHeight / GRID_H; val y1 = maxOf(y0 + 1, band.first + (gy + 1) * bandHeight / GRID_H)
                var lit = 0; var all = 0
                for (y in y0 until minOf(y1, h)) for (x in x0 until minOf(x1, w)) { all++; if (ink[y * w + x]) lit++ }
                all > 0 && lit * 2 >= all
            }
        }
    }

    /** Shared ink over all ink of two glyph grids. */
    private fun similarity(a: BooleanArray, b: BooleanArray): Float {
        var same = 0; var total = 0
        for (i in a.indices) if (a[i] || b[i]) { total++; if (a[i] == b[i]) same++ }
        return if (total == 0) 0f else same.toFloat() / total
    }
}
