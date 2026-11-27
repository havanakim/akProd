package com.bsd.akProd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** HP cells against the row's verified 000 cell on the PB 6호기 사출 photos: 000 matches, HP3 043 never does. */
class ZeroPeerTest {
    private fun crop(photo: String, b: IntArray): Triple<Int, Int, IntArray> {
        val image = ImageIO.read(File("src/androidTest/assets/pbinjection1127/$photo.jpg"))
        val w = b[2] - b[0]; val h = b[3] - b[1]
        val px = IntArray(w * h); image.getRGB(b[0], b[1], w, h, px, 0, w)
        return Triple(w, h, px)
    }

    /** HP3 (043), HP2 (000), HP1 (000) value boxes, read off the photos. */
    private val cells = mapOf(
        "pb6inj_8" to listOf(intArrayOf(508, 1215, 592, 1268), intArrayOf(672, 1218, 758, 1272), intArrayOf(842, 1220, 928, 1274)),
        "pb6inj_9" to listOf(intArrayOf(462, 1112, 548, 1162), intArrayOf(618, 1122, 706, 1172), intArrayOf(782, 1130, 872, 1182)),
        "pb6inj_10" to listOf(intArrayOf(585, 1100, 670, 1152), intArrayOf(755, 1103, 840, 1155), intArrayOf(922, 1103, 1008, 1155)),
        "pb6inj_7" to listOf(intArrayOf(392, 1064, 448, 1100), intArrayOf(488, 1064, 544, 1100), intArrayOf(584, 1064, 640, 1100))
    )

    @Test
    fun hp000MatchesTheVerifiedZeroOfItsRow() {
        cells.forEach { (photo, boxes) ->
            // Photo 8's HP2 is the one the zero check alone cannot settle (glare: 800).
            assertTrue(photo, ZeroPeer.allZeroLike(crop(photo, boxes[1]), crop(photo, boxes[2])).first)
        }
    }

    @Test
    fun hp043NeverMatchesAZero() {
        cells.forEach { (photo, boxes) ->
            assertFalse(photo, ZeroPeer.allZeroLike(crop(photo, boxes[0]), crop(photo, boxes[2])).first)
            assertFalse(photo, ZeroPeer.allZeroLike(crop(photo, boxes[0]), crop(photo, boxes[1])).first)
        }
    }
}
