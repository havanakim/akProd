package com.bsd.akProd

import kotlin.math.abs

/** Each printed header owns the number directly beneath it; never assign by a guessed column index. */
object Cp14TableReader {
    const val NAME = "CP14호기"
    const val MACHINE = "CP14"
    const val INJECTION = "INJECTION_HOLDING"
    const val METERING = "METERING_BACK_PRESSURE"
    const val LEGACY = "LEGACY_COMBINED"
    /** HYUNDAI MIRACON-I 계량·배압 screen: BP1~3, R1~3 + SB.SPEED, S6 ==> S5 ==> S0 ==> SB. */
    const val MIRACON = "METERING_MIRACON"
    val stages = listOf("IF", "S0", "IP", "IV", "HP", "HT", "S", "RV", "BP")
    val miraconStages = listOf("MBP", "MR", "MS")
    val blankAllowed = emptySet<String>()
    val groups = linkedMapOf(
        "IF" to "사출 위치 설정 (mm)", "S0" to "사출 시작 위치 설정 (mm)",
        "IP" to "사출 압력 (kg/cm²)", "IV" to "사출 속도 (%)", "HP" to "보압 압력 (kg/cm²)",
        "HT" to "보압 시간 (sec)", "S" to "계량 위치 (mm)", "RV" to "계량 속도 (%)", "BP" to "배압 (kg/cm²)",
        "MBP" to "배압 (kg/cm²)", "MR" to "계량 속도 (%)", "MS" to "계량 위치 (mm)"
    )
    /** MIRACON keys carry an M prefix so they never mix with the S5~S1 screen; text is as printed. */
    private val miraconKeys = linkedMapOf(
        "MBP1" to "BP1", "MBP2" to "BP2", "MBP3" to "BP3",
        "MR1" to "R1", "MR2" to "R2", "MR3" to "R3", "MRS" to "SB.SPEED",
        "MS6" to "S6", "MS5" to "S5", "MS0" to "S0", "MSB" to "SB"
    )
    fun printedName(key: String) = miraconKeys[key] ?: key
    val labels = linkedMapOf<String, String>().apply {
        groups.forEach { (prefix, title) ->
            if (prefix == "S0") put("S0", "S0 · $title")
            else if (prefix in miraconStages) miraconKeys.filterKeys { prefix(it) == prefix }
                .forEach { (key, printed) -> put(key, "$printed · $title") }
            else for (stage in 5 downTo 1) put("$prefix$stage", "$prefix$stage · $title")
        }
    }

    fun prefix(key: String) = if (key == "S0") "S0" else key.dropLast(1)
    private fun rowFamily(key: String) = if (prefix(key) in setOf("IF", "S0")) "IF" else prefix(key)

    /** A slanted row must use the next header in its own column, not the leftmost next-row header. */
    fun nextRowTop(anchor: Word, headers: Collection<Word>): Float? {
        val start = anchor.bottom + anchor.height * .03f
        val later =
            headers.filter { it.top > start && rowFamily(it.text) != rowFamily(anchor.text) }
        val radius = maxOf(anchor.height * 4.5f, (anchor.right - anchor.left) * 1.5f)
        return later.filter { abs(it.x - anchor.x) < radius }.minOfOrNull { it.top }
            ?: later.minOfOrNull { it.top }
    }

    fun stageName(prefix: String) = groups.getValue(prefix)
    fun prefixes(page: String): Set<String> = when (page) {
        INJECTION -> setOf("IF", "S0", "IP", "IV", "HP", "HT")
        METERING -> setOf("S", "RV", "BP")
        MIRACON -> miraconStages.toSet()
        LEGACY -> stages.toSet()
        else -> error("화면 구분을 선택하세요.")
    }

    fun pageTitle(page: String) = when (page) {
        INJECTION -> "사출 및 보압"
        METERING -> "계량·배압·흘림방지"
        MIRACON -> "MIRACON-I 계량·배압"
        else -> "기존 통합자료"
    }

    fun labelsFor(page: String) = labels.filterKeys { prefix(it) in prefixes(page) }
    fun validatePage(values: Map<String, String>, page: String) {
        val scoped = labelsFor(page)
        require(values.keys.all { it in scoped }) { "다른 화면의 항목이 포함되어 있습니다." }
        CaptureFieldValues.nullableValues(scoped.keys, values, number = ::number)
    }

    data class Word(
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val x get() = (left + right) / 2;
        val y get() = (top + bottom) / 2;
        val height get() = bottom - top
    }

