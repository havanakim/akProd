package com.bsd.akProd

import android.content.Context
import android.graphics.*
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Mono(HYUNDAI LCD) photo OCR shared by Cp14Activity and the device regression check.
 * Collects header-anchored candidates; it never fills a cell that has no verified source glyphs.
 */
class MonoTableOcr(
    private val image: Bitmap,
    private val page: String,
    private val active: Set<String>,
    private val context: Context? = null
) {
    val candidates = linkedMapOf<String, MutableMap<String, Pair<Cp14TableReader.Cell, Int>>>()
    val zeroCorrected = mutableSetOf<String>()
    val segmentReview = mutableSetOf<String>()
    val notices = mutableSetOf<String>()
    val beforeZeroCorrection = mutableMapOf<String, MutableSet<String>>()
    val afterZeroCorrection = mutableMapOf<String, MutableSet<String>>()
    val localVotes = mutableMapOf<String, MutableMap<String, Int>>()
    val allZeroVerified = mutableSetOf<String>()
    val anchors = linkedMapOf<String, Cp14TableReader.Word>()
    /** Candidate values that were read at least once in the screen's printed digit layout. */
    val displayMatched = mutableMapOf<String, MutableSet<String>>()
    val log = StringBuilder()
    var wrongPage = false
        private set

    private fun words(text: Text, width: Int, height: Int, roi: RectF) =
        text.textBlocks.flatMap { block -> block.lines.flatMap { it.elements } }
            .mapNotNull { element ->
                element.boundingBox?.let { b ->
                    Cp14TableReader.Word(
                        element.text,
                        roi.left + b.left.toFloat() / width * roi.width(),
                        roi.top + b.top.toFloat() / height * roi.height(),
                        roi.left + b.right.toFloat() / width * roi.width(),
                        roi.top + b.bottom.toFloat() / height * roi.height()
                    )
                }
            }

    fun accept(
        text: Text, width: Int = image.width, height: Int = image.height,
        roi: RectF = RectF(0f, 0f, 1f, 1f), pass: String = "원본"
    ) {
        if (wrongPage) return
        val tokens = words(text, width, height, roi)
        log.append("[인식 경로] $pass\n")
        if (roi.width() < .5f) log.append("[원문] " + text.text.replace("\n", " / ") + "\n")
        val parsed = Cp14TableReader.read(tokens + anchors.values)
        parsed.headers.forEach { anchors.putIfAbsent(it.text, it) }
        if (pass == "원본" && page != Cp14TableReader.LEGACY && parsed.cells.keys.any {
                Cp14TableReader.prefix(it) !in Cp14TableReader.prefixes(page)
            }) {
            wrongPage = true
            return
        }
        log.append("[표 인식]\n" + parsed.cells.entries.joinToString("\n") { (key, cell) -> "${Cp14TableReader.labels[key]}=${cell.value}" } + "\n")
        parsed.cells.filterKeys { it in active }.forEach { (key, original) ->
            val glyphs = original.words
            val left = glyphs.minOf { it.left }; val top = glyphs.minOf { it.top }
            val right = glyphs.maxOf { it.right }; val bottom = glyphs.maxOf { it.bottom }
            val corrected = CaptureNumberCorrection.correct(
                image, left, top, right, bottom, glyphs.joinToString("") { it.text },
                context, Cp14TableReader.MACHINE
            )
            val cell = if (corrected != null) {
                zeroCorrected.add(key)
                beforeZeroCorrection.getOrPut(key) { mutableSetOf() }.add(original.value)
                Cp14TableReader.number(corrected)
                    ?.let { afterZeroCorrection.getOrPut(key) { mutableSetOf() }.add(it) }
                log.append("[0/8/9 형태 보정] $key : " + original.value + " -> " + corrected + "\n")
                original.copy(value = Cp14TableReader.number(corrected) ?: original.value)
            } else original
            if (corrected == null && CaptureNumberCorrection.needsSegmentReview(
                    image, left, top, right, bottom, glyphs.joinToString("") { it.text })
            ) segmentReview.add(key)
            val votes = candidates.getOrPut(key) { linkedMapOf() }
            votes[cell.value] = cell to ((votes[cell.value]?.second ?: 0) + 1)
            val shown = corrected ?: glyphs.joinToString("") { it.text }
            if (Cp14TableReader.matchesDisplay(key, shown) != false)
                displayMatched.getOrPut(key) { mutableSetOf() }.add(cell.value)
            log.append("[후보] $pass $key=${cell.value} ($shown)\n")
            if (pass == "미수집 칸 확대" || pass == "칸별 CLAHE" || pass == "칸별 Otsu") {
                val counts = localVotes.getOrPut(key) { linkedMapOf() }
                counts[cell.value] = (counts[cell.value] ?: 0) + 1
            }
        }
    }

    /** Full-image passes, LCD passes, then enlarged reading of missing/conflicting cells. */
    suspend fun run(latin: TextRecognizer, korean: TextRecognizer, alive: () -> Boolean = { true }) {
        var mark = android.os.SystemClock.elapsedRealtime()
        fun lap(name: String) {
            val now = android.os.SystemClock.elapsedRealtime()
            log.append("[시간] $name ${now - mark}ms\n"); mark = now
        }
        runCatching { accept(LcdOcrPipeline.recognize(korean, image)) }
        lap("원본 한글")
        if (!alive() || wrongPage) return
        val contrast = contrast(image)
        try { runCatching { accept(LcdOcrPipeline.recognize(latin, contrast)) } } finally { contrast.recycle() }
        lap("대비")
        if (!alive()) return
        val mask = yellowTextMask(image)
        try { runCatching { accept(LcdOcrPipeline.recognize(latin, mask)) } } finally { mask.recycle() }
        lap("노란 글자")
        if (!alive()) return
        LcdOcrPipeline.run(
            image, latin, korean, shouldContinue = alive,
            accept = { text, w, h, roi, pass -> accept(text, w, h, roi, pass); lap("LCD $pass") },
            notice = { notices.add(it); log.append("[LCD 처리] $it\n") })
        // Read missing cells at larger scale. Headers determine the region; never fill missing cells with zero.
        val cells = anchors.values.filter {
            it.text in active && (it.text.startsWith("HP") || candidates[it.text].isNullOrEmpty() ||
                    candidates[it.text]!!.size > 1 ||
                    (it.text in segmentReview && candidates[it.text]!!.keys.any { value -> value.any { digit -> digit in "89" } }) ||
                    suspect(it.text))
        }.toList()
        // ML Kit and OpenCV work off the caller thread; candidate bookkeeping still resumes on the
        // caller's dispatcher, so a few cells can be in flight at once without racing the maps.
        val gate = Semaphore(3)
        coroutineScope {
            cells.map { anchor ->
                async { gate.withPermit { if (alive()) readCell(anchor, latin) } }
            }.awaitAll()
        }
        lap("칸별 확대 ${cells.size}칸")
    }

    /**
     * Every reading so far has a 6/8/9, which the slashed-zero LCD font is prone to (130.0 -> 136.0,
     * 000.0 -> 800.0). Such a cell is re-read at larger scale so a lone misreading must face a rival.
     */
    private fun suspect(key: String) =
        candidates[key].orEmpty().keys.let { values -> values.isNotEmpty() && values.all { v -> v.any { it in "689" } } }

    private suspend fun readCell(anchor: Cp14TableReader.Word, latin: TextRecognizer) {
        val family = { key: String -> if (key == "S0") "IF" else Cp14TableReader.prefix(key) }
        val peers = anchors.values.filter { family(it.text) == family(anchor.text) }.sortedBy { it.x }
        val index = peers.indexOf(anchor)
        // An edge column (IF5, S0) has no neighbour midpoint; a short header like "S0" sits over a
        // five-glyph value (130.0), so the crop must follow the column pitch or it cuts the last digit.
        val pitch = peers.zipWithNext { a, b -> b.x - a.x }.filter { it > 0 }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
        val edge = index == 0 || index == peers.lastIndex
        val half = maxOf(anchor.height * 3.0f, (anchor.right - anchor.left) * .85f, if (edge) pitch * .5f else 0f)
        // Position values sit right of their header ("041.0" runs under the "<==" of IF4), so in the IF
        // row a cell ends just before the next header's glyphs instead of at the centre midpoint.
        val ifRow = family(anchor.text) == "IF"
        val prev = peers.getOrNull(index - 1); val next = peers.getOrNull(index + 1)
        val l = maxOf(0f, anchor.x - half, when {
            prev == null -> 0f
            ifRow -> anchor.left - anchor.height * .9f
            else -> (prev.x + anchor.x) / 2
        })
        val r = minOf(1f, if (ifRow && next != null) 1f else anchor.x + half, when {
            next == null -> 1f
            ifRow -> next.left - next.height * .5f
            else -> (next.x + anchor.x) / 2
        })
        val t = anchor.bottom + anchor.height * .03f
        val b = minOf(1f, anchor.bottom + anchor.height * 2.2f, Cp14TableReader.nextRowTop(anchor, anchors.values) ?: 1f)
        if (r <= l || b <= t) return
        val x = (l * image.width).toInt()
        val y = (t * image.height).toInt()
        val w = ((r - l) * image.width).toInt().coerceAtMost(image.width - x)
        val h = ((b - t) * image.height).toInt().coerceAtMost(image.height - y)
        if (w < 2 || h < 2) return
        val source = Bitmap.createBitmap(image, x, y, w, h)
        val enlarged = Bitmap.createScaledBitmap(source, w * 3, h * 3, true)
        try {
            val roi = RectF(x.toFloat() / image.width, y.toFloat() / image.height,
                (x + w).toFloat() / image.width, (y + h).toFloat() / image.height)
            if ((anchor.text.startsWith("HP") || candidates[anchor.text].isNullOrEmpty() || suspect(anchor.text)) &&
                CaptureNumberCorrection.verifiedZero(source, if (anchor.text.startsWith("HP")) 3 else null)
            ) {
                val word = Cp14TableReader.Word("0", roi.left, roi.top, roi.right, roi.bottom)
                candidates.getOrPut(anchor.text) { linkedMapOf() }["0"] = Cp14TableReader.Cell("0", listOf(word)) to 1
                zeroCorrected.add(anchor.text)
                allZeroVerified.add(anchor.text)
                afterZeroCorrection.getOrPut(anchor.text) { linkedSetOf() }.add("0")
                log.append("[원본 0 글자 검증] ${anchor.text}=0\n")
                // Every HP glyph is positively a zero on the source pixels; OCR cannot add evidence here.
                if (anchor.text.startsWith("HP")) return
            }
            accept(LcdOcrPipeline.recognize(latin, enlarged), enlarged.width, enlarged.height, roi, "미수집 칸 확대")
            if (anchor.text in allZeroVerified) return
            // The enlarged read confirmed an unambiguous value seen elsewhere: skip the two costlier passes.
            val settled = decide(anchor.text)?.value
            if (settled != null && (localVotes[anchor.text]?.get(settled) ?: 0) >= 1 &&
                (candidates[anchor.text]?.get(settled)?.second ?: 0) >= 2
            ) return
            LcdOcrPipeline.run(source, latin, selected = true, modes = 2,
                accept = { text, cw, ch, local, pass ->
                    val mapped = RectF(roi.left + local.left * roi.width(), roi.top + local.top * roi.height(),
                        roi.left + local.right * roi.width(), roi.top + local.bottom * roi.height())
                    if (pass == "CLAHE" || pass == "Otsu") accept(text, cw, ch, mapped, "칸별 $pass")
                },
                notice = {})
        } finally {
            enlarged.recycle(); source.recycle()
        }
    }

    data class Decision(
        val cell: Cp14TableReader.Cell,
        val pressureAgreement: String?,
        val conflict: Set<String>?,
        val ambiguousDigits: Boolean
    ) {
        val value get() = if (conflict == null && !ambiguousDigits) cell.value else null
    }

    fun decide(key: String): Decision? {
        val options = candidates[key]?.takeIf { it.isNotEmpty() } ?: return null
        val sourceVerified = afterZeroCorrection[key].orEmpty().toSet()
        val local = localVotes[key].orEmpty().entries.sortedByDescending { it.value }
        val verifiedSet = afterZeroCorrection[key].orEmpty().toMutableSet()
        if (local.isNotEmpty() && local[0].value >= 2 && (local.size == 1 || local[0].value > local[1].value)) {
            verifiedSet.add(local[0].key)
        }
        val pressureAgreement = if (key.startsWith("HP") && key in allZeroVerified) "0"
        else if (key.startsWith("BP") || key.startsWith("HP")) MonoLocalAgreement.value(localVotes[key].orEmpty(), sourceVerified)
        else null
        val verified = options.filterKeys { it in verifiedSet }.values
        var chosen = pressureAgreement?.let { options[it]?.first }
            ?: (if (verified.isNotEmpty()) verified else options.values).maxBy { it.second }.first
        var remaining = if (pressureAgreement != null) setOf(pressureAgreement)
        else options.keys.filterNot { candidate ->
            verifiedSet.any { ZeroCandidateAgreement.supersedes(it, candidate) }
        }.toSet() - beforeZeroCorrection[key].orEmpty() + verifiedSet
        if (remaining.size > 1) {
            // A reading that never matched the printed layout (e.g. 2230 for a 00.0 speed) is OCR debris.
            val formatted = remaining.filter { it in displayMatched[key].orEmpty() || (it == "0" && key in allZeroVerified) }.toSet()
            // Dropping debris must not crown a lone reading (e.g. 935.0 for 035.0): a sole survivor needs 2 passes.
            if (formatted.size > 1 || formatted.size == 1 && (options[formatted.single()]?.second ?: 0) >= 2)
                remaining = formatted
        }
        if (remaining.size > 1) {
            // Accept a source-verified reading only when it clearly out-votes every rival.
            val ranked = remaining.sortedByDescending { options[it]?.second ?: 0 }
            val top = ranked[0]; val topVotes = options[top]?.second ?: 0
            val rival = ranked.drop(1).maxOf { options[it]?.second ?: 0 }
            if (top in verifiedSet && topVotes >= 2 && topVotes >= rival * 2) remaining = setOf(top)
        }
        if (remaining.size == 1) options[remaining.single()]?.first?.let { chosen = it }
        val ambiguous = key in segmentReview && key !in zeroCorrected && pressureAgreement == null &&
                chosen.value.any { it in "89" }
        return Decision(chosen, pressureAgreement, remaining.takeIf { it.size > 1 }, ambiguous)
    }

    companion object {
        fun contrast(image: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val gray = ColorMatrix().apply { setSaturation(0f) }
            gray.postConcat(ColorMatrix(floatArrayOf(
                1.35f, 0f, 0f, 0f, -35f, 0f, 1.35f, 0f, 0f, -35f, 0f, 0f, 1.35f, 0f, -35f, 0f, 0f, 0f, 1f, 0f)))
            Canvas(out).drawBitmap(image, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(gray) })
            return out
        }

        fun yellowTextMask(image: Bitmap): Bitmap {
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                val r = pixel shr 16 and 255; val g = pixel shr 8 and 255; val b = pixel and 255
                pixels[index] = if (r > 75 && g > 55 && r > b * 1.3 && g > b * 1.2) Color.BLACK else Color.WHITE
            }
            return Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        }
    }
}
