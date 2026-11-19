package com.bsd.akProd

import org.junit.Assert.*
import org.junit.Test

class MonoPositionTest {
    @Test fun allSixSettingsAreMappedAndCurrentPositionIsExcluded() {
        for(tilt in listOf(0f,.003f,-.003f)) {
            val words=mutableListOf<Cp14TableReader.Word>()
            val codes=listOf("IF5","(==|F4","(==IF3","(==IFZ","(==IF1","S8")
            val expected=listOf("41","44","75","100","114","130")
            codes.forEachIndexed { i,code ->
                val x=.08f+i*.145f; val y=.2f+i*tilt
                words.add(Cp14TableReader.Word(code,x,y,x+.07f,y+.025f))
                words.add(Cp14TableReader.Word(expected[i]+".0",x,y+.04f,x+.09f,y+.065f))
            }
            words.add(Cp14TableReader.Word("[0132.1 mm]",.75f,.1f,.95f,.13f))
            words.add(Cp14TableReader.Word("021.1",.4f,.7f,.5f,.73f))
            val result=Cp14TableReader.read(words).cells.mapValues { it.value.value }
            assertEquals(6,result.size)
            listOf("IF5","IF4","IF3","IF2","IF1","S0").forEachIndexed { i,key -> assertEquals(key,expected[i],result[key]) }
            assertFalse(result.values.contains("132.1"))
        }
    }

    @Test fun positionSettingsBelongOnlyToInjectionPageAndAreSerializable() {
        val keys=Cp14TableReader.labelsFor(Cp14TableReader.INJECTION).keys
        assertEquals(26,keys.size)
        assertTrue(keys.containsAll(listOf("IF5","IF4","IF3","IF2","IF1","S0")))
        assertFalse("S0" in Cp14TableReader.labelsFor(Cp14TableReader.METERING))
        val data=CaptureFieldValues.nullableValues(keys,mapOf("IF5" to "41.0","S0" to "130.0"),number=Cp14TableReader::number)
        assertEquals("41.0",data["IF5"])
        assertEquals("130.0",data["S0"])
        assertNull(data["IF4"])
    }
}
