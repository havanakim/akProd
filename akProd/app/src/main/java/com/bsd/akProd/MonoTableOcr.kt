package com.bsd.akProd

import android.content.Context
import android.graphics.*
import java.util.Locale
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private val context: Context? = null,
    /** Pass names to leave out; only the regression check uses this to measure each pass. */
    private val skip: Set<String> = emptySet()
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

    /**
     * MIRACON-I: per cell and value, how many readings had every 8 confirmed as a real 8 by its glyph
     * ([MiraconEightGlyph]) and how many had an 8 measured as a slashed 0.
     */
    val eightChecks = mutableMapOf<String, MutableMap<String, IntArray>>()

    /** While a cursor cell is read: its key, its inverted crop and where that crop lies in the photo. */
    private var cursorSource: Triple<String, Bitmap, RectF>? = null

    /** A value whose 8s the glyph check confirmed in at least two readings and twice as often as it refused them. */
    fun eightsVerified(key: String, value: String): Boolean =
        eightChecks[key]?.get(value)?.let { (yes, no) -> yes >= 2 && yes >= no * 2 } == true

    private fun checkEights(key: String, value: String, shown: String, left: Float, top: Float, right: Float, bottom: Float) {
        // The crop the glyph check was tuned on: the value's box plus a margin, so no digit touches the edge.
        val boxHeight = bottom - top
        val padX = boxHeight * .3f
        val l = ((left - padX) * image.width).toInt().coerceAtLeast(0)
        val r = ((right + padX) * image.width).toInt().coerceAtMost(image.width)
        val t = ((top - boxHeight * .25f) * image.height).toInt().coerceAtLeast(0)
        val b = ((bottom + boxHeight * .25f) * image.height).toInt().coerceAtMost(image.height)
        if (r - l < 6 || b - t < 8) return
        val pixels = IntArray((r - l) * (b - t))
        image.getPixels(pixels, 0, r - l, l, t, r - l, b - t)
        val real = MiraconEightGlyph.realEights(r - l, b - t, pixels, shown) ?: return
        val all = shown.indices.filter { shown[it] == '8' }.all { it in real }
        eightChecks.getOrPut(key) { linkedMapOf() }.getOrPut(value) { IntArray(2) }[if (all) 0 else 1]++
        log.append("[8 형태 확인] $key $shown : " + (if (all) "진짜 8" else "빗금 0 의심") + "\n")
    }
    val log = StringBuilder()
    var wrongPage = false
        private set

    /** When [wrongPage]: the screen the photo appears to show, if recognisable. */
    var suggestedPage: String? = null
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
        val parsed = Cp14TableReader.read(tokens + anchors.values, page)
        parsed.headers.forEach { anchors.putIfAbsent(it.text, it) }
        if (pass == "원본" && page != Cp14TableReader.LEGACY && (parsed.miraconHeaders >= 2 || parsed.cells.keys.any {
                Cp14TableReader.prefix(it) !in Cp14TableReader.prefixes(page)
            })) {
            wrongPage = true
            // Which screen the photo shows, so the metering menu can switch to it.
            suggestedPage = when {
                parsed.miraconHeaders >= 2 -> Cp14TableReader.MIRACON
                // MIRACON-I 사출 및 보압 seen as another 사출 screen: IP/IV with an S4~S1 position row.
                page != Cp14TableReader.MIRACON_INJECTION && parsed.cells.keys.any { it.startsWith("IP") || it.startsWith("IV") } &&
                    parsed.cells.keys.any { it.matches(Regex("S[1-4]")) } -> Cp14TableReader.MIRACON_INJECTION
                // The S5~S1 metering screen has no S0; MIRACON-I's position row does.
                (page == Cp14TableReader.METERING || page == Cp14TableReader.PB_METERING) && "S0" in parsed.cells -> Cp14TableReader.MIRACON
                parsed.cells.keys.any {
                    Cp14TableReader.prefix(it) in Cp14TableReader.prefixes(
                        Cp14TableReader.METERING
                    )
                } -> Cp14TableReader.METERING

                parsed.cells.keys.any {
                    Cp14TableReader.prefix(it) in Cp14TableReader.prefixes(
                        Cp14TableReader.INJECTION
                    )
                } -> Cp14TableReader.INJECTION

                else -> null
            }
            return
        }
        log.append("[표 인식]\n" + parsed.cells.entries.joinToString("\n") { (key, cell) -> "${Cp14TableReader.labels[key]}=${cell.value}" } + "\n")
        parsed.cells.filterKeys { it in active }.forEach { (key, original) ->
            val glyphs = original.words
            val left = glyphs.minOf { it.left };
            val top = glyphs.minOf { it.top }
            val right = glyphs.maxOf { it.right };
            val bottom = glyphs.maxOf { it.bottom }
            val rawText = OcrNumericGlyphs.canonical(glyphs.joinToString("") { it.text })
            // A cursor-cell reading is checked on its inverted crop (lit digits like every other cell), not on
            // the photo's dark-on-lit pixels; its box is mapped into that crop.
            val cursor = cursorSource?.takeIf { pass.startsWith("커서 칸") && it.first == key }
            val source = cursor?.second ?: image
            val (pl, pt, pr, pb) = cursor?.third?.let { roi ->
                listOf((left - roi.left) / roi.width(), (top - roi.top) / roi.height(),
                    (right - roi.left) / roi.width(), (bottom - roi.top) / roi.height()).map { it.coerceIn(0f, 1f) }
            } ?: listOf(left, top, right, bottom)
            // The check exists to turn misread 8/9 back into 0. On small photos it can instead invent
            // 8s (030.0 -> 830.0, 53 -> 853.0); a "correction" with more 8/9 than the reading is refused.
            val zeroCheck = CaptureNumberCorrection.correct(
                source, pl, pt, pr, pb, glyphs.joinToString("") { it.text },
                context, Cp14TableReader.MACHINE
            )?.takeIf { fixed -> fixed.count { it in "89" } <= rawText.count { it in "89" } }
            // The slashed zero is also misread as 3 or 6 (100.0 -> 103.0, 130.0 -> 136.0). Probe those
            // glyphs like an 8; only a positively verified zero shape replaces them, a real 3/6 stays.
            val base = zeroCheck ?: OcrNumericGlyphs.canonical(glyphs.joinToString("") { it.text })
            val slashed = zeroFromThreeOrSix(base, pl, pt, pr, pb, source)
            if (slashed != null) {
                Cp14TableReader.number(base)
                    ?.let { beforeZeroCorrection.getOrPut(key) { mutableSetOf() }.add(it) }
                log.append("[0/3·6 형태 보정] $key : $base -> $slashed\n")
            }
            val corrected = slashed ?: zeroCheck
            val cell = if (corrected != null) {
                zeroCorrected.add(key)
                beforeZeroCorrection.getOrPut(key) { mutableSetOf() }.add(original.value)
                Cp14TableReader.number(corrected)
                    ?.let { afterZeroCorrection.getOrPut(key) { mutableSetOf() }.add(it) }
                log.append("[0/8/9 형태 보정] $key : " + original.value + " -> " + corrected + "\n")
                original.copy(value = Cp14TableReader.number(corrected) ?: original.value)
            } else original
            if (corrected == null && CaptureNumberCorrection.needsSegmentReview(
                    source, pl, pt, pr, pb, glyphs.joinToString("") { it.text })
            ) segmentReview.add(key)
            val shown = corrected ?: glyphs.joinToString("") { it.text }
            // The cursor box often hides the last digit (02.7 read as 02): a cursor reading counts only in the
            // cell's full printed layout.
            if (cursor != null && Cp14TableReader.matchesDisplay(key, shown, page) != true) {
                log.append("[커서 칸 제외] $key ($shown) 표시 형식 불일치\n"); return@forEach
            }
            val votes = candidates.getOrPut(key) { linkedMapOf() }
            votes[cell.value] = cell to ((votes[cell.value]?.second ?: 0) + 1)
            if (Cp14TableReader.matchesDisplay(key, shown, page) != false)
                displayMatched.getOrPut(key) { mutableSetOf() }.add(cell.value)
            log.append("[후보] $pass $key=${cell.value} ($shown) @%.4f,%.4f,%.4f,%.4f\n".format(Locale.US, left, top, right, bottom))
            if (page in Cp14TableReader.miraconPages && '8' in OcrNumericGlyphs.canonical(shown))
                checkEights(key, cell.value, OcrNumericGlyphs.canonical(shown), left, top, right, bottom)
            if (pass == "미수집 칸 확대" || pass == "칸별 CLAHE" || pass == "칸별 Otsu" || pass.startsWith("커서 칸")) {
                val counts = localVotes.getOrPut(key) { linkedMapOf() }
                counts[cell.value] = (counts[cell.value] ?: 0) + 1
            }
        }
    }

    private fun zeroFromThreeOrSix(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        source: Bitmap = image
    ): String? {
        if (!text.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) || text.none { it == '3' || it == '6' }) return null
        val probe = text.map { if (it == '3' || it == '6') '8' else it }.joinToString("")
        val verified = CaptureNumberCorrection.correct(
            source,
            left,
            top,
            right,
            bottom,
            probe,
            context,
            Cp14TableReader.MACHINE
        )
            ?: return null
        if (verified.length != probe.length) return null
        // Keep every non-3/6 position as already decided; a 3/6 changes only when it verified as 0.
        val merged = text.indices.joinToString("") { i ->
            if (text[i] == '3' || text[i] == '6') (if (verified[i] == '0') "0" else text[i].toString()) else text[i].toString()
        }
        return merged.takeIf { it != text }
    }

    /** Full-image passes, LCD passes, then enlarged reading of missing/conflicting cells. */
    suspend fun run(
        latin: TextRecognizer,
        korean: TextRecognizer,
        alive: () -> Boolean = { true }
    ) {
        var mark = android.os.SystemClock.elapsedRealtime()
        fun lap(name: String) {
            val now = android.os.SystemClock.elapsedRealtime()
            log.append("[시간] $name ${now - mark}ms\n"); mark = now
        }
        // The 12MP contrast/mask bitmaps are built off the UI thread while the first OCR runs.
        // Not children of the caller: a cancelled screen must not wait on them, and finally releases them.
        val scope = CoroutineScope(Dispatchers.Default)
        val contrastJob =
            if ("대비" !in skip) scope.async(Dispatchers.Default) { contrast(image) } else null
        val maskJob =
            if ("노란 글자" !in skip) scope.async(Dispatchers.Default) { yellowTextMask(image) } else null
        try {
            if ("원본 한글" !in skip) runCatching { accept(LcdOcrPipeline.recognize(korean, image)) }
            lap("원본 한글")
            if (!alive() || wrongPage) return
            contrastJob?.await()?.let { contrast ->
                try {
                    runCatching { accept(LcdOcrPipeline.recognize(latin, contrast)) }
                } finally {
                    contrast.recycle()
                }
            }
            lap("대비")
            if (!alive()) return
            maskJob?.await()?.let { mask ->
                try {
                    runCatching { accept(LcdOcrPipeline.recognize(latin, mask)) }
                } finally {
                    mask.recycle()
                }
            }
            lap("노란 글자")
            if (!alive()) return
        } finally {
            // An early return must not leak a 12MP bitmap that is still being built: release it once ready.
            val jobs = listOfNotNull(contrastJob, maskJob)
            CoroutineScope(Dispatchers.Default).launch {
                jobs.forEach { job ->
                    runCatching { job.await() }.getOrNull()?.takeIf { !it.isRecycled }?.recycle()
                }
            }
        }
        LcdOcrPipeline.run(
            // Only CLAHE and Otsu: on the 6-photo regression the two directional-closing passes added
            // no correct value and no rival (12 mismatches/0 wrong either way) but cost ~2s per photo.
            image, latin, korean, modes = 2, shouldContinue = alive, skip = skip,
            accept = { text, w, h, roi, pass -> accept(text, w, h, roi, pass); lap("LCD $pass") },
            notice = { notices.add(it); log.append("[LCD 처리] $it\n") })
        // Read missing cells at larger scale. Headers determine the region; never fill missing cells with zero.
        val cells = anchors.values.filter {
            it.text in active && (it.text.startsWith("HP") || candidates[it.text].isNullOrEmpty() ||
                    candidates[it.text]!!.size > 1 ||
                    (it.text in segmentReview && candidates[it.text]!!.keys.any { value -> value.any { digit -> digit in "89" } }) ||
                    suspect(it.text) ||
                    // MIRACON-I fills a down-sized photo's value only from two readings: a lone one is re-read.
                    page in Cp14TableReader.miraconPages && candidates[it.text]!!.values.maxOf { c -> c.second } < 2)
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
     * Reads a cursor cell ([CursorCell]) from its box cut out and inverted: enlarged, then CLAHE and Otsu, each
     * reading a local vote ("커서 칸 …"). Returns false for an ordinary cell, which is left to the usual passes.
     * [x], [y] are the crop's pixel position in [image].
     */
    private suspend fun readCursorCell(key: String, source: Bitmap, x: Int, y: Int, latin: TextRecognizer): Boolean {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val margin = 4
        val box = CursorCell.box(source.width, source.height, pixels) ?: return false
        val (w, h, normalized) = CursorCell.normalize(source.width, source.height, pixels, margin) ?: return false
        if (w * 3 < 32 || h * 3 < 32) return false
        val cell = Bitmap.createBitmap(normalized, w, h, Bitmap.Config.ARGB_8888)
        val enlarged = Bitmap.createScaledBitmap(cell, w * 3, h * 3, true)
        // Readings of this cell so far came from the inverted pixels (030.0 read as 30.8, 938.6 …): set them
        // aside. They come back if the cursor passes yield nothing (e.g. the box hides the last digit).
        log.append("[커서 칸] $key 반전 영역 ${box.joinToString(",")} · 이전 후보 보류: ${candidates[key]?.keys}\n")
        val maps: List<MutableMap<String, *>> =
            listOf(candidates, localVotes, displayMatched, beforeZeroCorrection, afterZeroCorrection, eightChecks)
        val saved = maps.map { it.remove(key) }
        val flags = (key in zeroCorrected) to (key in segmentReview)
        zeroCorrected.remove(key); segmentReview.remove(key)
        var read = false
        try {
            // Where the normalised crop lies in the photo (its margin reaches just past the box).
            val roi = RectF(
                (x + box[0] - margin).toFloat() / image.width, (y + box[1] - margin).toFloat() / image.height,
                (x + box[0] - margin + w).toFloat() / image.width, (y + box[1] - margin + h).toFloat() / image.height
            )
            cursorSource = Triple(key, cell, roi)
            runCatching { accept(LcdOcrPipeline.recognize(latin, enlarged), enlarged.width, enlarged.height, roi, "커서 칸 확대") }
            LcdOcrPipeline.run(
                cell, latin, selected = true, modes = 2,
                accept = { text, cw, ch, local, pass ->
                    val mapped = RectF(
                        roi.left + local.left * roi.width(), roi.top + local.top * roi.height(),
                        roi.left + local.right * roi.width(), roi.top + local.bottom * roi.height()
                    )
                    if (pass == "CLAHE" || pass == "Otsu") accept(text, cw, ch, mapped, "커서 칸 $pass")
                },
                notice = {})
            read = !candidates[key].isNullOrEmpty()
        } finally {
            cursorSource = null; enlarged.recycle(); cell.recycle()
            if (!read) {
                // Nothing usable from the box: restore the earlier readings and let the ordinary passes run.
                @Suppress("UNCHECKED_CAST")
                maps.zip(saved).forEach { (map, value) ->
                    (map as MutableMap<String, Any?>).remove(key); if (value != null) map[key] = value
                }
                if (flags.first) zeroCorrected.add(key)
                if (flags.second) segmentReview.add(key)
                log.append("[커서 칸] $key 읽은 값 없음 · 이전 후보 복원, 일반 경로로 읽기\n")
            }
        }
        return read
    }

    /**
     * Every reading so far has a 6/8/9, which the slashed-zero LCD font is prone to (130.0 -> 136.0,
     * 000.0 -> 800.0). Such a cell is re-read at larger scale so a lone misreading must face a rival.
     */
    private fun suspect(key: String) =
        candidates[key].orEmpty().keys.let { values -> values.isNotEmpty() && values.all { v -> v.any { it in "689" } } }

    private suspend fun readCell(anchor: Cp14TableReader.Word, latin: TextRecognizer) {
        val family = { key: String -> if (key == "S0") "IF" else Cp14TableReader.prefix(key) }
        val peers =
            anchors.values.filter { family(it.text) == family(anchor.text) }.sortedBy { it.x }
        val index = peers.indexOf(anchor)
        // An edge column (IF5, S0) has no neighbour midpoint; a short header like "S0" sits over a
        // five-glyph value (130.0), so the crop must follow the column pitch or it cuts the last digit.
        val pitch = peers.zipWithNext { a, b -> b.x - a.x }.filter { it > 0 }.average().toFloat()
            .takeIf { !it.isNaN() } ?: 0f
        val edge = index == 0 || index == peers.lastIndex
        val half = maxOf(
            anchor.height * 3.0f,
            (anchor.right - anchor.left) * .85f,
            if (edge) pitch * .5f else 0f
        )
        // Position values sit right of their header ("041.0" runs under the "<==" of IF4), so in the IF
        // row a cell ends just before the next header's glyphs instead of at the centre midpoint.
        val ifRow = family(anchor.text) == "IF"
        val prev = peers.getOrNull(index - 1);
        val next = peers.getOrNull(index + 1)
        val l = maxOf(
            0f, anchor.x - half, when {
                prev == null -> 0f
                ifRow -> anchor.left - anchor.height * .9f
                else -> (prev.x + anchor.x) / 2
            }
        )
        val r = minOf(
            1f, if (ifRow && next != null) 1f else anchor.x + half, when {
                next == null -> 1f
                ifRow -> next.left - next.height * .5f
                else -> (next.x + anchor.x) / 2
            }
        )
        val t = anchor.bottom + anchor.height * .03f
        val b = minOf(
            1f,
            anchor.bottom + anchor.height * 2.2f,
            Cp14TableReader.nextRowTop(anchor, anchors.values) ?: 1f
        )
        if (r <= l || b <= t) return
        val x = (l * image.width).toInt()
        val y = (t * image.height).toInt()
        val w = ((r - l) * image.width).toInt().coerceAtMost(image.width - x)
        val h = ((b - t) * image.height).toInt().coerceAtMost(image.height - y)
        // ML Kit refuses images under 32px on a side; such a sliver is no cell worth reading.
        if (w < 2 || h < 2 || w * 3 < 32 || h * 3 < 32) return
        val source = Bitmap.createBitmap(image, x, y, w, h)
        val enlarged = Bitmap.createScaledBitmap(source, w * 3, h * 3, true)
        try {
            val roi = RectF(
                x.toFloat() / image.width, y.toFloat() / image.height,
                (x + w).toFloat() / image.width, (y + h).toFloat() / image.height
            )
            if ((anchor.text.startsWith("HP") || candidates[anchor.text].isNullOrEmpty() || suspect(
                    anchor.text
                )) &&
                CaptureNumberCorrection.verifiedZero(
                    source,
                    if (anchor.text.startsWith("HP")) 3 else null
                )
            ) {
                val word = Cp14TableReader.Word("0", roi.left, roi.top, roi.right, roi.bottom)
                candidates.getOrPut(anchor.text) { linkedMapOf() }["0"] =
                    Cp14TableReader.Cell("0", listOf(word)) to 1
                zeroCorrected.add(anchor.text)
                allZeroVerified.add(anchor.text)
                afterZeroCorrection.getOrPut(anchor.text) { linkedSetOf() }.add("0")
                log.append("[원본 0 글자 검증] ${anchor.text}=0\n")
                // Every HP glyph is positively a zero on the source pixels; OCR cannot add evidence here.
                if (anchor.text.startsWith("HP")) return
            }
            // The field under the HMI cursor is drawn inverted (dark digits on a lit box, e.g. IHT 030.0). The
            // ordinary passes read it as 30.8/38.8, so it is read only from its box cut out and inverted.
            if (readCursorCell(anchor.text, source, x, y, latin)) return
            accept(
                LcdOcrPipeline.recognize(latin, enlarged),
                enlarged.width,
                enlarged.height,
                roi,
                "미수집 칸 확대"
            )
            if (anchor.text in allZeroVerified) return
            // The enlarged read confirmed an unambiguous value seen elsewhere: skip the two costlier passes.
            val settled = decide(anchor.text)?.value
            if (settled != null && (localVotes[anchor.text]?.get(settled) ?: 0) >= 1 &&
                (candidates[anchor.text]?.get(settled)?.second ?: 0) >= 2
            ) return
            LcdOcrPipeline.run(
                source, latin, selected = true, modes = 2,
                accept = { text, cw, ch, local, pass ->
                    val mapped = RectF(
                        roi.left + local.left * roi.width(), roi.top + local.top * roi.height(),
                        roi.left + local.right * roi.width(), roi.top + local.bottom * roi.height()
                    )
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
        val ambiguousDigits: Boolean,
        val outOfOrder: Boolean = false
    ) {
        val value get() = if (conflict == null && !ambiguousDigits && !outOfOrder) cell.value else null
    }

    /**
     * All decisions, with the stage-position order checked: while some decided position breaks the
     * order, the one breaking it against the most others is withheld (marked [Decision.outOfOrder]).
     */
    fun decideAll(): Map<String, Decision> {
        val decisions = candidates.keys.mapNotNull { key -> decide(key)?.let { key to it } }.toMap()
            .toMutableMap()
        for (row in Cp14TableReader.orderedPositions) {
            while (true) {
                val known = row.mapIndexedNotNull { i, key ->
                    decisions[key]?.value?.toBigDecimalOrNull()?.let { Triple(i, key, it) }
                }
                val breaks = known.associate { (i, key, v) ->
                    key to known.count { (j, _, w) -> (j < i && w >= v) || (j > i && w <= v) }
                }
                val most = breaks.values.maxOrNull()?.takeIf { it > 0 } ?: break
                // A tie (two values contradicting only each other) cannot say which one is wrong: withhold both.
                breaks.filterValues { it == most }.keys.forEach { key ->
                    decisions[key] = decisions.getValue(key).copy(outOfOrder = true)
                    log.append("[순서 불일치] $key=${decisions.getValue(key).cell.value}\n")
                }
            }
        }
        return decisions
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
        else if (key.startsWith("BP") || key.startsWith("HP")) MonoLocalAgreement.value(
            localVotes[key].orEmpty(),
            sourceVerified
        )
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
            val formatted =
                remaining.filter { it in displayMatched[key].orEmpty() || (it == "0" && key in allZeroVerified) }
                    .toSet()
            // Dropping debris must not crown a lone reading (e.g. 935.0 for 035.0): a sole survivor needs 2 passes.
            if (formatted.size > 1 || formatted.size == 1 && (options[formatted.single()]?.second
                    ?: 0) >= 2
            )
                remaining = formatted
        }
        if (remaining.size > 1) {
            // Accept a source-verified reading only when it clearly out-votes every rival.
            val ranked = remaining.sortedByDescending { options[it]?.second ?: 0 }
            val top = ranked[0];
            val topVotes = options[top]?.second ?: 0
            val rival = ranked.drop(1).maxOf { options[it]?.second ?: 0 }
            // Votes cannot settle 0 against 8/9: if a rival is the same reading with a zero there
            // (130.0 vs 130.8), only the pixels could tell, so the conflict stays.
            val zeroRival = ranked.drop(1).any { ZeroCandidateAgreement.supersedes(it, top) }
            if (top in verifiedSet && topVotes >= 2 && topVotes >= rival * 2 && !zeroRival) remaining =
                setOf(top)
        }
        if (remaining.size == 1) options[remaining.single()]?.first?.let { chosen = it }
        var ambiguous =
            key in segmentReview && key !in zeroCorrected && pressureAgreement == null &&
                    chosen.value.any { it in "89" }
        if (page in Cp14TableReader.miraconPages) {
            // The slashed 0 of this font is read as 8 on blurred photos (60.0 -> 68, 40.0 -> 40.8) and a
            // partial pixel fix still leaves 8s, so a value with a 9, or an 8 whose glyph shape was not
            // confirmed as a real 8 (middle bar, no slash: [eightsVerified]), is never filled. A forwarded,
            // down-sized photo (60.0 read once as 65) must also match the printed layout and come from two
            // passes; a camera original may drop padding (053.0 read as 53) and is trusted as read.
            val downSized = maxOf(image.width, image.height) < 2500
            val realEight = '8' in chosen.value && '9' !in chosen.value && eightsVerified(key, chosen.value)
            // The glyph check settles 8 against slashed 0, which is what the segment review flagged.
            if (realEight) ambiguous = false
            if (chosen.value.any { it in "89" } && !realEight || downSized && (chosen.value !in displayMatched[key].orEmpty() ||
                        (options[chosen.value]?.second ?: 0) < 2)) ambiguous = true
        }
        return Decision(chosen, pressureAgreement, remaining.takeIf { it.size > 1 }, ambiguous)
    }

    companion object {
        fun contrast(image: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val gray = ColorMatrix().apply { setSaturation(0f) }
            gray.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1.35f,
                        0f,
                        0f,
                        0f,
                        -35f,
                        0f,
                        1.35f,
                        0f,
                        0f,
                        -35f,
                        0f,
                        0f,
                        1.35f,
                        0f,
                        -35f,
                        0f,
                        0f,
                        0f,
                        1f,
                        0f
                    )
                )
            )
            Canvas(out).drawBitmap(
                image,
                0f,
                0f,
                Paint().apply { colorFilter = ColorMatrixColorFilter(gray) })
            return out
        }

        fun yellowTextMask(image: Bitmap): Bitmap {
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                val r = pixel shr 16 and 255;
                val g = pixel shr 8 and 255;
                val b = pixel and 255
                pixels[index] =
                    if (r > 75 && g > 55 && r > b * 1.3 && g > b * 1.2) Color.BLACK else Color.WHITE
            }
            return Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        }
    }
}
