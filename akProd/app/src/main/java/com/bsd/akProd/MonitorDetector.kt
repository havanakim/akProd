package com.bsd.akProd

import kotlin.math.max

/** Finds a dark MIRACON monitor containing amber display text; never defaults to a fixed photo position. */
object MonitorDetector {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun detect(width: Int, height: Int, pixels: IntArray): Bounds? {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val queue = IntArray(pixels.size)
        var best: Bounds? = null
        var bestScore = 0.0
        for (threshold in intArrayOf(45, 65, 85)) {
            val dark = BooleanArray(pixels.size) { index ->
                val color = pixels[index]
                val r = color shr 16 and 255;
                val g = color shr 8 and 255;
                val b = color and 255
                (r * 299 + g * 587 + b * 114) / 1000 < threshold
            }
            for (seed in pixels.indices) {
                if (!dark[seed]) continue
                var head = 0;
                var tail = 1
                queue[0] = seed; dark[seed] = false
                var left = width;
                var right = 0;
                var top = height;
                var bottom = 0
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % width;
                    val y = index / width
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                    fun visit(next: Int) {
                        if (dark[next]) {
                            dark[next] = false; queue[tail++] = next
                        }
                    }
                    if (x > 0) visit(index - 1)
                    if (x + 1 < width) visit(index + 1)
                    if (y > 0) visit(index - width)
                    if (y + 1 < height) visit(index + width)
                }
                val w = right - left + 1;
                val h = bottom - top + 1
                val area = w * h
                val ratio = w.toFloat() / h
                if (w < width * .18 || h < height * .12 || ratio !in 1.05f..2.4f ||
                    area < pixels.size * .035 || tail < area * .55
                ) continue
                var amber = 0
                var rows = 0
                for (y in top..bottom) {
                    var rowCount = 0
                    for (x in left..right) {
                        val c = pixels[y * width + x]
                        val r = c shr 16 and 255;
                        val g = c shr 8 and 255;
                        val b = c and 255
                        if (r > 85 && g > 45 && r > g * 1.05 && g > b * 1.25) rowCount++
                    }
                    amber += rowCount
                    if (rowCount >= 3) rows++
                }
                if (amber < max(12, area / 2000) || rows < 5) continue
                val score =
                    area.toDouble() * tail / area * (1 + minOf(amber.toDouble() / area, .08))
                if (score > bestScore) {
                    bestScore = score
                    best = Bounds(
                        left.toFloat() / width, top.toFloat() / height,
                        (right + 1f) / width, (bottom + 1f) / height
                    )
                }
            }
        }
        return best
    }
}
