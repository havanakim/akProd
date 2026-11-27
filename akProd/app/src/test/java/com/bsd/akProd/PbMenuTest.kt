package com.bsd.akProd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PbMenuTest {
    @Test
    fun pbInjectionHasJournalRows() {
        val keys = Cp14TableReader.labelsFor(Cp14TableReader.PB_INJECTION).keys.toList()
        assertEquals(listOf("IF5", "IF4", "IF3", "IF2", "IF1", "IF0"), keys.take(6))
        assertEquals(6 + 5 + 5 + 2, keys.size)
        assertTrue("HP1" in keys && "HT1" in keys && "HP5" !in keys && "S0" !in keys)
        assertEquals(15, Cp14TableReader.labelsFor(Cp14TableReader.PB_METERING).size)
        // CP screens are unchanged.
        assertFalse("IF0" in Cp14TableReader.labelsFor(Cp14TableReader.INJECTION))
    }

    @Test
    fun readsIf0HeaderOnlyOnPbInjection() {
        val words = listOf(
            Cp14TableReader.Word("IF1", .50f, .10f, .58f, .14f),
            Cp14TableReader.Word("IF0", .70f, .10f, .78f, .14f),
            Cp14TableReader.Word("129", .50f, .16f, .58f, .20f),
            Cp14TableReader.Word("148", .70f, .16f, .78f, .20f)
        )
        assertEquals("148", Cp14TableReader.read(words, Cp14TableReader.PB_INJECTION).cells["IF0"]?.value)
        assertNull(Cp14TableReader.read(words, Cp14TableReader.INJECTION).cells["IF0"])
    }

    @Test
    fun journalValuesKeepTextAndCheckNumbers() {
        val page = Cp14TableReader.PB_INJECTION
        val stored = PbJournal.values(page, mapOf("ITEM_NAME" to "BRT", "CT_INJECT" to "23.2", "NOTE" to " 49.9 "))
        assertEquals("BRT", stored["ITEM_NAME"]); assertEquals("23.2", stored["CT_INJECT"]); assertEquals("49.9", stored["NOTE"])
        assertNull(stored["H1"])
        assertTrue(runCatching { PbJournal.values(page, mapOf("CT_COOL" to "6t")) }.isFailure)
        assertTrue(PbJournal.manualFields(Cp14TableReader.PB_METERING).isEmpty())
        // Every key is unique across the OCR rows and the typed-in items.
        val all = PbJournal.sections(page).values.flatten().map { it.key }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun pbMachinesAndHistoryFilter() {
        assertEquals((1..16).map { "P%02d".format(it) }, MachineNoPicker.pbNumbers)
        assertEquals("P14", CaptureSaveRepository.machineNo("""{"HMI":"P","MACHINE":"P14"}"""))
        assertEquals("P14 · 2차", CaptureSaveRepository.deviceLabel("P14", 2))
        assertEquals("P", Cp14Repository.hmiOf(Cp14TableReader.PB_METERING))
        assertEquals("M", Cp14Repository.hmiOf(Cp14TableReader.METERING))
        // PB history never lists old JSON without "HMI" (always CP).
        assertEquals("(JSON2 IS NOT NULL AND JSON_VALUE(JSON2,'$.HMI')='P')",
            CaptureSaveRepository.Part.METERING.rowsFor(CaptureSaveRepository.HMI_PB))
    }
}
