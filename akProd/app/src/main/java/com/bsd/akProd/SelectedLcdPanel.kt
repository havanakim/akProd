package com.bsd.akProd

/** Recognizes a filled yellow selection rectangle, rather than yellow glyphs on a dark LCD. */
object SelectedLcdPanel {
    data class Image(val width: Int, val height: Int, val pixels: IntArray)

    fun normalize(w: Int, h: Int, pixels: IntArray, threshold: Int = 145): Image? {
        if (w < 16 || h < 12 || pixels.size != w * h) return null
        val yellow = BooleanArray(pixels.size) { i ->
            val c = pixels[i];
            val r = c shr 16 and 255;
            val g = c shr 8 and 255;
            val b = c and 255
            r > 170 && g > 140 && b < 180 && r + g > b * 3
        }
        val seen = BooleanArray(yellow.size);
        val queue = IntArray(yellow.size)
        var points = emptyList<Int>()
        for (seed in yellow.indices) {
            if (!yellow[seed] || seen[seed]) continue
            var head = 0;
            var tail = 1; queue[0] = seed; seen[seed] = true
            while (head < tail) {
                val i = queue[head++];
                val x = i % w;
                val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx;
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val next = ny * w + nx
                    if (yellow[next] && !seen[next]) {
                        seen[next] = true; queue[tail++] = next
                    }
                }
            }
            if (tail > points.size) points = queue.take(tail)
        }
        if (points.isEmpty()) return null
        val l = points.minOf { it % w };
        val r = points.maxOf { it % w } + 1
        val t = points.minOf { it / w };
        val b = points.maxOf { it / w } + 1
        val bw = r - l;
        val bh = b - t
        if (bw < bh * 1.8 || bw * bh < w * h * .35) return null
        fun band(top: Int, bottom: Int): Double {
            var count = 0;
            var hits = 0
            for (y in top until bottom) for (x in l + 2 until r - 2) {
                count++; if (yellow[y * w + x]) hits++
            }
            return if (count == 0) 0.0 else hits.toDouble() / count
        }

        val edge = maxOf(2, bh / 9)
        val upper = band(t + 1, minOf(b, t + edge));
        val lower = band(maxOf(t, b - edge), b - 1)
        if (maxOf(upper, lower) < .94 || minOf(upper, lower) < .50) return null
        val inset = maxOf(2, bh / 14);
        val left = l + inset;
        val top = t + inset
        val width = bw - inset * 2;
        val height = bh - inset * 2
        if (width < 8 || height < 8) return null
        val pad = 4;
        val output = IntArray((width + pad * 2) * (height + pad * 2)) { -1 }
        for (y in 0 until height) for (x in 0 until width) {
            val c = pixels[(top + y) * w + left + x]
            val gray =
                ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000
            output[(y + pad) * (width + pad * 2) + x + pad] =
                if (gray < threshold) -0x1000000 else -1
        }
        return Image(width + pad * 2, height + pad * 2, output)
    }
}