    data class Cell(val value: String, val words: List<Word>)
    data class Result(
        val cells: Map<String, Cell>,
        val visibleStages: Set<String>,
        val headers: List<Word>,
        /** MIRACON-only headers (R1~R3, SB.SPEED, S6) seen while reading another screen. */
        val miraconHeaders: Int = 0
    )

    private fun compact(text: String) = text.uppercase().replace(Regex("[\\s<>=→←:：()]"), "")
    private fun header(text: String): String? {
        val cleaned = compact(text).replace(Regex("^1P"), "IP").replace(Regex("^1V"), "IV")
            .replace(Regex("^[1L|]F"), "IF")
            .replace(Regex("^[RB][UVW]"), "RV")
            // The mono LCD font draws V like U; ML Kit reads IV1..IV5 as IU1/IUi/IUZ/IUS.
            .replace(Regex("^[I1L|][UW](?=[1-5SIZ]${'$'})"), "IV")
            .replace(Regex("^[8${'$'}](?=[1-5SI]${'$'})"), "S")
        // Anchors fed back between passes keep their MIRACON key.
        if (cleaned.matches(Regex("M(BP[1-3]|R[1-3S]|S[650B])"))) return cleaned
        // MIRACON-I headers: R1~R3, SB.SPEED, S6 and SB (SB stays separate here; see [alias]).
        if (cleaned.matches(Regex("(SB\\.?|S8\\.?)?SPEED"))) return "RS"
        Regex("R([1-3])").matchEntire(cleaned)?.let { return "R" + it.groupValues[1] }
        if (cleaned == "S6" || cleaned == "SG") return "S6"
        if (cleaned == "SB") return "SB"
        if (cleaned in setOf("S0", "SO", "S8", "SØ")) return "S0"
        val match =
            Regex("^(IF|IP|IV|HP|HT|RV|BP|S)([1-5SIZ])$").matchEntire(cleaned) ?: return null
        val digit = when (match.groupValues[2]) {
            "S" -> "5"; "I" -> "1"; "Z" -> "2"; else -> match.groupValues[2]
        }
        return match.groupValues[1] + digit
    }

    fun number(text: String): String? {
        val value = text.trim().replace(Regex("\\s"), "").replace('O', '0').replace('o', '0')
        return value.takeIf { it.matches(Regex("[0-9]{1,9}(?:\\.[0-9]{1,4})?")) }
            ?.toBigDecimal()?.stripTrailingZeros()?.toPlainString()
    }

    private val miraconOnly = setOf("R1", "R2", "R3", "RS", "S6")

    /**
     * MIRACON-I codes become M-keys; elsewhere they are dropped (only counted, so a MIRACON photo in
     * another screen can be recognised) and "SB" is the usual misreading of S0.
     */
    private fun alias(candidates: LinkedHashMap<String, MutableList<Word>>, page: String) {
        if (page != MIRACON) {
            candidates.remove("SB")?.let { candidates.getOrPut("S0") { mutableListOf() }.addAll(it) }
            miraconOnly.forEach { candidates.remove(it) }
            return
        }
        // S0 and SB are both read as S0/SB/S8; along "S6 ==> S5 ==> S0 ==> SB" the left one is S0.
        val zeroLike = (candidates.remove("S0").orEmpty() + candidates.remove("SB").orEmpty().map { it.copy(text = "SB") })
            .sortedBy { it.x }
        when {
            zeroLike.size >= 2 -> {
                candidates.getOrPut("MS0") { mutableListOf() }.add(zeroLike.first().copy(text = "MS0"))
                candidates.getOrPut("MSB") { mutableListOf() }.add(zeroLike.last().copy(text = "MSB"))
            }
            zeroLike.size == 1 -> {
                val key = if (zeroLike.single().text == "SB") "MSB" else "MS0"
                candidates.getOrPut(key) { mutableListOf() }.add(zeroLike.single().copy(text = key))
            }
        }
        val rename = mapOf("BP1" to "MBP1", "BP2" to "MBP2", "BP3" to "MBP3", "R1" to "MR1", "R2" to "MR2",
            "R3" to "MR3", "RS" to "MRS", "S6" to "MS6", "S5" to "MS5")
        rename.forEach { (from, to) ->
            candidates.remove(from)?.let { list -> candidates.getOrPut(to) { mutableListOf() }.addAll(list.map { it.copy(text = to) }) }
        }
    }

