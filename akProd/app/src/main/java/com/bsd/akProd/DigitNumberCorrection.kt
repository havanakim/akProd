package com.bsd.akProd

/** Independent decisions prevent one ambiguous digit from discarding another digit's correction. */
object DigitNumberCorrection {
    data class Result(
        val text: String,
        val uncertainPositions: Set<Int>,
        val methods: Map<Int, String>
    )

    /** LCD scan lines can hide the diagonal counters. Require agreement at two mild source scales. */
    fun analyzeStable(
        width: Int,
        height: Int,
        pixels: IntArray,
        recognized: String,
        templates: List<ZeroFontTemplates.Glyph> = emptyList()
    ): Result {
        val original = analyze(width, height, pixels, recognized, templates)
        if (original.uncertainPositions.isEmpty() && original.text.none { it in "89" }) return original
        fun smooth(size: Int, vertical: Int = size): IntArray {
            val radius = size / 2;
            val ry = vertical / 2
            val out = pixels.copyOf()
            for (y in ry until height - ry) for (x in radius until width - radius) {
                var r = 0;
                var g = 0;
                var b = 0
                for (dy in -ry..ry) for (dx in -radius..radius) {
                    val c =
                        pixels[(y + dy) * width + x + dx]; r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255
                }
                val count = size * vertical
                out[y * width + x] =
                    (255 shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
            return out
        }

        val a = analyze(width, height, smooth(3), recognized, templates)
        val b = analyze(width, height, smooth(5), recognized, templates)
        val c = if(original.text.indices.any { it !in b.uncertainPositions && b.text[it]=='0' && (it in a.uncertainPositions || a.text[it]!='0') }) analyze(width,height,smooth(3,5),recognized,templates) else a
        val confirmed =
            original.text.indices.filter { original.text[it] in "089" && (original.text[it]!='8' || it in original.uncertainPositions) && it !in b.uncertainPositions && b.text[it]=='0' && ((it !in a.uncertainPositions && a.text[it]=='0') || (it !in c.uncertainPositions && c.text[it]=='0')) }
        val text = original.text.toCharArray(); confirmed.forEach { text[it] = '0' }
        return Result(
            String(text),
            original.uncertainPositions - confirmed.toSet(),
            original.methods + confirmed.associateWith { "source-smoothing-zero" })
    }

    fun analyze(
        width: Int, height: Int, pixels: IntArray, recognized: String,
        templates: List<ZeroFontTemplates.Glyph> = emptyList()
    ): Result {
        val raw = recognized.replace(Regex("\\s"), "")
        val targets = raw.indices.filter { raw[it] in "089" }.toSet()
        if (targets.isEmpty()) return Result(raw, emptySet(), emptyMap())
        val panels =
            listOf(145, 205).mapNotNull { SelectedLcdPanel.normalize(width, height, pixels, it) }
        if (panels.isNotEmpty()) {
            val results = panels.map { analyze(it.width, it.height, it.pixels, raw, templates) }
                .filter { it.uncertainPositions.isEmpty() }
            if (results.map { it.text }.distinct().size == 1) return results.first()
            return Result(raw, targets, emptyMap())
        }
        val segment = SevenSegmentVerifier.verify(width, height, pixels, raw)
        if (segment.digits.isEmpty()) {
            // Preserve the existing conservative fallback when projection could not align every digit.
            val corrected = ZeroFontTemplates.correct(width, height, pixels, raw, templates)
                ?: SlashedZeroDetector.correct(width, height, pixels, raw)
            if (corrected == null || corrected.length != raw.length) return Result(
                raw,
                targets,
                emptyMap()
            )
            val changed =
                raw.indices.filter { raw[it] != corrected[it] }.associateWith { "font/diagonal" }
            return Result(corrected, targets - changed.keys, changed)
        }
        val output = raw.toCharArray()
        val uncertain = targets.toMutableSet()
        val methods = linkedMapOf<Int, String>()
        segment.digits.forEach { digit ->
            if (digit.value != null) {
                output[digit.position] = digit.value
                uncertain.remove(digit.position); methods[digit.position] = "segments"
            } else {
                val w = digit.right - digit.left;
                val h = digit.bottom - digit.top
                // Keep original intensities: an Otsu bitmap has already erased small zero counters.
                val pad = maxOf(2, h / 12);
                val pw = w + pad * 2;
                val ph = h + pad * 2
                val glyph = IntArray(pw * ph) { pixels[0] }
                for (y in 0 until h) for (x in 0 until w) {
                    glyph[(y + pad) * pw + x + pad] =
                        pixels[(digit.top + y) * width + digit.left + x]
                }
                // A zero can also be positively verified without changing it.
                val probe = if (raw[digit.position] == '0') "8" else raw[digit.position].toString()
                val learned = ZeroFontTemplates.correct(pw, ph, glyph, probe, templates)
                val diagonal =
                    if (learned == null) SlashedZeroDetector.correct(pw, ph, glyph, probe) else null
                if (learned == "0" || diagonal == "0") {
                    output[digit.position] = '0'; uncertain.remove(digit.position)
                    methods[digit.position] =
                        if (learned != null) "learned-zero" else "diagonal-zero"
                }
            }
        }
        return Result(String(output), uncertain, methods)
    }
}
