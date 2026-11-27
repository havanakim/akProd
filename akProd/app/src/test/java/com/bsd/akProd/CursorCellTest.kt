package com.bsd.akProd

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** The inverted field under the HMI cursor (IHT 030.0 on PB 6호기 photos) against ordinary cells. */
class CursorCellTest {
    private fun crop(photo: String, l: Int, t: Int, r: Int, b: Int): Triple<Int, Int, IntArray> {
        val image = ImageIO.read(File("src/androidTest/assets/pbinjection1127/$photo.jpg"))
        val pixels = IntArray((r - l) * (b - t)); image.getRGB(l, t, r - l, b - t, pixels, 0, r - l)
        return Triple(r - l, b - t, pixels)
    }

    @Test
    fun findsTheCursorBox() {
        listOf(
            crop("pb6inj_7", 440, 1110, 545, 1165), crop("pb6inj_8", 595, 1310, 745, 1375),
            crop("pb6inj_9", 550, 1210, 695, 1280), crop("pb6inj_10", 675, 1185, 825, 1255)
        ).forEach { (w, h, px) ->
            val box = assertNotNull(CursorCell.box(w, h, px)).let { CursorCell.box(w, h, px)!! }
            // A wide box well inside the crop, not the whole crop.
            assertTrue(box[2] - box[0] > (box[3] - box[1]) * 1.4f && box[0] > 0 && box[1] > 0)
            val (nw, nh, normal) = CursorCell.normalize(w, h, px)!!
            // After inversion the box background is dark and the digit strokes lit.
            val gray = normal.map { it and 255 }
            assertTrue(gray.count { it < 64 } > gray.size / 2 && gray.count { it > 160 } > nw * nh / 20)
        }
    }

    @Test
    fun ordinaryCellsAreNotCursorCells() {
        // HT2 00.0 and HP3 043 next to the cursor, lit digits on the dark screen.
        val (w, h, px) = crop("pb6inj_10", 860, 1185, 1010, 1255)
        assertNull(CursorCell.box(w, h, px))
        val (w2, h2, px2) = crop("pb6inj_10", 580, 1100, 700, 1150)
        assertNull(CursorCell.box(w2, h2, px2))
    }
}
