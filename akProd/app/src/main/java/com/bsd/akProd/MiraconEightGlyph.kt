package com.bsd.akProd

/**
 * Real 8 versus slashed 0 on the HYUNDAI MIRACON-I LCD font (bright digits on a dark screen).
 *
 * The slashed 0 has one diagonal stroke from lower-left to upper-right, so its counter-space is dark only at
 * upper-left and lower-right. An 8 has a horizontal middle bar and two stacked counters, so upper-left and
 * upper-right are dark alike, lower-left and lower-right alike, and the middle band is lit. Each glyph is
 * measured by the brightness of those inner regions, normalised between the screen and the ink, so no
 * single binarisation threshold decides it.
 */
object MiraconEightGlyph {
    /** Inner-region brightness of one digit glyph, each 0 (screen) .. 1 (ink). */
    data class Shape(val upperLeft: Float, val upperRight: Float, val lowerLeft: Float, val lowerRight: Float, val middle: Float) {
        /** Slash evidence: the off-diagonal corners lit, the on-diagonal corners dark. */
        val diagonal get() = (upperRight + lowerLeft) - (upperLeft + lowerRight)
        /** Middle-bar evidence: the middle band against the four counters. */
        val bar get() = middle - (upperLeft + upperRight + lowerLeft + lowerRight) / 4
        /** No slash (misread slashed 0s measure 0.55 and up) and a lit middle. */
        val eight get() = diagonal <= MAX_DIAGONAL && bar >= 0f
    }

    internal const val MAX_DIAGONAL = .40f
    /** The cell's own slashed 0s must show their slash at least this much… */
    internal const val MIN_ZERO_DIAGONAL = .25f
    /** …and an accepted 8 must measure at least this much less slashed than every one of them. */
    internal const val ZERO_MARGIN = .15f

    /**
     * Positions in [text] (as read, e.g. "080.0") whose 8 is a real 8 by its shape. Null when the cell's glyphs
     * cannot be matched one-to-one with the digits of [text]; then nothing is verified.
     */
    fun realEights(width: Int, height: Int, pixels: IntArray, text: String): Set<Int>? {
        val shapes = shapes(width, height, pixels, text) ?: return null
        // Self-check: the MIRACON values are zero-padded (08, 080.0, 60.0), so a cell nearly always holds a 0
        // read as 0. Only when every such 0 measures as a clear slashed 0 are the glyph boxes trusted; a
        // misplaced box (a shifted cut of touching digits) fails here instead of passing a 0 as an 8.
        val zeros = shapes.filter { (position, _) -> text[position] == '0' }.values
        val slashed = zeros.minOfOrNull { it.diagonal } ?: return null
        if (slashed < MIN_ZERO_DIAGONAL) return null
        return shapes.filter { (position, shape) ->
            text[position] == '8' && shape.eight && shape.diagonal <= slashed - ZERO_MARGIN
        }.keys
    }

    /** An 8 measuring at least this slashed is a misread 0 (misread 0s measured 0.55 and up, real 8s 0.34 at most). */
    internal const val SLASHED_DIAGONAL = .50f

    /**
     * True when every 8 of [text] is clearly a slashed 0 by its shape (40.8 for 40.0), with the same self-check as
     * [realEights]: the cell's glyphs must split one-to-one and its own 0s must show their slash. False when unsure.
     */
    fun allEightsSlashed(width: Int, height: Int, pixels: IntArray, text: String): Boolean {
        val shapes = shapes(width, height, pixels, text) ?: return false
        val zeros = shapes.filter { (position, _) -> text[position] == '0' }.values
        if (zeros.isEmpty() || zeros.minOf { it.diagonal } < MIN_ZERO_DIAGONAL) return false
        val eights = shapes.filter { (position, _) -> text[position] == '8' }.values
        return eights.isNotEmpty() && eights.all { it.diagonal >= SLASHED_DIAGONAL }
    }

