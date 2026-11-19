package com.bsd.akProd

import org.junit.Test

class OcrRegressionTest {
    @Test fun captureSaveDoesNotBlockOrDuplicate() { CaptureSaveWorkerCheck.main(emptyArray()) }
    @Test fun monoScreensSaveIndependently() { Cp14PageCheck.main(emptyArray()) }
    @Test fun monoTableMapping() { Cp14TableReaderCheck.main(emptyArray()) }
    @Test fun coloredTableMapping() { Cp3TableReaderCheck.main(emptyArray()) }
    @Test fun monitorDetection() { MonitorDetectorCheck.main(emptyArray()) }
    @Test fun slashedZeroRecognition() { SlashedZeroDetectorCheck.main(emptyArray()) }
    @Test fun learnedZeroRecognition() { ZeroFontTemplatesCheck.main(emptyArray()) }
}
