package com.bsd.akProd

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Test

/** Source-photo crops exercise the bitmap font, rather than ideal seven-segment drawings. */
class MonoHoldingPhotoRegressionTest {
    private fun correct(id: String,raw: String): String {
        val image=ImageIO.read(File("src/androidTest/assets/ocr/$id.png"))
        return DigitNumberCorrection.analyze(image.width,image.height,
            image.getRGB(0,0,image.width,image.height,null,0,image.width),raw).text
    }

    @Test fun holdingZerosAndLeadingZeroUseSourcePixels() {
        assertEquals("000",correct("20261116_090335_HP5","888"))
        assertEquals("000.0",correct("20261116_090335_HT5","888.8"))
        assertEquals("020.0",correct("20261116_090335_HT1","820.0"))
        assertEquals("075",correct("20261116_090335_HP1","875"))
    }

    @Test fun genuineEightAndNineInSourcePhotosArePreserved() {
        val root=File("src/androidTest/assets/ocr")
        val pattern=Regex("\"id\":\\s*\"([^\"]+)\"[\\s\\S]*?\"expected\":\\s*\"([^\"]+)\"")
        var checked=0
        pattern.findAll(File(root,"cases.json").readText()).forEach { match ->
            val expected=match.groupValues[2]
            if(expected.any { it in "89" }) {
                assertEquals(match.groupValues[1],expected,correct(match.groupValues[1],expected))
                checked++
            }
        }
        org.junit.Assert.assertTrue(checked>=20)
    }

    @Test fun invertedMeteringSelectionKeepsDecimalZero() {
        assertEquals("25.0",correct("20261116_090348_RV1","25.8"))
        assertEquals("25.0",correct("20261116_090252_RV1","25.8"))
    }

    @Test fun backPressureZeroIsVerifiedInBrightAndDimPhotos() {
        for(photo in listOf("20261116_090348","20261116_090252")) for(stage in listOf(5,4,2,1)) {
            assertEquals("$photo BP$stage","20",correct("${photo}_BP$stage","28"))
        }
    }
}
