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
    fun holdPressureCaptionBelowTheTableIsNotItsHeader() {
        fun w(t: String, l: Float, top: Float) = Cp14TableReader.Word(t, l, top, l + .05f, top + .03f)
        // IP4 header .40 → IV4 header .48: one header/value step of .08. The HP row headers are not read; the graph
        // caption "<— HP3 <— HP2 <— HP1" lies below the HT row, with nothing under it.
        val table = listOf(
            w("IP4", .20f, .40f), w("038", .20f, .44f), w("IV4", .20f, .48f), w("38.0", .20f, .52f),
            w("043", .30f, .58f), w("000", .40f, .58f), w("000", .50f, .58f),
            w("IHT", .30f, .62f), w("HT2", .40f, .62f), w("HT1", .50f, .62f),
            w("030.0", .30f, .66f), w("00.0", .40f, .66f), w("00.0", .50f, .66f)
        )
        val caption = listOf(w("HP3", .63f, .80f), w("HP2", .72f, .80f), w("HP1", .81f, .80f))
        val cells = Cp14TableReader.read(table + caption, Cp14TableReader.MIRACON_INJECTION).cells
        assertEquals("43", cells["HP3"]?.value); assertEquals("0", cells["HP2"]?.value); assertEquals("0", cells["HP1"]?.value)
        assertEquals("30", cells["HT3"]?.value)
        // Real HP headers above the HT row are kept as read.
        val real = listOf(w("HP3", .30f, .54f), w("HP2", .40f, .54f), w("HP1", .50f, .54f))
        val kept = Cp14TableReader.read(table + real + caption, Cp14TableReader.MIRACON_INJECTION)
        assertEquals("43", kept.cells["HP3"]?.value)
        assertTrue(kept.headers.filter { it.text.startsWith("HP") }.all { it.top < .6f })
    }

    @Test
    fun missingS0IsPlacedOnePitchRightOfIf1() {
        fun w(t: String, l: Float) = Cp14TableReader.Word(t, l, .44f, l + .03f, .455f)
        // Photo 9: S4~S1 found at widening pitches (.101, .107, .116), S0 not.
        val anchors = linkedMapOf("IF4" to w("IF4", .2327f), "IF3" to w("IF3", .3340f), "IF2" to w("IF2", .4407f), "IF1" to w("IF1", .5567f))
        Cp14TableReader.inferStartPosition(anchors)
        // .116 grown by its .009 step: ~.125 right of IF1.
        assertEquals(.5567f + .125f, anchors.getValue("S0").left, .003f)
        // A found S0 is never moved.
        val found = linkedMapOf("IF2" to w("IF2", .44f), "IF1" to w("IF1", .55f), "IF3" to w("IF3", .33f), "S0" to w("S0", .70f))
        Cp14TableReader.inferStartPosition(found)
        assertEquals(.70f, found.getValue("S0").left, 1e-4f)
    }

    @Test
    fun hpHeadersFollowTheirValuesOnlyOnePerColumn() {
        fun w(t: String, l: Float) = Cp14TableReader.Word(t, l, .54f, l + .05f, .57f)
        val anchors = linkedMapOf("HP3" to w("HP3", .30f), "HP2" to w("HP2", .40f), "HP1" to w("HP1", .50f))
        // MIRACON-I: values sit left of the HT-aligned guess; one per column moves each header onto its value.
        assertTrue(Cp14TableReader.alignToValues(anchors, "HP", listOf(w("043", .26f), w("000", .36f), w("000", .46f))))
        assertEquals(.26f, anchors.getValue("HP3").left, 1e-4f); assertEquals(.46f, anchors.getValue("HP1").left, 1e-4f)
        // Two values for three headers: nothing moves.
        val before = anchors.toMap()
        assertFalse(Cp14TableReader.alignToValues(anchors, "HP", listOf(w("043", .20f), w("Kg", .60f))))
        assertEquals(before, anchors.toMap())
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
