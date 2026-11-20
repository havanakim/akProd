package com.bsd.akProd

/** Dedicated entry point for the color HMI upper metering/suck-back table. */
class ColoredMeteringActivity : Cp3Activity() {
    override val registrationPage: String = Cp3TableReader.METERING
}
