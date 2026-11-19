package com.bsd.akProd

import org.junit.Assert.*
import org.junit.Test

class ZeroCandidateAgreementTest {
    @Test fun onlyVerifiedZeroAtSamePlaceSupersedesAnotherPass() {
        assertTrue(ZeroCandidateAgreement.supersedes("20","820"))
        assertTrue(ZeroCandidateAgreement.supersedes("25","25.8"))
        assertTrue(ZeroCandidateAgreement.supersedes("10","10.8"))
        assertFalse(ZeroCandidateAgreement.supersedes("75","675"))
        assertFalse(ZeroCandidateAgreement.supersedes("10","12"))
        assertFalse(ZeroCandidateAgreement.supersedes("25.8","25"))
    }
}
