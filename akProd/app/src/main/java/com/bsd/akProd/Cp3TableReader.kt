package com.bsd.akProd

import kotlin.math.abs

/** CP3's stage columns, independent of Android and of the photograph's absolute position. */
object Cp3TableReader {
    const val INJECTION="INJECTION_HOLDING"
    const val METERING="METERING_BACK_PRESSURE"
    /** The screen page for a stored SCREEN_TYPE; a record without one is the 사출/보압 page. */
    fun pageOf(stored: String?)=if(stored==METERING) METERING else INJECTION
    val meteringStages=listOf("SUCKBACK_1")+(1..5).map { "METER_$it" }
    val disabledMeteringFields=setOf("SUCKBACK_1_BACK_PRESSURE")
    val disabledInjectionFields=setOf("HOLD_3_SPEED","HOLD_2_SPEED","HOLD_3_POSITION","HOLD_2_POSITION","HOLD_1_POSITION")
    fun disabledFor(page: String)=if(page==METERING) disabledMeteringFields else disabledInjectionFields
    val meteringRows=linkedMapOf("PRESSURE" to "압력 (kgf/cm²)","SPEED" to "속도 (%)","POSITION" to "위치 (mm)","BACK_PRESSURE" to "배압 (kgf/cm²)")
    val meteringLabels=linkedMapOf<String,String>().apply {
        meteringStages.forEach { stage -> meteringRows.forEach { (row,title) -> put("${stage}_$row",stageName(stage)+" · $title") } }
    }
    fun labelsFor(page: String)=if(page==METERING) meteringLabels else labels
    fun stagesFor(page: String)=if(page==METERING) meteringStages else stages
    fun rowsFor(page: String)=if(page==METERING) meteringRows else rowLabels
    const val NAME = "CP3호기"
    const val MACHINE = "CP3"
    val stages = listOf("HOLD_3", "HOLD_2", "HOLD_1", "INJECT_5", "INJECT_4", "INJECT_3", "INJECT_2", "INJECT_1")
    val rowLabels = linkedMapOf("PRESSURE" to "압력 (kgf/cm²)", "SPEED" to "속도 (%)", "TIME" to "시간 (sec)", "POSITION" to "위치 (mm)")
    val labels = linkedMapOf<String,String>().apply {
        stages.forEach { stage -> rowLabels.forEach { (row,label) ->
            put("${stage}_$row", stageName(stage)+" · "+label)
        } }
    }
    val blankAllowed = setOf("HOLD_3_SPEED", "HOLD_2_SPEED")

    /**
     * Saved JSON FIELDS names, by stage prefix and row; the stage number follows the name.
     * 사출: INJECT_5_PRESSURE → IP5, _SPEED → IV, _TIME → IT, _POSITION → IF.
     * 계량: METER_1_PRESSURE → RP1, _SPEED → RV, _POSITION → S, _BACK_PRESSURE → BP.
     * 보압 (HOLD_…) and 흘림방지 (SUCKBACK_…) keep their internal names.
     */
    private val storedRowNames = mapOf(
        "INJECT" to mapOf("PRESSURE" to "IP", "SPEED" to "IV", "TIME" to "IT", "POSITION" to "IF"),
        "METER" to mapOf("PRESSURE" to "RP", "SPEED" to "RV", "POSITION" to "S", "BACK_PRESSURE" to "BP")
    )
    private val storedNames: Map<String, String> = buildMap {
        storedRowNames.forEach { (prefix, rows) ->
            for (n in 1..5) rows.forEach { (row, name) -> put("${prefix}_${n}_$row", "$name$n") }
        }
    }
    private val internalNames = storedNames.entries.associate { (key, name) -> name to key }

    /** The FIELDS name saved for an internal [key]; keys without a short name are saved as they are. */
    fun storedKey(key: String): String = storedNames[key] ?: key

