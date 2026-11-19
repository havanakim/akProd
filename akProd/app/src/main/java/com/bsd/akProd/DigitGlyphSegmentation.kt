package com.bsd.akProd

import kotlin.math.abs

/** Splits touching bitmap digits only at supported projection valleys, with the OCR digit count unchanged. */
internal object DigitGlyphSegmentation {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private data class Component(val pixels: List<Int>, val box: Box, val edge: Boolean)

    fun split(width: Int, height: Int, ink: BooleanArray, characters: List<Char>): List<Box>? {
        if (characters.isEmpty() || characters.size > 16) return null
        // Preserve separated segment strokes before component cleanup removes short bars.
        val projected = mutableListOf<Box>()
        var column = 0
        while (column < width) {
            if ((0 until height).none { ink[it * width + column] }) {
                column++; continue
            }
            val left = column
            while (column < width && (0 until height).any { ink[it * width + column] }) column++
            var top = height;
            var bottom = 0
            for (x in left until column) for (y in 0 until height) if (ink[y * width + x]) {
                top = minOf(top, y); bottom = maxOf(bottom, y + 1)
            }
            projected.add(Box(left, top, column, bottom))
        }
        val maxHeight = projected.maxOfOrNull { it.bottom - it.top } ?: return null
        val separated = projected.filter { it.bottom - it.top >= maxHeight * .65 }
        if (separated.size == characters.size) return separated
        val seen = BooleanArray(ink.size);
        val queue = IntArray(ink.size)
        val components = mutableListOf<Component>()
        for (seed in ink.indices) {
            if (!ink[seed] || seen[seed]) continue
            var head = 0;
            var tail = 1; queue[0] = seed; seen[seed] = true
            var l = width;
            var t = height;
            var r = 0;
            var b = 0;
            var edge = false
            while (head < tail) {
                val index = queue[head++];
                val x = index % width;
                val y = index / width
                l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x + 1); b = maxOf(b, y + 1)
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) edge = true
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx;
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (ink[next] && !seen[next]) {
                        seen[next] = true; queue[tail++] = next
                    }
                }
            }
            components.add(Component(queue.take(tail), Box(l, t, r, b), edge))
        }
        val minArea = maxOf(2, ink.size / 2000)
        val eligible = components.filter { c ->
            val w = c.box.right - c.box.left;
            val h = c.box.bottom - c.box.top
            c.pixels.size >= minArea && !(c.edge && (w > h * 8 && h < height * .15 || h > w * 8 && w < width * .04))
        }
        val tallest = eligible.maxOfOrNull { it.box.bottom - it.box.top } ?: return null
        // Short components are decimal points/noise, not extra digit slots. No closing is applied.
        val keep = eligible.filter { it.box.bottom - it.box.top >= tallest * .45 }
        ink.fill(false); keep.forEach { c -> c.pixels.forEach { ink[it] = true } }
        val count = IntArray(width);
        val top = IntArray(width) { height };
        val bottom = IntArray(width)
        for (y in 0 until height) for (x in 0 until width) if (ink[y * width + x]) {
            count[x]++; top[x] = minOf(top[x], y); bottom[x] = maxOf(bottom[x], y + 1)
        }
        val left = count.indexOfFirst { it > 0 };
        val right = count.indexOfLast { it > 0 } + 1
        if (left < 0 || right <= left) return null
        val fullHeight = bottom.max() - top.filter { it < height }.min()
        val cuts = mutableListOf(left)
        val radius = maxOf(2, fullHeight / 8)
        for (x in left + radius until right - radius) {
            val density = count[x - 1] + count[x]
            if (density > fullHeight * 1.5) continue
            val minimum = (maxOf(1, x - radius)..minOf(
                width - 1,
                x + radius
            )).minOf { count[it - 1] + count[it] }
            if (density == minimum && (cuts.last() == left || x - cuts.last() > radius)) cuts.add(x)
        }
        cuts.add(right)
        if (cuts.size < characters.size + 1) return null
        fun box(a: Int, b: Int): Box? {
            val columns = (a until b).filter { count[it] > 0 }
            if (columns.isEmpty()) return null
            return Box(
                columns.first(),
                columns.minOf { top[it] },
                columns.last() + 1,
                columns.maxOf { bottom[it] })
        }

        val scores =
            Array(characters.size + 1) { DoubleArray(cuts.size) { Double.POSITIVE_INFINITY } }
        val parents = Array(characters.size + 1) { IntArray(cuts.size) { -1 } }
        scores[0][0] = 0.0
        for (n in 1..characters.size) for (end in 1 until cuts.size) for (start in 0 until end) {
            if (!scores[n - 1][start].isFinite()) continue
            val b = box(cuts[start], cuts[end]) ?: continue
            val h = b.bottom - b.top;
            val ratio = (b.right - b.left).toDouble() / h
            val narrow = characters[n - 1] == '1'
            if (h < fullHeight * .55 || ratio !in (if (narrow) .08 else .20)..1.10) continue
            val boundary =
                if (end == cuts.lastIndex) 0.0 else (count[cuts[end] - 1] + count[cuts[end]]).toDouble() / fullHeight
            val score =
                scores[n - 1][start] + abs(ratio - (if (narrow) .30 else .66)) + boundary * 2
            if (score < scores[n][end]) {
                scores[n][end] = score; parents[n][end] = start
            }
        }
        var end = cuts.lastIndex
        if (!scores[characters.size][end].isFinite()) return null
        val boxes = mutableListOf<Box>()
        for (n in characters.size downTo 1) {
            val start = parents[n][end]
            if (start < 0) return null
            boxes.add(box(cuts[start], cuts[end]) ?: return null); end = start
        }
        return boxes.reversed()
    }
}
