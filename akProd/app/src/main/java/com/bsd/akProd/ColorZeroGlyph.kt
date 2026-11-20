package com.bsd.akProd

/**
 * Dotted-zero check for the colour HYUNDAI HMI font (dark digits on a light cell).
 * At its middle row a zero keeps two straight dark walls around a small centre dot, while an 8
 * pinches in to its bar so both outer edges turn light; a 9 has no lower-left wall. Only an OCR
 * 8/9 is ever turned into 0, and only when every tested threshold agrees, so a real 8 or 9 stays.
 */
object ColorZeroGlyph {
    private data class Box(val left: Int, val right: Int, val top: Int, val bottom: Int)

    /** Region is normalized in [image]; a small margin keeps the full glyph walls. */
    fun correct(image: android.graphics.Bitmap, left: Float, top: Float, right: Float, bottom: Float, rawText: String): String? {
        val margin = maxOf(2, ((bottom - top) * image.height * .12f).toInt())
        val x = (left * image.width - margin).toInt().coerceIn(0, image.width - 1)
        val y = (top * image.height - margin).toInt().coerceIn(0, image.height - 1)
        val endX = (right * image.width + margin).toInt().coerceIn(x + 1, image.width)
        val endY = (bottom * image.height + margin).toInt().coerceIn(y + 1, image.height)
        val pixels = IntArray((endX - x) * (endY - y))
        image.getPixels(pixels, 0, endX - x, x, y, endX - x, endY - y)
        return correct(endX - x, endY - y, pixels, rawText)
    }

    /**
     * ML Kit often returns nothing for a cell holding a single "0". Inside the cell (borders trimmed),
     * accept it only when exactly one glyph is found and it is a verified dotted zero.
     */
    fun loneZero(image: android.graphics.Bitmap, cell: android.graphics.RectF): Boolean {
        val x = ((cell.left + cell.width() * .08f) * image.width).toInt().coerceIn(0, image.width - 1)
        val y = ((cell.top + cell.height() * .12f) * image.height).toInt().coerceIn(0, image.height - 1)
        val endX = ((cell.right - cell.width() * .08f) * image.width).toInt().coerceIn(x + 1, image.width)
        val endY = ((cell.bottom - cell.height() * .12f) * image.height).toInt().coerceIn(y + 1, image.height)
        val w = endX - x; val h = endY - y
        if (w < 6 || h < 10) return false
        val pixels = IntArray(w * h)
        image.getPixels(pixels, 0, w, x, y, w, h)
        return loneZero(w, h, pixels)
    }

    /**
     * A single small glyph in a wide striped cell would put Otsu between stripe and background, so
     * first locate the clearly dark strokes, then judge only that glyph with a margin around it.
     */
    fun loneZero(w: Int, h: Int, pixels: IntArray): Boolean {
        val (sw, sh, sub) = locateGlyph(w, h, pixels) ?: return false
        return correct(sw, sh, sub, "8") == "0"
    }