    /** The internal key for a saved FIELDS name (the reverse of [storedKey]). */
    fun internalKey(stored: String): String = internalNames[stored] ?: stored
    fun stageName(stage: String) = (if(stage.startsWith("SUCKBACK")) "흘림방지" else if(stage.startsWith("METER")) "계량 " else if(stage.startsWith("HOLD")) "보압 " else "사출 ")+stage.substringAfterLast('_')
    data class Word(val text: String,val left: Float,val top: Float,val right: Float,val bottom: Float) {
        val x get()=(left+right)/2
        val y get()=(top+bottom)/2
        val height get()=bottom-top
    }
    data class Cell(val value: String, val words: List<Word>)
    data class Result(val cells: Map<String,Cell>, val visibleStages: Set<String>, val headers: List<Word> = emptyList())
    private data class Header(val stage: String,val word: Word)
    private fun compact(s: String)=s.replace(Regex("\\s"),"")
    fun number(raw: String): String? {
        val value=compact(raw).replace('O','0').replace('o','0')
        return value.takeIf { it.matches(Regex("[0-9]{1,9}(?:\\.[0-9]{1,4})?")) }
            ?.toBigDecimal()?.stripTrailingZeros()?.toPlainString()
    }
    fun read(words: List<Word>,page: String=INJECTION): Result {
        val metering=page==METERING
        val labels=labelsFor(page)
        val rowLabels=rowsFor(page)
        val headerPattern=Regex(if(metering) "^(계량[12345]|흘림방지1)$" else "^(보압[123]|사출[12345])$")
        val headers=mutableListOf<Header>()
        words.forEach { first ->
            // Korean and the stage number are often separate OCR elements.
            val neighbours=words.filter { it.left>=first.left &&
                abs(it.y-first.y)<=maxOf(it.height,first.height)*.65f &&
                it.left-first.right<=maxOf(first.height,it.height)*4 }
                .sortedBy { it.left }.take(3)
            for(count in 1..neighbours.size) {
                val group=neighbours.take(count)
                val title=compact(group.joinToString("") { it.text })
                if (!headerPattern.matches(title)) continue
                val stage=(if(title.startsWith("흘림방지")) "SUCKBACK_" else if(metering) "METER_" else if(title.startsWith("보압")) "HOLD_" else "INJECT_")+title.last()
                val box=Word(title,group.minOf { it.left },group.minOf { it.top },group.maxOf { it.right },group.maxOf { it.bottom })
                if(headers.none { it.stage==stage && abs(it.word.y-box.y)<box.height }) headers.add(Header(stage,box))
                break
            }
        }
        if(headers.isEmpty()) return Result(emptyMap(),emptySet())
        // Alarm/footer headings must not become part of the main grid. Retain the largest header band.
        val band=headers.maxBy { h -> headers.count { abs(it.word.y-h.word.y)<maxOf(h.word.height,it.word.height)*2.5f } }
        val active=headers.filter { abs(it.word.y-band.word.y)<maxOf(it.word.height,band.word.height)*2.5f }
            .groupBy { it.stage }.filterValues { it.size==1 }.values.map { it.single() }.sortedBy { it.word.x }
        if(active.isEmpty()) return Result(emptyMap(),emptySet())
        val columnGap=active.zipWithNext { a,b -> b.word.x-a.word.x }.sorted().let { if(it.isEmpty()) active.first().word.height*4 else it[it.size/2] }
        val numeric=words.filter { number(it.text)!=null || compact(it.text)=="." }
        val columns=linkedMapOf<Header,List<Cell>>()
        active.forEachIndexed { index,header ->
            val left=if(index>0) (active[index-1].word.x+header.word.x)/2 else header.word.x-columnGap*.48f
            val right=if(index+1<active.size) (active[index+1].word.x+header.word.x)/2 else header.word.x+columnGap*.48f
            val candidates=numeric.filter { it.x in left..right && it.y>header.word.bottom+header.word.height*.08f &&
                it.y<header.word.bottom+header.word.height*9f }.sortedBy { it.y }
            val lines=mutableListOf<MutableList<Word>>()
            candidates.forEach { word ->
                val line=lines.lastOrNull()
                if(line!=null && abs(line.map { it.y }.average()-word.y)<word.height*.65f) line.add(word)
                else lines.add(mutableListOf(word))
            }
            columns[header]=lines.mapNotNull { line ->
                val ordered=line.sortedBy { it.left }
                if(ordered.zipWithNext().any { (a,b) -> b.left-a.right>maxOf(a.height,b.height) }) null
                else number(ordered.joinToString("") { it.text })?.let { Cell(it,ordered) }
            }
        }
        // A full four-cell injection column establishes row offsets without relying on Korean row OCR.
        val reference=columns.filter { (it.key.stage.startsWith("METER") || it.key.stage.startsWith("INJECT")) && it.value.size==4 }
            .filter { (_,cells) ->
                val gaps=cells.zipWithNext { a,b -> b.words.map { it.y }.average()-a.words.map { it.y }.average() }
                val mid=gaps.sorted()[1]
                mid>0 && gaps.all { it in mid*.55..mid*1.5 }
            }
        val offsets=if(reference.isNotEmpty()) (0..3).map { row ->
            reference.map { (h,cells) -> cells[row].words.map { it.y }.average().toFloat()-h.word.y }.sorted().let { it[it.size/2] }
        } else {
            // On a left-only photograph, use all four row labels and compensate the header slope.
            val rowNames=if(metering) listOf("압력","속도","위치","배압") else listOf("압력","속도","시간","위치")
            val first=active.first().word; val last=active.last().word
            val slope=if(last.x-first.x>.001f) (last.y-first.y)/(last.x-first.x) else 0f
            rowNames.map { row ->
                words.filter { compact(it.text)==row && it.x<active.first().word.x && it.y>first.y }
                    .singleOrNull()?.let { it.y-(first.y+slope*(it.x-first.x)) }
            }.let { if(it.any { value -> value==null }) return Result(emptyMap(),active.map { it.stage }.toSet(),active.map { it.word }) else it.filterNotNull() }
        }
        if(offsets.zipWithNext().any { (a,b) -> b<=a }) return Result(emptyMap(),active.map { it.stage }.toSet(),active.map { it.word })
        val rowGap=offsets.zipWithNext { a,b -> b-a }.sorted()[1]
        val output=linkedMapOf<String,Cell>()
        columns.forEach { (header,cells) ->
            rowLabels.keys.forEachIndexed { row,key ->
                val field="${header.stage}_$key"
                if(field in disabledFor(page)) return@forEachIndexed
                if(field !in labels) return@forEachIndexed
                val matches=cells.filter { cell -> abs(cell.words.map { it.y }.average()-(header.word.y+offsets[row]))<rowGap*.38f }
                if(matches.size==1) output[field]=matches.single()
            }
        }
        return Result(output,active.map { it.stage }.toSet(),active.map { it.word })
    }
    fun validate(values: Map<String,String>, absent: Set<String>,page: String=INJECTION) {
        require(values.keys.all { it in labelsFor(page) }) { "다른 화면의 항목이 포함되어 있습니다." }
        require(disabledFor(page).all { values[it].isNullOrBlank() }) { "표시 없는 항목은 입력할 수 없습니다." }
        CaptureFieldValues.nullableValues(labelsFor(page).keys,values,absent,::number)
    }
}
