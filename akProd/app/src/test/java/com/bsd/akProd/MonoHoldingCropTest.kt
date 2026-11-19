package com.bsd.akProd

import org.junit.Assert.assertEquals
import org.junit.Test

class MonoHoldingCropTest {
    @Test fun ocrLetterBIsAnEightCandidateWithoutChangingManualValidation() {
        assertEquals("895",OcrNumericGlyphs.canonical("B95"))
        assertEquals("BP1",OcrNumericGlyphs.canonical("BP1"))
        org.junit.Assert.assertNull(Cp14TableReader.number("B95"))
    }
    @Test fun rightHoldingValueIsNotClippedByLeftTimeHeader() {
        val pressure=Cp14TableReader.Word("HP1",.8f,.45f,.86f,.49f)
        val left=Cp14TableReader.Word("HT5",.1f,.50f,.16f,.54f)
        val right=Cp14TableReader.Word("HT1",.8f,.59f,.86f,.63f)
        assertEquals(.59f,Cp14TableReader.nextRowTop(pressure,listOf(pressure,left,right))!!,.0001f)
    }
}
