package com.bsd.akProd

import org.junit.Assert.*
import org.junit.Test

class CaptureFieldValuesTest {
    @Test fun zeroFormsRemainNumbersInBothReaders() {
        for(raw in listOf("0","000","0.0","000.0","000.0000")) {
            assertEquals("0",Cp3TableReader.number(raw))
            assertEquals("0",Cp14TableReader.number(raw))
            val values=CaptureFieldValues.nullableValues(setOf("value"),mapOf("value" to raw),number=Cp3TableReader::number)
            assertNotNull(values["value"])
            assertEquals(raw,values["value"])
        }
    }
    @Test fun missingAndBlankAreNullWhileZeroIsPresent() {
        val values=CaptureFieldValues.nullableValues(setOf("zero","blank","missing"),
            mapOf("zero" to "000.0","blank" to "  "),number=Cp14TableReader::number)
        assertEquals("000.0",values["zero"])
        assertTrue(values.containsKey("blank")); assertNull(values["blank"])
        assertTrue(values.containsKey("missing")); assertNull(values["missing"])
    }
    @Test fun zeroOcrTokensMapToTheirCellsWithoutBeingDropped() {
        for(raw in listOf("000","000.0")) {
            val mono=Cp14TableReader.read(listOf(
                Cp14TableReader.Word("IP5",.1f,.1f,.2f,.13f),
                Cp14TableReader.Word(raw,.1f,.16f,.2f,.18f)))
            assertEquals("0",mono.cells["IP5"]?.value)
            val words=mutableListOf(Cp3TableReader.Word("사출1",.4f,.1f,.5f,.13f))
            repeat(4) { row ->
                val top=.15f+row*.05f
                words.add(Cp3TableReader.Word(raw,.41f,top,.49f,top+.02f))
            }
            val colored=Cp3TableReader.read(words)
            assertEquals(4,colored.cells.size)
            assertTrue(colored.cells.values.all { it.value=="0" })
        }
    }
    @Test fun partialPagesAndEmptyColoredFormCanBeRegistered() {
        Cp14TableReader.validatePage(mapOf("IP5" to "000.0"),Cp14TableReader.INJECTION)
        Cp14TableReader.validatePage(emptyMap(),Cp14TableReader.METERING)
        Cp3TableReader.validate(mapOf("INJECT_1_SPEED" to "000"),emptySet())
        Cp3TableReader.validate(emptyMap(),emptySet())
    }
    @Test fun invalidNonblankValuesAndUnknownKeysAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Cp14TableReader.validatePage(mapOf("IP5" to "not a number"),Cp14TableReader.INJECTION)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Cp3TableReader.validate(mapOf("unknown" to "0"),emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            Cp3TableReader.validate(mapOf("INJECT_1_SPEED" to "0"),setOf("INJECT_1_SPEED"))
        }
    }
}
