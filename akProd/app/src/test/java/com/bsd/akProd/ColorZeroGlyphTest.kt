package com.bsd.akProd

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Cells cut from androidTest/assets/coloredInjection photos (EXIF-rotated 90°, so 3000x4000 upright).
 * OCR text is what ML Kit returned for the cell; truth is what the panel shows.
 */
class ColorZeroGlyphTest {
    private val photos = mutableMapOf<String, BufferedImage>()
    private fun photo(name: String) = photos.getOrPut(name) {
        val raw = ImageIO.read(File("src/androidTest/assets/coloredInjection/$name.jpg"))
        BufferedImage(raw.height, raw.width, BufferedImage.TYPE_INT_RGB).also { out ->
            for (y in 0 until raw.height) for (x in 0 until raw.width) out.setRGB(raw.height - 1 - y, x, raw.getRGB(x, y))
        }
    }

    private data class Case(val photo: String, val x: Int, val y: Int, val w: Int, val h: Int, val ocr: String, val expected: String?)

    private val cases = listOf(
        Case("20261116_090526", 2290, 2080, 90, 75, "18", "10"),
        Case("20261116_090526", 2600, 2375, 215, 70, "190.0", "100.0"),
        // Genuine 8s must never become 0.
        Case("20261116_090526", 2265, 2365, 175, 75, "48.0", null),
        Case("20261116_090526", 1980, 1930, 80, 75, "78", null),
        // Glare wiped the lower half of this 8 (OCR: 79); it must not be read as 70.
        Case("20261116_090538", 1355, 1235, 70, 57, "79", null),
        // A lone "0" ML Kit skipped (HOLD_3 pressure): tight box and the whole trimmed cell interior.
        Case("20261116_090526", 955, 1910, 70, 75, "8", "0"),
        Case("20261116_090526", 785, 1900, 250, 85, "8", "0")
    )

    /** Same trimming as [ColorZeroGlyph.loneZero], on the cell rectangle the app logged for HOLD_3 pressure. */
    @Test
    fun loneZeroInAppCell() {
        val image = photo("20261116_090526")
        val l = .2526f; val t = .4702f; val r = .3533f; val b = .5148f
        val x = ((l + (r - l) * .08f) * image.width).toInt(); val y = ((t + (b - t) * .12f) * image.height).toInt()
        val w = ((r - (r - l) * .08f) * image.width).toInt() - x; val h = ((b - (b - t) * .12f) * image.height).toInt() - y
        val px = image.getRGB(x, y, w, h, null, 0, w)
        assertEquals(true, ColorZeroGlyph.loneZero(w, h, px))
    }

    @Test
    fun dottedZeroOnlyReplacesVerifiedZeros() {
        cases.forEach { c ->
            val image = photo(c.photo)
            val px = image.getRGB(c.x, c.y, c.w, c.h, null, 0, c.w)
            val actual = ColorZeroGlyph.correct(c.w, c.h, px, c.ocr)
            if (actual != c.expected) println(ColorZeroGlyph.describe(c.w, c.h, px, c.ocr.count { it != '.' }))
            assertEquals("${c.photo} ${c.ocr}", c.expected, actual)
        }
    }
}
