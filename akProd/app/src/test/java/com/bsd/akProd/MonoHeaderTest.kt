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

    /** MIRACON-I 계량·배압 layout as read from the 11/13 photo; S0 and SB are told apart by order. */
    @Test
    fun miraconScreen() {
        val words = listOf(
            w("BP1", .26f, .29f, .29f, .30f), w("BP2", .33f, .29f, .36f, .30f), w("BP3", .40f, .29f, .43f, .30f),
            w("03", .26f, .305f, .28f, .315f), w("03", .33f, .305f, .35f, .315f), w("03", .40f, .305f, .42f, .315f),
            w("R1", .26f, .323f, .28f, .332f), w("R2", .33f, .323f, .35f, .332f), w("R3", .40f, .323f, .42f, .332f),
            w("SB.SPEED", .48f, .323f, .55f, .332f),
            w("60.0", .25f, .338f, .29f, .348f), w("60.0", .32f, .338f, .36f, .348f), w("60.0", .39f, .338f, .43f, .348f),
            w("40.0", .49f, .338f, .53f, .348f),
            w("S6", .28f, .355f, .30f, .364f), w("==>", .31f, .355f, .34f, .364f), w("S5", .35f, .355f, .37f, .364f),
            w("==>", .38f, .355f, .41f, .364f), w("S8", .42f, .355f, .44f, .364f), w("==>", .45f, .355f, .48f, .364f),
            w("SB", .50f, .355f, .52f, .364f),
            w("010.0", .27f, .372f, .32f, .382f), w("030.0", .34f, .372f, .39f, .382f),
            w("053.0", .41f, .372f, .46f, .382f), w("06.0", .49f, .372f, .53f, .382f)
        )
        val cells = Cp14TableReader.read(words, Cp14TableReader.MIRACON).cells.mapValues { it.value.value }
        assertEquals(mapOf("MBP1" to "3", "MBP2" to "3", "MBP3" to "3", "MR1" to "60", "MR2" to "60", "MR3" to "60",
            "MRS" to "40", "MS6" to "10", "MS5" to "30", "MS0" to "53", "MSB" to "6"), cells)
        // The same photo on the S5~S1 screen is recognised as MIRACON-I rather than read as BP/S values.
        assertTrue(Cp14TableReader.read(words, Cp14TableReader.METERING).miraconHeaders >= 2)
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
