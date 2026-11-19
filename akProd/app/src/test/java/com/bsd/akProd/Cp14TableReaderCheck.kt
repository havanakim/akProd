package com.bsd.akProd

/** Structured OCR-word fixtures; these do not measure ML Kit photograph recognition accuracy. */
object Cp14TableReaderCheck {
    @JvmStatic fun main(args: Array<String>) {
        val sample=linkedMapOf(
            "IP" to listOf("096","092","090","062","038"),
            "IV" to listOf("27.0","25.0","20.0","04.3","03.3"),
            "HP" to listOf("000","000","000","000","075"),
            "HT" to listOf("000.0","000.0","000.0","000.0","020.0"),
            "S" to listOf("010.0","050.0","100.0","130.0","148.0"),
            "RV" to listOf("24.0","24.0","24.0","25.0","25.0"),
            "BP" to listOf("20","20","20","20","20"))
        fun picture(groups: List<String>,xShift: Float=0f,yShift: Float=0f,split: Boolean=false)=buildList {
            groups.forEachIndexed { row,prefix ->
                for(index in 0..4) {
                    val code=prefix+(5-index); val x=.22f+index*.13f+xShift
                    val y=.17f+row*.105f+index*.002f+yShift
                    if(split) {
                        add(Cp14TableReader.Word(prefix,x-.025f,y,x+.006f,y+.022f))
                        add(Cp14TableReader.Word((5-index).toString(),x+.008f,y,x+.025f,y+.022f))
                    } else add(Cp14TableReader.Word(code,x-.025f,y,x+.025f,y+.022f))
                    add(Cp14TableReader.Word(sample.getValue(prefix)[index],x-.027f,y+.036f,x+.035f,y+.061f))
                }
            }
        }
        val injection=Cp14TableReader.read(picture(listOf("IP","IV","HP","HT")))
        check(injection.cells.size==20) { "Injection/holding headers missing: ${injection.cells.keys}" }
        check(injection.cells["IP5"]?.value=="96" && injection.cells["IP1"]?.value=="38")
        check(injection.cells["IV2"]?.value=="4.3" && injection.cells["IV1"]?.value=="3.3")
        check(injection.cells["HP5"]?.value=="0" && injection.cells["HT1"]?.value=="20")
        val metering=Cp14TableReader.read(picture(listOf("S","RV","BP")))
        check(metering.cells.size==15 && metering.cells["S1"]?.value=="148")
        check(metering.cells["S5"]?.value=="10" && metering.cells["RV2"]?.value=="25")
        check(metering.cells["BP1"]?.value=="20")
        check((injection.cells+metering.cells).size==35 && Cp14TableReader.labels.size==41)
        Cp14TableReader.validate((injection.cells+metering.cells).mapValues { it.value.value })
        Cp14TableReader.validate(injection.cells.mapValues { it.value.value })
        val split=Cp14TableReader.read(picture(listOf("IP","IV","HP","HT"),split=true))
        check(split.cells.size==20) { "Split headers: ${split.cells.keys}" }
        check(Cp14TableReader.read(picture(listOf("S","RV","BP"),xShift=-.12f,yShift=.13f)).cells.size==15)
        val missing=picture(listOf("IP","IV","HP","HT")).filterNot { it.text=="062" }
        check("IP2" !in Cp14TableReader.read(missing).cells && Cp14TableReader.read(missing).cells["IP1"]?.value=="38")
        val outside=picture(listOf("IP","IV","HP","HT"))+listOf(
            Cp14TableReader.Word("IF5",.1f,.05f,.15f,.07f),Cp14TableReader.Word("022.0",.1f,.09f,.15f,.12f),
            Cp14TableReader.Word("120.0",.2f,.85f,.25f,.88f))
        check(Cp14TableReader.read(outside).cells.size==21) { "IF settings must be mapped; current readings must not be mapped" }
        check(Cp14TableReader.read(emptyList()).cells.isEmpty())
        check(Cp14TableReader.number("000.0")=="0" && Cp14TableReader.number("")==null)
        check(Cp14TableReader.read(picture(listOf("IP")).map { if(it.text=="IP5") it.copy(text="1P5") else it }).cells["IP5"]?.value=="96")
        check(Cp14TableReader.read(picture(listOf("RV")).map { if(it.text.startsWith("RV")) it.copy(text=it.text.replace("RV","RW")) else it }).cells.size==5)
        check(runCatching { Cp14TableReader.validate((injection.cells+metering.cells).mapValues { it.value.value },setOf("HP5")) }.isFailure)
        val graphLegend=picture(listOf("IP","IV","HP","HT"))+Cp14TableReader.Word("HP5",.90f,.78f,.96f,.81f)
        check(Cp14TableReader.read(graphLegend).cells["HP5"]?.value=="0") { "Graph legend must not replace the table header" }
        println("CP14 reader: 20 checks passed (35 header fields, both pages, split headers, graph legends, zero values, missing-cell isolation)")
    }
}