    fun read(words: List<Word>, page: String = INJECTION): Result {
        val candidates = linkedMapOf<String, MutableList<Word>>()
        words.filter { it.height > 0 }.forEach { first ->
            val near = words.filter {
                it.left >= first.left &&
                        abs(it.y - first.y) < maxOf(it.height, first.height) * .65f &&
                        it.left - first.right < maxOf(it.height, first.height) * 3
            }.sortedBy { it.left }.take(3)
            for (count in 1..near.size) {
                val group = near.take(count)
                val code = header(group.joinToString("") { it.text }) ?: continue
                // ML Kit returns "<==IF4" as one word; the arrow would pull the column centre left of
                // the printed IF4 and cut the last digit of the value under it. Keep only the code glyphs.
                val first = group.first()
                val arrow = Regex("^[<>=({\\[\\-—~]+").find(first.text)?.value?.length ?: 0
                val left = if (arrow in 1 until first.text.length)
                    first.left + (first.right - first.left) * arrow / first.text.length
                else first.left
                val box = Word(
                    code,
                    if (group.size == 1) left else group.minOf { it.left },
                    group.minOf { it.top },
                    group.maxOf { it.right },
                    group.maxOf { it.bottom })
                val instances = candidates.getOrPut(code) { mutableListOf() }
                // "<== S0" and "S0" are the same printed header: keep the tighter box instead of two rivals.
                val overlap = instances.indexOfFirst {
                    abs(it.y - box.y) < maxOf(it.height, box.height) &&
                            it.left < box.right && box.left < it.right
                }
                if (overlap < 0) instances.add(box)
                else if (box.right - box.left < instances[overlap].right - instances[overlap].left) instances[overlap] = box
                break
            }
        }
        val miraconHeaders = if (page == MIRACON) 0 else candidates.keys.count { it in miraconOnly }
        alias(candidates, page)
        val all = candidates.values.flatten()
        fun support(word: Word) = all.filter {
            rowFamily(it.text) == rowFamily(word.text) &&
                    abs(it.y - word.y) < maxOf(it.height, word.height) * 2
        }.map { it.text }.distinct().size

        val anchors = linkedMapOf<String, Word>()
        candidates.forEach { (code, instances) ->
            val best = instances.maxOf { support(it) }
            val winners = instances.filter { support(it) == best }
            // A solitary HP5 label on a graph must not displace the five-column HP table.
            if (winners.size == 1) anchors[code] = winners.single()
        }
        inferMissingHeaders(anchors)
        val output = linkedMapOf<String, Cell>()
        anchors.forEach { (code, anchor) ->
            val prefix = rowFamily(code)
            val peers = anchors.filter { (key, word) ->
                rowFamily(key) == prefix && abs(word.y - anchor.y) < maxOf(
                    word.height,
                    anchor.height
                ) * 2
            }.values.sortedBy { it.x }
            val index = peers.indexOf(anchor)
            val defaultHalf = maxOf((anchor.right - anchor.left) * .85f, anchor.height * 2.1f)
            val left = if (index > 0) maxOf(
                anchor.x - defaultHalf,
                (peers[index - 1].x + anchor.x) / 2
            ) else anchor.x - defaultHalf
            val right = if (index + 1 < peers.size) minOf(
                anchor.x + defaultHalf,
                (peers[index + 1].x + anchor.x) / 2
            ) else anchor.x + defaultHalf
            val nextHeader =
                anchors.values.filter { other -> other.y > anchor.y + anchor.height && other.x in left..right }
                    .minOfOrNull { it.top }
            val bottom = minOf(anchor.bottom + anchor.height * 2.5f, nextHeader ?: 1f)
            val values = words.filter {
                (number(OcrNumericGlyphs.canonical(it.text)) != null || it.text.trim() == ".") &&
                        it.x in left..right && it.y > anchor.bottom + anchor.height * .03f && it.y < bottom
            }.sortedBy { it.y }
            val lines = mutableListOf<MutableList<Word>>()
            values.forEach { word ->
                val line = lines.lastOrNull()
                if (line != null && abs(line.map { it.y }.average() - word.y) < maxOf(
                        word.height,
                        line.maxOf { it.height }) * .65f
                ) line.add(word)
                else lines.add(mutableListOf(word))
            }
            if (lines.size != 1) return@forEach
            val row = lines.single().sortedBy { it.left }
            if (row.zipWithNext()
                    .any { (a, b) -> b.left - a.right > maxOf(a.height, b.height) }
            ) return@forEach
            val value = number(OcrNumericGlyphs.canonical(row.joinToString("") { it.text }))
                ?: return@forEach
            output[code] = Cell(value, row)
        }
        return Result(output, anchors.keys.map { prefix(it) }.toSet(), anchors.values.toList(), miraconHeaders)
    }

