package com.bsd.akProd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Real 8 against slashed 0 on photographed HYUNDAI MIRACON-I screens. Cell boxes are the OCR word boxes the
 * app logged for these photos (androidTest assets); the crop margin is the one MonoTableOcr uses.
 */
class MiraconEightGlyphTest {
    private fun realEights(photo: String, text: String, l: Float, t: Float, r: Float, b: Float): Set<Int>? {
        val image = ImageIO.read(File("src/androidTest/assets/$photo"))
        val w = image.width; val h = image.height
        val pad = (b - t) * .3f
        val x0 = ((l - pad) * w).toInt().coerceAtLeast(0); val x1 = ((r + pad) * w).toInt().coerceAtMost(w)
        val y0 = ((t - (b - t) * .25f) * h).toInt().coerceAtLeast(0); val y1 = ((b + (b - t) * .25f) * h).toInt().coerceAtMost(h)
        val pixels = IntArray((x1 - x0) * (y1 - y0))
        image.getRGB(x0, y0, x1 - x0, y1 - y0, pixels, 0, x1 - x0)
        return MiraconEightGlyph.realEights(x1 - x0, y1 - y0, pixels, text)
    }

    @Test
    fun confirmsRealEightsOfPb6() {
        // BP 08 and S5 080.0 on PB 6호기.
        assertTrue(1 in realEights("pbmiracon1127/pb6_0522.jpg", "08", .3693f, .4275f, .3893f, .4370f).orEmpty())
        assertTrue(1 in realEights("pbmiracon1127/pb6_0524.jpg", "08", .3827f, .4180f, .4013f, .4270f).orEmpty())
        assertTrue(1 in realEights("pbmiracon1127/pb6_0522.jpg", "080.0", .3180f, .4925f, .3713f, .5020f).orEmpty())
        assertTrue(1 in realEights("pbmiracon1127/pb6_0526.jpg", "080.0", .3687f, .6005f, .4160f, .6105f).orEmpty())
    }

    @Test
    fun refusesSlashedZerosReadAsEight() {
        // 60.0 read as 68.0, 40.0 read as 40.8 on the CP MIRACON-I photos.
        assertFalse(1 in realEights("miracon1120/mir1113_164333.jpg", "68.0", .3207f, .6035f, .3507f, .6100f).orEmpty())
        assertFalse(3 in realEights("miracon1120/mir1113_164333.jpg", "40.8", .4433f, .6015f, .4727f, .6085f).orEmpty())
        // 080.0 read as 880.0 from a crop that cuts the leading 0: never confirmed.
        assertFalse(0 in realEights("pbmiracon1127/pb6_0524.jpg", "880.0", .3424f, .4763f, .3854f, .4881f).orEmpty())
    }

    @Test
    fun needsAZeroInTheCellToCalibrate() {
        // Without a 0 read as 0 in the same cell there is nothing to compare the slash against.
        assertTrue(realEights("pbmiracon1127/pb6_0522.jpg", "8", .3693f, .4275f, .3893f, .4370f) == null)
    }
}
