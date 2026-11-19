package com.bsd.akProd
import org.junit.Assert.*
import org.junit.Test
import javax.imageio.ImageIO
class LcdScanlineTest {
    private fun source(name:String,raw:String):DigitNumberCorrection.Result {
        val image=ImageIO.read(javaClass.getResourceAsStream("/lcd/$name"))
        val crop=image.getSubimage(0,image.height/4,image.width,image.height-image.height/4)
        return DigitNumberCorrection.analyzeStable(crop.width,crop.height,crop.getRGB(0,0,crop.width,crop.height,null,0,crop.width),raw)
    }
    @Test fun scanlinesDoNotHideThreeSourceZeros() {
        val result=source("zero-scanlines.png","888")
        assertEquals("000",result.text); assertTrue(result.uncertainPositions.isEmpty())
    }
    @Test fun genuineNinetyFiveNeverBecomesAllZero() {
        assertEquals("095",source("value-095.png","895").text)
        assertNotEquals("000",source("value-095.png","888").text)
    }
}
