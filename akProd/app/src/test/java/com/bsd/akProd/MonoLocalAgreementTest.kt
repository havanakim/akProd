package com.bsd.akProd

import org.junit.Assert.*
import org.junit.Test

class MonoLocalAgreementTest {
    @Test fun enlargedCellAgreementSurvivesWholePhotoNoise() {
        assertEquals("20",MonoLocalAgreement.value(mapOf("20" to 3,"229" to 1)))
        assertEquals("35",MonoLocalAgreement.value(mapOf("35" to 2,"85" to 1)))
        assertEquals("0",MonoLocalAgreement.value(mapOf("0" to 2)))
        assertEquals("95",MonoLocalAgreement.value(mapOf("95" to 3,"695" to 1)))
        assertEquals("75",MonoLocalAgreement.value(mapOf("75" to 2,"675" to 1)))
    }
    @Test fun missingSingleOrTiedReadsDoNotInventPressure() {
        assertNull(MonoLocalAgreement.value(emptyMap()))
        assertNull(MonoLocalAgreement.value(mapOf("20" to 1)))
        assertNull(MonoLocalAgreement.value(mapOf("20" to 2,"28" to 2)))
        assertNull(MonoLocalAgreement.value(mapOf("28" to 2,"20" to 1)))
        assertEquals("20",MonoLocalAgreement.value(mapOf("28" to 2,"20" to 1),setOf("20")))
    }
}
