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
    fun miraconInjectionUsesInjectionKeys() {
        val page = Cp14TableReader.MIRACON_INJECTION
        val labels = Cp14TableReader.labelsFor(page)
        assertEquals(listOf("IP4", "IP3", "IP2", "IP1", "IV4", "IV3", "IV2", "IV1", "IF4", "IF3", "IF2", "IF1", "S0",
            "HP3", "HP2", "HP1", "HT3", "HT2", "HT1"), labels.keys.toList())
        // Shown as printed on the screen.
        assertTrue(labels.getValue("IF4").startsWith("S4 · ") && labels.getValue("HT3").startsWith("IHT · "))
        // Both PB 사출 screens carry the typed-in journal items.
        assertEquals(PbJournal.manualFields(Cp14TableReader.PB_INJECTION), PbJournal.manualFields(page))
        val all = PbJournal.sections(page).values.flatten().map { it.key }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun readsMiraconInjectionRows() {
        fun w(t: String, l: Float, top: Float) = Cp14TableReader.Word(t, l, top, l + .06f, top + .03f)
        // "S4 <== S3 … <== S0" over 012.0 … 152.0, and "IHT HT2" over 030.0 00.0.
        val words = listOf(w("S4", .20f, .40f), w("S3", .30f, .40f), w("S0", .60f, .40f),
            w("012.0", .20f, .44f), w("025.0", .30f, .44f), w("152.0", .60f, .44f),
            w("IHT", .30f, .60f), w("HT2", .45f, .60f), w("030.0", .30f, .64f), w("00.0", .45f, .64f))
        val cells = Cp14TableReader.read(words, Cp14TableReader.MIRACON_INJECTION).cells
        assertEquals("012.0", cells["IF4"]?.value?.let { "%05.1f".format(it.toDouble()) })
        assertEquals("152", cells["S0"]?.value); assertEquals("30", cells["HT3"]?.value); assertEquals("0", cells["HT2"]?.value)
        assertFalse("S4" in cells || "IHT" in cells)
        // On the CP screen IHT is never a header.
        assertNull(Cp14TableReader.read(words, Cp14TableReader.INJECTION).cells["HT3"])
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
