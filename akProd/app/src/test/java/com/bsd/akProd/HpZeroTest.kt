package com.bsd.akProd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * The source-pixel zero check that settles HP 000 on MIRACON-I photos (PB 6호기 사출 및 보압): every glyph of HP2/HP1
 * 000 verifies as a zero, while HP3 043 never does. Cell boxes are read off the photos.
 */
class HpZeroTest {
    private fun allZero(photo: String, l: Int, t: Int, r: Int, b: Int): Boolean {
        val image = ImageIO.read(File("src/androidTest/assets/pbinjection1127/$photo.jpg"))
        val px = IntArray((r - l) * (b - t)); image.getRGB(l, t, r - l, b - t, px, 0, r - l)
        val result = DigitNumberCorrection.analyzeStable(r - l, b - t, px, "888")
        return result.text == "000" && result.uncertainPositions.isEmpty()
    }

    @Test
    fun hp000VerifiesAsZero() {
        assertTrue(allZero("pb6inj_10", 755, 1103, 840, 1155)); assertTrue(allZero("pb6inj_10", 922, 1103, 1008, 1155))
        assertTrue(allZero("pb6inj_9", 618, 1122, 706, 1172)); assertTrue(allZero("pb6inj_9", 782, 1130, 872, 1182))
        assertTrue(allZero("pb6inj_7", 488, 1064, 544, 1100)); assertTrue(allZero("pb6inj_7", 584, 1064, 640, 1100))
    }

    @Test
    fun hp043NeverVerifiesAsZero() {
        assertFalse(allZero("pb6inj_10", 585, 1100, 670, 1152)); assertFalse(allZero("pb6inj_9", 462, 1112, 548, 1162))
        assertFalse(allZero("pb6inj_8", 508, 1215, 592, 1268)); assertFalse(allZero("pb6inj_7", 392, 1064, 448, 1100))
    }
}
