package com.bsd.akProd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Header readings taken from the 20261119_134423 mono photo (ML Kit output, normalized coordinates). */
class MonoHeaderTest {
    private fun w(t: String, l: Float, top: Float, r: Float, b: Float) = Cp14TableReader.Word(t, l, top, r, b)

    @Test
    fun speedHeadersReadAsIuAreSpeedRow() {
        val words = listOf(
            w("IU5", .330f, .438f, .356f, .448f), w("IU3", .535f, .442f, .561f, .452f),
            w("IUZ", .636f, .443f, .662f, .453f), w("IUi", .737f, .445f, .763f, .455f),
            w("27.0", .325f, .460f, .357f, .470f), w("25.0", .429f, .462f, .459f, .472f),
            w("22.0", .531f, .463f, .561f, .473f), w("03.7", .631f, .465f, .661f, .475f),
            w("02.7", .743f, .464f, .773f, .474f)
        )
        val result = Cp14TableReader.read(words)
        // IV4 is never printed-read; it is placed between IV5 and IV3 and still reads its own digits.
        assertEquals(mapOf("IV5" to "27", "IV4" to "25", "IV3" to "22", "IV2" to "3.7", "IV1" to "2.7"),
            result.cells.mapValues { it.value.value }.filterKeys { it.startsWith("IV") })
    }

    @Test
    fun startPositionJoinedWithArrowIsOneHeader() {
        val words = listOf(
            w("<==IFi", .630f, .352f, .674f, .362f), w("(==", .740f, .355f, .766f, .365f),
            w("S8", .800f, .356f, .822f, .366f),
            w("114.0", .686f, .374f, .726f, .384f), w("130.0", .786f, .379f, .826f, .389f)
        )
        val result = Cp14TableReader.read(words)
        assertEquals("130", result.cells["S0"]?.value)
        assertTrue(result.headers.single { it.text == "S0" }.left >= .79f)
    }

    @Test
    fun displayLayout() {
        assertEquals(true, Cp14TableReader.matchesDisplay("IV1", "02.7"))
        assertEquals(false, Cp14TableReader.matchesDisplay("IV3", "2230"))
        assertEquals(false, Cp14TableReader.matchesDisplay("S0", "13.8"))
        assertEquals(true, Cp14TableReader.matchesDisplay("BP1", "20"))
        assertEquals(false, Cp14TableReader.matchesDisplay("S5", "818"))
        assertEquals(true, Cp14TableReader.matchesDisplay("RV1", "16.3"))
    }
}
