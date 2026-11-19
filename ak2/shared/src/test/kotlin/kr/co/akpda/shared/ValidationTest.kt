package kr.co.akpda.shared
import kotlin.test.*
class ValidationTest {
    @Test fun preservesFractionalQuantity() { assertEquals("12.345",positive("12.345","수량").toPlainString()) }
    @Test fun rejectsZeroNegativeAndInvalidQuantities() { listOf("0","-1","","NaN","1,000","1E100","0.0000001").forEach { assertFailsWith<IllegalArgumentException> { positive(it,"수량") } } }
    @Test fun rejectsMixedAndDuplicateBoxes() {
        val a=mapOf("BX_ID" to "A","PO_NO" to "P","INVOICE_NUMBER" to "I")
        validateBoxes(listOf(a,a+("BX_ID" to "B")))
        assertFailsWith<IllegalArgumentException> { validateBoxes(listOf(a,a)) }
        assertFailsWith<IllegalArgumentException> { validateBoxes(listOf(a,a+mapOf("BX_ID" to "B","PO_NO" to "X"))) }
    }
}
