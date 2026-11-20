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
        // Camera originals are stored landscape with EXIF rotation; a forwarded copy is already upright.
        if (raw.width < raw.height) return@getOrPut raw
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
        Case("20261116_090526", 785, 1900, 250, 85, "8", "0"),
        // The same panel forwarded at half size (1500x2000, digits ~25 px tall), as reported from the device.
        Case("20261116_090538_half", 1058, 780, 90, 36, "138.0", null),
        Case("20261116_090538_half", 913, 780, 90, 36, "180.0", "100.0"),
        Case("20261116_090538_half", 250, 727, 70, 36, "8.0", "0.0"),
        Case("20261116_090538_half", 522, 622, 50, 40, "78", null),
        Case("20261116_090538_half", 522, 672, 35, 40, "8", null),
        Case("20261116_090538_half", 780, 778, 90, 38, "48.0", null),
        // Dust smudge on the zero of 30.0 (INJECT_5 time), read as 39.0.
        Case("20261116_090526", 1876, 2204, 164, 76, "39.0", "30.0"),
        Case("20261116_090526_half", 938, 1102, 82, 38, "39.0", "30.0")
    )

    /**
     * Pressure cells of the half-size forwarded photo, as the app found them. HOLD_3's "0" must be
     * found; glare has all but erased HOLD_2's "0", which must stay blank rather than be guessed.
     */
    @Test
    fun loneZeroOnHalfSizePhoto() {
        val image = photo("20261116_090538_half")
        fun lone(l: Float, t: Float, r: Float, b: Float): Boolean {
            val x = ((l + (r - l) * .08f) * image.width).toInt(); val y = ((t + (b - t) * .12f) * image.height).toInt()
            val w = ((r - (r - l) * .08f) * image.width).toInt() - x; val h = ((b - (b - t) * .12f) * image.height).toInt() - y
            return ColorZeroGlyph.loneZero(w, h, image.getRGB(x, y, w, h, null, 0, w))
        }
        assertEquals(true, lone(.1422f, .3094f, .2233f, .3452f))
        assertEquals(false, lone(.2233f, .3094f, .3044f, .3452f))
    }

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
        val failures = cases.mapNotNull { c ->
            val image = photo(c.photo)
            val px = image.getRGB(c.x, c.y, c.w, c.h, null, 0, c.w)
            val actual = ColorZeroGlyph.correct(c.w, c.h, px, c.ocr)
            if (actual == c.expected) null
            else "${c.photo} ${c.ocr} expected=${c.expected} actual=$actual\n" + ColorZeroGlyph.describe(c.w, c.h, px, c.ocr.count { it != '.' })
        }
        failures.forEach(::println)
        assertEquals(emptyList<String>(), failures.map { it.lineSequence().first() })
    }
}
