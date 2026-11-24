package com.bsd.akProd

import org.junit.Assert.assertEquals
import org.junit.Test

class Cp3StoredKeyTest {
    @Test fun injectRowsAreSavedWithShortNames() {
        val expected = (5 downTo 1).flatMap { n ->
            listOf(
                "INJECT_${n}_PRESSURE" to "IP$n", "INJECT_${n}_SPEED" to "IV$n",
                "INJECT_${n}_TIME" to "IT$n", "INJECT_${n}_POSITION" to "IF$n"
            )
        }
        assertEquals(20, expected.size)
        expected.forEach { (key, stored) ->
            assertEquals(stored, Cp3TableReader.storedKey(key))
            assertEquals(key, Cp3TableReader.internalKey(stored))
        }
        // Every 사출 key of the screen is covered, and no two keys share a saved name.
        val injectKeys = Cp3TableReader.labels.keys.filter { it.startsWith("INJECT_") }
        assertEquals(expected.map { it.first }.toSet(), injectKeys.toSet())
        assertEquals(Cp3TableReader.labels.size, Cp3TableReader.labels.keys.map(Cp3TableReader::storedKey).toSet().size)
    }

    @Test fun meterRowsAreSavedWithShortNames() {
        val expected = (1..5).flatMap { n ->
            listOf(
                "METER_${n}_PRESSURE" to "RP$n", "METER_${n}_SPEED" to "RV$n",
                "METER_${n}_POSITION" to "S$n", "METER_${n}_BACK_PRESSURE" to "BP$n"
            )
        }
        assertEquals(20, expected.size)
        expected.forEach { (key, stored) ->
            assertEquals(stored, Cp3TableReader.storedKey(key))
            assertEquals(key, Cp3TableReader.internalKey(stored))
        }
        val meterKeys = Cp3TableReader.meteringLabels.keys.filter { it.startsWith("METER_") }
        assertEquals(expected.map { it.first }.toSet(), meterKeys.toSet())
        // No two keys of the 계량·흘림방지 screen share a saved name.
        val labels = Cp3TableReader.meteringLabels.keys
        assertEquals(labels.size, labels.map(Cp3TableReader::storedKey).toSet().size)
    }

    @Test fun otherKeysAreSavedUnchanged() {
        listOf("HOLD_3_PRESSURE", "HOLD_1_TIME", "SUCKBACK_1_PRESSURE", "SUCKBACK_1_SPEED", "SUCKBACK_1_POSITION").forEach {
            assertEquals(it, Cp3TableReader.storedKey(it))
            assertEquals(it, Cp3TableReader.internalKey(it))
        }
    }
}
