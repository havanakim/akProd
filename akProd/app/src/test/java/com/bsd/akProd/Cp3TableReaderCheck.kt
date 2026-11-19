package com.bsd.akProd

/** OCR token fixtures: tests mapping and validation, not actual photograph recognition accuracy. */
object Cp3TableReaderCheck {
    @JvmStatic fun main(args: Array<String>) {
        val rows=listOf("PRESSURE","SPEED","TIME","POSITION")
        val values=listOf(
            listOf("0",null,"0.0",null),listOf("0",null,"0.0",null),listOf("78","8","25.0",null),
            listOf("78","11","30.0","23.0"),listOf("76","10","30.0","48.0"),
            listOf("66","5","30.0","100.0"),listOf("40","2","20.0","138.0"),listOf("22","2","20.0","160.0"))
        fun picture(indices: List<Int>,shift: Float=0f,splitHeader: Boolean=false): List<Cp3TableReader.Word> = buildList {
            for(i in indices) {
                val x=.10f+i*.105f; val top=.18f+i*.002f+shift
                val stage=Cp3TableReader.stages[i]
                if(splitHeader) {
                    add(Cp3TableReader.Word(if(stage.startsWith("HOLD")) "보압" else "사출",x-.035f,top,x+.005f,top+.025f))
                    add(Cp3TableReader.Word(stage.last().toString(),x+.007f,top,x+.025f,top+.025f))
                } else add(Cp3TableReader.Word(Cp3TableReader.stageName(stage),x-.035f,top,x+.035f,top+.025f))
                values[i].forEachIndexed { row,value -> if(value!=null) {
                    val y=top+.05f+row*.045f
                    add(Cp3TableReader.Word(value,x-.021f,y,x+.025f,y+.022f))
                } }
            }
        }
        check(Cp3TableReader.labels.size==32)
        val full=Cp3TableReader.read(picture((0..7).toList()))
        check(full.visibleStages.size==8 && full.cells.size==27) { "Full grid must have 27 displayed cells: ${full.cells.keys}" }
        check(full.cells["HOLD_3_PRESSURE"]?.value=="0")
        check(full.cells["HOLD_3_TIME"]?.value=="0")
        check("HOLD_3_SPEED" !in full.cells && "HOLD_2_SPEED" !in full.cells)
        check(full.cells["HOLD_1_SPEED"]?.value=="8")
        check(full.cells["INJECT_5_PRESSURE"]?.value=="78" && full.cells["INJECT_1_POSITION"]?.value=="160")
        val left=Cp3TableReader.read(picture((0..5).toList()))
        val right=Cp3TableReader.read(picture((2..7).toList()))
        check(left.cells.keys.none { it.startsWith("INJECT_1_") })
        check(right.cells.keys.none { it.startsWith("HOLD_3_") })
        check((left.cells+right.cells).mapValues { it.value.value }==full.cells.mapValues { it.value.value })
        check(Cp3TableReader.read(picture((0..7).toList(),shift=.23f)).cells.size==27)
        check(Cp3TableReader.read(picture((0..7).toList(),splitHeader=true)).cells.size==27)
        val missing=picture((0..7).toList()).filterNot { it.text=="100.0" }
        check("INJECT_3_POSITION" !in Cp3TableReader.read(missing).cells)
        check(Cp3TableReader.read(missing).cells["INJECT_3_TIME"]?.value=="30")
        check(Cp3TableReader.read(emptyList()).cells.isEmpty())
        Cp3TableReader.validate(full.cells.mapValues { it.value.value },Cp3TableReader.blankAllowed)
        Cp3TableReader.validate(full.cells.mapValues { it.value.value },emptySet())
        check(runCatching { Cp3TableReader.validate(full.cells.mapValues { it.value.value },setOf("INJECT_1_SPEED")) }.isFailure)
        check(Cp3TableReader.number("0")=="0" && Cp3TableReader.number("0.0")=="0" && Cp3TableReader.number("")==null)
        check(Cp3TableReader.read(picture((3..7).toList())).cells.size==20)
        println("CP3 table reader: 20 checks passed; 32 fields (5 disabled), blank-vs-zero, full/left/right images, split headings, row omissions")
    }
}
