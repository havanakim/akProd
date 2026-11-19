package com.bsd.akProd

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorDigitProtectionTest {
    @Test fun colorFontEightIsNotErasedBySegmentClassifier() {
        assertEquals("138.0",OcrNumericGlyphs.preserveColorDigits("138.0","130.0"))
        assertEquals("12.8",OcrNumericGlyphs.preserveColorDigits("12.8","12.0"))
    }
    @Test fun moireDoesNotReplaceAnOcrZeroWithEightOrNine() {
        assertEquals("140",OcrNumericGlyphs.preserveColorDigits("140","149"))
        assertEquals("50.0",OcrNumericGlyphs.preserveColorDigits("50.0","58.8"))
    }
    @Test fun independentlyVerifiedNineToZeroRemainsAvailable() {
        assertEquals("140",OcrNumericGlyphs.preserveColorDigits("149","140"))
    }
}