    /** Each digit position of [text] with its glyph shape; null when segmentation does not match [text]. */
    fun shapes(width: Int, height: Int, pixels: IntArray, text: String): Map<Int, Shape>? {
        val raw = text.trim()
        if (!raw.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) || width < 6 || height < 8 || pixels.size != width * height) return null
        // Yellow-on-dark LCD: red+green carry the ink.
        val gray = IntArray(pixels.size) { i -> val c = pixels[i]; ((c shr 16 and 255) + (c shr 8 and 255)) / 2 }
        val threshold = otsu(gray) ?: return null
        val ink = BooleanArray(gray.size) { gray[it] > threshold }
        val dark = gray.filter { it <= threshold }.average().toFloat()
        val lit = gray.filter { it > threshold }.average().toFloat()
        if (lit - dark < 25) return null
        val positions = raw.indices.filter { raw[it] != '.' }
        val glyphs = split(glyphs(width, height, ink), positions.size)
        if (glyphs == null) return null
        // A digit cut by the cell crop (e.g. the leading 0 of 080.0 halved, then read as 8) cannot be measured.
        if (glyphs.any { it[0] <= 0 || it[2] >= width || it[1] <= 0 || it[3] >= height }) return null
        fun level(l: Float, t: Float, r: Float, b: Float, box: IntArray): Float {
            val gw = box[2] - box[0]; val gh = box[3] - box[1]
            val x0 = box[0] + (gw * l).toInt(); val x1 = maxOf(x0 + 1, box[0] + (gw * r).toInt())
            val y0 = box[1] + (gh * t).toInt(); val y1 = maxOf(y0 + 1, box[1] + (gh * b).toInt())
            var sum = 0L; var n = 0
            for (y in y0 until minOf(y1, height)) for (x in x0 until minOf(x1, width)) { sum += gray[y * width + x]; n++ }
            return if (n == 0) 0f else ((sum.toFloat() / n - dark) / (lit - dark)).coerceIn(0f, 1f)
        }
        return positions.zip(glyphs).associate { (position, box) ->
            position to Shape(
                upperLeft = level(.22f, .18f, .48f, .40f, box), upperRight = level(.52f, .18f, .78f, .40f, box),
                lowerLeft = level(.22f, .60f, .48f, .82f, box), lowerRight = level(.52f, .60f, .78f, .82f, box),
                middle = level(.30f, .45f, .70f, .55f, box)
            )
        }
    }

    /** Digit boxes [left, top, right, bottom) left to right: ink column runs as tall as a digit (the '.' is not). */
    private fun glyphs(width: Int, height: Int, ink: BooleanArray): List<IntArray> {
        val columns = (0 until width).map { x -> (0 until height).count { y -> ink[y * width + x] } }
        val runs = mutableListOf<IntArray>()
        var start = -1
        for (x in 0..width) {
            val on = x < width && columns[x] > 0
            if (on && start < 0) start = x
            if (!on && start >= 0) {
                val rows = (0 until height).filter { y -> (start until x).any { ink[y * width + it] } }
                runs.add(intArrayOf(start, rows.first(), x, rows.last() + 1)); start = -1
            }
        }
        val tallest = runs.maxOfOrNull { it[3] - it[1] } ?: return emptyList()
        return runs.filter { it[3] - it[1] >= tallest * .7f && it[2] - it[0] >= 3 }
    }

    /**
     * Neighbouring LCD digits often touch, so one run may hold several digits. The font is fixed-pitch: a run
     * takes as many digits as its width holds at the mean pitch, and is cut into equal parts. Null unless
     * that yields exactly [digits] glyphs, each about as wide as the others.
     */
    private fun split(runs: List<IntArray>, digits: Int): List<IntArray>? {
        if (digits == 0 || runs.isEmpty()) return null
        if (runs.size == digits) return runs
        val unit = runs.sumOf { it[2] - it[0] }.toFloat() / digits
        val counts = runs.map { Math.round((it[2] - it[0]) / unit).coerceAtLeast(1) }
        if (counts.sum() != digits) return null
        if (runs.zip(counts).any { (run, count) -> ((run[2] - run[0]).toFloat() / count / unit) !in .75f..1.3f }) return null
        return runs.zip(counts).flatMap { (run, count) ->
            val width = (run[2] - run[0]).toFloat() / count
            (0 until count).map { i ->
                intArrayOf(run[0] + Math.round(width * i), run[1], run[0] + Math.round(width * (i + 1)), run[3])
            }
        }
    }

    private fun otsu(gray: IntArray): Int? {
        val histogram = IntArray(256); gray.forEach { histogram[it]++ }
        val total = gray.sumOf { it.toLong() }.toDouble()
        var count = 0; var sum = 0.0; var best = -1.0; var threshold = -1
        for (value in 0..254) {
            count += histogram[value]; sum += value.toDouble() * histogram[value]
            val rest = gray.size - count
            if (count == 0 || rest == 0) continue
            val delta = sum / count - (total - sum) / rest
            val score = count.toDouble() * rest * delta * delta
            if (score > best) { best = score; threshold = value }
        }
        return threshold.takeIf { it >= 0 }
    }
}