    /** The single glyph inside a cell crop, with a margin: width, height and its pixels. */
    internal fun locateGlyph(w: Int, h: Int, pixels: IntArray): Triple<Int, Int, IntArray>? {
        val gray = IntArray(pixels.size) { i ->
            val c = pixels[i]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000
        }
        val sorted = gray.sorted()
        val median = sorted[sorted.size / 2]; val darkest = sorted[sorted.size / 200]
        if (median - darkest < 30) return null
        val cut = darkest + (median - darkest) * 45 / 100
        val dark = BooleanArray(gray.size) { gray[it] <= cut }
        val borderRow = BooleanArray(h) { y -> (0 until w).count { dark[y * w + it] } > w / 2 }
        val borderCol = BooleanArray(w) { x -> (0 until h).count { dark[it * w + x] } > h * 9 / 10 }
        // The glyph is the largest dark component of glyph size; stray specks and border fragments are not.
        val ink = BooleanArray(gray.size) { dark[it] && !borderRow[it / w] && !borderCol[it % w] }
        val seen = BooleanArray(ink.size); val queue = IntArray(ink.size)
        var left = w; var right = -1; var top = h; var bottom = -1; var best = 0
        for (seed in ink.indices) {
            if (!ink[seed] || seen[seed]) continue
            var head = 0; var tail = 1; queue[0] = seed; seen[seed] = true
            var l = w; var r = -1; var t = h; var b = -1
            while (head < tail) {
                val i = queue[head++]; val px = i % w; val py = i / w
                l = minOf(l, px); r = maxOf(r, px); t = minOf(t, py); b = maxOf(b, py)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx; val ny = py + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val n = ny * w + nx
                    if (ink[n] && !seen[n]) { seen[n] = true; queue[tail++] = n }
                }
            }
            val height = b - t + 1; val width = r - l + 1
            if (height >= h / 4 && height <= h * 9 / 10 && width in 3..w / 2 && tail > best) {
                best = tail; left = l; right = r; top = t; bottom = b
            }
        }
        if (right < 0) return null
        // On a small photo a zero's thin caps break, leaving its two walls as separate components:
        // take in every stroke beside the chosen one at the same height.
        val reach = (bottom - top + 1) * 7 / 10
        val (l0, r0, t0, b0) = listOf(left, right, top, bottom)
        for (y in t0..b0) for (x in (l0 - reach).coerceAtLeast(0)..(r0 + reach).coerceAtMost(w - 1)) if (ink[y * w + x]) {
            left = minOf(left, x); right = maxOf(right, x)
        }
        val pad = maxOf(2, (bottom - top) / 5)
        val x0 = (left - pad).coerceAtLeast(0); val x1 = (right + pad).coerceAtMost(w - 1)
        val y0 = (top - pad).coerceAtLeast(0); val y1 = (bottom + pad).coerceAtMost(h - 1)
        val sw = x1 - x0 + 1; val sh = y1 - y0 + 1
        return Triple(sw, sh, IntArray(sw * sh) { i -> pixels[(y0 + i / sw) * w + x0 + i % sw] })
    }

    fun correct(w: Int, h: Int, pixels: IntArray, rawText: String): String? {
        val raw = rawText.replace(Regex("\\s"), "")
        if (!raw.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) || raw.none { it == '8' || it == '9' }) return null
        if (w < 6 || h < 10 || pixels.size != w * h) return null
        val gray = smoothIfLarge(w, h, IntArray(pixels.size) { i ->
            val c = pixels[i]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000
        })
        val otsu = otsu(gray) ?: return null
        val positions = raw.indices.filter { raw[it] != '.' }
        // Looser thresholds pull in cell borders, so only the Otsu level and two stricter ones are used.
        // Thin strokes of small (forwarded, ~25 px) digits break at stricter levels, so test a slightly looser one.
        val shifts = if (h >= 40) listOf(0, -8, -16) else listOf(0, 4)
        val verdicts = shifts.map { shift -> classify(w, h, gray, otsu + shift, positions.size) ?: return null }
        val output = raw.toCharArray(); var changed = false
        positions.forEachIndexed { index, position ->
            if (raw[position] in "89" && verdicts.all { it[index] }) { output[position] = '0'; changed = true }
        }
        return if (changed) String(output) else null
    }

    /** Diagnostic for tests: segmentation and per-glyph measurements at the Otsu threshold. */
    internal fun describe(w: Int, h: Int, pixels: IntArray, digits: Int): String {
        val gray = smoothIfLarge(w, h, IntArray(pixels.size) { i ->
            val c = pixels[i]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000
        })
        val t = otsu(gray) ?: return "no threshold"
        val out = StringBuilder()
        for (shift in listOf(-30, -20, -10, 0, 10)) {
            debug = StringBuilder()
            val result = classify(w, h, gray, t + shift, digits)
            out.append("t=${t + shift}($shift) result=$result\n$debug")
        }
        debug = null
        return out.toString()
    }
    private var debug: StringBuilder? = null

    /**
     * Dust or glare can bridge neighbouring digits into one box. The font is monospaced, so a box
     * clearly wider than a normal digit is cut into equal digit-wide parts; null if that cannot match.
     */
    private fun splitJoined(glyphs: List<Box>, ink: BooleanArray, w: Int, digits: Int): List<Box>? {
        val normal = glyphs.filter { (it.right - it.left + 1).toFloat() / (it.bottom - it.top + 1) in .35f..0.95f }
        if (normal.isEmpty()) return null
        val unit = normal.minOf { it.right - it.left + 1 } // a joined box is never the narrowest
        val parts = glyphs.flatMap { b ->
            val width = b.right - b.left + 1
            val k = Math.round(width.toFloat() / unit).coerceIn(1, digits)
            if (k == 1 || width < unit * 1.6f) listOf(b) else (0 until k).map { i ->
                val l = b.left + width * i / k; val r = b.left + width * (i + 1) / k - 1
                val ys = (b.top..b.bottom).filter { y -> (l..r).any { ink[y * w + it] } }
                Box(l, r, ys.firstOrNull() ?: b.top, ys.lastOrNull() ?: b.bottom)
            }
        }
        return parts.takeIf { it.size == digits }
    }

    /** Per digit: true when the glyph is positively a dotted zero at this threshold. */
    private fun classify(w: Int, h: Int, gray: IntArray, threshold: Int, digits: Int): List<Boolean>? {
        val dark = BooleanArray(gray.size) { gray[it] <= threshold }
        // Cell border rows run across most of the crop; they are not part of any glyph.
        val border = BooleanArray(h) { y -> (0 until w).count { dark[y * w + it] } > w / 2 }
        // Likewise vertical cell borders run through nearly every row.
        val borderColumn = BooleanArray(w) { x -> (0 until h).count { dark[it * w + x] } > h * 9 / 10 }
        val ink = BooleanArray(gray.size) { dark[it] && !border[it / w] && !borderColumn[it % w] }
        val boxes = mutableListOf<Box>(); var start = -1
        for (x in 0..w) {
            val has = x < w && (0 until h).count { ink[it * w + x] } >= 2
            if (has && start < 0) start = x
            if (!has && start >= 0) {
                val ys = (0 until h).filter { y -> (start until x).count { ink[y * w + it] } >= 1 }
                if (ys.isNotEmpty()) boxes.add(Box(start, x - 1, ys.first(), ys.last()))
                start = -1
            }
        }
        val tallest = boxes.maxOfOrNull { it.bottom - it.top + 1 } ?: return null
        var glyphs = boxes.filter { it.bottom - it.top + 1 >= tallest * .7f && it.right - it.left >= 2 }
        if (glyphs.size < digits) glyphs = splitJoined(glyphs, ink, w, digits) ?: glyphs
        debug?.append("boxes=$boxes glyphs=${glyphs.size}/$digits\n")
        if (glyphs.size != digits) return null
        val bg = gray.sorted()[gray.size * 9 / 10]
        return glyphs.map { b ->
            val bw = b.right - b.left + 1; val bh = b.bottom - b.top + 1
            fun dark(x0: Float, x1: Float, y: Int): Float {
                val xs = (b.left + (bw * x0).toInt())..(b.left + (bw * x1).toInt()).coerceAtMost(b.right)
                return xs.map { (bg - gray[y * w + it]).coerceAtLeast(0) }.average().toFloat()
            }
            fun band(x0: Float, x1: Float, y0: Float, y1: Float) =
                ((b.top + (bh * y0).toInt())..(b.top + (bh * y1).toInt()).coerceAtMost(b.bottom)).map { dark(x0, x1, it) }.average().toFloat()
            val aspect = bw.toFloat() / bh
            // The darkest centre row is the 8's bar or the zero's dot.
            val mid = ((b.top + bh * 35 / 100)..(b.top + bh * 65 / 100)).maxBy { dark(.42f, .58f, it) }
            val rows = (mid - 1..mid + 1).filter { it in b.top..b.bottom }
            fun midDark(x0: Float, x1: Float) = rows.map { dark(x0, x1, it) }.average().toFloat()
            val peak = (0 until 10).maxOf { midDark(it / 10f, (it + 1) / 10f - .01f) }.coerceAtLeast(1f)
            // An 8 pinches in at its bar so both outer edges are light there; a zero's straight walls stay dark.
            val edge = minOf(midDark(0f, .19f), midDark(.8f, 1f)) / peak
            // A 9 has no lower-left wall; a zero has one.
            val lowerLeft = band(0f, .2f, .6f, .85f) / peak
            // Glare can wipe an 8's lower half (it then looks like an open zero); require both caps intact.
            fun cap(y0: Float, y1: Float) = ((b.top + (bh * y0).toInt())..(b.top + (bh * y1).toInt()).coerceAtMost(b.bottom))
                .maxOf { dark(.3f, .7f, it) } / peak
            val topCap = cap(0f, .2f)
            val bottomCap = cap(.8f, 1f)
            debug?.append("glyph $b aspect=%.2f edge=%.2f lowerLeft=%.2f top=%.2f bottom=%.2f\n"
                .format(aspect, edge, lowerLeft, topCap, bottomCap))
            val capMin = if (bh >= 40) .45f else .35f // a blurred small cap is fainter, yet a wiped one is gone
            // Small 8s lose their waist but keep caps as dark as the blurred bar (cap/peak >= 0.9); small zeros do not.
            val small = bh < 40
            aspect in .35f..0.95f && edge >= (if (small) .6f else .5f) && lowerLeft >= .45f &&
                topCap >= capMin && bottomCap >= capMin && (!small || edge >= .75f || maxOf(topCap, bottomCap) < .9f)
        }
    }

    // The 3x3 blur only tames panel stripes at camera resolution; on a forwarded half-size photo
    // (digits ~25 px) it would also erase the 8's pinched waist.
    private fun smoothIfLarge(w: Int, h: Int, g: IntArray) = if (h >= 40) smooth(w, h, g) else g

    private fun smooth(w: Int, h: Int, g: IntArray): IntArray {
        val out = g.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var s = 0
            for (dy in -1..1) for (dx in -1..1) s += g[(y + dy) * w + x + dx]
            out[y * w + x] = s / 9
        }
        return out
    }

    private fun otsu(g: IntArray): Int? {
        val hist = IntArray(256); g.forEach { hist[it.coerceIn(0, 255)]++ }
        val sum = g.sum().toDouble(); var count = 0; var part = 0.0; var best = -1.0; var threshold = 127
        for (t in 0..254) {
            count += hist[t]; part += t.toDouble() * hist[t]
            if (count == 0 || count == g.size) continue
            val d = part / count - (sum - part) / (g.size - count)
            val score = count.toDouble() * (g.size - count) * d * d
            if (score > best) { best = score; threshold = t }
        }
        return threshold.takeIf { best > 0 }
    }
}