    /** Fixed digit layout printed on the HYUNDAI 사출 및 보압 screen (e.g. 041.0, 076, 27.0, 000, 035.0). */
    private val displayFormats = mapOf(
        "IF" to Regex("\\d{3}\\.\\d"), "S0" to Regex("\\d{3}\\.\\d"), "IP" to Regex("\\d{3}"),
        "IV" to Regex("\\d{2}\\.\\d"), "HP" to Regex("\\d{3}"), "HT" to Regex("\\d{3}\\.\\d"),
        // 계량·배압 화면: 010.0 / 24.0 / 20
        "S" to Regex("\\d{3}\\.\\d"), "RV" to Regex("\\d{2}\\.\\d"), "BP" to Regex("\\d{2,3}"),
        // MIRACON-I: BP 03, R 60.0 / SB.SPEED 40.0, S6~S0 010.0 and SB 06.0
        "MBP" to Regex("\\d{2,3}"), "MR" to Regex("\\d{2}\\.\\d"), "MS" to Regex("\\d{2,3}\\.\\d")
    )

    /**
     * Stage positions run one way along the screen arrows (S5 ==> S1, IF5 ==> IF1 ==> S0), so a
     * decided position that breaks the order against most others is a misread (010.0 read as 818).
     */
    val orderedPositions = listOf(listOf("S5", "S4", "S3", "S2", "S1"), listOf("IF5", "IF4", "IF3", "IF2", "IF1", "S0"), listOf("MS6", "MS5", "MS0"))

    /** null when the field has no known layout; then the format is never used to discard a reading. */
    fun matchesDisplay(key: String, shown: String): Boolean? =
        displayFormats[prefix(key)]?.matches(OcrNumericGlyphs.canonical(shown))

    private val columnPairs =mapOf("IP" to "IV", "IV" to "IP", "HP" to "HT", "HT" to "HP")

    /**
     * A header OCR missed (e.g. IV4 between IV5 and IV3) is placed only from printed neighbours:
     * the midpoint of both same-row neighbours, or the same stage of the paired row (IP/IV, HP/HT)
     * shifted by the offset measured on at least two shared stages. Values still come from source glyphs.
     */
    internal fun inferMissingHeaders(anchors: MutableMap<String, Word>) {
        repeat(2) {
            for (prefix in listOf("IF", "IP", "IV", "HP", "HT", "S", "RV", "BP")) {
                for (stage in 5 downTo 1) {
                    val code = "$prefix$stage"
                    if (code in anchors) continue
                    val before = anchors["$prefix${stage + 1}"]
                    val after = anchors["$prefix${stage - 1}"]
                    if (before != null && after != null && after.x > before.x &&
                        abs(before.y - after.y) < maxOf(before.height, after.height) * 1.5f
                    ) {
                        val halfW = ((before.right - before.left) + (after.right - after.left)) / 4
                        val halfH = (before.height + after.height) / 4
                        val x = (before.x + after.x) / 2; val y = (before.y + after.y) / 2
                        anchors[code] = Word(code, x - halfW, y - halfH, x + halfW, y + halfH)
                        continue
                    }
                    val pair = columnPairs[prefix] ?: continue
                    val source = anchors["$pair$stage"] ?: continue
                    val shared = (1..5).mapNotNull { s ->
                        val a = anchors["$prefix$s"]; val b = anchors["$pair$s"]
                        if (a != null && b != null) (a.x - b.x) to (a.y - b.y) else null
                    }
                    if (shared.size < 2) continue
                    val dx = shared.map { it.first }.sorted()[shared.size / 2]
                    val dy = shared.map { it.second }.sorted()[shared.size / 2]
                    val rowHeight = anchors.filterKeys { prefix(it) == prefix }.values.map { it.height }.average().toFloat()
                    if (abs(dy) < rowHeight * .8f || shared.any { abs(it.second - dy) > rowHeight }) continue
                    anchors[code] = Word(code, source.left + dx, source.top + dy, source.right + dx, source.bottom + dy)
                }
            }
        }
    }

    fun validate(values: Map<String, String>, absent: Set<String> = emptySet()) {
        CaptureFieldValues.nullableValues(labels.keys, values, absent, ::number)
    }
}
