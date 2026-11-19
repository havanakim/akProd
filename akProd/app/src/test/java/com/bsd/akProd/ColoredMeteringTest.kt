package com.bsd.akProd
import org.junit.Assert.*
import org.junit.Test

class ColoredMeteringTest {
    @Test fun emptyDarkCellsAreUnavailableButPrintedZeroIsPreserved() {
        assertEquals(5,Cp3TableReader.disabledFor(Cp3TableReader.INJECTION).size)
        assertTrue("SUCKBACK_1_BACK_PRESSURE" in Cp3TableReader.disabledFor(Cp3TableReader.METERING))
        val values=mapOf("SUCKBACK_1_PRESSURE" to "0","SUCKBACK_1_BACK_PRESSURE" to "")
        Cp3TableReader.validate(values,emptySet(),Cp3TableReader.METERING)
        val stored=CaptureFieldValues.nullableValues(Cp3TableReader.meteringLabels.keys,values,emptySet(),Cp3TableReader::number)
        assertEquals("0",stored["SUCKBACK_1_PRESSURE"])
        assertNull(stored["SUCKBACK_1_BACK_PRESSURE"])
    }
    @Test fun ascendingMeteringHeadersDoNotCollectLowerPurgeNumbers() {
        val words=mutableListOf<Cp3TableReader.Word>()
        for(stage in 1..5) {
            val x=.2f+stage*.12f
            words.add(Cp3TableReader.Word("계량$stage",x,.2f,x+.07f,.23f))
            listOf("140","42",listOf("10.0","50.0","100.0","150.0","186.0")[stage-1],"24").forEachIndexed { row,value ->
                words.add(Cp3TableReader.Word(value,x,.25f+row*.045f,x+.06f,.275f+row*.045f))
            }
            words.add(Cp3TableReader.Word("999",x,.62f,x+.07f,.65f))
        }
        val result=Cp3TableReader.read(words,Cp3TableReader.METERING)
        assertEquals(20,result.cells.size)
        assertEquals("186",result.cells["METER_5_POSITION"]?.value)
        assertEquals("24",result.cells["METER_1_BACK_PRESSURE"]?.value)
    }
}
