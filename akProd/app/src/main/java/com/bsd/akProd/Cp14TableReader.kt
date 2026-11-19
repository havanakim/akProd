package com.bsd.akProd

import kotlin.math.abs

/** Each printed header owns the number directly beneath it; never assign by a guessed column index. */
object Cp14TableReader {
    const val NAME = "CP14호기"
    const val MACHINE = "CP14"
    const val INJECTION = "INJECTION_HOLDING"
    const val METERING = "METERING_BACK_PRESSURE"
    const val LEGACY = "LEGACY_COMBINED"
    val stages = listOf("IF", "S0", "IP", "IV", "HP", "HT", "S", "RV", "BP")
    val blankAllowed = emptySet<String>()
    val groups = linkedMapOf(
        "IF" to "사출 위치 설정 (mm)", "S0" to "사출 시작 위치 설정 (mm)",
        "IP" to "사출 압력 (kg/cm²)", "IV" to "사출 속도 (%)", "HP" to "보압 압력 (kg/cm²)",
        "HT" to "보압 시간 (sec)", "S" to "계량 위치 (mm)", "RV" to "계량 속도 (%)", "BP" to "배압 (kg/cm²)"
    )
    val labels = linkedMapOf<String, String>().apply {
        groups.forEach { (prefix, title) ->
            if (prefix == "S0") put("S0", "S0 · $title")
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
        LEGACY -> stages.toSet()
        else -> error("화면 구분을 선택하세요.")
    }

    fun pageTitle(page: String) = when (page) {
        INJECTION -> "사출 및 보압"
        METERING -> "계량·배압·흘림방지"
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
        val headers: List<Word>
    )

    private fun compact(text: String) = text.uppercase().replace(Regex("[\\s<>=→←:：()]"), "")
    private fun header(text: String): String? {
        val cleaned = compact(text).replace(Regex("^1P"), "IP").replace(Regex("^1V"), "IV")
            .replace(Regex("^[1L|]F"), "IF")
            .replace(Regex("^[RB][UVW]"), "RV")
            .replace(Regex("^[8${'$'}](?=[1-5SI]${'$'})"), "S")
        if (cleaned == "S0" || cleaned == "SO" || cleaned == "S8") return "S0"
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

    fun read(words: List<Word>): Result {
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
                val box = Word(
                    code,
                    group.minOf { it.left },
                    group.minOf { it.top },
                    group.maxOf { it.right },
                    group.maxOf { it.bottom })
                val instances = candidates.getOrPut(code) { mutableListOf() }
                if (instances.none { abs(it.x - box.x) < box.height && abs(it.y - box.y) < box.height }) instances.add(
                    box
                )
                break
            }
        }
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
        return Result(output, anchors.keys.map { prefix(it) }.toSet(), anchors.values.toList())
    }

    fun validate(values: Map<String, String>, absent: Set<String> = emptySet()) {
        CaptureFieldValues.nullableValues(labels.keys, values, absent, ::number)
    }
}
