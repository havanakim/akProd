package com.bsd.akProd

import org.junit.Assert.assertEquals
import org.junit.Test

class MachineNoTest {
    @Test
    fun readsMachineNumberFromStoredJson() {
        assertEquals("C05", CaptureSaveRepository.machineNo("""{"SCREEN":"CP14호기","MACHINE":"C05","FIELDS":{}}"""))
        assertEquals("C16", CaptureSaveRepository.machineNo("""{"MACHINE" : "C16"}"""))
        // A draft saved before the picker existed has none and is stored with the menu code in MACHINE_CODE.
        assertEquals(null, CaptureSaveRepository.machineNo("""{"SCREEN":"CP3호기","MACHINE":"CP3","FIELDS":{}}"""))
        // Outside C01~C16 is never written to the column.
        assertEquals(null, CaptureSaveRepository.machineNo("""{"MACHINE":"C17"}"""))
        assertEquals(null, CaptureSaveRepository.machineNo("""{"MACHINE":"C00"}"""))
        assertEquals((1..16).map { "C%02d".format(it) }, MachineNoPicker.numbers)
    }
}
