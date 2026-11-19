package com.bsd.akProd

object Cp14PageCheck {
    @JvmStatic fun main(args: Array<String>) {
        val reader=Cp14TableReader
        val injection=reader.labelsFor(reader.INJECTION).mapValues { "000.0" }
        val metering=reader.labelsFor(reader.METERING).mapValues { "010.0" }
        check(injection.size==26 && metering.size==15)
        reader.validatePage(injection,reader.INJECTION)
        reader.validatePage(metering,reader.METERING)
        reader.validatePage(injection-reader.labelsFor(reader.INJECTION).keys.first(),reader.INJECTION)
        check(runCatching { reader.validatePage(injection+metering,reader.INJECTION) }.isFailure)
        check(runCatching { reader.validatePage(injection,reader.METERING) }.isFailure)
        reader.validatePage(injection+metering,reader.LEGACY)
        println("CP_mono screen validation: 7 checks passed")
    }
}
