package com.bsd.akProd

import org.junit.Assert.*
import org.junit.Test

class SevenSegmentVerifierTest {
    @Test fun sourceSmoothingPreservesGenuineEightAndNine() {
        for(digit in listOf("8","9")) for(light in listOf(true,false)) {
            val (w,p)=image(digit,light)
            assertEquals(digit,DigitNumberCorrection.analyzeStable(w,48,p,digit).text)
        }
    }
    @Test fun selectedYellowPanelPreservesRealEightAndNine() {
        for(digit in listOf("8","9")) {
            val (sourceWidth,source)=image(digit)
            val w=120; val h=70
            val pixels=IntArray(w*h) { -0xddddde }
            for(y in 5 until h-5) for(x in 5 until w-5) pixels[y*w+x]=0xffffff00.toInt()
            for(y in 0 until 48) for(x in 0 until sourceWidth) {
                if(source[y*sourceWidth+x]==-0x1000000) pixels[(y+11)*w+x+42]=-0x1000000
            }
            assertEquals(digit,DigitNumberCorrection.analyze(w,h,pixels,digit).text)
        }
    }
    private fun image(text: String, light: Boolean=true, partialMiddle: Boolean=false): Pair<Int,IntArray> {
        val width=text.length*28+8
        val background=if(light) -1 else -0x1000000
        val foreground=if(light) -0x1000000 else -1
        val pixels=IntArray(width*48) { background }
        text.forEachIndexed { index,c ->
            val x=4+index*28; val y=4
            fun bar(l:Int,t:Int,r:Int,b:Int) {
                for(yy in t until b) for(xx in l until r) pixels[(yy+y)*width+xx+x]=foreground
            }
            if(c=='.') { bar(0,35,3,38); return@forEachIndexed }
            bar(2,1,18,6); bar(2,34,18,39)
            bar(0,7,5,17); bar(15,7,20,17)
            if(c!='9') bar(0,22,5,33)
            bar(15,22,20,33)
            if(c=='8' || c=='9') bar(2,18,if(partialMiddle) 10 else 18,23)
        }
        return width to pixels
    }

    @Test fun missingMiddleIsZeroEvenWhenOcrSaysEight() {
        val (w,p)=image("0")
        val result=SevenSegmentVerifier.verify(w,48,p,"8")
        assertEquals("0",result.text); assertTrue(result.checked); assertFalse(result.uncertain)
    }
    @Test fun realMiddlePreservesEightInBothPolarities() {
        for(light in listOf(true,false)) {
            val (w,p)=image("8",light)
            val result=SevenSegmentVerifier.verify(w,48,p,"0")
            assertEquals("8",result.text); assertTrue(result.checked); assertFalse(result.uncertain)
        }
    }
    @Test fun disconnectedOuterSegmentsAndDecimalStaySeparate() {
        val (w,p)=image("0.8")
        val result=SevenSegmentVerifier.verify(w,48,p,"8.0")
        assertEquals("0.8",result.text); assertFalse(result.uncertain)
    }
    @Test fun brokenMiddleAbstainsRatherThanInventingZero() {
        val (w,p)=image("8",partialMiddle=true)
        val result=SevenSegmentVerifier.verify(w,48,p,"8")
        assertEquals("8",result.text); assertTrue(result.uncertain)
    }
    @Test fun mismatchedDigitCountAndUniformImageAbstain() {
        val (w,p)=image("0")
        assertTrue(SevenSegmentVerifier.verify(w,48,p,"88").uncertain)
        val result=SevenSegmentVerifier.verify(20,40,IntArray(800) { -1 },"8")
        assertEquals("8",result.text); assertFalse(result.checked)
    }

    @Test fun slashedZeroDoesNotBecomeAnEight() {
        val (w,p)=image("0")
        for(y in 8..33) {
            val x=4+17-(y-8)*15/25
            for(dx in -1..1) p[(y+4)*w+x+dx]=-0x1000000
        }
        val result=SevenSegmentVerifier.verify(w,48,p,"0")
        assertEquals("0",result.text)
        assertTrue(result.uncertain)
    }

    @Test fun leadingZerosArePreservedDuringShapeVerification() {
        val (w,p)=image("00.8")
        val result=SevenSegmentVerifier.verify(w,48,p,"88.0")
        assertEquals("00.8",result.text)
        assertFalse(result.uncertain)
    }
    @Test fun zeroMisreadAsNineIsCorrectedInBothPolarities() {
        for(light in listOf(true,false)) {
            val (w,p)=image("0",light)
            val result=SevenSegmentVerifier.verify(w,48,p,"9")
            assertEquals("0",result.text); assertTrue(result.checked); assertFalse(result.uncertain)
        }
    }
    @Test fun genuineNineIsPreservedAndCanBeDistinguishedFromEight() {
        for(raw in listOf("0","8","9")) {
            val (w,p)=image("9")
            val result=SevenSegmentVerifier.verify(w,48,p,raw)
            assertEquals("9",result.text); assertFalse(result.uncertain)
        }
    }
    @Test fun mixedDigitsAndDecimalAreCorrectedIndependently() {
        val (w,p)=image("089.0")
        val result=DigitNumberCorrection.analyze(w,48,p,"980.9")
        assertEquals("089.0",result.text)
        assertTrue(result.uncertainPositions.isEmpty())
    }
    @Test fun uncertainNeighborDoesNotDiscardCertainCorrection() {
        val (w,p)=image("08",partialMiddle=true)
        val result=DigitNumberCorrection.analyze(w,48,p,"98")
        assertEquals("08",result.text)
        assertFalse(0 in result.uncertainPositions)
        assertTrue(1 in result.uncertainPositions)
    }
    @Test fun learnedZeroCannotOverrideVerifiedEightOrNine() {
        val (zw,zp)=image("0")
        val templates=ZeroFontTemplates.learn(zw,48,zp,"0")
        val (w,p)=image("890")
        val result=DigitNumberCorrection.analyze(w,48,p,"898",templates)
        assertEquals("890",result.text)
    }
}
