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

    /** Similarity a cut-off last digit needs with the cell's own full 0 … */
    internal const val MIN_ZERO_MATCH = .80f
    /** … and its lead over every other full digit of the cell. */
    internal const val ZERO_LEAD = .15f

    /**
     * The cursor box often ends inside the last digit: OCR then reads "030." for 030.0. True when that cut-off digit
     * is a 0, judged against the cell's own glyphs in the same font: its visible part must match the left part of a
     * full 0 of [read] (e.g. the leading 0 of 030) closely, and clearly better than any other full digit (the 3).
     * [width]·[height]·[pixels] are a [normalize]d cell (lit digits on dark); [read] is the OCR text, digits then ".".
     */
    fun cutOffZero(width: Int, height: Int, pixels: IntArray, read: String): Boolean =
        cutOffZeroDetail(width, height, pixels, read).first

    /** [cutOffZero] with the reason, for the OCR log. */
    fun cutOffZeroDetail(width: Int, height: Int, pixels: IntArray, read: String): Pair<Boolean, String> =
        complete(width, height, pixels, read).let { (text, why) -> (text == read.trim() + "0") to why }

    /** Glyphs read as the same digit look at least this alike; glyphs read as different digits less. */
    internal const val SAME_DIGIT = .70f

    /**
     * The full value of a cursor reading whose last digit the box cuts off ("030." → "030.0"), or null with the reason.
     * Every digit before the point is checked against the cell's own glyphs: one read as 6/8/9 that clearly matches a
     * 0 of the cell is a slashed 0 ("930." → "030."); then glyphs read as the same digit must look alike and glyphs
     * read as different digits must not (so a 0 read as 3, "330.", is refused). Finally the cut-off digit must be a 0
     * ([cutOffZero]).
     */
    fun complete(width: Int, height: Int, pixels: IntArray, read: String): Pair<String?, String> {
        fun no(why: String) = null to why
        var digits = read.trim()
        if (!digits.matches(Regex("[0-9]+\\.")) || pixels.size != width * height) return no("형식")
        val full = digits.length - 1
        val (glyphs, ink) = segment(width, height, pixels, full)
            .let { (boxes, ink) -> (boxes ?: return no(ink.second)) to ink.first }
        // Left [columns] of glyph a against glyph b, both scaled to the same height: shared ink over all ink.
        fun similarity(a: IntArray, b: IntArray, columns: Int): Float {
            val h = minOf(a[3] - a[1], b[3] - b[1])
            var same = 0; var total = 0
            for (y in 0 until h) for (x in 0 until columns) {
                val ay = a[1] + y * (a[3] - a[1]) / h; val by = b[1] + y * (b[3] - b[1]) / h
                val p = ink[ay * width + a[0] + x]; val q = ink[by * width + b[0] + x]
                if (p || q) { total++; if (p == q) same++ }
            }
            return if (total == 0) 0f else same.toFloat() / total
        }
        val heads = glyphs.take(full)
        val narrow = heads.minOf { it[2] - it[0] }
        fun alike(i: Int, j: Int) = similarity(heads[i], heads[j], narrow)
        // A 6/8/9 that clearly matches a 0 of the cell (and no other digit) is a slashed 0.
        val chars = digits.toCharArray()
        for (i in 0 until full) if (chars[i] in "689") {
            val zeros = (0 until full).filter { it != i && digits[it] == '0' }.map { alike(i, it) }
            val others = (0 until full).filter { it != i && digits[it] != '0' && digits[it] !in "689" }.map { alike(i, it) }
            if ((zeros.maxOrNull() ?: 0f) >= MIN_ZERO_MATCH && (zeros.maxOrNull() ?: 0f) - (others.maxOrNull() ?: 0f) >= ZERO_LEAD)
                chars[i] = '0'
        }
        digits = String(chars)
        if ('0' !in digits.dropLast(1) || digits.any { it in "689" }) return no("0 기준 글자 없음 또는 6/8/9 남음 ($digits)")
        for (i in 0 until full) for (j in i + 1 until full) {
            val s = alike(i, j)
            if ((digits[i] == digits[j]) != (s >= SAME_DIGIT)) return no("글자 불일치 ${digits[i]}/${digits[j]} %.2f".format(s))
        }
        val cut = glyphs.last()
        val fullWidths = heads.map { it[2] - it[0] }
        // Compared over the columns the cut-off digit shows (at most a full digit's width).
        val cutWidth = minOf(cut[2] - cut[0], fullWidths.min())
        if (cutWidth < fullWidths.average() * .45f || cut[2] - cut[0] > fullWidths.max() * 1.3f)
            return no("잘린 폭 ${cut[2] - cut[0]} / $fullWidths")
        val zero = heads.withIndex().filter { digits[it.index] == '0' }.maxOf { similarity(it.value, cut, cutWidth) }
        val other = heads.withIndex().filter { digits[it.index] != '0' }.maxOfOrNull { similarity(it.value, cut, cutWidth) } ?: 0f
        val why = "0 유사도 %.2f · 다른 글자 %.2f".format(zero, other)
        return if (zero >= MIN_ZERO_MATCH && zero - other >= ZERO_LEAD) (digits + "0") to why else no(why)
    }

    /**
     * Boxes [left, top, right, bottom) of the [full] digits before the point and of the cut-off digit after it (last),
     * with the ink mask they were found on; null boxes with the reason when the cell cannot be split that way.
     */
    internal fun segment(width: Int, height: Int, pixels: IntArray, full: Int): Pair<List<IntArray>?, Pair<BooleanArray, String>> {
        val g = IntArray(pixels.size) { pixels[it] and 255 }
        val threshold = (g.maxOrNull() ?: 0) / 2
        val lit = BooleanArray(g.size) { g[it] > threshold }
        // Rows lit across most of the width are the box border, not digits.
        for (y in 0 until height) if ((0 until width).count { lit[y * width + it] } > width * .6f)
            for (x in 0 until width) lit[y * width + x] = false
        // A digit stroke is a few pixels thick; a lone speck of the LCD grid has at most one lit neighbour.
        val ink = BooleanArray(g.size) { i ->
            val x = i % width; val y = i / width
            lit[i] && (-1..1).sumOf { dy -> (-1..1).count { dx ->
                (dx != 0 || dy != 0) && x + dx in 0 until width && y + dy in 0 until height && lit[(y + dy) * width + x + dx]
            } } >= 2
        }
        // Keep only blobs shaped like digit strokes: leftovers of the box border are lines one or two pixels thick,
        // and the decimal point is far shorter than a digit.
        val seen = BooleanArray(ink.size); val queue = IntArray(ink.size)
        val blobs = mutableListOf<Pair<IntArray, IntArray>>()
        for (seed in ink.indices) {
            if (!ink[seed] || seen[seed]) continue
            var head = 0; var tail = 1; queue[0] = seed; seen[seed] = true
            var l = width; var t = height; var r = 0; var b = 0
            while (head < tail) {
                val i = queue[head++]; val x = i % width; val y = i / width
                l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val n = ny * width + nx
                    if (ink[n] && !seen[n]) { seen[n] = true; queue[tail++] = n }
                }
            }
            blobs.add(intArrayOf(l, t, r + 1, b + 1) to queue.copyOf(tail))
        }
        // Border remnants (straight lines, or an L of two) fill almost none of their bounding box; digit strokes do.
        val (lines, shapes) = blobs.partition { (box, pixelsOf) ->
            val w = box[2] - box[0]; val h = box[3] - box[1]
            w <= 2 || h <= 2 || pixelsOf.size < w * h * .15f
        }
        val tallestBlob = shapes.maxOfOrNull { it.first[3] - it.first[1] } ?: 0
        (lines + shapes.filter { (box, _) -> box[3] - box[1] < tallestBlob * .5f })
            .forEach { (_, pixelsOf) -> pixelsOf.forEach { ink[it] = false } }
        // Column runs of ink, each with its rows; digits are tall, the decimal point is not.
        val minInk = 1
        val runs = mutableListOf<IntArray>()
        var start = -1
        for (x in 0..width) {
            val on = x < width && (0 until height).count { ink[it * width + x] } >= minInk
            if (on && start < 0) start = x
            if (!on && start >= 0) {
                val rows = (0 until height).filter { y -> (start until x).any { ink[y * width + it] } }
                runs.add(intArrayOf(start, rows.first(), x, rows.last() + 1)); start = -1
            }
        }
        fun no(why: String) = null to (ink to why)
        val tallest = runs.maxOfOrNull { it[3] - it[1] } ?: return no("글자 없음")
        val tall = runs.filter { it[3] - it[1] >= tallest * .7f }
        if (tall.size < 2 || full < 1) return no("글자 ${tall.size}개")
        // The point and the cut-off digit after it can touch: the digit is the right-end block of columns with ink
        // in the upper part of the row (the point has ink only at the bottom).
        val last = tall.last()
        val upper = last[1] + (last[3] - last[1]) * .4f
        val upperCols = (last[0] until last[2]).filter { x -> (last[1] until upper.toInt()).any { ink[it * width + x] } }
        if (upperCols.isEmpty()) return no("잘린 글자 없음")
        var cutLeft = upperCols.last()
        while (cutLeft - 1 in upperCols) cutLeft--
        val cut = intArrayOf(cutLeft, last[1], last[2], last[3])
        // Digits before the point may touch (03 as one run): the font is fixed-pitch, so a run takes as many digits
        // as its width holds at the mean pitch and is cut into equal parts.
        val before = tall.dropLast(1)
        val unit = before.sumOf { it[2] - it[0] }.toFloat() / full
        val counts = before.map { Math.round((it[2] - it[0]) / unit).coerceAtLeast(1) }
        if (counts.sum() != full) return no("글자 나누기 ${before.joinToString { "${it[0]}-${it[2]}" }}")
        val boxes = before.zip(counts).flatMap { (run, count) ->
            val step = (run[2] - run[0]).toFloat() / count
            (0 until count).map { i -> intArrayOf(run[0] + Math.round(step * i), run[1], run[0] + Math.round(step * (i + 1)), run[3]) }
        } + listOf(cut)
        return boxes to (ink to "")
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
