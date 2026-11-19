package com.bsd.akProd

import org.junit.Assert.*
import org.junit.Test

class MeteringPhotoTableTest {
    @Test fun photoHeadersKeepStageAndDecimalValues() {
        val words=mutableListOf<Cp14TableReader.Word>()
        val rows=listOf("S" to listOf("010.0","050.0","100.0","130.0","148.0"),
            "RV" to listOf("24.0","24.0","24.0","25.0","25.0"),
            "BP" to List(5) { "20" })
        rows.forEachIndexed { row,(prefix,values) ->
            values.forEachIndexed { col,value ->
                val x=.05f+col*.19f; val y=.1f+row*.25f
                words.add(Cp14TableReader.Word(prefix+(5-col),x,y,x+.06f,y+.04f))
                words.add(Cp14TableReader.Word(value,x,y+.06f,x+.08f,y+.10f))
            }
        }
        val cells=Cp14TableReader.read(words).cells.mapValues { it.value.value }
        assertEquals(15,cells.size)
        assertEquals("10",cells["S5"])
        assertEquals("148",cells["S1"])
        assertEquals("24",cells["RV3"])
        assertEquals("25",cells["RV1"])
        assertTrue((1..5).all { cells["BP$it"]=="20" })
        Cp14TableReader.validatePage(cells,Cp14TableReader.METERING)
    }

    @Test fun meteringScopeExcludesInjectionFields() {
        assertEquals(15,Cp14TableReader.labelsFor(Cp14TableReader.METERING).size)
        assertTrue(runCatching {
            Cp14TableReader.validatePage(mapOf("IP1" to "38"),Cp14TableReader.METERING)
        }.isFailure)
    }

    @Test fun monitorHeaderLookalikesKeepTheirColumns() {
        val codes=listOf("${'$'}5","84","${'$'}3","82","81","BU1","RU2")
        val words=codes.flatMapIndexed { index,code ->
            val x=.02f+index*.13f
            listOf(Cp14TableReader.Word(code,x,.1f,x+.05f,.13f),
                Cp14TableReader.Word("25.0",x,.15f,x+.06f,.18f))
        }
        val cells=Cp14TableReader.read(words).cells
        assertTrue(cells.keys.containsAll(listOf("S5","S4","S3","S2","S1","RV1","RV2")))
    }
}
